import json
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient
import jwt
import pytest

from app.security import service_auth as auth
from .synthetic_jwt import NOW


def rejected(verifier, token, code="ACCESS_TOKEN_INVALID"):
    with pytest.raises(auth.ServiceAuthError) as result:
        verifier.verify_token(token)
    assert result.value.code == code


@pytest.mark.parametrize("kid", ["test-current", "test-previous"])
def test_valid_rotating_registered_keys(verifier, token_factory, rsa_keys, kid):
    key = rsa_keys[kid == "test-previous"]
    result = verifier.verify_token(token_factory(headers={"kid":kid}, key=key))
    assert result.subject == "tingle-be"
    assert result.scopes == frozenset({"ai:execute"})


@pytest.mark.parametrize("name", sorted(auth.REQUIRED_CLAIMS))
def test_every_required_claim(verifier, token_factory, name):
    rejected(verifier, token_factory(omit=[name]))


@pytest.mark.parametrize("name,value", [
    ("iss", "urn:tingle:be:prod"), ("iss", "urn:tingle:be:TEST"), ("iss", None),
    ("aud", "urn:tingle:ai:dev"), ("aud", ["urn:tingle:ai:test"]),
    ("aud", ["urn:tingle:ai:test", "other"]), ("aud", None),
    ("sub", "user-123"), ("sub", None), ("sub", 1),
    ("jti", "11111111-2222-1333-8444-555555555555"), ("jti", "not-a-uuid"), ("jti", 1), ("jti", None),
    ("jti", uuid4().hex), ("jti", "00000000-0000-0000-0000-000000000000"),
    ("scope", None), ("scope", ["ai:execute"]), ("scope", {"ai:execute":True}), ("scope", 1), ("scope", True),
])
def test_identity_claim_formats(verifier, token_factory, name, value):
    rejected(verifier, token_factory({name:value}))


@pytest.mark.parametrize("name", ["iat", "nbf", "exp"])
@pytest.mark.parametrize("value", ["1800000000", 1_800_000_000.0, True, False, None, [], {}])
def test_dates_are_strict_integers(verifier, token_factory, name, value):
    rejected(verifier, token_factory({name:value}))


@pytest.mark.parametrize("scope", ["", " ", "other", "ai", "ai:execute-only", "prefix-ai:execute", "ai:execut", "ai:execute,other"])
def test_valid_identity_insufficient_scope(verifier, token_factory, scope):
    rejected(verifier, token_factory({"scope":scope}), "FORBIDDEN")


def test_absent_scope_is_forbidden(verifier, token_factory):
    rejected(verifier, token_factory(omit=["scope"]), "FORBIDDEN")


@pytest.mark.parametrize("scope", ["ai:execute", "other ai:execute more", "  ai:execute  "])
def test_confirmed_scope_exact_membership(verifier, token_factory, scope):
    identity = verifier.verify_token(token_factory({"scope":scope}))
    assert "ai:execute" in identity.scopes


@pytest.mark.parametrize("header", [
    {"kid":"unknown"}, {"kid":None}, {"kid":[]}, {"kid":""},
    {"typ":"JWT"}, {"typ":"tingle-service+jwt "}, {"typ":None},
    {"crit":["unrecognized"]}, {"b64":True},
])
def test_header_contract(verifier, token_factory, header):
    rejected(verifier, token_factory(headers=header))


def test_missing_kid_and_typ(verifier, token_factory, rsa_keys):
    claims = jwt.decode(token_factory(), options={"verify_signature":False})
    for headers in ({"typ":"tingle-service+jwt"}, {"kid":"test-current", "typ":None}):
        rejected(verifier, jwt.encode(claims, rsa_keys[0], algorithm="RS256", headers=headers))


@pytest.mark.parametrize("algorithm", ["RS384", "RS512", "PS256", "none", "HS256"])
def test_only_rs256(verifier, token_factory, rsa_keys, algorithm):
    claims = jwt.decode(token_factory(), options={"verify_signature":False})
    key = None if algorithm == "none" else "synthetic-hmac-secret-at-least-32-bytes" if algorithm == "HS256" else rsa_keys[0]
    token = jwt.encode(claims, key, algorithm=algorithm, headers={"kid":"test-current", "typ":"tingle-service+jwt"})
    rejected(verifier, token)


