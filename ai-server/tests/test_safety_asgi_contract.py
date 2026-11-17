"""Merged app.main ASGI contracts; all dialogue/releases/credentials synthetic.

Real runtime cases and explicitly labelled engine fault injections are separate.
No standalone FastAPI app, direct route call or service-auth override is used.
"""

from copy import deepcopy
import json
import time
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.risk_detection import local_engine
from app.risk_detection.http_api import Finding, Message, Payload
from app.risk_detection.local_engine import LocalRuleRiskEngine
from app.risk_detection.releases import TokenizedLocalRuleReleaseCatalog, DevelopmentReleaseError
from app.risk_detection.tokenization import LocalFileTokenizer, serialize_context
from safety48_runtime_support import message, request_for, synthetic_release
from safety_asgi_contract_support import (
    SECRET_MARKER, expect, main_factory, no_external_io, synthetic_private_keys,
)
from test_risk_detection_http import VERSION_FIELDS, finding, verdict


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
def test_missing_release_never_registers_an_engine(main_factory, monkeypatch, environment):
    def forbidden(*args):
        pytest.fail("Disabled runtime reached the engine")
    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", forbidden)
    monkeypatch.setattr(LocalRuleRiskEngine, "count_tokens", forbidden)
    host = main_factory(environment=environment, configured=False)
    expect(host.post(raw=b"invalid-body-not-read"), 422, "POLICY_UNRESOLVED")
    expect(host.post(raw=b"invalid-body-not-read", credential=None), 401, "AUTH_REQUIRED")


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
def test_synthetic_release_works_in_every_environment_without_switch(main_factory, environment):
    host = main_factory(environment=environment)
    expect(host.post())


@pytest.mark.parametrize("pin", ["", "0" * 64])
def test_main_startup_refuses_unapproved_descriptor_pin(main_factory, pin):
    with pytest.raises(DevelopmentReleaseError, match="^Safety Guard development runtime unavailable$"):
        main_factory(pin=pin)


def test_main_without_service_keys_is_unavailable(main_factory):
    host = main_factory(keys=False)
    expect(host.post(), 503, "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("case,status,code", [
    ("missing", 401, "AUTH_REQUIRED"), ("signature", 401, "ACCESS_TOKEN_INVALID"),
    ("scope", 403, "FORBIDDEN"), ("no-scope", 403, "FORBIDDEN"),
    ("expired", 401, "ACCESS_TOKEN_EXPIRED"), ("issuer", 401, "ACCESS_TOKEN_INVALID"),
    ("audience", 401, "ACCESS_TOKEN_INVALID"), ("subject", 401, "ACCESS_TOKEN_INVALID"),
    ("kid", 401, "ACCESS_TOKEN_INVALID"), ("type", 401, "ACCESS_TOKEN_INVALID"),
    ("lifetime", 401, "ACCESS_TOKEN_INVALID"), ("future", 401, "ACCESS_TOKEN_INVALID"),
])
def test_real_jwt_gate_precedes_body_and_engine(main_factory, case, status, code):
    host = main_factory()
    now = int(time.time())
    changes = {
        "scope": {"scope": "ai:risk"}, "issuer": {"iss": "urn:tingle:be:prod"},
        "audience": {"aud": "urn:tingle:ai:prod"}, "subject": {"sub": "synthetic-other-service"},
        "expired": {"iat": now - 400, "nbf": now - 400, "exp": now - 100},
        "lifetime": {"iat": now, "nbf": now, "exp": now + 301},
        "future": {"iat": now + 60, "nbf": now + 60, "exp": now + 360},
    }.get(case)
    credential = None if case == "missing" else host.token(
        changes, wrong_key=case == "signature", omit=("scope",) if case == "no-scope" else (),
        headers={"kid": "unregistered"} if case == "kid" else {"typ": "JWT"} if case == "type" else None)
    response = host.post(raw=b"invalid-sensitive-body", credential=credential)
    expect(response, status, code)
    assert response.headers["www-authenticate"].startswith('Bearer realm="tingle-ai"')
    assert "invalid-sensitive-body" not in response.text


