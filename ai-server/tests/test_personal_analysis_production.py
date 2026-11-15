"""Real prod boundaries; a synthetic policy is never a wording approval."""

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
from app.personal_analysis_api import production, proposals
from app.personal_analysis_api.adapter import PresentationPolicy, difference_evidence
from app.personal_analysis_api.dto import Reading
from app.personal_analysis_api.review_samples import synthetic_request

PATH = "/internal/ai/personal-analyses"
FIXTURES = Path(__file__).parent / "fixtures/personal-analysis-api"
KID = "synthetic-personal-prod"


@pytest.fixture(scope="module")
def signing_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def synthetic_policy():
    # Exercise the future production connection without blessing proposal wording/IDs.
    templates = {}
    for kind in ("succeeded", "insufficient"):
        answers = {int(a["questionId"][1:]): a["value"] for a in synthetic_request(kind)["answers"]}
        for row in core.readings(answers):
            templates[row["key"], row["title"]] = "TEST-ONLY-" + row["key"]

    def difference(row, index, answers):
        return Reading(
            key=f"self-preference-Q{row['selfId']:03}-Q{row['id']:03}",
            templateId="TEST-ONLY-DIFFERENCE", text="Synthetic policy boundary test.",
            evidenceQuestionIds=difference_evidence(row, answers), priority=2 - index,
        )

    return PresentationPolicy(
        reading_template_ids=templates, difference_reading=difference,
        reading_text_formatters={value: lambda *_: "Synthetic policy boundary test."
                                 for value in templates.values()},
    )


@pytest.fixture
def host(tmp_path, signing_key, monkeypatch, synthetic_policy):
    public = tmp_path / "public.pem"
    public.write_bytes(signing_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))

    def forbidden(*args, **kwargs):
        pytest.fail("Personal analysis must not discover credentials or call GMS")

    monkeypatch.setattr(GmsClient, "from_environment", forbidden)
    monkeypatch.setattr(GmsClient, "request_text", forbidden)

    def build(*, environment="prod", enabled=True, configured_keys=True, policy_available=True):
        monkeypatch.setattr(production, "production_presentation_policy",
                            lambda: synthetic_policy if policy_available else None)
        if environment == "prod":
            monkeypatch.setattr(proposals, "proposed_label_only_policy",
                                lambda: pytest.fail("Production selected the UNAPPROVED proposal"))
        configured = Settings(_env_file=None, app_env=environment,
            personal_analysis_prod_enabled=enabled,
            ai_service_public_keys={KID: str(public)} if configured_keys else {},
            explanation_release_descriptor_files=[], icebreaker_release_descriptor_file="",
            risk_local_rules_enabled=False)
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
def test_prod_opt_in_executes_real_adapter_and_preserves_response(host, headers, kind, status):
    expected = json.loads((FIXTURES / f"{kind}.response.proposal.json").read_bytes())
    for row in expected["readings"]:
        row["templateId"] = "TEST-ONLY-" + row["key"]
        row["text"] = "Synthetic policy boundary test."
    for row in expected["selfPreferenceDifferences"]:
        row.update(templateId="TEST-ONLY-DIFFERENCE", text="Synthetic policy boundary test.")
    with host() as client:
        response = client.post(PATH, json=synthetic_request(kind), headers=headers())
        assert client.app.state.risk_runtime.resources.engine is None
    assert response.status_code == 200
    assert response.json() == expected and response.json()["status"] == status
    assert response.headers["Cache-Control"] == "no-store"
    assert response.headers["X-Request-Id"]


def test_repository_has_no_approved_production_presentation():
    assert production.production_presentation_policy() is None


def test_prod_on_without_approved_policy_refuses_activation(host, monkeypatch):
    monkeypatch.setattr(adapter_module, "PersonalAnalysisAdapter",
                        lambda **_: pytest.fail("Unapproved production created an adapter"))
    with pytest.raises(RuntimeError, match="production presentation policy is not approved"):
        host(policy_available=False)


def test_prod_off_does_not_resolve_a_policy_or_register_a_router(monkeypatch):
    monkeypatch.setattr(production, "production_presentation_policy",
                        lambda: pytest.fail("Disabled production resolved presentation"))
    monkeypatch.setattr(proposals, "proposed_label_only_policy",
                        lambda: pytest.fail("Disabled production selected the proposal"))
    # No runtime or OpenAPI route may exist after installing the guard.
    configured = Settings(_env_file=None, app_env="prod", personal_analysis_prod_enabled=False,
                          ai_service_public_keys={}, explanation_release_descriptor_files=[],
                          icebreaker_release_descriptor_file="", risk_local_rules_enabled=False)
    with TestClient(create_app(configured)) as client:
        assert not hasattr(client.app.state, "personal_analysis_runtime")
        assert PATH not in client.get("/openapi.json").json()["paths"]
        assert client.post(PATH, json={}).status_code == 404


@pytest.mark.parametrize("environment,enabled,expected", [
    ("prod", False, 404), ("prod", True, 200),
    ("local", False, 200), ("test", False, 200), ("dev", False, 200),
    ("local", True, 200), ("test", True, 200), ("dev", True, 200),
])
@pytest.mark.parametrize("kind", ["succeeded", "insufficient"])
def test_opt_in_preserves_existing_environment_behavior(host, headers, environment, enabled, expected, kind):
    with host(environment=environment, enabled=enabled) as client:
        response = client.post(PATH, json=synthetic_request(kind), headers=headers(environment=environment))
    assert response.status_code == expected
    if environment != "prod":
        assert response.json() == json.loads((FIXTURES / f"{kind}.response.proposal.json").read_bytes())


def test_prod_opt_in_setting_defaults_to_false(monkeypatch):
    monkeypatch.delenv("PERSONAL_ANALYSIS_PROD_ENABLED", raising=False)
    assert Settings(_env_file=None).personal_analysis_prod_enabled is False
    monkeypatch.setenv("PERSONAL_ANALYSIS_PROD_ENABLED", "true")
    assert Settings(_env_file=None).personal_analysis_prod_enabled is True


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


def test_prod_opt_in_without_service_keys_remains_unavailable(host):
    with host(configured_keys=False) as client:
        response = client.post(PATH, content=b"not-json")
    assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"
