"""Tokenizer-free local rules with synthetic consistency pins, never approvals."""

import builtins
import json

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from app.match_explanation.gms import GmsClient
from app.middleware.request_id import install_error_handlers
from app.risk_detection.local_engine import EngineUnavailableError, LocalRuleRiskEngine
from app.risk_detection.releases import DevelopmentReleaseError, LocalRuleReleaseCatalog
from app.risk_detection.runtime import create_development_router
from safety48_runtime_support import message, request_for, synthetic_release
from safety_asgi_contract_support import expect, main_factory, no_external_io, synthetic_private_keys


@pytest.fixture
def plain_release(tmp_path, monkeypatch, no_external_io):
    release = synthetic_release(tmp_path, tokenized=False)
    assert not release.tokenizer.exists()
    original = builtins.__import__

    def no_tokenizer(name, *args, **kwargs):
        if name == "tokenizers" or name.startswith("tokenizers."):
            pytest.fail("Plain local rules imported an optional tokenizer")
        return original(name, *args, **kwargs)

    def forbidden(*args, **kwargs):
        pytest.fail("Plain local rules accessed GMS")

    monkeypatch.setattr(builtins, "__import__", no_tokenizer)
    # Match/Icebreaker must still construct the shared client at startup.
    for name in ("from_environment", "request_text"):
        monkeypatch.setattr(GmsClient, name, forbidden)
    return release


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
@pytest.mark.parametrize("text,kind", [
    ("합성 점심값은 반씩 나누자.", "NORMAL"),
    ("원금 보장하니 인증금을 송금해.", "RISK"),
    ("그거 보내 줘. 알지?", "INSUFFICIENT"),
])
def test_real_main_without_tokenizer(main_factory, plain_release, environment, text, kind):
    host = main_factory(environment=environment, release=plain_release)
    engine = host.client.app.state.risk_runtime.resources.engine
    assert type(engine) is LocalRuleRiskEngine and not engine.requires_token_budget
    response = expect(host.post(request_for(plain_release, [message(text)])))
    assert response["verdict"]["kind"] == kind
    assert text not in json.dumps(response, ensure_ascii=False)


