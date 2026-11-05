"""Synthetic credentials exist only in test memory; no PEM/token fixture on disk."""

from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import pytest

from app.security import service_auth as auth

from .synthetic_jwt import NOW, sign_synthetic


@pytest.fixture(scope="session")
def rsa_keys():
    return tuple(rsa.generate_private_key(public_exponent=65537, key_size=2048) for _ in range(2))


@pytest.fixture(scope="session")
def public_pems(rsa_keys):
    return tuple(key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ) for key in rsa_keys)


@pytest.fixture
def verifier(public_pems, monkeypatch):
    monkeypatch.setattr(auth, "time", lambda: NOW)
    return auth.ServiceJWTVerifier("test", {"test-current": public_pems[0], "test-previous": public_pems[1]})


@pytest.fixture
def token_factory(rsa_keys):
    def make(changes=None, *, omit=(), headers=None, key=None):
        claims = dict(
            iss="urn:tingle:be:test", aud="urn:tingle:ai:test", sub="tingle-be",
            iat=NOW, nbf=NOW, exp=NOW+300, jti=str(uuid4()), scope="ai:execute",
        )
        claims.update(changes or {})
        for name in omit:
            del claims[name]
        return sign_synthetic(claims, key or rsa_keys[0],
                              {"typ":"tingle-service+jwt", "kid":"test-current", **(headers or {})})
    return make
