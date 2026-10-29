"""Synthetic keys and in-process HTTP only; no deployed keys or model requests."""

from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID, uuid1, uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import jwt
import pytest

from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.security.service_auth import ServiceAuthenticator, ServiceAuthSettings


NOW = 1_793_242_800
KID = "synthetic-key-1"


@pytest.fixture(scope="module")
def synthetic_keys(tmp_path_factory):
    root = tmp_path_factory.mktemp("service-auth-synthetic-keys")
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = root / "public.pem"
    public.write_bytes(private.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ))
    return private, public


def claims(**changes):
    value = {
        "iss": "urn:tingle:be:local", "aud": "urn:tingle:ai:local", "sub": "tingle-be",
        "iat": NOW, "nbf": NOW, "exp": NOW + 300, "jti": str(uuid4()), "scope": "ai:execute",
    }
    value.update(changes)
    return value


def token(private, payload=None, **headers):
    configured = {"typ": "tingle-service+jwt", "kid": KID}
    configured.update(headers)
    return jwt.encode(payload if payload is not None else claims(), private, algorithm="RS256", headers=configured)


def client_for(synthetic_keys, *, now=NOW, settings=None):
    _, public = synthetic_keys
    auth = ServiceAuthenticator(settings or ServiceAuthSettings(public_keys={KID: public}), now=lambda: now)
    app = FastAPI()
    install_error_handlers(app)
    app.add_middleware(RequestIdMiddleware)

    @app.get("/protected")
    def protected(request: Request, verified=Depends(auth)):
        return {"subject": verified["sub"], "requestId": request.state.request_id}

    @app.get("/boom")
    def boom():
        raise RuntimeError("token-private-key-claim-path-must-not-leak")

    @app.get("/custom-error")
    def custom_error():
        raise HTTPException(503, detail="secret-settings-path")

    return TestClient(app), auth


def assert_error(response, status, code):
    assert response.status_code == status
    assert response.json()["code"] == code
    assert set(response.json()) == {"code", "message"}
    assert response.headers["cache-control"] == "no-store"
    assert str(UUID(response.headers["x-request-id"])) == response.headers["x-request-id"].lower()
    assert "detail" not in response.json()


def test_normal_token_preserves_request_id_and_accepts_case_insensitive_bearer(synthetic_keys):
    client, auth = client_for(synthetic_keys)
    supplied = str(uuid4()).upper()
    response = client.get("/protected", headers={
        "Authorization": "bEaReR " + token(synthetic_keys[0]), "X-Request-Id": supplied,
    })
    assert auth.is_ready
    assert response.status_code == 200
    assert response.json() == {"subject": "tingle-be", "requestId": supplied}
    assert response.headers["x-request-id"] == supplied
    assert response.headers["cache-control"] == "no-store"


@pytest.mark.parametrize("scope", ["", " ", "ai:execute-any", "ai:risk", "read ai:execute-any"])
def test_valid_identity_without_exact_permission_is_forbidden(synthetic_keys, scope):
    client, _ = client_for(synthetic_keys)
    response = client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0], claims(scope=scope))})
    assert_error(response, 403, "FORBIDDEN")
    assert response.headers["www-authenticate"] == (
        'Bearer realm="tingle-ai", error="insufficient_scope", scope="ai:execute"'
    )


def test_missing_scope_is_permission_error_but_extra_ascii_space_permissions_work(synthetic_keys):
    client, _ = client_for(synthetic_keys)
    payload = claims()
    del payload["scope"]
    denied = client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0], payload)})
    assert_error(denied, 403, "FORBIDDEN")
    accepted = token(synthetic_keys[0], claims(scope="read  ai:execute write"))
    assert client.get("/protected", headers={"Authorization": "Bearer " + accepted}).status_code == 200


@pytest.mark.parametrize("scope", [None, [], ["ai:execute"], 1, True])
def test_non_string_scope_is_invalid_token(synthetic_keys, scope):
    client, _ = client_for(synthetic_keys)
    response = client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0], claims(scope=scope))})
    assert_error(response, 401, "ACCESS_TOKEN_INVALID")