@pytest.mark.parametrize("environment", ["dev", "prod"])
@pytest.mark.parametrize(("text", "expected"), [
    ("너 죽여버린다.", [dict(sequence=1, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]),
    ("돈 안 보내면 네 사진 가족들한테 뿌릴게.",
     [dict(sequence=1, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]),
    ("인증코드 나한테 넘겨.", [dict(sequence=1, category="OTHER", noticeType="PERSONAL_INFO", audience="RECIPIENT")]),
    ("인증코드는 누구에게도 넘기지 마세요.", []),
    ("오늘 사진을 가족들에게 보냈어.", []),
    ("점심값은 반씩 나누자.", []),
    ("너무 웃겨서 나 죽여버린다 진짜.", []),
    ("오늘 너무 더워서 다 죽여버린다.", []),
    ("너 때문에 내 폰 배터리 죽여버린다.", []),
    ("너네 강아지 사진 단톡에 뿌릴게!", []),
    ("네 생일 사진 친구들한테 뿌릴게.", []),
    ("너 죽여버릴거야.", [dict(sequence=1, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]),
    ("돈 안 주면 네 사진 다 뿌릴거야.",
     [dict(sequence=1, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]),
    ("너 때문에 내 폰 배터리 죽여버린다. 그래도 너 죽여버릴거야.",
     [dict(sequence=1, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]),
    ("너네 강아지 사진 단톡에 뿌릴게! 너 죽여버린다.",
     [dict(sequence=1, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]),
    ("네 생일 사진 친구들한테 뿌릴게. 너 죽여버릴거야.",
     [dict(sequence=1, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]),
])
def test_sg02_real_main_direct_risks_and_controls_without_tokenizer(
        main_factory, plain_release, environment, text, expected):
    host = main_factory(environment=environment, release=plain_release)
    verdict = expect(host.post(request_for(plain_release, [message(text)])))["verdict"]
    assert verdict["kind"] == ("RISK" if expected else "NORMAL")
    assert verdict["findings"] == expected


@pytest.mark.parametrize("environment", ["dev", "prod"])
def test_same_release_produces_same_verdict_and_keeps_authentication(main_factory, plain_release, environment):
    host = main_factory(environment=environment, release=plain_release)
    payload = request_for(plain_release, [message("원금 보장하니 인증금을 송금해.")])
    response = expect(host.post(payload))
    assert response["verdict"]["findings"] == [
        dict(sequence=1, category="FRAUD", noticeType="MONEY_REQUEST", audience="RECIPIENT")]
    expect(host.post(payload, credential=None), 401, "AUTH_REQUIRED")
    expect(host.post(payload, credential="invalid"), 401, "ACCESS_TOKEN_INVALID")
    expect(host.post(payload, credential=host.token({"scope": "wrong"})), 403, "FORBIDDEN")
    other = "prod" if environment == "dev" else "dev"
    expect(host.post(payload, credential=host.token({"aud": f"urn:tingle:ai:{other}"})),
           401, "ACCESS_TOKEN_INVALID")


def test_direct_router_without_switch_or_tokenizer(plain_release):
    app = FastAPI()
    install_error_handlers(app)
    app.include_router(create_development_router(lambda _: True,
        descriptor_file=str(plain_release.descriptor), descriptor_sha256=plain_release.test_descriptor_pin))
    with TestClient(app) as client:
        response = client.post("/internal/ai/risk-detections", json=request_for(plain_release))
    assert response.status_code == 200 and response.json()["verdict"]["kind"] == "NORMAL"


@pytest.mark.parametrize("environment", ["dev", "prod"])
@pytest.mark.parametrize("case", ["characters", "messages", "url", "email", "phone", "order"])
def test_plain_rules_keep_input_boundaries(main_factory, plain_release, environment, case):
    host = main_factory(environment=environment, release=plain_release)
    messages = {
        "characters": [message("a" * 4000), message("b", 2)],
        "messages": [message("인사", i + 1) for i in range(11)],
        "url": [message("https://example.invalid")],
        "email": [message("synthetic@example.invalid")],
        "phone": [message("010-0000-0000")],
        "order": [message("인사", 2), message("인사", 1)],
    }[case]
    expect(host.post(request_for(plain_release, messages)), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("environment", ["dev", "prod"])
def test_plain_rules_do_not_invent_token_counts(main_factory, plain_release, monkeypatch, environment):
    def forbidden(*args, **kwargs):
        pytest.fail("Plain rules requested model tokens")

    host = main_factory(environment=environment, release=plain_release)
    monkeypatch.setattr(LocalRuleRiskEngine, "count_tokens", forbidden)
    # More than 3,000 serialized bytes is valid for rules within the character limit.
    expect(host.post(request_for(plain_release, [message("가" * 4000)])))
    expect(host.post(request_for(plain_release, [message("가" * 4001)])), 400, "INVALID_REQUEST")


def test_plain_engine_never_returns_a_fake_zero_count():
    with pytest.raises(EngineUnavailableError):
        LocalRuleRiskEngine().count_tokens([], {})


@pytest.mark.parametrize("environment", ["dev", "prod"])
@pytest.mark.parametrize("target", ["descriptor", "artifact"])
def test_plain_release_mutation_is_rejected(main_factory, plain_release, environment, target):
    host = main_factory(environment=environment, release=plain_release)
    path = getattr(plain_release, target)
    path.write_bytes(path.read_bytes() + b" ")
    expect(host.post(), 422, "POLICY_UNRESOLVED")


@pytest.mark.parametrize("environment", ["dev", "prod"])
@pytest.mark.parametrize("field", ["modelVersion", "promptVersion", "inputVersion", "scope", "inputLimits"])
def test_plain_release_version_mismatch_fails_startup(main_factory, plain_release, environment, field):
    artifact = json.loads(plain_release.artifact.read_bytes())
    artifact[field] = {"maxMessages": 11, "maxCharacters": 4000} if field == "inputLimits" else "unreviewed"
    plain_release.rewrite_artifact(artifact)
    with pytest.raises(DevelopmentReleaseError):
        main_factory(environment=environment, release=plain_release)


@pytest.mark.parametrize("environment", ["dev", "prod"])
def test_new_descriptor_hash_cannot_approve_old_runtime_source(main_factory, plain_release, environment):
    artifact = json.loads(plain_release.artifact.read_bytes())
    # Actual runtime.py SHA-256 before this change and before any test rewrite.
    artifact["sources"]["runtime.py"] = "485f25f0d1b7aee55b7a98ae9cf9c213306478cedb576638c623632295009a7c"
    plain_release.rewrite_artifact(artifact)
    with pytest.raises(DevelopmentReleaseError):
        main_factory(environment=environment, release=plain_release, pin=plain_release.test_descriptor_pin)


@pytest.mark.parametrize("environment", ["dev", "prod"])
@pytest.mark.parametrize("previous", [
    "fd440ab3a7144ef715d4f73db7d240c474d5056cb6dc465765634ac48392aa73",  # develop before SG02
    "40ea590152748557433642c715733e314f01fde69eff1a6cb4381d7b541aa7ed",  # MR 405 before review
])
def test_sg02_matching_descriptor_pin_cannot_approve_pre_fix_rules(main_factory, plain_release, environment, previous):
    artifact = json.loads(plain_release.artifact.read_bytes())
    assert artifact["sources"]["rule_engine.py"] != previous
    artifact["sources"]["rule_engine.py"] = previous
    plain_release.rewrite_artifact(artifact)
    with pytest.raises(DevelopmentReleaseError):
        main_factory(environment=environment, release=plain_release, pin=plain_release.test_descriptor_pin)


@pytest.mark.parametrize("environment", ["dev", "prod"])
def test_absent_release_preserves_other_routes_but_partial_configuration_fails_startup(
        main_factory, plain_release, environment):
    host = main_factory(environment=environment, release=plain_release, configured=False)
    assert host.client.get("/health").status_code == 200
    paths = host.client.app.openapi()["paths"]
    assert any("icebreaker" in path for path in paths)
    assert any("explanation" in path for path in paths)
    expect(host.post(), 422, "POLICY_UNRESOLVED")
    with pytest.raises(DevelopmentReleaseError):
        main_factory(environment=environment, release=plain_release, pin="")


def test_legacy_development_release_is_not_promoted(tmp_path):
    legacy = synthetic_release(tmp_path, development_only=True)
    with pytest.raises(DevelopmentReleaseError):
        LocalRuleReleaseCatalog(str(legacy.descriptor), descriptor_sha256=legacy.test_descriptor_pin)
