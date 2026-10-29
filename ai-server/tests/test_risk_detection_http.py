"""Synthetic in-process HTTP and temporary release bytes; no provider calls or real dialogue."""

from copy import deepcopy
import hashlib
import json
from uuid import UUID, uuid4

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import pytest

from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.risk_detection.http_api import ReleaseCatalog, create_router


ROUTE = "/internal/ai/risk-detections"
PRIVATE_TEXT = "synthetic-private-message-never-return"
VERSION_FIELDS = (
    "surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion", "analysisVersion",
    "typeReferenceVersion", "matchingVersion", "productPolicyVersion", "engineVersion",
)


def verdict(kind="NORMAL", findings=None, **changes):
    result = {
        "kind": kind, "findings": findings or [], "confidence": 0.8,
        "modelVersion": "synthetic-model-v1", "promptVersion": "synthetic-prompt-v1",
    }
    result.update(changes)
    return result


def finding(sequence=1, category="FRAUD", notice="MONEY_REQUEST", audience="RECIPIENT"):
    return {"sequence": sequence, "category": category, "noticeType": notice, "audience": audience}


class SyntheticEngine:
    def __init__(self, result=None, tokens=100, tokenizer_error=False, model_error=False):
        self.result = result or verdict()
        self.tokens = tokens
        self.tokenizer_error = tokenizer_error
        self.model_error = model_error
        self.counted = []
        self.analyzed = []

    def count_tokens(self, messages, manifest):
        self.counted.append(deepcopy((messages, manifest)))
        if self.tokenizer_error:
            raise RuntimeError(PRIVATE_TEXT + "-tokenizer-path-secret")
        return self.tokens

    def analyze(self, messages, manifest):
        self.analyzed.append(deepcopy((messages, manifest)))
        if self.model_error:
            raise RuntimeError(PRIVATE_TEXT + "-provider-secret")
        return deepcopy(self.result)


@pytest.fixture
def release(tmp_path):
    artifact = tmp_path / "artifact.json"
    artifact.write_bytes(b'{"syntheticRiskRules":true}')
    manifest = {field: "synthetic-v1" for field in VERSION_FIELDS}
    manifest.update(releaseId="11111111-1111-4111-8111-111111111111",
                    contentHash=hashlib.sha256(artifact.read_bytes()).hexdigest())
    descriptor = tmp_path / "release.json"
    descriptor.write_text(json.dumps({"manifest": manifest, "artifact": "artifact.json"}), encoding="utf-8")
    return descriptor, artifact, manifest


def payload(release):
    return {
        "attemptId": str(uuid4()), "contextId": 123, "manifest": deepcopy(release[2]),
        "contextBundle": [{"sequence": 1, "speaker": "A", "text": PRIVATE_TEXT,
                           "fresh": True, "urlMasked": False}],
    }


def client_for(release, engine, authenticate=None):
    # This test double covers the domain boundary; cryptographic auth has its own tests.
    app = FastAPI()
    install_error_handlers(app)
    app.add_middleware(RequestIdMiddleware)
    app.include_router(create_router(authenticate or (lambda request: {"scope": "ai:execute"}),
                                     ReleaseCatalog(str(release[0])), engine))
    return TestClient(app)


def assert_error(response, status, code):
    assert response.status_code == status
    assert response.json()["code"] == code
    assert set(response.json()) == {"code", "message"}
    assert response.headers["cache-control"] == "no-store"
    assert str(UUID(response.headers["x-request-id"])) == response.headers["x-request-id"].lower()
    assert PRIVATE_TEXT not in response.text


@pytest.mark.parametrize("kind", ["NORMAL", "RISK", "INSUFFICIENT", "FAILED"])
def test_transport_preserves_each_verdict_without_echoing_dialogue_or_context_identity(release, kind):
    expected = verdict(kind, [finding()] if kind == "RISK" else [])
    engine = SyntheticEngine(expected)
    request = payload(release)
    response = client_for(release, engine).post(ROUTE, json=request)
    assert response.status_code == 200
    assert response.json() == {"attemptId": request["attemptId"], "manifest": request["manifest"],
                               "verdict": expected}
    assert "contextId" not in response.json()
    assert PRIVATE_TEXT not in response.text
    assert response.headers["cache-control"] == "no-store"
    assert engine.counted == [(request["contextBundle"], request["manifest"])]
    assert engine.analyzed == engine.counted


def test_unregistered_engine_is_rejected_before_request_validation(release):
    response = client_for(release, None).post(ROUTE, content=b"{invalid-private-body")
    assert_error(response, 422, "POLICY_UNRESOLVED")


