"""Real in-memory synthetic JWT verification with the offline rule engine."""

import pytest
from fastapi.testclient import TestClient

from app.main import app as production_app
from app.risk_detection.local_engine import LocalRuleRiskEngine
from app.security.service_auth import ServiceAuthenticator, ServiceAuthSettings
from test_risk_detection_http import ROUTE, assert_error, client_for, payload, release


@pytest.mark.parametrize("case,status,code", [
    ("valid", 200, None),
    ("missing", 401, "AUTH_REQUIRED"),
    ("expired", 401, "ACCESS_TOKEN_EXPIRED"),
    ("wrong-scope", 403, "FORBIDDEN"),
    ("wrong-key", 401, "ACCESS_TOKEN_INVALID"),
])
def test_actual_engine_retains_service_jwt_gate(release, verifier, token_factory, rsa_keys,
                                               monkeypatch, case, status, code):
    authenticator = ServiceAuthenticator(ServiceAuthSettings(environment="test"))
    monkeypatch.setattr(authenticator, "_verifier", verifier)
    counted = []

    def synthetic_counter(text):
        counted.append(text)
        return 100

    engine = LocalRuleRiskEngine(count_input_tokens=synthetic_counter)
    headers = {}
    if case != "missing":
        changes = {"scope": "ai:risk"} if case == "wrong-scope" else None
        if case == "expired":
            from .synthetic_jwt import NOW
            changes = {"iat": NOW - 400, "nbf": NOW - 400, "exp": NOW - 100}
        token = token_factory(changes, key=rsa_keys[1] if case == "wrong-key" else None)
        headers["Authorization"] = f"Bearer {token}"
    request = payload(release)
    request["contextBundle"][0]["text"] = "오늘 집 앞에서 기다렸다가 널 때리겠다."
    response = client_for(release, engine, authenticator).post(ROUTE, json=request, headers=headers)
    assert response.status_code == status
    assert len(counted) == (1 if status == 200 else 0)
    if code:
        assert_error(response, status, code)
    else:
        assert response.json()["verdict"]["findings"] == [
            dict(sequence=1, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]


def test_production_app_stays_unregistered_with_valid_service_jwt(verifier, token_factory, monkeypatch):
    monkeypatch.setattr(production_app.state.service_auth, "_verifier", verifier)
    response = TestClient(production_app).post(
        ROUTE, content=b"invalid-body-never-parsed",
        headers={"Authorization": f"Bearer {token_factory()}"},
    )
    assert_error(response, 422, "POLICY_UNRESOLVED")
