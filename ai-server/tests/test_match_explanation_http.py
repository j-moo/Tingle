"""합성 인증·직접 bundle·임시 release 파일 ASGI 검사. 실제 승인 release/DB/모델 증거가 아니다."""

import copy
import hashlib
import json
from uuid import UUID, uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.match_explanation.http_api import BoundaryError, ContextResolver, create_router
from app.match_explanation.releases import ReleaseRegistry

PATH = "/internal/ai/explanations"
HEADERS = {"Authorization": "Bearer synthetic-token"}
IDENTITY = object()


@pytest.fixture
def release(tmp_path):
    artifact = tmp_path / "synthetic.bundle"
    artifact.write_bytes(b'{"synthetic": true}\n')
    names = ("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
             "analysisVersion", "typeReferenceVersion", "matchingVersion",
             "productPolicyVersion", "engineVersion")
    manifest = {"releaseId": str(uuid4()), **{name: "synthetic-v1" for name in names},
                "contentHash": hashlib.sha256(artifact.read_bytes()).hexdigest()}
    descriptor = tmp_path / "synthetic-descriptor.json"
    descriptor.write_text(json.dumps({"manifest": manifest, "artifact": artifact.name}), encoding="utf-8")
    return ReleaseRegistry([descriptor]), manifest, artifact


def context(manifest):
    reference = {"encounter_id": 1001, "evaluation_ref": "c" * 64,
                 "version_ref": manifest["releaseId"]}
    return {
        "context": {
            "reference": reference, "viewer": "B", "conflicting_source_refs": [],
            "evidence": [{
                "reference": dict(reference), "source_ref": "synthetic-verified-comparison",
                "evidence": {"id": "e1", "direction": "A_TO_B", "kind": "ONE_WAY_PREFERENCE_FIT",
                             "topic": "contact", "fact": "A의 실제 연락 빈도가 B의 허용 범위에 들어간다."},
            }],
        },
        "scope": {"reference": dict(reference), "viewer": "B", "version": "synthetic-approved",
                  "model_evidence_ids": [], "display_evidence_ids": ["e1"]},
    }


def payload(release):
    manifest = copy.deepcopy(release[1])
    return {"attemptId": str(uuid4()), "contextId": 1001, "manifest": manifest,
            "contextBundle": context(manifest)}


def synthetic_auth(request):
    assert request.headers["Authorization"] == HEADERS["Authorization"]
    return IDENTITY


def client_for(resolver=None, authenticate=synthetic_auth):
    app = FastAPI()
    app.include_router(create_router(resolver, authenticate))
    return TestClient(app)


def test_direct_bundle_preserves_wire_without_callbacks_tokens_or_models(release, monkeypatch):
    from app.match_explanation.gms import GmsClient

    def forbidden(*args, **kwargs):
        pytest.fail("Rule route must not create a model client")
    monkeypatch.setattr(GmsClient, "from_environment", forbidden)
    body = payload(release)
    before = copy.deepcopy(body)
    request_id = str(uuid4())
    with client_for(ContextResolver(release[0])) as client:
        response = client.post(PATH, json=body, headers={**HEADERS, "X-Request-Id": request_id})
    assert response.status_code == 200
    assert response.json() == {
        "source": "RULE_FALLBACK", "text": "상대의 연락 방식이 당신의 선호와 맞아요.",
        "evidenceKeys": [], "promptVersion": None, "encounterId": 1001,
    }
    assert body == before
    assert response.headers["Cache-Control"] == "no-store"
    assert response.headers["X-Request-Id"] == request_id


@pytest.mark.parametrize("missing", ["auth", "resolver", "release"])
def test_unconfigured_boundary_is_closed(release, missing):
    resolver = None if missing == "resolver" else ContextResolver(
        None if missing == "release" else release[0])
    with client_for(resolver, None if missing == "auth" else synthetic_auth) as client:
        response = client.post(PATH, json=payload(release), headers=HEADERS)
    assert response.status_code == 503
    assert response.json()["code"] == "SERVICE_UNAVAILABLE"


@pytest.mark.parametrize("identity", [None, True, False])
def test_unverified_auth_identity_is_closed(release, identity):
    with client_for(ContextResolver(release[0]), lambda request: identity) as client:
        response = client.post(PATH, json=payload(release), headers=HEADERS)
    assert response.status_code == 503