def test_authentication_runs_before_model_availability_or_body_validation(release):
    calls = []

    def denied(request):
        calls.append(request.url.path)
        raise HTTPException(401, detail={"code": "AUTH_REQUIRED", "message": PRIVATE_TEXT})

    response = client_for(release, None, denied).post(ROUTE, content=b"{invalid")
    assert_error(response, 401, "AUTH_REQUIRED")
    assert calls == [ROUTE]


@pytest.mark.parametrize("change", ["missing", "tampered", "unknown-version", "extra-field"])
def test_unverifiable_release_files_never_reach_the_engine(release, change):
    descriptor, artifact, manifest = release
    if change == "missing":
        artifact.unlink()
    elif change == "tampered":
        artifact.write_text("changed synthetic rules", encoding="utf-8")
    elif change == "unknown-version":
        altered = {**manifest, "analysisVersion": "synthetic-v2"}
        descriptor.write_text(json.dumps({"manifest": altered, "artifact": "artifact.json"}), encoding="utf-8")
    else:
        descriptor.write_text(json.dumps({"manifest": manifest, "artifact": "artifact.json", "extra": True}),
                              encoding="utf-8")
    engine = SyntheticEngine()
    assert_error(client_for(release, engine).post(ROUTE, json=payload(release)), 422, "POLICY_UNRESOLVED")
    assert engine.counted == engine.analyzed == []


@pytest.mark.parametrize("reference", ["../artifact.json", "nested/../artifact.json", "./artifact.json"])
def test_release_descriptor_cannot_use_traversal_even_if_normalization_returns_inside(release, reference):
    descriptor, _, manifest = release
    (descriptor.parent / "nested").mkdir()
    descriptor.write_text(json.dumps({"manifest": manifest, "artifact": reference}), encoding="utf-8")
    engine = SyntheticEngine()
    assert_error(client_for(release, engine).post(ROUTE, json=payload(release)), 422, "POLICY_UNRESOLVED")
    assert engine.counted == engine.analyzed == []


def test_message_character_and_token_limits_are_independent_at_their_exact_boundaries(release):
    engine = SyntheticEngine(tokens=3000)
    request = payload(release)
    request["contextBundle"] = [
        {"sequence": index + 1, "speaker": "A" if index % 2 == 0 else "B", "text": "가" * 400,
         "fresh": index >= 2, "urlMasked": False} for index in range(10)
    ]
    response = client_for(release, engine).post(ROUTE, json=request)
    assert response.status_code == 200
    assert engine.analyzed[0][0] == request["contextBundle"]
    assert [message["sequence"] for message in engine.analyzed[0][0] if message["fresh"]] == list(range(3, 11))


@pytest.mark.parametrize("limit", ["messages", "characters", "unicode-characters", "tokens"])
def test_each_input_cap_is_enforced_without_silently_dropping_a_fresh_message(release, limit):
    request = payload(release)
    engine = SyntheticEngine(tokens=3001 if limit == "tokens" else 1)
    if limit == "messages":
        request["contextBundle"] = [{**request["contextBundle"][0], "sequence": i + 1} for i in range(11)]
    elif limit == "characters":
        request["contextBundle"] = [{**request["contextBundle"][0], "sequence": i + 1, "text": "x" * 2001}
                                     for i in range(2)]
    elif limit == "unicode-characters":
        request["contextBundle"][0]["text"] = "🌱" * 4001
    assert_error(client_for(release, engine).post(ROUTE, json=request), 400, "INVALID_REQUEST")
    assert engine.analyzed == []
    assert len(engine.counted) == (1 if limit == "tokens" else 0)


def test_character_budget_counts_unicode_codepoints_instead_of_utf16_units(release):
    request = payload(release)
    request["contextBundle"][0]["text"] = "🌱" * 4000
    engine = SyntheticEngine(tokens=3000)
    assert client_for(release, engine).post(ROUTE, json=request).status_code == 200
    assert engine.analyzed[0][0][0]["text"] == request["contextBundle"][0]["text"]


@pytest.mark.parametrize("tokens", [-1, 3001, True, 2.5, "100"])
def test_tokenizer_must_return_an_independently_valid_bounded_integer(release, tokens):
    engine = SyntheticEngine(tokens=tokens)
    assert_error(client_for(release, engine).post(ROUTE, json=payload(release)), 400, "INVALID_REQUEST")
    assert engine.analyzed == []


@pytest.mark.parametrize("case", ["descending", "duplicate", "context-after-fresh", "no-fresh", "blank"])
def test_order_and_fresh_context_boundary_are_validated_before_tokenization(release, case):
    request = payload(release)
    first = request["contextBundle"][0]
    request["contextBundle"] = [first, {**first, "sequence": 2}]
    if case == "descending":
        request["contextBundle"].reverse()
    elif case == "duplicate":
        request["contextBundle"][1]["sequence"] = 1
    elif case == "context-after-fresh":
        request["contextBundle"][1]["fresh"] = False
    elif case == "no-fresh":
        for message in request["contextBundle"]:
            message["fresh"] = False
    else:
        request["contextBundle"][0]["text"] = " \t\n"
    engine = SyntheticEngine()
    assert_error(client_for(release, engine).post(ROUTE, json=request), 400, "INVALID_REQUEST")
    assert engine.counted == engine.analyzed == []


