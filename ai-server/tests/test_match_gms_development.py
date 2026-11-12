"""Real create_app/auth/release + offline GMS transport, never network credentials."""

import json
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from fastapi.testclient import TestClient
import httpx2 as httpx
import pytest

from app.config import Settings
from app.main import create_app
from app.match_explanation.gms import GmsClient
from security.synthetic_jwt import NOW
from test_icebreakers_http import signing_key, make_headers, KID
from test_match_explanation_http import release, payload, PATH
from test_match_explanation_gms import envelope


@pytest.fixture
def host(tmp_path, signing_key, release, monkeypatch):
    public = tmp_path / "public.pem"
    public.write_bytes(signing_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    monkeypatch.setattr("app.security.service_auth.time", lambda: NOW)

    def make(handler, *, enabled=True, environment="test"):
        configured = Settings(_env_file=None, app_env=environment,
            ai_service_public_keys={KID: str(public)},
            explanation_release_descriptor_files=[str(tmp_path / "synthetic-descriptor.json")],
            risk_local_rules_enabled=False, icebreaker_release_descriptor_file="")
        provider = GmsClient("synthetic-only", transport=httpx.MockTransport(handler))
        return TestClient(create_app(configured, _gms_client=provider if enabled else None))
    return make


def candidate():
    return {"text": "상대의 연락 빈도가 당신의 선호와 맞아요.", "evidenceIds": ["e1"]}


@pytest.mark.parametrize("fault", [None, "unsafe", "timeout", "provider", "review"])
def test_opt_in_generates_or_safely_falls_back(host, make_headers, release, fault):
    calls = []
    def handler(request):
        body = json.loads(request.content)
        data = json.loads(body["input"][1]["content"])
        assert not ({"attemptId", "contextId", "manifest", "scope", "reference"} & data.keys())
        calls.append(data)
        if fault == "timeout":
            raise httpx.ReadTimeout("synthetic private error", request=request)
        if fault == "provider":
            return httpx.Response(503, text="synthetic private error")
        if len(calls) == 1:
            result = candidate()
            if fault == "unsafe": result["text"] += " 두 분은 서로 좋아할 거예요."
        else:
            result = {"supported": fault != "review", "directionCorrect": True,
                      "privacySafe": True, "evidenceIds": ["e1"]}
        return httpx.Response(200, json=envelope(json.dumps(result)))

    body = payload(release)
    body["contextBundle"]["scope"]["model_evidence_ids"] = ["e1"]
    with host(handler) as client:
        response = client.post(PATH, json=body, headers=make_headers())
    assert response.status_code == 200
    assert response.json()["source"] == ("GENERATED" if fault is None else "RULE_FALLBACK")
    assert len(calls) == (2 if fault in {None, "review"} else 1)
    assert response.json()["evidenceKeys"] == []
    assert "synthetic private" not in response.text


@pytest.mark.parametrize("gate", ["default", "scope", "no-evidence", "auth", "manifest", "production"])
def test_closed_gates_do_not_call_gms(host, make_headers, release, gate):
    def forbidden(_): pytest.fail("Gate must stop provider")
    body = payload(release)
    status = 200
    if gate in {"default", "production"}:
        body["contextBundle"]["scope"]["model_evidence_ids"] = ["e1"]
        status = 400
    elif gate == "no-evidence":
        body["contextBundle"]["context"]["evidence"] = []
        body["contextBundle"]["scope"]["display_evidence_ids"] = []
        status = 503
    elif gate == "manifest":
        body["manifest"]["engineVersion"] = "not-registered"
        status = 503
    elif gate == "auth": status = 401
    environment = "prod" if gate == "production" else "test"
    with host(forbidden, enabled=gate != "default", environment=environment) as client:
        response = client.post(PATH, json=body, headers={} if gate == "auth" else make_headers(environment=environment))
    assert response.status_code == status


def test_disclosure_revoked_during_generation_prevents_review_and_response(host, make_headers, release):
    calls = []
    def handler(_):
        calls.append(1)
        release[2].write_bytes(b"revoked synthetic artifact")
        return httpx.Response(200, json=envelope(json.dumps(candidate())))
    body = payload(release)
    body["contextBundle"]["scope"]["model_evidence_ids"] = ["e1"]
    with host(handler) as client:
        response = client.post(PATH, json=body, headers=make_headers())
    assert response.status_code == 503 and len(calls) == 1