@pytest.mark.parametrize(("headers", "status", "code"), [
    ({}, 401, "AUTH_REQUIRED"), ({"Authorization": ""}, 401, "ACCESS_TOKEN_INVALID"),
    ({"Authorization": "Basic secret"}, 401, "ACCESS_TOKEN_INVALID"),
    ({"Authorization": "Bearer one two"}, 401, "ACCESS_TOKEN_INVALID"),
    ({"Authorization": "Bearer one, Bearer two"}, 400, "INVALID_REQUEST"),
    ([("Authorization", "Bearer one"), ("Authorization", "Bearer two")], 400, "INVALID_REQUEST"),
])
def test_authorization_header_errors_stop_authentication(release, headers, status, code):
    def forbidden(*args):
        pytest.fail("Invalid header must stop before auth hook")
    with client_for(ContextResolver(release[0]), forbidden) as client:
        response = client.post(PATH, json=payload(release), headers=headers)
    assert response.status_code == status
    assert response.json()["code"] == code
    assert response.headers["Cache-Control"] == "no-store"
    assert 'realm="tingle-ai"' in response.headers["WWW-Authenticate"]


@pytest.mark.parametrize(("status", "code"), [
    (401, "ACCESS_TOKEN_INVALID"), (401, "ACCESS_TOKEN_EXPIRED"),
    (403, "FORBIDDEN"), (503, "SERVICE_UNAVAILABLE"),
])
def test_common_auth_failure_is_preserved(release, status, code):
    def auth(request):
        raise BoundaryError(status, code)
    with client_for(ContextResolver(release[0]), auth) as client:
        response = client.post(PATH, json=payload(release), headers=HEADERS)
    assert response.status_code == status
    assert response.json()["code"] == code
    assert ("WWW-Authenticate" in response.headers) == (status in {401, 403})


def test_unexpected_auth_error_does_not_expose_details(release):
    def auth(request):
        raise RuntimeError("synthetic-secret")
    with client_for(ContextResolver(release[0]), auth) as client:
        response = client.post(PATH, json=payload(release), headers=HEADERS)
    assert response.status_code == 503
    assert "synthetic-secret" not in response.text


@pytest.mark.parametrize(("field", "value"), [
    ("contextId", True), ("contextId", "1001"), ("contextId", 0), ("contextId", 2**63),
    ("attemptId", "not-uuid"), ("viewer", "B"), ("evidence", []), ("contextBundle", None),
])
def test_only_strict_four_field_request_is_accepted(release, field, value):
    body = payload(release)
    body[field] = value
    with client_for(ContextResolver(release[0])) as client:
        response = client.post(PATH, json=body, headers=HEADERS)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"


def test_three_field_legacy_request_and_any_missing_field_are_rejected(release):
    with client_for(ContextResolver(release[0])) as client:
        for field in payload(release):
            body = payload(release)
            del body[field]
            assert client.post(PATH, json=body, headers=HEADERS).status_code == 400


@pytest.mark.parametrize(("field", "value"), [
    ("releaseId", 1), ("surveyVersion", 1), ("engineVersion", ""),
    ("wordingVersion", "x" * 101), ("contentHash", "A" * 64), ("unknown", "extra"),
])
def test_manifest_keeps_exact_eleven_field_shape(release, field, value):
    body = payload(release)
    body["manifest"][field] = value
    with client_for(ContextResolver(release[0])) as client:
        assert client.post(PATH, json=body, headers=HEADERS).status_code == 400


@pytest.mark.parametrize("change", ["viewer", "context", "model", "topic", "source", "duplicate",
                                    "missing", "display_duplicate", "conflict_duplicate", "subset",
                                    "version", "fingerprint", "scope_reference", "unknown"])