def test_invalid_signature_never_classified_as_expired(verifier, token_factory, rsa_keys):
    # Both expiry and identity could look valid; only the registered key is trusted.
    rejected(verifier, token_factory({"iat":NOW-400,"nbf":NOW-400,"exp":NOW-100}, key=rsa_keys[1]))


def test_forged_payload(verifier, token_factory):
    token = token_factory()
    header, _, signature = token.split(".")
    payload = jwt.utils.base64url_encode(b'{"sub":"user"}').decode()
    rejected(verifier, f"{header}.{payload}.{signature}")


@pytest.mark.parametrize("value", ["", "opaque", "a.b", "a.b.c.d", "a.b.c", "a=.b.c", "a.b.c!", "a.b.c\n"])
def test_malformed_compact_tokens(verifier, value):
    rejected(verifier, value)


@pytest.mark.parametrize("extra", [
    {"jku":"https://untrusted.invalid/keys"}, {"x5u":"https://untrusted.invalid/cert"},
    {"jwk":{"kty":"RSA","n":"untrusted"}}, {"x5c":["untrusted"]},
])
def test_header_key_sources_are_rejected_without_network(verifier, token_factory, extra, monkeypatch):
    import socket
    monkeypatch.setattr(socket, "create_connection", lambda *_a,**_k: pytest.fail("JWT must not fetch keys"))
    rejected(verifier, token_factory(headers=extra))
    rejected(verifier, token_factory(headers={**extra,"kid":"unknown"}))


@pytest.mark.parametrize("delta", [-300, -1, 0, 1, 299, 301, 600])
def test_exact_five_minute_lifetime(verifier, token_factory, delta):
    rejected(verifier, token_factory({"exp":NOW+delta}))


@pytest.mark.parametrize("delta", [-1, 1, 30])
def test_nbf_must_equal_iat(verifier, token_factory, delta):
    rejected(verifier, token_factory({"nbf":NOW+delta}))


@pytest.mark.parametrize("offset,expected", [
    (30,None), (31,"ACCESS_TOKEN_INVALID"), (-300,None), (-329,None),
    (-330,"ACCESS_TOKEN_EXPIRED"), (-331,"ACCESS_TOKEN_EXPIRED"),
])
def test_time_boundaries(verifier, token_factory, offset, expected):
    token = token_factory({"iat":NOW+offset,"nbf":NOW+offset,"exp":NOW+offset+300})
    if expected:
        rejected(verifier, token, expected)
    else:
        verifier.verify_token(token)


@pytest.mark.parametrize("invalid", [{"sub":"user"}, {"scope":[]}, {"aud":["urn:tingle:ai:test"]}, {"jti":"bad"}])
def test_expired_invalid_identity_is_invalid(verifier, token_factory, invalid):
    rejected(verifier, token_factory({"iat":NOW-400,"nbf":NOW-400,"exp":NOW-100,**invalid}))


def test_expired_identity_precedes_missing_scope(verifier, token_factory):
    rejected(verifier, token_factory({"iat":NOW-400,"nbf":NOW-400,"exp":NOW-100},omit=["scope"]),"ACCESS_TOKEN_EXPIRED")


@pytest.mark.parametrize("raw", [b'[]', b'null', b'{"iss":"a","iss":"b"}', b'{"scope":NaN}', b'{'])
def test_signed_non_object_duplicate_or_non_json_claims(verifier, rsa_keys, raw):
    token = jwt.api_jws.PyJWS().encode(raw, rsa_keys[0], algorithm="RS256", headers={"kid":"test-current","typ":"tingle-service+jwt"})
    rejected(verifier, token)


@pytest.mark.parametrize("env", ["local", "test", "dev", "prod"])
def test_each_environment_is_separate_without_env_claim(public_pems, token_factory, env):
    verifier = auth.ServiceJWTVerifier(env, {"test-current":public_pems[0]})
    now = int(auth.time())
    verifier.verify_token(token_factory({"iss":f"urn:tingle:be:{env}","aud":f"urn:tingle:ai:{env}",
                                        "iat":now,"nbf":now,"exp":now+300}))
    if env != "test":
        rejected(verifier, token_factory())


