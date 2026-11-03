"""Offline BE service JWT verification; no remote keys, token logging or bypass."""

from collections.abc import Mapping
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime
from typing import Callable
import json
import math
from pathlib import Path
import re
from time import time
from types import MappingProxyType
from uuid import UUID, RFC_4122

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse
from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict

SERVICE_SCOPE = "ai:execute"
TOKEN_LIFETIME_SECONDS = 300
CLOCK_SKEW_SECONDS = 30
ENVIRONMENTS = frozenset({"local", "test", "dev", "prod"})
REQUIRED_CLAIMS = frozenset({"iss", "aud", "sub", "iat", "nbf", "exp", "jti"})
REALM = 'Bearer realm="tingle-ai"'
ERRORS = {
    "INVALID_REQUEST": (400, "인증 요청 형식이 올바르지 않습니다.", REALM + ', error="invalid_request"'),
    "AUTH_REQUIRED": (401, "내부 서비스 인증이 필요합니다.", REALM),
    "ACCESS_TOKEN_EXPIRED": (401, "서비스 인증 토큰이 만료되었습니다.", REALM + ', error="invalid_token"'),
    "ACCESS_TOKEN_INVALID": (401, "서비스 인증 토큰이 올바르지 않습니다.", REALM + ', error="invalid_token"'),
    "FORBIDDEN": (403, "내부 서비스 접근 권한이 없습니다.", REALM + f', error="insufficient_scope", scope="{SERVICE_SCOPE}"'),
    "SERVICE_UNAVAILABLE": (503, "서비스 인증을 처리할 수 없습니다.", None),
}


class ServiceAuthError(Exception):
    """Only fixed codes/messages leave this boundary; never attach raw JWT data."""

    def __init__(self, code: str) -> None:
        self.code = code
        self.status_code, self.message, self.challenge = ERRORS[code]
        super().__init__(code)

    def response(self) -> JSONResponse:
        headers = {"Cache-Control": "no-store"}
        if self.challenge:
            headers["WWW-Authenticate"] = self.challenge
        return JSONResponse(
            status_code=self.status_code,
            content={"code": self.code, "message": self.message}, headers=headers,
        )


class ServiceAuthConfigurationError(RuntimeError):
    def __init__(self) -> None:
        super().__init__("Service authentication configuration is unavailable or invalid")


class ServiceAuthSettings(BaseSettings):
    """Paths only. PEM contents and private keys never belong in .env/examples."""

    app_env: str = Field(default="local", validation_alias=AliasChoices("app_env", "environment"))
    service_auth_public_key_files: dict[str, Path] = Field(
        default_factory=dict, repr=False,
        validation_alias=AliasChoices("service_auth_public_key_files", "public_keys", "ai_service_public_keys"),
    )
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    @property
    def environment(self) -> str:
        return self.app_env

    @property
    def public_keys(self) -> dict[str, Path]:
        return self.service_auth_public_key_files



@dataclass(frozen=True)
class ServiceIdentity:
    subject: str
    scopes: frozenset[str]


def _unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("Duplicate JSON member")
        result[name] = value
    return result


def _reject_constant(_):
    raise ValueError("Non-JSON numeric constant")