def test_bundle_mismatch_is_rejected_before_rule_success(release, change):
    body = payload(release)
    raw = body["contextBundle"]
    if change == "viewer":
        raw["scope"]["viewer"] = "A"
    elif change == "context":
        raw["context"]["reference"]["encounter_id"] = 1002
    elif change == "model":
        raw["scope"]["model_evidence_ids"] = ["e2"]
    elif change == "topic":
        raw["context"]["evidence"][0]["evidence"]["topic"] = "religion"
    elif change == "source":
        raw["context"]["evidence"][0]["reference"]["evaluation_ref"] = "d" * 64
    elif change == "duplicate":
        raw["context"]["evidence"] *= 2
    elif change == "missing":
        del raw["scope"]["display_evidence_ids"]
    elif change == "display_duplicate":
        raw["scope"]["display_evidence_ids"] = ["e1", "e1"]
    elif change == "conflict_duplicate":
        raw["context"]["conflicting_source_refs"] = ["r", "r"]
    elif change == "subset":
        raw["scope"]["display_evidence_ids"] = ["e2"]
    elif change == "version":
        raw["context"]["reference"]["version_ref"] = str(uuid4())
    elif change == "fingerprint":
        for target in (raw["context"]["reference"], raw["scope"]["reference"],
                       raw["context"]["evidence"][0]["reference"]):
            target["evaluation_ref"] = "not-a-be-run-fingerprint"
    elif change == "scope_reference":
        raw["scope"]["reference"]["evaluation_ref"] = "d" * 64
    else:
        raw["context"]["unexpected"] = True
    with client_for(ContextResolver(release[0])) as client:
        response = client.post(PATH, json=body, headers=HEADERS)
    assert response.status_code == 400
    assert "text" not in response.json()


@pytest.mark.parametrize("change", ["empty", "conflict", "unsupported"])
def test_unusable_evidence_never_becomes_generic_explanation(release, change):
    body = payload(release)
    raw = body["contextBundle"]
    if change == "empty":
        raw["context"]["evidence"] = []
        raw["scope"]["display_evidence_ids"] = []
    elif change == "conflict":
        raw["context"]["conflicting_source_refs"] = ["synthetic-verified-comparison"]
    else:
        raw["context"]["evidence"][0]["evidence"]["fact"] = "비교에서 확인한 연락 관련 요약이다."
    with client_for(ContextResolver(release[0])) as client:
        assert client.post(PATH, json=body, headers=HEADERS).status_code == 503


def test_service_identity_is_not_used_as_business_viewer(release):
    with client_for(ContextResolver(release[0]), lambda request: {"subject": "service-A"}) as client:
        response = client.post(PATH, json=payload(release), headers=HEADERS)
    assert response.status_code == 200
    assert response.json()["text"] == "상대의 연락 방식이 당신의 선호와 맞아요."


def test_changed_artifact_before_return_discards_result(release, monkeypatch):
    registry, _, artifact = release
    original = registry.verify
    calls = []
    def verify(manifest):
        calls.append(1)
        if len(calls) == 3:
            artifact.write_bytes(b"changed-synthetic-content")
        original(manifest)
    monkeypatch.setattr(registry, "verify", verify)
    with client_for(ContextResolver(registry)) as client:
        response = client.post(PATH, json=payload(release), headers=HEADERS)
    assert response.status_code == 503
    assert "text" not in response.json()
    assert len(calls) == 3


@pytest.mark.parametrize("change", ["release", "version", "artifact"])
def test_unregistered_release_manifest_mismatch_or_changed_bytes_is_closed(release, change):
    body = payload(release)
    if change == "release":
        body["manifest"]["releaseId"] = str(uuid4())
        body["contextBundle"] = context(body["manifest"])
    elif change == "version":
        body["manifest"]["matchingVersion"] = "other"
    else:
        release[2].write_bytes(b"changed")
    with client_for(ContextResolver(release[0])) as client:
        response = client.post(PATH, json=body, headers=HEADERS)
    assert response.status_code == 503


@pytest.mark.parametrize("headers", [
    {"X-Request-Id": "invalid"},
    [("X-Request-Id", str(uuid4())), ("X-Request-Id", str(uuid4()))],
])
def test_invalid_or_duplicate_request_id_is_replaced(release, headers):
    auth_headers = list(HEADERS.items()) + (headers if isinstance(headers, list) else list(headers.items()))
    with client_for(ContextResolver(release[0])) as client:
        response = client.post(PATH, json=payload(release), headers=auth_headers)
    assert UUID(response.headers["X-Request-Id"]).version == 4
    assert response.headers["Cache-Control"] == "no-store"
