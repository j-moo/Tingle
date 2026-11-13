"""-145 합성 평가: 현재 동작 재현과 사람 기준 품질 수용을 구분한다.

품질 수용 실패를 xfail/skip으로 숨기지 않는다. generator/verifier는 내부
프로토콜 Mock이며 실제 모델의 품질, 비용, 지연을 측정하지 않는다.
"""

import asyncio
import copy
import json
import socket
from pathlib import Path

import httpx2 as httpx
import pytest

from app.match_explanation import (
    DisclosureScope, Evidence, MatchExplanationService, Reference,
    ResolvedContext, SourceEvidence,
)
from app.match_explanation.dto import ModelInput
from app.match_explanation.gms import GmsClient
from app.match_explanation.grounding import validate_grounding
from app.match_explanation.validation import parse_output, select_evidence


DATA = json.loads((Path(__file__).parent / "fixtures/mat04-145/fidelity_cases.json")
                  .read_text(encoding="utf-8"))
CASES = DATA["cases"]


def prohibit_external_io(monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("합성 평가에서 실제 네트워크/환경 자격증명을 사용하면 안 됩니다")

    async def forbidden_async(*args, **kwargs):
        forbidden()

    # Windows asyncio의 내부 socketpair는 보존하고 DNS/HTTP 외부 경계를 막는다.
    monkeypatch.setattr(socket, "create_connection", forbidden)
    monkeypatch.setattr(socket, "getaddrinfo", forbidden)
    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", forbidden)
    monkeypatch.setattr(httpx.AsyncHTTPTransport, "handle_async_request", forbidden_async)
    monkeypatch.setattr(GmsClient, "from_environment", forbidden)


@pytest.fixture(autouse=True)
def offline_only(monkeypatch):
    prohibit_external_io(monkeypatch)


def context_for(case):
    reference = Reference(
        encounter_id=14528, evaluation_ref="synthetic-independent-fidelity-145",
        version_ref="synthetic-evaluation-only",
    )
    records = tuple(
        SourceEvidence(reference=reference, source_ref=row["source_ref"],
                       evidence=Evidence.model_validate(row["evidence"]))
        for row in case["records"]
    )
    ids = frozenset(row.evidence.id for row in records)
    context = ResolvedContext(reference=reference, viewer=case["viewer"], evidence=records)
    scope = DisclosureScope(
        reference=reference, viewer=case["viewer"], version="synthetic-public-scope",
        display_evidence_ids=frozenset(case.get("display_ids", ids)),
        model_evidence_ids=frozenset(case.get("model_ids", ids)),
    )
    return context, scope


class ScriptedModels:
    """오승인을 포함한 고정 응답이다. 사실 판정기가 아니다."""

    def __init__(self, case):
        self.case = case
        self.requests = []
        self.verifications = []
        self.raw = None

    async def generate(self, request):
        self.requests.append(request)
        fault = self.case.get("fault")
        if fault == "generator_error":
            raise RuntimeError("synthetic-upstream-failure")
        if fault == "deadline":
            # 즉시 예외를 던지지 않고 서비스의 공유 deadline으로 실제 취소한다.
            await asyncio.Event().wait()
        if fault == "invalid_json":
            self.raw = "{malformed synthetic response"
        else:
            payload = dict(self.case["candidate"])
            if fault == "missing_ids":
                payload.pop("evidenceIds")
            self.raw = json.dumps(payload, ensure_ascii=False)
        return self.raw

    async def verify(self, request, output):
        self.verifications.append((request, output))
        fault = self.case.get("fault")
        if fault == "verifier_error":
            raise RuntimeError("synthetic-reviewer-failure")
        return json.dumps({
            "supported": fault != "verifier_reject",
            "directionCorrect": True, "privacySafe": True,
            "evidenceIds": ["e3"] if fault == "verifier_ids" else output.evidenceIds,
        })


def evaluate_case(case):
    context, scope = context_for(case)
    before = (context.model_dump_json(), scope.model_dump_json())
    models = ScriptedModels(case)
    baseline = asyncio.run(MatchExplanationService(authorize=lambda _: True).explain(
        context, scope=scope,
    ))
    actual = asyncio.run(MatchExplanationService(
        generator=models, verifier=models, authorize=lambda _: True,
        timeout_seconds=0.02 if case.get("fault") == "deadline" else 8.0,
    ).explain(context, scope=scope))
    display, selected, _ = select_evidence(context, scope)
    fault = case.get("fault")
    if not display:
        gate = "selection"
    elif not selected:
        gate = "model_scope"
    elif fault in {"generator_error", "deadline"}:
        gate = "generation_error" if fault == "generator_error" else "generation_timeout"
    else:
        request = ModelInput(viewer=context.viewer, evidence=selected)
        try:
            output = parse_output(models.raw, request)
        except (ValueError, TypeError):
            gate = "parse"
        else:
            try:
                validate_grounding(output, request)
            except ValueError:
                gate = "grounding"
            else:
                gate = ("verification_error" if fault == "verifier_error"
                        else "verdict" if actual.reason == "INVALID_OUTPUT" else "accepted")
    return {
        "id": case["id"], "category": case["category"], "label": case["label"],
        "human_safe": case["human_safe"], "risk": case.get("risk"),
        "candidate": case["candidate"], "fault": fault,
        "baseline_text": baseline.explanation.text if baseline.explanation else None,
        "baseline_ids": list(baseline.used_evidence_ids),
        "source": actual.explanation.source if actual.explanation else None,
        "final_text": actual.explanation.text if actual.explanation else None,
        "reason": actual.reason, "gate": gate,
        "used_ids": list(actual.used_evidence_ids),
        "calls": [len(models.requests), len(models.verifications)],
        "input_unchanged": before == (context.model_dump_json(), scope.model_dump_json()),
        "request_evidence": [[item.model_dump() for item in request.evidence]
                             for request in models.requests],
        "selected_evidence": [item.model_dump() for item in selected],
        "public_evidence_keys": list(actual.explanation.evidenceKeys) if actual.explanation else [],
    }


def test_fidelity_dataset_is_independent_and_has_all_required_categories():
    assert len(CASES) == 28
    assert len({case["id"] for case in CASES}) == 28
    assert {group: sum(case["category"] == group for case in CASES)
            for group in "ABCDE"} == {"A": 5, "B": 5, "C": 6, "D": 5, "E": 7}
    # 기존 fixture의 단일 후보를 복사해 새 평가로 중복 집계하지 않는다.
    previous = json.loads((Path(__file__).parent / "fixtures/mat04-145/synthetic_cases.json")
                          .read_text(encoding="utf-8"))
    previous_texts = {row["text"] for row in previous["claims"]}
    previous_texts.update(row["accepted_text"] for row in previous["one_way"])
    previous_texts.add(previous["multi"]["accepted_text"])
    assert all(case["candidate"]["text"] not in previous_texts for case in CASES)


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["id"])
def test_fidelity_current_behavior_and_fallback(case):
    """PASS는 재현 성공이다. 정상 의역의 차단을 품질 성공으로 세지 않는다."""
    record = evaluate_case(case)
    expected = case["observed"]
    assert record["baseline_text"] == case["baseline"]["text"]
    assert record["baseline_ids"] == case["baseline"]["ids"]
    for field in ("source", "reason", "gate", "calls"):
        assert record[field] == expected[field], (case["id"], field, record)
    assert record["input_unchanged"]
    assert record["public_evidence_keys"] == []
    assert all(evidence == record["selected_evidence"] for evidence in record["request_evidence"])
    if record["source"] == "GENERATED":
        assert record["final_text"] == case["candidate"]["text"]
        assert record["used_ids"] == case["candidate"]["evidenceIds"]
    else:
        assert record["final_text"] == case["baseline"]["text"]
        assert record["used_ids"] == case["baseline"]["ids"]
    if case["human_safe"] is False:
        assert record["source"] != "GENERATED", "위험 문장이 최종 반환됨"
        assert record["final_text"] != case["candidate"]["text"]


