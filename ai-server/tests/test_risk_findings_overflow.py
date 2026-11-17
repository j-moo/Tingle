"""Fixed valid-input overflow regressions; real tokenizer is mandatory here.

No importorskip/skip marker: CI must fail if the declared development tokenizer
is missing instead of silently skipping the regression that needs it.
"""

from copy import deepcopy
import json
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest
import tokenizers

from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.risk_detection.http_api import Message, Verdict
from app.risk_detection.rule_engine import _detect
from app.risk_detection.runtime import create_development_router
from app.risk_detection.tokenization import TOKENIZERS_VERSION
from safety48_runtime_support import message, request_for, synthetic_release
from test_risk_detection_http import ROUTE


CASE = json.loads((Path(__file__).parent / "fixtures/safety48-rules/findings_overflow.json")
                  .read_text(encoding="utf-8"))


def client(release):
    app = FastAPI()
    install_error_handlers(app)
    app.add_middleware(RequestIdMiddleware)
    # Domain adapter test. Actual JWT and loopback are covered by the existing
    # real app.main test, updated with the same overflow request in this change.
    app.include_router(create_development_router(
        lambda _: True,
        descriptor_file=str(release.descriptor), descriptor_sha256=release.test_descriptor_pin,
    ))
    return TestClient(app)


@pytest.mark.parametrize("count,raw_total,expected", [(9, 45, 36), (10, 50, 40)])
def test_valid_overflow_preserves_every_fresh_notice_unit_with_real_tokens(tmp_path, count, raw_total, expected):
    assert tokenizers.__version__ == TOKENIZERS_VERSION == "0.22.2"
    assert CASE["syntheticOnly"] and CASE["rawFindingCount"] == 45
    messages = [message(CASE["text"], sequence=11 + i, speaker="AB"[i % 2]) for i in range(count)]
    if count == CASE["messageCount"]:
        assert sum(len(m["text"]) for m in messages) == CASE["characterCount"] == 603
    assert sum(len(_detect(Message(**m), [])[0]) for m in messages) == raw_total
    release = synthetic_release(tmp_path)
    request = request_for(release, messages)
    original = deepcopy(request)
    response = client(release).post(ROUTE, json=request)
    assert response.status_code == 200
    result = Verdict.model_validate(response.json()["verdict"])
    assert result.kind == CASE["expectedKind"] == "RISK"
    assert len(result.findings) == expected == count * CASE["expectedNoticeUnitsPerMessage"]
    assert expected <= 40
    expected_units = {(m["sequence"], *unit) for m in messages for unit in CASE["noticeUnits"]}
    actual_units = {(f.sequence, f.noticeType, f.audience) for f in result.findings}
    assert actual_units == expected_units
    assert len(actual_units) == len(result.findings)
    assert {(f.sequence, f.category, f.noticeType, f.audience) for f in result.findings} == {
        (m["sequence"], CASE["representativeCategories"][notice], notice, audience)
        for m in messages for notice, audience in CASE["noticeUnits"]
    }
    assert request == original


LEGACY_COMBINATIONS = [
    ("FRAUD", "MONEY_REQUEST", "RECIPIENT"),
    ("HARASSMENT", "HARASSMENT", "BOTH"),
    ("THREAT", "HARASSMENT", "BOTH"),
    ("OTHER", "PERSONAL_INFO", "RECIPIENT"),
    ("OTHER", "OFF_PLATFORM", "RECIPIENT"),
]


def expected_legacy(messages, *, omit_last_threat=False):
    return [dict(sequence=m["sequence"], category=category, noticeType=notice, audience=audience)
            for m in messages
            for category, notice, audience in LEGACY_COMBINATIONS
            if not (omit_last_threat and m is messages[-1] and category == "THREAT")]


@pytest.mark.parametrize("total", [39, 40])
def test_legacy_findings_and_response_versions_are_exactly_preserved_up_to_40(tmp_path, total):
    messages = [message(CASE["text"], sequence=i + 1, speaker="AB"[i % 2]) for i in range(8)]
    if total == 39:
        messages[-1]["text"] = ("원금 보장하니 인증금 송금해. 너 같은 멍청이. "
                                "비밀번호를 보내. 보안 경고 무시하고 앱 설치해.")
    release = synthetic_release(tmp_path)
    response = client(release).post(ROUTE, json=request_for(release, messages))
    assert response.status_code == 200
    assert response.json()["verdict"] == {
        "kind": "RISK", "findings": expected_legacy(messages, omit_last_threat=total == 39),
        "confidence": None, "modelVersion": "safety48-local-rules-r2-v1", "promptVersion": "safety48-no-llm-r2-v1",
    }
    assert len(response.json()["verdict"]["findings"]) == total


@pytest.mark.parametrize("tail", ["fraud", "separate-threat-and-harassment"])
def test_overflow_never_merges_different_sequences_or_single_categories(tmp_path, tail):
    messages = [message(CASE["text"], sequence=i + 1, speaker="AB"[i % 2]) for i in range(8)]
    if tail == "fraud":
        messages.append(message("원금 보장하니 인증금을 송금해.", sequence=9))
        expected_tail = {(9, "FRAUD", "MONEY_REQUEST", "RECIPIENT")}
    else:
        messages.extend([message("내가 널 때릴 거야.", sequence=9),
                         message("너 같은 멍청이.", sequence=10, speaker="B")])
        expected_tail = {(9, "THREAT", "HARASSMENT", "BOTH"), (10, "HARASSMENT", "HARASSMENT", "BOTH")}
    release = synthetic_release(tmp_path)
    response = client(release).post(ROUTE, json=request_for(release, messages))
    assert response.status_code == 200
    result = Verdict.model_validate(response.json()["verdict"])
    assert result.kind == "RISK" and len(result.findings) == 32 + len(expected_tail)
    assert {(f.sequence, f.category, f.noticeType, f.audience) for f in result.findings if f.sequence >= 9} == expected_tail
    assert {f.sequence for f in result.findings} == {m["sequence"] for m in messages}


def test_old_context_is_never_promoted_to_overflow_evidence(tmp_path):
    messages = [message(CASE["text"], sequence=1, fresh=False)]
    messages.extend(message(CASE["text"], sequence=11 + i, speaker="AB"[i % 2]) for i in range(9))
    release = synthetic_release(tmp_path)
    response = client(release).post(ROUTE, json=request_for(release, messages))
    assert response.status_code == 200
    result = Verdict.model_validate(response.json()["verdict"])
    assert result.kind == "RISK" and len(result.findings) == 36
    assert {(f.sequence, f.noticeType, f.audience) for f in result.findings} == {
        (m["sequence"], *unit) for m in messages if m["fresh"] for unit in CASE["noticeUnits"]
    }