def _json_object(value: bytes) -> dict:
    parsed = json.loads(value, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    if not isinstance(parsed, dict):
        raise ValueError("Expected a JSON object")
    return parsed


def bearer_token(request: Request) -> str:
    values = request.headers.getlist("authorization")
    if not values:
        raise ServiceAuthError("AUTH_REQUIRED")
    if len(values) != 1 or "," in values[0]:
        raise ServiceAuthError("INVALID_REQUEST")
    parts = values[0].split()
    if len(parts) > 2:
        raise ServiceAuthError("INVALID_REQUEST")
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise ServiceAuthError("ACCESS_TOKEN_INVALID")
    return parts[1]


class ServiceJWTVerifier:
    """Validate every registered key at construction, then verify locally in memory."""

    def __init__(self, env: str, public_keys: Mapping[str, bytes], now: Callable | None = None) -> None:
        try:
            if env not in ENVIRONMENTS or not public_keys:
                raise ValueError("Missing configuration")
            keys = {}
            for kid, pem in public_keys.items():
                if not isinstance(kid, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", kid):
                    raise ValueError("Invalid key identifier")
                lines = pem.strip().splitlines()
                if (len(lines) < 3 or (lines[0], lines[-1]) not in {
                    (b"-----BEGIN PUBLIC KEY-----", b"-----END PUBLIC KEY-----"),
                    (b"-----BEGIN RSA PUBLIC KEY-----", b"-----END RSA PUBLIC KEY-----"),
                } or any(not re.fullmatch(rb"[A-Za-z0-9+/=]+", line) for line in lines[1:-1])):
                    raise ValueError("Exactly one public PEM required")
                key = serialization.load_pem_public_key(pem)
                if not isinstance(key, RSAPublicKey) or key.key_size < 2048:
                    raise ValueError("RSA public key of at least 2048 bits required")
                keys[kid] = key
        except Exception:
            raise ServiceAuthConfigurationError() from None
        self._now = now
        self.env = env
        self._keys = MappingProxyType(keys)

    @classmethod
    def from_settings(cls, settings: ServiceAuthSettings) -> "ServiceJWTVerifier":
        try:
            public_keys = {
                kid: path.read_bytes()
                for kid, path in settings.service_auth_public_key_files.items()
            }
        except Exception:
            raise ServiceAuthConfigurationError() from None
        return cls(settings.app_env, public_keys)

    @classmethod
    def from_environment(cls) -> "ServiceJWTVerifier":
        try:
            settings = ServiceAuthSettings()
        except Exception:
            raise ServiceAuthConfigurationError() from None
        return cls.from_settings(settings)

    def verify_token(self, token: str) -> ServiceIdentity:
        claims = self.verify_claims(token)
        return ServiceIdentity(claims["sub"], frozenset(claims.get("scope", "").split(" ")))

    def verify_claims(self, token: str) -> dict:
        try:
            if not re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token):
                raise ValueError("Invalid compact JWT")
            # This untrusted header selects only an already-loaded local key.
            header = _json_object(jwt.utils.base64url_decode(token.split(".")[0]))
            kid = header.get("kid")
            if (header.get("alg") != "RS256" or header.get("typ") != "tingle-service+jwt"
                    or not isinstance(kid, str) or kid not in self._keys
                    or set(header) - {"alg", "typ", "kid"}):
                raise ValueError("Invalid service header")
            # Algorithm is pinned here. jku/jwk/x5u/x5c are never key sources.
            payload = jwt.api_jws.PyJWS().decode(token, self._keys[kid], algorithms=["RS256"])
            claims = _json_object(payload)
            self._validate_identity(claims)
        except (jwt.InvalidTokenError, ValueError, TypeError, OverflowError, RecursionError):
            raise ServiceAuthError("ACCESS_TOKEN_INVALID") from None

        now = self._now() if self._now is not None else time()
        if isinstance(now, datetime):
            if now.tzinfo is None:
                raise RuntimeError("Authentication clock unavailable")
            now = now.timestamp()
        if isinstance(now, bool) or not isinstance(now, (int, float)) or not math.isfinite(now):
            raise RuntimeError("Authentication clock unavailable")
        if claims["iat"] > now + CLOCK_SKEW_SECONDS:
            raise ServiceAuthError("ACCESS_TOKEN_INVALID")
        # At exp + 30 the token is expired (the upper bound is exclusive).
        if now >= claims["exp"] + CLOCK_SKEW_SECONDS:
            raise ServiceAuthError("ACCESS_TOKEN_EXPIRED")
        scopes = frozenset(claims.get("scope", "").split(" "))
        if SERVICE_SCOPE not in scopes:
            raise ServiceAuthError("FORBIDDEN")
        return claims

    def _validate_identity(self, claims: dict) -> None:
        if not REQUIRED_CLAIMS <= claims.keys():
            raise ValueError("Missing service claim")
        # Only iss/aud bind the environment; no extra env-claim policy is defined.
        if (claims["iss"] != f"urn:tingle:be:{self.env}"
                or not isinstance(claims["aud"], str)
                or claims["aud"] != f"urn:tingle:ai:{self.env}"
                or claims["sub"] != "tingle-be"):
            raise ValueError("Invalid service identity")
        if any(type(claims[name]) is not int for name in ("iat", "nbf", "exp")):
            raise ValueError("Strict integer seconds required")
        if claims["nbf"] != claims["iat"] or claims["exp"] - claims["iat"] != TOKEN_LIFETIME_SECONDS:
            raise ValueError("Invalid service lifetime")
        jti = claims["jti"]
        if not isinstance(jti, str) or len(jti) != 36:
            raise ValueError("Invalid service identifier")
        identifier = UUID(jti)
        if str(identifier) != jti.lower() or identifier.version != 4 or identifier.variant != RFC_4122:
            raise ValueError("UUIDv4 required")
        if "scope" in claims and not isinstance(claims["scope"], str):
            raise ValueError("Scope must be a string")

    def __call__(self, request: Request) -> bool:
        try:
            request.state.service_identity = self.verify_token(bearer_token(request))
        except ServiceAuthError:
            raise
        except Exception:
            raise ServiceAuthError("SERVICE_UNAVAILABLE") from None
        return True


@asynccontextmanager
async def service_auth_lifespan(app: FastAPI):
    """Opt-in startup gate: bad/missing keys prevent this host from becoming ready.

    This does not register a business route or approve presentation policies.
    Production main uses the compatibility boundary and retains existing risk/explanation routes.
    Personal analysis is registered only in the isolated HTTP test host.
    """
    app.state.service_auth = ServiceJWTVerifier.from_environment()
    try:
        yield
    finally:
        del app.state.service_auth


class ServiceAuthenticator:
    """Compatibility boundary for existing main/risk consumers; one JWT verifier.

    Keeps claims/HTTPException and atomic key reload behavior at this boundary.
    Personal analysis uses ServiceJWTVerifier's identity/ServiceAuthError API.
    """

    def __init__(self, settings: ServiceAuthSettings | None = None, now: Callable | None = None):
        self.settings = settings or ServiceAuthSettings()
        self._now = now
        self._verifier = None
        self.reload_keys()

    @property
    def is_ready(self) -> bool:
        return self._verifier is not None

    def reload_keys(self) -> bool:
        try:
            verifier = ServiceJWTVerifier(self.settings.environment, {
                kid: Path(path).read_bytes() for kid, path in self.settings.public_keys.items()
            }, now=self._now)
        except Exception:
            self._verifier = None
            return False
        self._verifier = verifier
        return True

    def __call__(self, request: Request) -> dict:
        return self.authenticate(request)

    def authenticate(self, request: Request) -> dict:
        try:
            if self._verifier is None:
                raise ServiceAuthError("SERVICE_UNAVAILABLE")
            claims = self._verifier.verify_claims(bearer_token(request))
            request.state.service_identity = ServiceIdentity(
                claims["sub"], frozenset(claims.get("scope", "").split(" ")))
            return claims
        except ServiceAuthError as error:
            headers = {"Cache-Control": "no-store"}
            if error.challenge:
                headers["WWW-Authenticate"] = error.challenge
            raise HTTPException(error.status_code, {"code": error.code, "message": error.message},
                                headers=headers) from None
        except Exception:
            raise HTTPException(503, {"code": "SERVICE_UNAVAILABLE",
                                      "message": ERRORS["SERVICE_UNAVAILABLE"][1]},
                                headers={"Cache-Control": "no-store"}) from None