@pytest.mark.parametrize("valid", [finding(), finding(category="OTHER", notice="PERSONAL_INFO"),
                                   finding(category="OTHER", notice="OFF_PLATFORM"),
                                   finding(category="HARASSMENT", notice="HARASSMENT", audience="BOTH"),
                                   finding(category="THREAT", notice="HARASSMENT", audience="BOTH")])
def test_explicit_supported_notice_audience_mappings_are_preserved(release, valid):
    expected = verdict("RISK", [valid])
    response = client_for(release, SyntheticEngine(expected)).post(ROUTE, json=payload(release))
    assert response.status_code == 200
    assert response.json()["verdict"] == expected


@pytest.mark.parametrize("case", ["context-evidence", "missing-evidence", "duplicate", "sender",
                                  "wrong-category", "no-risk-finding", "normal-with-finding", "bad-confidence",
                                  "unknown-category", "blank-model-version"])
def test_invalid_model_result_is_unavailable_and_never_becomes_a_notice(release, case):
    request = payload(release)
    request["contextBundle"] = [{**request["contextBundle"][0], "fresh": False},
                                 {**request["contextBundle"][0], "sequence": 2}]
    result = verdict("RISK", [finding(sequence=2)])
    if case == "context-evidence":
        result["findings"][0]["sequence"] = 1
    elif case == "missing-evidence":
        result["findings"][0]["sequence"] = 999
    elif case == "duplicate":
        result["findings"] *= 2
    elif case == "sender":
        result["findings"][0]["audience"] = "SENDER"
    elif case == "wrong-category":
        result["findings"][0]["category"] = "OTHER"
    elif case == "no-risk-finding":
        result["findings"] = []
    elif case == "normal-with-finding":
        result["kind"] = "NORMAL"
    elif case == "bad-confidence":
        result["confidence"] = 1.01
    elif case == "unknown-category":
        result["findings"][0]["category"] = "invented"
    else:
        result["modelVersion"] = " "
    assert_error(client_for(release, SyntheticEngine(result)).post(ROUTE, json=request),
                 503, "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("case", ["unknown-root", "unknown-message", "unknown-manifest", "string-context",
                                  "boolean-context", "oversized-context", "boolean-sequence", "string-fresh",
                                  "numeric-version", "blank-version", "abbreviated-attempt", "uppercase-hash"])
def test_unknown_fields_and_json_type_coercion_are_rejected(release, case):
    request = payload(release)
    if case == "unknown-root":
        request["userId"] = 999
    elif case == "unknown-message":
        request["contextBundle"][0]["userId"] = 999
    elif case == "unknown-manifest":
        request["manifest"]["unapprovedVersion"] = "synthetic"
    elif case == "string-context":
        request["contextId"] = "123"
    elif case == "boolean-context":
        request["contextId"] = True
    elif case == "oversized-context":
        request["contextId"] = 9223372036854775808
    elif case == "boolean-sequence":
        request["contextBundle"][0]["sequence"] = True
    elif case == "string-fresh":
        request["contextBundle"][0]["fresh"] = "true"
    elif case == "numeric-version":
        request["manifest"]["surveyVersion"] = 1
    elif case == "blank-version":
        request["manifest"]["surveyVersion"] = " "
    elif case == "abbreviated-attempt":
        request["attemptId"] = "1-1-1-1-1"
    else:
        request["manifest"]["contentHash"] = "A" * 64
    engine = SyntheticEngine()
    assert_error(client_for(release, engine).post(ROUTE, json=request), 400, "INVALID_REQUEST")
    assert engine.counted == engine.analyzed == []


@pytest.mark.parametrize("case", ["duplicate-root", "duplicate-nested", "nan", "trailing", "oversized"])
def test_json_parser_rejects_duplicate_keys_nonfinite_constants_and_oversized_bodies(release, case):
    raw = json.dumps(payload(release))
    if case == "duplicate-root":
        raw = raw[:-1] + ', "contextId": 456}'
    elif case == "duplicate-nested":
        raw = raw.replace('"sequence": 1', '"sequence": 1, "sequence": 2')
    elif case == "nan":
        raw = raw.replace('"contextId": 123', '"contextId": NaN')
    elif case == "trailing":
        raw += "{}"
    else:
        raw = " " * 65537 + raw
    engine = SyntheticEngine()
    assert_error(client_for(release, engine).post(ROUTE, content=raw,
                                                 headers={"Content-Type": "application/json"}), 400, "INVALID_REQUEST")
    assert engine.counted == engine.analyzed == []