@pytest.mark.parametrize("changes", [
    {"iss": "urn:tingle:be:dev"}, {"aud": "urn:tingle:ai:prod"},
    {"aud": ["urn:tingle:ai:local"]}, {"sub": "some-user"},
    {"iat": True}, {"iat": str(NOW)}, {"nbf": float(NOW)}, {"exp": str(NOW + 300)},
    {"nbf": NOW - 1}, {"exp": NOW}, {"exp": NOW + 301},
    {"jti": str(uuid1())}, {"jti": "arbitrary"}, {"jti": None},
])
def test_wrong_identity_types_and_lifetime_are_invalid(synthetic_keys, changes):
    client, _ = client_for(synthetic_keys)
    response = client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0], claims(**changes))})
    assert_error(response, 401, "ACCESS_TOKEN_INVALID")


@pytest.mark.parametrize("required", ["iss", "aud", "sub", "iat", "nbf", "exp", "jti"])
def test_required_claim_missing_is_invalid(synthetic_keys, required):
    client, _ = client_for(synthetic_keys)
    payload = claims()
    del payload[required]
    response = client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0], payload)})
    assert_error(response, 401, "ACCESS_TOKEN_INVALID")


@pytest.mark.parametrize("now, status, code", [
    (NOW - 30, 200, None), (NOW - 31, 401, "ACCESS_TOKEN_INVALID"),
    (NOW + 329, 200, None), (NOW + 330, 401, "ACCESS_TOKEN_EXPIRED"),
])
def test_exact_clock_skew_boundaries(synthetic_keys, now, status, code):
    client, _ = client_for(synthetic_keys, now=now)
    response = client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0])})
    if code:
        assert_error(response, status, code)
        assert response.headers["www-authenticate"] == 'Bearer realm="tingle-ai", error="invalid_token"'
    else:
        assert response.status_code == status


@pytest.mark.parametrize("headers", [
    {"typ": "JWT"}, {"kid": "not-registered"}, {"kid": "../public.pem"},
    {"jku": "https://keys.invalid/key.json"}, {"x5u": "https://keys.invalid/cert.pem"},
    {"jwk": {}}, {"x5c": []}, {"crit": []}, {"cty": "JWT"},
])
def test_untrusted_or_unsupported_token_headers_are_rejected(synthetic_keys, headers):
    client, _ = client_for(synthetic_keys)
    response = client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0], **headers)})
    assert_error(response, 401, "ACCESS_TOKEN_INVALID")


def test_wrong_signature_and_symmetric_or_unsigned_algorithms_are_rejected(synthetic_keys):
    client, _ = client_for(synthetic_keys)
    other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    hs = jwt.encode(claims(), "synthetic-only-secret-not-a-rsa-key", algorithm="HS256",
                    headers={"typ": "tingle-service+jwt", "kid": KID})
    unsigned = jwt.encode(claims(), key="", algorithm="none", headers={"typ": "tingle-service+jwt", "kid": KID})
    for encoded in [token(other), hs, unsigned, "not.a.jwt"]:
        response = client.get("/protected", headers={"Authorization": "Bearer " + encoded})
        assert_error(response, 401, "ACCESS_TOKEN_INVALID")


def test_missing_duplicate_and_joined_authorization_are_distinguished(synthetic_keys):
    client, _ = client_for(synthetic_keys)
    encoded = token(synthetic_keys[0])
    assert_error(client.get("/protected"), 401, "AUTH_REQUIRED")
    assert_error(client.get("/protected", headers=[("authorization", "Bearer " + encoded),
                                                  ("Authorization", "Bearer " + encoded)]), 400, "INVALID_REQUEST")
    for value in ["Bearer " + encoded + ", " + encoded, "Bearer " + encoded + " Bearer " + encoded]:
        response = client.get("/protected", headers={"Authorization": value})
        assert_error(response, 400, "INVALID_REQUEST")
        assert response.headers["www-authenticate"] == 'Bearer realm="tingle-ai", error="invalid_request"'
    for value in ["", "Basic value", "Bearer "]:
        assert_error(client.get("/protected", headers={"Authorization": value}), 401, "ACCESS_TOKEN_INVALID")
    assert_error(client.get("/protected?token=" + encoded, cookies={"token": encoded}), 401, "AUTH_REQUIRED")


