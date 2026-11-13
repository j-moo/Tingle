"""임시 RSA 키·등록 파일과 실제 공통 인증기를 쓰는 ASGI 연결 검증."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import jwt
import pytest

from app.match_explanation.releases import ReleaseError
from app.match_explanation.runtime import create_runtime_router
from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.security.service_auth import ServiceAuthenticator, ServiceAuthSettings


PATH = "/internal/ai/explanations"
NOW = 1_793_242_800
KID = "synthetic-explanation-key"


@pytest.fixture(scope="module")
def private_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def configured(tmp_path, private_key):
    public = tmp_path / "synthetic-public.pem"
    public.write_bytes(private_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ))
    auth = ServiceAuthenticator(ServiceAuthSettings(
        environment="test", public_keys={KID: public},
    ), now=lambda: NOW)
    artifact = tmp_path / "synthetic.bundle"
    artifact.write_bytes(b'{"synthetic": true}\n')
    versions = ("surveyVersion", "wordingVersion", "scoringVersion", "typeModelVersion",
                "analysisVersion", "typeReferenceVersion", "matchingVersion",
                "productPolicyVersion", "engineVersion")
    manifest = {"releaseId": str(uuid4()), **{field: "synthetic-v1" for field in versions},
                "contentHash": hashlib.sha256(artifact.read_bytes()).hexdigest()}
    descriptor = tmp_path / "synthetic-descriptor.json"
    descriptor.write_text(json.dumps({"manifest": manifest, "artifact": artifact.name}), encoding="utf-8")
    reference = {"encounter_id": 1001, "evaluation_ref": "c" * 64,
                 "version_ref": manifest["releaseId"]}
    bundle = {
        "context": {"reference": reference, "viewer": "B", "conflicting_source_refs": [],
                    "evidence": [{"reference": dict(reference), "source_ref": "synthetic-source",
                                  "evidence": {"id": "e1", "direction": "A_TO_B",
                                               "kind": "ONE_WAY_PREFERENCE_FIT", "topic": "contact",
                                               "fact": "A의 실제 연락 빈도가 B의 허용 범위에 들어간다."}}]},
        "scope": {"reference": dict(reference), "viewer": "B", "version": "synthetic-public-v1",
                  "model_evidence_ids": [], "display_evidence_ids": ["e1"]},
    }
    body = {"attemptId": str(uuid4()), "contextId": 1001, "manifest": manifest, "contextBundle": bundle}
    return auth, descriptor, body, public, artifact


def token(private, **changes):
    claims = {"iss": "urn:tingle:be:test", "aud": "urn:tingle:ai:test", "sub": "tingle-be",
              "iat": NOW, "nbf": NOW, "exp": NOW + 300, "jti": str(uuid4()), "scope": "ai:execute"}
    claims.update(changes)
    return jwt.encode(claims, private, algorithm="RS256", headers={"typ": "tingle-service+jwt", "kid": KID})


def client_for(auth, descriptor_files=()):
    app = FastAPI()
    install_error_handlers(app)
    app.add_middleware(RequestIdMiddleware)
    app.include_router(create_runtime_router(auth, descriptor_files))
    return TestClient(app)


def assert_error(response, status, code):
    assert response.status_code == status
    assert response.json()["code"] == code
    assert set(response.json()) == {"code", "message"}
    assert response.headers["cache-control"] == "no-store"
    assert "synthetic-secret" not in response.text


def test_verified_common_service_token_reaches_registered_rules_without_model(configured, private_key, monkeypatch):
    from app.match_explanation.gms import GmsClient

    def forbidden(*args, **kwargs):
        pytest.fail("Rule explanation must not create a model client")
    monkeypatch.setattr(GmsClient, "from_environment", forbidden)
    auth, descriptor, body, _, _ = configured
    request_id = str(uuid4())
    with client_for(auth, [descriptor]) as client:
        response = client.post(PATH, json=body, headers={
            "Authorization": "Bearer " + token(private_key), "X-Request-Id": request_id,
        })
    assert response.status_code == 200
    assert response.json() == {"source": "RULE_FALLBACK", "text": "상대의 연락 방식이 당신의 선호와 맞아요.",
                               "evidenceKeys": [], "promptVersion": None, "encounterId": 1001}
    assert response.headers["x-request-id"] == request_id
    assert response.headers["cache-control"] == "no-store"


@pytest.mark.parametrize("case, status, code", [
    ("invalid", 401, "ACCESS_TOKEN_INVALID"), ("expired", 401, "ACCESS_TOKEN_EXPIRED"),
    ("scope", 403, "FORBIDDEN"), ("missing", 401, "AUTH_REQUIRED"),
    ("duplicate", 400, "INVALID_REQUEST"),
])
def test_common_auth_errors_keep_their_status_and_code(configured, private_key, case, status, code):
    auth, descriptor, body, _, _ = configured
    signed = token(private_key)
    if case == "invalid":
        signed = "not-a-signed-token"
    elif case == "expired":
        signed = token(private_key, iat=NOW - 331, nbf=NOW - 331, exp=NOW - 31)
    elif case == "scope":
        signed = token(private_key, scope="ai:risk")
    headers = {"Authorization": "Bearer " + signed}
    if case == "missing":
        headers = {}
    elif case == "duplicate":
        headers = [("Authorization", "Bearer " + signed), ("Authorization", "Bearer " + signed)]
    with client_for(auth, [descriptor]) as client:
        response = client.post(PATH, json=body, headers=headers)
    assert_error(response, status, code)
    assert 'realm="tingle-ai"' in response.headers["www-authenticate"]


@pytest.mark.parametrize("missing", ["keys", "release"])
def test_empty_runtime_configuration_remains_closed(configured, private_key, missing):
    auth, descriptor, body, _, _ = configured
    if missing == "keys":
        auth = ServiceAuthenticator(ServiceAuthSettings(environment="test"), now=lambda: NOW)
    with client_for(auth, () if missing == "release" else [descriptor]) as client:
        response = client.post(PATH, json=body, headers={"Authorization": "Bearer " + token(private_key)})
    assert_error(response, 503, "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("changed", ["artifact", "key"])
def test_key_reload_failure_and_artifact_tampering_do_not_run_rules(configured, private_key, changed):
    auth, descriptor, body, public, artifact = configured
    with client_for(auth, [descriptor]) as client:
        if changed == "artifact":
            artifact.write_bytes(b"changed-synthetic-artifact")
        else:
            public.write_text("broken-synthetic-public-key")
            assert not auth.reload_keys()
        response = client.post(PATH, json=body, headers={"Authorization": "Bearer " + token(private_key)})
        assert_error(response, 503, "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("status, detail", [
    (500, {"code": "synthetic-secret"}), (401, {"code": "synthetic-secret"}),
    (503, "synthetic-secret"),
])
def test_unexpected_authentication_failure_does_not_expose_details(configured, status, detail):
    _, descriptor, body, _, _ = configured

    def failing_auth(request):
        raise HTTPException(status, detail=detail)

    with client_for(failing_auth, [descriptor]) as client:
        response = client.post(PATH, json=body, headers={"Authorization": "Bearer synthetic-token"})
    assert_error(response, 503, "SERVICE_UNAVAILABLE")


def test_invalid_explicit_release_registration_fails_without_registering_a_fixture(tmp_path):
    with pytest.raises(ReleaseError):
        create_runtime_router(lambda request: {}, [tmp_path / "missing-descriptor.json"])


@pytest.mark.parametrize("configured_keys", [False, True])
def test_actual_main_registers_explanations_with_safe_default_release(
    configured, private_key, tmp_path, configured_keys,
):
    _, _, body, _, _ = configured
    result = run_actual_main(configured, private_key, tmp_path, {"default": body}, configured_keys)
    assert result["registered"] and result["riskRegistered"]
    assert result["ready"] is configured_keys
    assert result["healthStatus"] == 200 and result["health"] == {"status": "ok"}
    assert result["cases"]["default"] == [
        503, {"code": "SERVICE_UNAVAILABLE", "message": "SERVICE_UNAVAILABLE"}, "no-store",
    ]


def test_actual_main_applies_explanation_evidence_contract(configured, private_key, tmp_path):
    _, _, body, _, _ = configured
    raw = json.dumps(body)
    display = '"display_evidence_ids": ["e1"]'
    version = f'"version_ref": "{body["manifest"]["releaseId"]}"'
    private_fact = "합성비공개성명과 응답 원문"
    cases = {
        "verified": body,
        "no-display-scope": raw.replace(display, '"display_evidence_ids": []'),
        "private-fact": raw.replace(json.dumps(
            body["contextBundle"]["context"]["evidence"][0]["evidence"]["fact"],
        ), json.dumps(private_fact)),
        "unknown-evidence-id": raw.replace(display, '"display_evidence_ids": ["e2"]'),
        "manifest-mismatch": {
            **body, "manifest": {**body["manifest"], "matchingVersion": "unregistered-v2"},
        },
        # 실제 필수 제외 판단은 BE에 있다. AI는 원본 판정 필드가 섞인 요청을 거부한다.
        "raw-excluded-decision": {
            **body, "directionAB": 98, "directionBA": 97, "coverageAB": 1, "coverageBA": 1,
            "eligible": False, "excluded": ["synthetic-A-to-B-required"], "harmonicScore": 97.5,
        },
        "duplicate-display-scope": raw.replace(display, f'"display_evidence_ids": [], {display}'),
        "duplicate-version-ref": raw.replace(
            version, f'"version_ref": "{uuid4()}", {version}', 1,
        ),
    }
    result = run_actual_main(configured, private_key, tmp_path, cases, True, register_release=True)
    responses = result["cases"]
    assert responses.pop("verified") == [
        200, {"source": "RULE_FALLBACK", "text": "상대의 연락 방식이 당신의 선호와 맞아요.",
              "evidenceKeys": [], "promptVersion": None, "encounterId": 1001}, "no-store",
    ]
    for case, (status, response, cache) in responses.items():
        unavailable = case in {"no-display-scope", "private-fact", "manifest-mismatch"}
        code = "SERVICE_UNAVAILABLE" if unavailable else "INVALID_REQUEST"
        assert (status, response, cache) == (
            503 if unavailable else 400, {"code": code, "message": code}, "no-store",
        ), case


def run_actual_main(configured, private_key, tmp_path, cases, configured_keys, *, register_release=False):
    _, descriptor, _, public, _ = configured
    env = {key: value for key, value in os.environ.items()
           if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    env.update({
        "APP_ENV": "test", "AI_SERVICE_PUBLIC_KEYS": json.dumps({KID: str(public)} if configured_keys else {}),
        "RISK_RELEASE_DESCRIPTOR_FILE": "", "PYTHONPATH": str(Path(__file__).resolve().parents[1]),
        "PYTHONIOENCODING": "utf-8", "PYTHONDONTWRITEBYTECODE": "1",
    })
    if register_release:
        env["EXPLANATION_RELEASE_DESCRIPTOR_FILES"] = json.dumps([str(descriptor)])
    now = int(time.time())
    probe = """