@pytest.mark.parametrize("stage", ["tokenizer", "model"])
def test_runtime_failures_return_generic_503_without_provider_or_message_text(release, stage, caplog):
    engine = SyntheticEngine(tokenizer_error=stage == "tokenizer", model_error=stage == "model")
    response = client_for(release, engine).post(ROUTE, json=payload(release))
    assert_error(response, 503, "SERVICE_UNAVAILABLE")
    assert "provider-secret" not in response.text
    assert "tokenizer-path-secret" not in response.text
    assert PRIVATE_TEXT not in caplog.text


@pytest.mark.parametrize("text", [
    "open https://example.invalid/private", "open HTTP://example.invalid/private",
    "open www.example.invalid", "send to synthetic@example.invalid",
    "call 010-0000-0000", "call +82 10 0000 0000", "call 01000000000",
    "text\u0000value", "text\rvalue", "text\u007fvalue", "text\u0085value",
    "text\ud800value", "text\udfffvalue",
])
@pytest.mark.parametrize("url_masked", [False, True])
def test_direct_route_rejects_unmasked_identifiers_and_invalid_characters_before_engine(release, text, url_masked):
    request = payload(release)
    request["contextBundle"][0].update(text=text, urlMasked=url_masked)
    engine = SyntheticEngine()
    # JSON escapes preserve an invalid surrogate until the server validates it;
    # the HTTP client must not fail earlier while trying to UTF-8 encode it.
    response = client_for(release, engine).post(ROUTE, content=json.dumps(request),
                                               headers={"Content-Type": "application/json"})
    assert_error(response, 400, "INVALID_REQUEST")
    assert engine.counted == engine.analyzed == []


def test_masked_placeholders_newlines_tabs_and_unicode_text_remain_valid(release):
    request = payload(release)
    request["contextBundle"][0].update(
        text="합성 대화🌱\n[웹 주소 차단]\t[이메일 숨김] [전화번호 숨김]", urlMasked=True,
    )
    engine = SyntheticEngine()
    response = client_for(release, engine).post(ROUTE, json=request)
    assert response.status_code == 200
    assert engine.analyzed[0][0] == request["contextBundle"]


@pytest.mark.parametrize("change", ["artifact", "descriptor", "deleted"])
def test_release_changed_inside_engine_discards_the_result_as_policy_unresolved(release, change):
    descriptor, artifact, manifest = release

    class TamperingEngine(SyntheticEngine):
        def analyze(self, messages, supplied_manifest):
            result = super().analyze(messages, supplied_manifest)
            if change == "artifact":
                artifact.write_text("changed while synthetic analysis was running", encoding="utf-8")
            elif change == "descriptor":
                descriptor.write_text(json.dumps({"manifest": {**manifest, "engineVersion": "changed"},
                                                  "artifact": "artifact.json"}), encoding="utf-8")
            else:
                artifact.unlink()
            return result

    engine = TamperingEngine()
    response = client_for(release, engine).post(ROUTE, json=payload(release))
    assert_error(response, 422, "POLICY_UNRESOLVED")
    assert "verdict" not in response.json()
    assert len(engine.analyzed) == 1


def test_descriptor_growth_before_open_is_rejected_even_when_extra_bytes_are_valid_json_whitespace(release,
                                                                                                  monkeypatch):
    from pathlib import Path

    descriptor = release[0]
    original_open = Path.open
    enlarged = False

    def growing_open(path, mode="r", *args, **kwargs):
        nonlocal enlarged
        if path == descriptor and mode == "rb" and not enlarged:
            enlarged = True
            with original_open(path, "ab") as writer:
                writer.write(b" " * 65537)
        return original_open(path, mode, *args, **kwargs)

    monkeypatch.setattr(Path, "open", growing_open)
    engine = SyntheticEngine()
    response = client_for(release, engine).post(ROUTE, json=payload(release))
    assert_error(response, 422, "POLICY_UNRESOLVED")
    assert enlarged
    assert engine.counted == engine.analyzed == []


def test_oversized_artifact_is_rejected_without_invoking_engine(release):
    descriptor, artifact, manifest = release
    artifact.write_bytes(b"x" * (1048576 + 1))
    altered = {**manifest, "contentHash": hashlib.sha256(artifact.read_bytes()).hexdigest()}
    descriptor.write_text(json.dumps({"manifest": altered, "artifact": "artifact.json"}), encoding="utf-8")
    request = payload(release)
    request["manifest"] = altered
    engine = SyntheticEngine()
    assert_error(client_for(release, engine).post(ROUTE, json=request), 422, "POLICY_UNRESOLVED")
    assert engine.counted == engine.analyzed == []