@pytest.mark.parametrize("case", ["syntax", "duplicate-root", "duplicate-message", "nan", "trailing", "utf8"])
def test_json_parser_contract(main_factory, case):
    host = main_factory()
    raw = json.dumps(request_for(host.release)).encode()
    if case == "syntax":
        raw = b"{invalid"
    elif case == "duplicate-root":
        raw = raw[:-1] + b',"contextId":2}'
    elif case == "duplicate-message":
        raw = raw.replace(b'"sequence": 1', b'"sequence": 1, "sequence": 2')
    elif case == "nan":
        raw = raw.replace(b'"contextId": 123', b'"contextId": NaN')
    elif case == "trailing":
        raw += b"{}"
    else:
        raw = b"\xff"
    expect(host.post(raw=raw), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("size,status", [(65535, 200), (65536, 200), (65537, 400)])
@pytest.mark.parametrize("chunked", [False, True])
def test_body_byte_limit_including_streamed_chunks(main_factory, size, status, chunked):
    host = main_factory()
    raw = json.dumps(request_for(host.release), ensure_ascii=False).encode("utf-8")
    raw += b" " * (size - len(raw))
    assert len(raw) == size
    content = iter((raw[:32768], raw[32768:65536], raw[65536:])) if chunked else raw
    response = host.post(raw=content)
    expect(response, status, "INVALID_REQUEST" if status == 400 else None)
    if chunked:
        assert len(response.extensions["contract_observation"]["asgiChunkSizes"]) >= 2


@pytest.mark.parametrize("field", ["attemptId", "contextId", "manifest", "contextBundle"])
def test_required_payload_fields(main_factory, field):
    host = main_factory()
    payload = request_for(host.release)
    payload.pop(field)
    expect(host.post(payload), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("case", [
    "sequence-missing", "sequence-duplicate", "sequence-descending", "sequence-zero", "sequence-bool",
    "fresh-missing", "fresh-string", "fresh-number", "fresh-none", "no-fresh", "old-after-fresh",
    "empty-bundle", "eleven-messages", "text-empty", "text-blank", "text-4001", "total-4001",
    "speaker-invalid", "root-extra", "message-extra", "manifest-extra", "context-bool", "context-overflow",
])
def test_dto_and_context_boundaries(main_factory, case):
    host = main_factory()
    payload = request_for(host.release, [message("합성", 1, fresh=False), message("합성", 2)])
    messages = payload["contextBundle"]
    if case == "sequence-missing":
        messages[0].pop("sequence")
    elif case in {"sequence-duplicate", "sequence-descending", "sequence-zero", "sequence-bool"}:
        messages[1]["sequence"] = {"sequence-duplicate": 1, "sequence-descending": -1,
                                   "sequence-zero": 0, "sequence-bool": True}[case]
        if case == "sequence-descending":
            messages[0]["sequence"], messages[1]["sequence"] = 2, 1
    elif case == "fresh-missing":
        messages[1].pop("fresh")
    elif case.startswith("fresh-"):
        messages[1]["fresh"] = {"fresh-string": "true", "fresh-number": 1, "fresh-none": None}[case]
    elif case == "no-fresh":
        messages[1]["fresh"] = False
    elif case == "old-after-fresh":
        messages[0]["fresh"], messages[1]["fresh"] = True, False
    elif case == "empty-bundle":
        payload["contextBundle"] = []
    elif case == "eleven-messages":
        payload["contextBundle"] = [message("합성", i + 1) for i in range(11)]
    elif case.startswith("text-"):
        messages[1]["text"] = {"text-empty": "", "text-blank": " \t\n", "text-4001": "가" * 4001}[case]
    elif case == "total-4001":
        messages[0]["text"], messages[1]["text"] = "가" * 2000, "나" * 2001
    elif case == "speaker-invalid":
        messages[1]["speaker"] = "USER"
    elif case == "root-extra":
        payload["state"] = SECRET_MARKER
    elif case == "message-extra":
        messages[1]["userId"] = 99
    elif case == "manifest-extra":
        payload["manifest"]["shadowVersion"] = SECRET_MARKER
    else:
        payload["contextId"] = True if case == "context-bool" else 2**63
    expect(host.post(payload), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("case", ["single-4000", "ten-total-4000", "unicode-4000", "sequence-gap", "long-max"])
def test_approved_input_upper_boundaries(main_factory, case):
    host = main_factory()
    messages = [message("가" * 4000)]
    if case == "ten-total-4000":
        messages = [message("가" * 400, i + 1, fresh=i >= 2) for i in range(10)]
    elif case == "unicode-4000":
        messages = [message("🌱" * 4000)]
    elif case == "sequence-gap":
        messages = [message("합성 이전", 2, fresh=False), message("합성 신규", 9)]
    elif case == "long-max":
        messages = [message("합성", 2**63 - 1)]
    expect(host.post(request_for(host.release, messages)))


@pytest.mark.parametrize("field", ["releaseId", *VERSION_FIELDS, "contentHash"])
def test_entire_manifest_must_equal_registered_release(main_factory, field):
    host = main_factory()
    payload = request_for(host.release)
    payload["manifest"][field] = str(uuid4()) if field == "releaseId" else "0" * 64 if field == "contentHash" else "unregistered-version"
    expect(host.post(payload), 422, "POLICY_UNRESOLVED")


@pytest.mark.parametrize("field", ["releaseId", *VERSION_FIELDS, "contentHash"])
def test_all_eleven_manifest_fields_are_required(main_factory, field):
    host = main_factory()
    payload = request_for(host.release)
    payload["manifest"].pop(field)
    expect(host.post(payload), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("target", ["descriptor", "artifact", "tokenizer"])
@pytest.mark.parametrize("during", [False, True], ids=["before-engine", "during-engine"])
def test_real_release_integrity_before_and_after_analysis(main_factory, monkeypatch, target, during):
    host = main_factory()
    original = LocalRuleRiskEngine.analyze
    calls = []

    def mutate():
        path = getattr(host.release, target)
        path.write_bytes(path.read_bytes() + b" ")

    def analyze(engine, messages, manifest):
        calls.append(True)
        result = original(engine, messages, manifest)
        if during:
            mutate()
        return result

    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", analyze)
    if not during:
        mutate()
    expect(host.post(), 422, "POLICY_UNRESOLVED")
    assert len(calls) == int(during)


@pytest.mark.parametrize("change", [
    "missing-tokenizer", "tokenizer-hash", "broken-tokenizer", "artifact-hash", "modelVersion",
    "promptVersion", "inputVersion", "tokenizersVersion", "sources", "missing-version",
])
def test_main_startup_verifies_artifact_and_tokenizer(main_factory, tmp_path, change):
    release = synthetic_release(tmp_path)
    artifact = json.loads(release.artifact.read_text(encoding="utf-8"))
    if change == "missing-tokenizer":
        release.tokenizer.unlink()
    elif change == "tokenizer-hash":
        artifact["tokenizer"]["sha256"] = "0" * 64
    elif change == "broken-tokenizer":
        import hashlib
        release.tokenizer.write_bytes(b"{}")
        artifact["tokenizer"]["sha256"] = hashlib.sha256(b"{}").hexdigest()
    elif change == "sources":
        artifact["sources"]["rule_engine.py"] = "0" * 64
    elif change == "missing-version":
        artifact.pop("inputVersion")
    elif change != "artifact-hash":
        artifact[change] = "unapproved-synthetic-version"
    release.rewrite_artifact(artifact)
    if change == "artifact-hash":
        release.artifact.write_bytes(b"changed")
    with pytest.raises(DevelopmentReleaseError, match="^Safety Guard development runtime unavailable$"):
        main_factory(release=release)


@pytest.mark.parametrize("total,status", [(3000, 200), (3001, 400)])
def test_actual_registered_tokenizer_exact_limit(main_factory, tmp_path, total, status):
    release = synthetic_release(tmp_path)
    catalog = TokenizedLocalRuleReleaseCatalog(str(release.descriptor), descriptor_sha256=release.test_descriptor_pin)
    overhead = catalog.tokenizer.count(serialize_context([Message(**message("!"))])) - 1
    messages = [message("!" * (total - overhead))]
    assert catalog.tokenizer.count(serialize_context([Message(**m) for m in messages])) == total
    host = main_factory(release=release)
    expect(host.post(request_for(release, messages)), status, "INVALID_REQUEST" if status == 400 else None)


@pytest.mark.parametrize("value", [-1, True, 2.5, "100", None])
def test_real_engine_rejects_malformed_tokenizer_result(main_factory, monkeypatch, value):
    monkeypatch.setattr(LocalFileTokenizer, "count", lambda *_: value)
    host = main_factory()
    expect(host.post(), 503, "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("value", [-1, 3001, True, 2.5, "100", None])
def test_transport_rejects_malformed_engine_token_count(main_factory, monkeypatch, value):
    host = main_factory()
    monkeypatch.setattr(LocalRuleRiskEngine, "count_tokens", lambda *_: value)
    expect(host.post(), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("kind,text", [
    ("NORMAL", "합성 점심값은 반씩 나누자."), ("RISK", "원금 보장하니 인증금을 보내."),
    ("INSUFFICIENT", "그거 보내 줘. 알지?"),
])
def test_actual_rule_verdict_and_be_wire_shape(main_factory, kind, text):
    host = main_factory()
    payload = request_for(host.release, [message(text)])
    body = expect(host.post(payload))
    assert set(body) == {"attemptId", "manifest", "verdict"}
    assert body["attemptId"] == payload["attemptId"] and body["manifest"] == payload["manifest"]
    assert body["verdict"]["kind"] == kind
    assert set(body["verdict"]) == {"kind", "findings", "confidence", "modelVersion", "promptVersion"}
    assert body["verdict"]["confidence"] is None
    assert text not in json.dumps(body, ensure_ascii=False)
    assert body["verdict"]["findings"] == ([finding()] if kind == "RISK" else [])


def test_actual_rules_can_use_old_context_but_only_return_fresh_evidence(main_factory):
    host = main_factory()
    payload = request_for(host.release, [message("원금 보장 투자에 인증금을 보내.", fresh=False), message("지금 보내.", 2)])
    body = expect(host.post(payload))
    assert body["verdict"]["kind"] == "RISK"
    assert body["verdict"]["findings"] == [finding(sequence=2)]


@pytest.mark.parametrize("kind", ["NORMAL", "RISK", "INSUFFICIENT", "FAILED"])
def test_controlled_engine_preserves_all_existing_be_verdicts(main_factory, monkeypatch, kind):
    host = main_factory()
    expected = verdict(kind, [finding()] if kind == "RISK" else [])
    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", lambda *_: deepcopy(expected))
    assert expect(host.post())["verdict"] == expected


@pytest.mark.parametrize("mapping", [
    ("FRAUD", "MONEY_REQUEST", "RECIPIENT"), ("HARASSMENT", "HARASSMENT", "BOTH"),
    ("THREAT", "HARASSMENT", "BOTH"), ("OTHER", "PERSONAL_INFO", "RECIPIENT"),
    ("OTHER", "OFF_PLATFORM", "RECIPIENT"),
])
def test_controlled_engine_valid_findings(main_factory, monkeypatch, mapping):
    host = main_factory()
    expected = verdict("RISK", [finding(category=mapping[0], notice=mapping[1], audience=mapping[2])])
    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", lambda *_: deepcopy(expected))
    assert expect(host.post())["verdict"] == expected


@pytest.mark.parametrize("total,status", [(40, 200), (41, 503)])
def test_controlled_engine_finding_count_boundary(main_factory, monkeypatch, total, status):
    host = main_factory()
    mappings = [("FRAUD", "MONEY_REQUEST", "RECIPIENT"), ("HARASSMENT", "HARASSMENT", "BOTH"),
                ("THREAT", "HARASSMENT", "BOTH"), ("OTHER", "PERSONAL_INFO", "RECIPIENT"),
                ("OTHER", "OFF_PLATFORM", "RECIPIENT")]
    items = [finding(sequence=i + 1, category=c, notice=n, audience=a)
             for i in range(10) for c, n, a in mappings][:total]
    result = verdict("RISK", items)
    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", lambda *_: deepcopy(result))
    payload = request_for(host.release, [message("합성", i + 1) for i in range(10)])
    body = expect(host.post(payload), status, "SERVICE_UNAVAILABLE" if status == 503 else None)
    if status == 200:
        assert len(body["verdict"]["findings"]) == 40


@pytest.mark.parametrize("case", [
    "old-sequence", "unknown-sequence", "category-notice", "recipient-sender", "recipient-both",
    "harassment-recipient", "unknown-category", "unknown-notice", "unknown-audience", "missing-sequence",
    "duplicate", "risk-empty", "normal-findings", "failed-findings", "findings-41", "confidence-nan",
    "confidence-over", "version-blank", "version-missing", "shadow-state", "shadow-route", "shadow-audit",
])
def test_controlled_engine_invalid_results_fail_closed(main_factory, monkeypatch, case):
    host = main_factory()
    payload = request_for(host.release, [message("합성 이전", fresh=False), message("합성 신규", 2)])
    result = verdict("RISK", [finding(sequence=2)])
    item = result["findings"][0]
    if case in {"old-sequence", "unknown-sequence"}:
        item["sequence"] = 1 if case == "old-sequence" else 99
    elif case == "category-notice":
        item["category"] = "OTHER"
    elif case in {"recipient-sender", "recipient-both"}:
        item["audience"] = "SENDER" if case == "recipient-sender" else "BOTH"
    elif case == "harassment-recipient":
        item.update(category="THREAT", noticeType="HARASSMENT", audience="RECIPIENT")
    elif case.startswith("unknown-"):
        item[{"unknown-category": "category", "unknown-notice": "noticeType", "unknown-audience": "audience"}[case]] = "UNKNOWN"
    elif case == "missing-sequence":
        item.pop("sequence")
    elif case in {"duplicate", "findings-41"}:
        result["findings"] *= 2 if case == "duplicate" else 41
    elif case == "risk-empty":
        result["findings"] = []
    elif case in {"normal-findings", "failed-findings"}:
        result["kind"] = "NORMAL" if case == "normal-findings" else "FAILED"
    elif case.startswith("confidence-"):
        result["confidence"] = float("nan") if case == "confidence-nan" else 1.01
    elif case == "version-blank":
        result["modelVersion"] = " "
    elif case == "version-missing":
        result.pop("promptVersion")
    else:
        result[case.removeprefix("shadow-")] = SECRET_MARKER
    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", lambda *_: deepcopy(result))
    expect(host.post(payload), 503, "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("stage", ["tokenizer", "rules", "engine"])
def test_runtime_exceptions_never_leak_dialogue_model_response_or_credentials(main_factory, monkeypatch, stage):
    def fail(*args):
        raise RuntimeError(SECRET_MARKER + " synthetic-dialogue synthetic-full-model-response")
    if stage == "tokenizer":
        monkeypatch.setattr(LocalFileTokenizer, "count", fail)
    elif stage == "rules":
        monkeypatch.setattr(local_engine, "classify", fail)
    else:
        monkeypatch.setattr(LocalRuleRiskEngine, "analyze", fail)
    host = main_factory()
    body = expect(host.post(request_for(host.release, [message("synthetic-dialogue")])), 503, "SERVICE_UNAVAILABLE")
    assert "synthetic-dialogue" not in json.dumps(body)
    assert "synthetic-full-model-response" not in json.dumps(body)


@pytest.mark.parametrize("text", ["https://example.invalid", "synthetic@example.invalid", "010-0000-0000", "text\u0000value"])
@pytest.mark.parametrize("masked", [False, True])
def test_unmasked_identifiers_cannot_be_approved_by_a_flag(main_factory, text, masked):
    host = main_factory()
    item = message(text)
    item["urlMasked"] = masked
    response = host.post(request_for(host.release, [item]))
    expect(response, 400, "INVALID_REQUEST")
    assert text not in response.text


def test_safe_response_and_request_id_correlation(main_factory):
    host = main_factory()
    request_id = str(uuid4())
    response = host.post(headers={"X-Request-Id": request_id})
    body = expect(response)
    assert response.headers["x-request-id"] == request_id
    assert set(body) == {"attemptId", "manifest", "verdict"}
    assert not {"state", "route", "audit", "contextId", "contextBundle", "text", "rawResponse"} & set(body)


# Contract discrepancy probes intentionally retain their expected contract.
# A service defect must remain FAIL; do not xfail/skip or change the expectation.
def test_sequence_must_fit_be_signed_long(main_factory):
    host = main_factory()
    response = host.post(request_for(host.release, [message("널 때릴 거야.", 2**63)]))
    expect(response, 400, "INVALID_REQUEST")


@pytest.mark.parametrize("depth", [1100, 10000])
def test_deep_json_is_invalid_request_instead_of_internal_error(main_factory, depth):
    host = main_factory()
    expect(host.post(raw=b"[" * depth + b"0" + b"]" * depth), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("sequence,status", [(2**63 - 1, 200), (2**63, 503)])
def test_finding_sequence_must_fit_be_signed_long(main_factory, monkeypatch, sequence, status):
    item = finding(sequence=sequence)
    # Test the DTO too: an unknown sequence would already fail the fresh check.
    if status == 200:
        assert Finding.model_validate(item).sequence == sequence
    else:
        with pytest.raises(ValidationError):
            Finding.model_validate(item)
    host = main_factory()
    result = verdict("RISK", [item])
    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", lambda *_: deepcopy(result))
    payload = request_for(host.release, [message("합성", 2**63 - 1)])
    body = expect(host.post(payload), status, "SERVICE_UNAVAILABLE" if status == 503 else None)
    if status == 200:
        assert body["verdict"]["findings"] == [item]


def test_request_dto_recursion_is_invalid_without_reaching_engine(main_factory, monkeypatch):
    host = main_factory()

    def fail(*args):
        raise RecursionError(SECRET_MARKER + " synthetic-dto-error-detail")

    def forbidden(*args):
        pytest.fail("Invalid request reached the engine")

    monkeypatch.setattr(Payload, "model_validate", fail)
    monkeypatch.setattr(LocalRuleRiskEngine, "count_tokens", forbidden)
    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", forbidden)
    expect(host.post(request_for(host.release, [message(SECRET_MARKER)])), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("method", ["count_tokens", "analyze"])
def test_engine_recursion_remains_service_unavailable(main_factory, monkeypatch, method):
    host = main_factory()

    def fail(*args):
        raise RecursionError(SECRET_MARKER + " synthetic-engine-error-detail")

    monkeypatch.setattr(LocalRuleRiskEngine, method, fail)
    expect(host.post(request_for(host.release, [message(SECRET_MARKER)])), 503, "SERVICE_UNAVAILABLE")


def test_main_startup_refuses_pre_contract_source_with_matching_pin(main_factory, tmp_path):
    release = synthetic_release(tmp_path)
    artifact = json.loads(release.artifact.read_text(encoding="utf-8"))
    # Actual http_api.py bytes at baseline HEAD 6d385e0, before this contract fix.
    previous_source = "d08a2b0710f51b688369d3ddb05125081c2743fca72ff4e2a8576a9f6d6c298b"
    assert artifact["sources"]["http_api.py"] != previous_source
    artifact["sources"]["http_api.py"] = previous_source
    release.rewrite_artifact(artifact)
    with pytest.raises(DevelopmentReleaseError, match="^Safety Guard development runtime unavailable$"):
        main_factory(release=release, pin=release.test_descriptor_pin)