@pytest.mark.parametrize("env", ["", "production", "TEST", None])
def test_config_environment_fail_closed(public_pems, env):
    with pytest.raises(auth.ServiceAuthConfigurationError):
        auth.ServiceJWTVerifier(env, {"kid":public_pems[0]})


@pytest.mark.parametrize("kind", ["missing", "broken", "empty-kid", "ec", "private", "weak", "partially-broken"])
def test_all_configured_keys_are_validated(public_pems, rsa_keys, kind):
    keys = {"kid":public_pems[0]}
    if kind == "missing": keys = {}
    elif kind == "broken": keys = {"kid":b"synthetic-private-sentinel"}
    elif kind == "empty-kid": keys = {" ":public_pems[0]}
    elif kind == "ec": keys = {"kid":ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)}
    elif kind == "private": keys = {"kid":rsa_keys[0].private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())}
    elif kind == "weak": keys = {"kid":rsa.generate_private_key(public_exponent=65537,key_size=1024).public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)}
    else: keys["bad"] = b"synthetic-private-sentinel"
    with pytest.raises(auth.ServiceAuthConfigurationError) as result:
        auth.ServiceJWTVerifier("test", keys)
    assert "synthetic-private-sentinel" not in str(result.value)


@pytest.mark.parametrize("kind", ["valid", "missing", "broken", "no-keys", "bad-env", "bad-json"])
def test_environment_startup_gate(tmp_path, public_pems, monkeypatch, kind):
    public_file = tmp_path/"public.pem"
    public_file.write_bytes(b"synthetic-private-sentinel" if kind == "broken" else public_pems[0])
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("APP_ENV", "invalid" if kind == "bad-env" else "test")
    files = {} if kind == "no-keys" else {"test-current":str(tmp_path/"missing.pem" if kind == "missing" else public_file)}
    monkeypatch.setenv("SERVICE_AUTH_PUBLIC_KEY_FILES", "not-json" if kind == "bad-json" else json.dumps(files))
    host = FastAPI(lifespan=auth.service_auth_lifespan)
    if kind == "valid":
        with TestClient(host):
            assert isinstance(host.state.service_auth, auth.ServiceJWTVerifier)
        assert not hasattr(host.state,"service_auth")
    else:
        with pytest.raises(auth.ServiceAuthConfigurationError) as result:
            with TestClient(host):
                pytest.fail("Invalid authentication configuration became ready")
        assert "synthetic-private-sentinel" not in str(result.value)
        assert not hasattr(host.state,"service_auth")


@pytest.mark.parametrize("offset,code", [(-30,None),(-30.001,"ACCESS_TOKEN_INVALID"),(329.999,None),(330,"ACCESS_TOKEN_EXPIRED")])
def test_fractional_clock_edges(verifier,token_factory,monkeypatch,offset,code):
    monkeypatch.setattr(auth,"time",lambda:NOW+offset)
    token=token_factory()
    if code:
        rejected(verifier,token,code)
    else:
        verifier.verify_token(token)


def test_unknown_kid_is_invalid_even_when_clock_is_unavailable(verifier,token_factory,monkeypatch):
    def unavailable(): raise OSError("synthetic-sensitive-sentinel")
    monkeypatch.setattr(auth,"time",unavailable)
    rejected(verifier,token_factory(headers={"kid":"unknown"}))


@pytest.mark.parametrize("kind", ["two-keys","trailing-data","public-and-private"])
def test_only_one_public_pem_is_allowed(public_pems,rsa_keys,kind):
    data=public_pems[0]
    if kind == "two-keys": data+=public_pems[1]
    elif kind == "trailing-data": data+=b"synthetic-sensitive-sentinel"
    else: data+=rsa_keys[0].private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
    with pytest.raises(auth.ServiceAuthConfigurationError):
        auth.ServiceJWTVerifier("test",{"kid":data})


def test_pkcs1_public_pem_is_supported(rsa_keys):
    pem=rsa_keys[0].public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.PKCS1)
    auth.ServiceJWTVerifier("test",{"kid":pem})