import json
import sys
from unittest.mock import DEFAULT, patch
from app.main import app
from app.match_explanation.gms import GmsClient
from fastapi.testclient import TestClient
supplied = json.load(sys.stdin)
with TestClient(app) as client, patch.multiple(
    GmsClient, from_environment=DEFAULT, generate=DEFAULT, verify=DEFAULT,
) as model:
    health = client.get('/health')
    responses = {}
    headers = {'Authorization': 'Bearer ' + supplied['token'], 'Content-Type': 'application/json'}
    for case, body in supplied['cases'].items():
        content = body if isinstance(body, str) else json.dumps(body)
        response = client.post('/internal/ai/explanations', content=content, headers=headers)
        responses[case] = [response.status_code, response.json(), response.headers.get('cache-control')]
    for method in model.values():
        method.assert_not_called()
    paths = app.openapi()["paths"]
    print(json.dumps({'registered': '/internal/ai/explanations' in paths,
                      'riskRegistered': '/internal/ai/risk-detections' in paths,
                      'ready': app.state.service_auth.is_ready,
                      'healthStatus': health.status_code, 'health': health.json(),
                      'cases': responses}))
"""
    completed = subprocess.run(
        [sys.executable, "-c", probe], cwd=tmp_path, env=env,
        input=json.dumps({"cases": cases, "token": token(private_key, iat=now, nbf=now, exp=now + 300)}),
        capture_output=True, text=True, encoding="utf-8", timeout=30, check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stderr == ""
    return json.loads(completed.stdout)