@pytest.mark.parametrize("case", [case for case in CASES if case["human_safe"] is True and case["contract_status"] == "CONFIRMED"],
                         ids=lambda case: case["id"])
def test_fidelity_confirmed_candidate_quality_acceptance(case):
    """안전한 동일 의미 후보 수용 기준. 현재 실패는 blocker로 그대로 보고한다."""
    record = evaluate_case(case)
    assert record["source"] == "GENERATED", (
        f"{case['id']} 정상 근거/의역 과잉 차단: "
        f"gate={record['gate']} reason={record['reason']}; "
        f"후보={case['candidate']['text']}; 최종={record['final_text']}"
    )


@pytest.mark.parametrize("case", [case for case in CASES if case["contract_status"] == "PENDING"],
                         ids=lambda case: case["id"])
def test_fidelity_pending_contract_preserves_conservative_behavior(case):
    """Contract-pending cases remain covered; this is not generation acceptance."""
    record = evaluate_case(case)
    assert record["source"] == case["observed"]["source"]
    assert record["source"] != "GENERATED"
    assert record["reason"] == case["observed"]["reason"]


@pytest.mark.parametrize("case_id,field,value", [
    ("D01", "text", "두 분 모두 책 읽는 활동을 자주 즐겨요."),
    ("D01", "text", "두 분 모두 책 읽는 활동만 즐겨요."),
    ("D01", "text", "두 분 모두 책 읽는 활동을 즐기지 않아요."),
    ("D01", "text", "두 분 모두 책 읽는 활동을 즐겨요. 상대가 좋아할 거예요."),
    ("D01", "fact", "두 사람 모두 독서를 선호한다고 응답했다."),
    ("D01", "fact", "A와 B 모두 독서를 즐긴다고 응답했다."),
    ("D03", "text", "약속 준비 시점이 같아요."),
    ("D03", "text", "당신이 상대보다 먼저 약속을 준비해요."),
    ("D03", "text", "약속 준비 시점이 달라요. 갈등은 없어요."),
    ("D03", "fact", "약속 준비 방식에는 차이가 있다."),
])
def test_confirmed_paraphrases_do_not_authorize_nearby_claims(case_id, field, value):
    case = copy.deepcopy(next(row for row in CASES if row["id"] == case_id))
    if field == "text": case["candidate"]["text"] = value
    else: case["records"][0]["evidence"]["fact"] = value
    record = evaluate_case(case)
    assert record["source"] != "GENERATED"
    assert record["calls"][1] == 0
