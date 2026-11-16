"""Real personal-analysis policy, service authentication and validation in prod."""

import json
from pathlib import Path
import time
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient
import jwt
import pytest

from app.config import Settings
from app.main import create_app
from app.match_explanation.gms import GmsClient
from app.personal_analysis import core
from app.personal_analysis_api import adapter as adapter_module
from app.personal_analysis_api.review_samples import synthetic_request

PATH = "/internal/ai/personal-analyses"
FIXTURES = Path(__file__).parent / "fixtures/personal-analysis-api"
KID = "synthetic-personal-prod"


@pytest.fixture(scope="module")
def signing_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def host(tmp_path, signing_key, monkeypatch):
    public = tmp_path / "public.pem"
    public.write_bytes(signing_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))

    def forbidden(*args, **kwargs):
        pytest.fail("Personal analysis must not discover credentials or call GMS")

    monkeypatch.setattr(GmsClient, "from_environment", forbidden)
    monkeypatch.setattr(GmsClient, "request_text", forbidden)

    def build(*, environment="prod", configured_keys=True):
        configured = Settings(_env_file=None, app_env=environment,
            ai_service_public_keys={KID: str(public)} if configured_keys else {},
            icebreaker_release_descriptor_file="")
        return TestClient(create_app(configured))

    return build


@pytest.fixture
def headers(signing_key):
    def make(*, environment="prod", **changes):
        now = int(time.time())
        claims = {"iss": f"urn:tingle:be:{environment}", "aud": f"urn:tingle:ai:{environment}",
                  "sub": "tingle-be", "iat": now, "nbf": now, "exp": now + 300,
                  "jti": str(uuid4()), "scope": "ai:execute", **changes}
        token = jwt.encode(claims, signing_key, algorithm="RS256",
                           headers={"kid": KID, "typ": "tingle-service+jwt"})
        return {"Authorization": "Bearer " + token}
    return make


@pytest.mark.parametrize("kind,status", [("succeeded", "SUCCEEDED"), ("insufficient", "INSUFFICIENT")])
def test_prod_executes_real_policy_and_preserves_response(host, headers, kind, status):
    expected = json.loads((FIXTURES / f"{kind}.response.proposal.json").read_bytes())
    with host() as client:
        response = client.post(PATH, json=synthetic_request(kind), headers=headers())
        assert client.app.state.risk_runtime.resources.engine is None
    assert response.status_code == 200
    assert response.json() == expected and response.json()["status"] == status
    assert response.headers["Cache-Control"] == "no-store"
    assert response.headers["X-Request-Id"]


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
@pytest.mark.parametrize("kind", ["succeeded", "insufficient"])
def test_all_environments_register_the_same_analysis_contract(host, headers, environment, kind):
    with host(environment=environment) as client:
        assert client.get("/health").status_code == 200
        assert PATH in client.get("/openapi.json").json()["paths"]
        response = client.post(PATH, json=synthetic_request(kind), headers=headers(environment=environment))
    assert response.status_code == 200
    assert response.json() == json.loads((FIXTURES / f"{kind}.response.proposal.json").read_bytes())


@pytest.mark.parametrize("case,status,code", [
    ("missing", 401, "AUTH_REQUIRED"), ("invalid", 401, "ACCESS_TOKEN_INVALID"),
    ("scope", 403, "FORBIDDEN"), ("audience", 401, "ACCESS_TOKEN_INVALID"),
    ("expired", 401, "ACCESS_TOKEN_EXPIRED"),
])
def test_prod_authentication_rejects_before_parsing_or_calculation(host, headers, monkeypatch, case, status, code):
    def forbidden(*args, **kwargs):
        pytest.fail("Unauthenticated request reached calculation")
    monkeypatch.setattr(adapter_module, "analyze_personal", forbidden)
    credentials = {
        "missing": {}, "invalid": {"Authorization": "Bearer invalid-synthetic"},
        "scope": headers(scope="other"), "audience": headers(aud="urn:tingle:ai:dev"),
        "expired": headers(iat=int(time.time()) - 600, nbf=int(time.time()) - 600, exp=int(time.time()) - 300),
    }[case]
    with host() as client:
        response = client.post(PATH, content=b"not-json", headers=credentials)
    assert response.status_code == status and response.json()["code"] == code


@pytest.mark.parametrize("field", ["surveyVersion", "analysisVersion"])
def test_prod_unsupported_version_rejected_before_core(host, headers, monkeypatch, field):
    body = synthetic_request("succeeded")
    body[field] = "unsupported-version"
    monkeypatch.setattr(adapter_module, "analyze_personal", lambda *_: pytest.fail("Unsupported version ran core"))
    with host() as client:
        response = client.post(PATH, json=body, headers=headers())
    assert response.status_code == 409 and response.json()["code"] == "ANALYSIS_VERSION_UNSUPPORTED"


@pytest.mark.parametrize("name", list(adapter_module.APPROVED_DATA_HASHES))
def test_prod_reference_hash_mismatch_fails_closed(host, headers, tmp_path, monkeypatch, name):
    damaged = tmp_path / "data"
    damaged.mkdir()
    for filename in adapter_module.APPROVED_DATA_HASHES:
        (damaged / filename).write_bytes((adapter_module.DATA_DIR / filename).read_bytes())
    with (damaged / name).open("ab") as stream:
        stream.write(b" ")
    monkeypatch.setattr(adapter_module, "DATA_DIR", damaged)
    monkeypatch.setattr(adapter_module, "analyze_personal", lambda *_: pytest.fail("Changed reference ran core"))
    with host() as client:
        response = client.post(PATH, json=synthetic_request("succeeded"), headers=headers())
    assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"


def test_prod_calculation_fault_is_not_information_insufficiency(host, headers, monkeypatch, caplog):
    def broken(*args, **kwargs):
        raise RuntimeError("synthetic-sensitive-fault")
    monkeypatch.setattr(core, "classify_answers", broken)
    with host() as client:
        response = client.post(PATH, json=synthetic_request("succeeded"), headers=headers())
    assert response.status_code == 500 and response.json()["code"] == "INTERNAL_ERROR"
    assert "synthetic-sensitive-fault" not in response.text + caplog.text


def test_prod_without_service_keys_remains_unavailable(host):
    with host(configured_keys=False) as client:
        response = client.post(PATH, content=b"not-json")
    assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"