@pytest.mark.parametrize("bad", ["", "not-a-uuid", "a" * 300])
def test_bad_or_duplicate_request_id_is_regenerated(synthetic_keys, bad):
    client, _ = client_for(synthetic_keys)
    headers = [("Authorization", "Bearer " + token(synthetic_keys[0])), ("X-Request-Id", bad)]
    if not bad:
        headers = [("Authorization", "Bearer " + token(synthetic_keys[0])),
                   ("X-Request-Id", str(uuid4())), ("X-Request-Id", str(uuid4()))]
    response = client.get("/protected", headers=headers)
    assert response.status_code == 200
    assert UUID(response.headers["x-request-id"]).version == 4
    assert response.json()["requestId"] == response.headers["x-request-id"]


def test_missing_configuration_bad_file_or_wrong_environment_fail_closed(synthetic_keys, tmp_path):
    for configured in [ServiceAuthSettings(), ServiceAuthSettings(public_keys={KID: tmp_path / "missing.pem"}),
                       ServiceAuthSettings(environment="unknown", public_keys={KID: synthetic_keys[1]})]:
        client, auth = client_for(synthetic_keys, settings=configured)
        assert not auth.is_ready
        response = client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0])})
        assert_error(response, 503, "SERVICE_UNAVAILABLE")
        assert "www-authenticate" not in response.headers
        assert str(tmp_path) not in response.text


@pytest.mark.parametrize("kind", ["broken", "weak-rsa", "ec", "private"])
def test_invalid_public_key_material_is_not_ready(synthetic_keys, tmp_path, kind):
    path = tmp_path / "bad-public.pem"
    if kind == "broken":
        path.write_text("not a public key", encoding="utf-8")
    elif kind == "private":
        path.write_bytes(synthetic_keys[0].private_bytes(serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    else:
        key = (rsa.generate_private_key(public_exponent=65537, key_size=1024)
               if kind == "weak-rsa" else ec.generate_private_key(ec.SECP256R1()))
        path.write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,
                         serialization.PublicFormat.SubjectPublicKeyInfo))
    client, auth = client_for(synthetic_keys, settings=ServiceAuthSettings(public_keys={KID: path}))
    assert not auth.is_ready
    assert_error(client.get("/protected"), 503, "SERVICE_UNAVAILABLE")


def test_key_rotation_and_config_loss_are_atomic(synthetic_keys, tmp_path):
    private, public = synthetic_keys
    rotated = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    rotated_path = tmp_path / "new.pem"
    rotated_path.write_bytes(rotated.public_key().public_bytes(serialization.Encoding.PEM,
                             serialization.PublicFormat.SubjectPublicKeyInfo))
    settings = ServiceAuthSettings(public_keys={KID: public, "new": rotated_path})
    client, auth = client_for(synthetic_keys, settings=settings)
    for encoded in [token(private), token(rotated, kid="new")]:
        assert client.get("/protected", headers={"Authorization": "Bearer " + encoded}).status_code == 200
    del settings.public_keys[KID]
    assert auth.reload_keys()
    assert_error(client.get("/protected", headers={"Authorization": "Bearer " + token(private)}),
                 401, "ACCESS_TOKEN_INVALID")
    rotated_path.unlink()
    assert not auth.reload_keys()
    assert_error(client.get("/protected"), 503, "SERVICE_UNAVAILABLE")


def test_generic_server_errors_keep_metadata_and_do_not_leak_details(synthetic_keys):
    client, _ = client_for(synthetic_keys)
    supplied = str(uuid4())
    for path, expected_status, code in [("/boom", 500, "INTERNAL_ERROR"),
                                        ("/custom-error", 503, "SERVICE_UNAVAILABLE")]:
        response = client.get(path, headers={"X-Request-Id": supplied})
        assert_error(response, expected_status, code)
        assert response.headers["x-request-id"] == supplied
        assert "token-private-key" not in response.text
        assert "secret-settings-path" not in response.text


def test_aware_datetime_clock_works_and_invalid_clock_is_service_unavailable(synthetic_keys):
    client, _ = client_for(synthetic_keys, now=datetime.fromtimestamp(NOW, timezone.utc))
    assert client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0])}).status_code == 200
    for bad in [True, float("nan"), datetime(2026, 1, 1)]:
        client, _ = client_for(synthetic_keys, now=bad)
        assert_error(client.get("/protected", headers={"Authorization": "Bearer " + token(synthetic_keys[0])}),
                     503, "SERVICE_UNAVAILABLE")
