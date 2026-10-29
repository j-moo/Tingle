"""Offline RS256 verification for the agreed BE-to-AI service token contract.

No token-provided URL, key or key path is ever used. Missing configuration is a
service availability error, never an authentication bypass.
"""

from dataclasses import dataclass, field
from datetime import datetime
import math
from pathlib import Path
import re
import time
from typing import Any, Callable
from uuid import UUID

from cryptography.exceptions import UnsupportedAlgorithm
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey
from fastapi import HTTPException, Request
import jwt


_KID = re.compile(r"[A-Za-z0-9._-]{1,64}\Z", re.ASCII)
_BEARER = re.compile(r"(?i:Bearer) +([^\s,]+)\Z", re.ASCII)
_ENVIRONMENTS = frozenset({"local", "test", "dev", "prod"})
_TOKEN_HEADERS = frozenset({"alg", "typ", "kid"})
_REALM = 'Bearer realm="tingle-ai"'
_AUTH_MESSAGE = "내부 서비스 인증에 실패했습니다."


@dataclass(frozen=True)
class ServiceAuthSettings:
    environment: str = "local"
    public_keys: dict[str, str | Path] = field(default_factory=dict)


class ServiceAuthenticator:
    """Callable FastAPI dependency using an explicitly configured key allowlist.

    ``now`` returns UTC Unix seconds or an aware datetime. ``is_ready`` exposes
    configuration validity without exposing paths or key material. Deployments
    can atomically reload the same configured files with ``reload_keys()``.
    """

    def __init__(
        self,
        settings: ServiceAuthSettings | None = None,
        now: Callable[[], float | datetime] = time.time,
    ) -> None:
        self.settings = settings or ServiceAuthSettings()
        self._now = now
        self._keys: dict[str, RSAPublicKey] = {}
        self._ready = False
        self.reload_keys()

    @property
    def is_ready(self) -> bool:
        return self._ready

    def reload_keys(self) -> bool:
        """Load all trusted files before serving; a partial/broken set fails closed."""
        try:
            if (
                not isinstance(self.settings.environment, str)
                or self.settings.environment not in _ENVIRONMENTS
                or not isinstance(self.settings.public_keys, dict)
                or not self.settings.public_keys
            ):
                raise ValueError("Invalid service authentication configuration")
            loaded: dict[str, RSAPublicKey] = {}
            for kid, configured_path in self.settings.public_keys.items():
                if not isinstance(kid, str) or _KID.fullmatch(kid) is None:
                    raise ValueError("Invalid configured key identifier")
                if not isinstance(configured_path, (str, Path)):
                    raise ValueError("Invalid configured public key path")
                key = serialization.load_pem_public_key(Path(configured_path).read_bytes())
                if not isinstance(key, RSAPublicKey) or key.key_size < 2048:
                    raise ValueError("Invalid configured RSA public key")
                loaded[kid] = key
        except (OSError, ValueError, TypeError, UnsupportedAlgorithm):
            self._keys = {}
            self._ready = False
            return False
        self._keys = loaded
        self._ready = True
        return True

    def __call__(self, request: Request) -> dict[str, Any]:
        return self.authenticate(request)

    def authenticate(self, request: Request) -> dict[str, Any]:
        if not self.is_ready:
            raise _error(503, "SERVICE_UNAVAILABLE")
        authorization = request.headers.getlist("authorization")
        if len(authorization) > 1:
            raise _error(400, "INVALID_REQUEST", "invalid_request")
        if not authorization:
            raise _error(401, "AUTH_REQUIRED")
        value = authorization[0]
        if "," in value or len(value.split()) > 2:
            raise _error(400, "INVALID_REQUEST", "invalid_request")
        match = _BEARER.fullmatch(value)
        if match is None:
            raise _error(401, "ACCESS_TOKEN_INVALID", "invalid_token")
        token = match.group(1)
        try:
            header = jwt.get_unverified_header(token)
            if (
                set(header) - _TOKEN_HEADERS
                or header.get("alg") != "RS256"
                or header.get("typ") != "tingle-service+jwt"
                or not isinstance(header.get("kid"), str)
                or _KID.fullmatch(header["kid"]) is None
            ):
                raise jwt.InvalidTokenError("Unsupported token header")
            key = self._keys.get(header["kid"])
            if key is None:
                raise jwt.InvalidTokenError("Unknown key identifier")
            # Signature validation always uses a fixed asymmetric algorithm. Time,
            # identity and strict JSON types are checked below using the injected clock.
            claims = jwt.decode(
                token,
                key=key,
                algorithms=["RS256"],
                options={
                    "verify_exp": False,
                    "verify_iat": False,
                    "verify_nbf": False,
                    "verify_aud": False,
                    "verify_iss": False,
                    "verify_sub": False,
                    "verify_jti": False,
                },
            )
            self._validate_structure(claims)
        except (jwt.PyJWTError, ValueError, TypeError, OverflowError):
            raise _error(401, "ACCESS_TOKEN_INVALID", "invalid_token") from None

        now = self._current_seconds()
        if claims["iat"] > now + 30:
            raise _error(401, "ACCESS_TOKEN_INVALID", "invalid_token")
        if now >= claims["exp"] + 30:
            raise _error(401, "ACCESS_TOKEN_EXPIRED", "invalid_token")
        if "ai:execute" not in claims.get("scope", "").split(" "):
            raise _error(403, "FORBIDDEN", "insufficient_scope")
        return claims

    def _validate_structure(self, claims: dict[str, Any]) -> None:
        expected = {
            "iss": f"urn:tingle:be:{self.settings.environment}",
            "aud": f"urn:tingle:ai:{self.settings.environment}",
            "sub": "tingle-be",
        }
        if any(type(claims.get(name)) is not str or claims[name] != value for name, value in expected.items()):
            raise ValueError("Invalid service identity")
        if any(type(claims.get(name)) is not int for name in ("iat", "nbf", "exp")):
            raise ValueError("Invalid service token times")
        if claims["nbf"] != claims["iat"] or not 0 < claims["exp"] - claims["iat"] <= 300:
            raise ValueError("Invalid service token lifetime")
        identifier = claims.get("jti")
        if not isinstance(identifier, str):
            raise ValueError("Invalid token identifier")
        parsed = UUID(identifier)
        if parsed.version != 4 or str(parsed) != identifier.lower():
            raise ValueError("Invalid token identifier")
        if "scope" in claims and not isinstance(claims["scope"], str):
            raise ValueError("Invalid permission type")

    def _current_seconds(self) -> float:
        try:
            now = self._now()
            if isinstance(now, datetime):
                if now.tzinfo is None:
                    raise ValueError("Naive clock")
                now = now.timestamp()
            if isinstance(now, bool) or not isinstance(now, (int, float)) or not math.isfinite(now):
                raise ValueError("Invalid clock")
            return float(now)
        except (ValueError, TypeError, OverflowError, OSError):
            raise _error(503, "SERVICE_UNAVAILABLE") from None


def _error(status: int, code: str, bearer_error: str | None = None) -> HTTPException:
    headers: dict[str, str] = {}
    if status != 503:
        challenge = _REALM
        if bearer_error:
            challenge += f', error="{bearer_error}"'
        if bearer_error == "insufficient_scope":
            challenge += ', scope="ai:execute"'
        headers["WWW-Authenticate"] = challenge
    message = "일시적으로 서비스를 사용할 수 없습니다." if status == 503 else _AUTH_MESSAGE
    return HTTPException(status_code=status, detail={"code": code, "message": message}, headers=headers)
