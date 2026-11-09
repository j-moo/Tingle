"""Test-process-only main loader; real settings, JWT verifier and release checks.

Private keys/JWTs stay in memory. Only synthetic public keys and release files
are written under pytest's temporary directory. No runtime imports this module.
"""

from contextlib import ExitStack
from dataclasses import dataclass, field
from importlib import import_module
import ipaddress
import json
import socket
import sys
import time
from uuid import UUID, uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient
import httpx
import jwt
import pytest

from app.config import Settings
from safety48_runtime_support import request_for, synthetic_release


ROUTE = "/internal/ai/risk-detections"
KID = "synthetic-asgi-contract-key"
SECRET_MARKER = "synthetic-only-sensitive-error-marker"


@pytest.fixture(scope="module")
def synthetic_private_keys():
    return tuple(rsa.generate_private_key(public_exponent=65537, key_size=2048) for _ in range(2))


@pytest.fixture(autouse=True)
def no_external_io(monkeypatch):
    """Fail the test even when a service catches the blocked network exception."""
    attempts = []
    connect, connect_ex, resolve = socket.socket.connect, socket.socket.connect_ex, socket.getaddrinfo

    def check(host):
        try:
            local = host == "localhost" or ipaddress.ip_address(host).is_loopback
        except ValueError:
            local = False
        if not local:
            attempts.append(True)  # Never retain an endpoint or credentials.
            raise RuntimeError("External network forbidden by ASGI contract test")

    def guarded_connect(connection, address):
        if connection.family in {socket.AF_INET, socket.AF_INET6}:
            check(address[0])
        return connect(connection, address)

    def guarded_connect_ex(connection, address):
        if connection.family in {socket.AF_INET, socket.AF_INET6}:
            check(address[0])
        return connect_ex(connection, address)

    def guarded_resolve(host, *args, **kwargs):
        if host is not None:
            check(host)
        return resolve(host, *args, **kwargs)

    monkeypatch.setattr(socket.socket, "connect", guarded_connect)
    monkeypatch.setattr(socket.socket, "connect_ex", guarded_connect_ex)
    monkeypatch.setattr(socket, "getaddrinfo", guarded_resolve)
    yield
    assert not attempts, "A test attempted external network access"


@dataclass(repr=False)
class MainHarness:
    client: TestClient
    release: object
    environment: str
    private_keys: tuple = field(repr=False)
    tokens: list = field(default_factory=list, repr=False)
    observations: list = field(default_factory=list)

    def token(self, changes=None, *, wrong_key=False, omit=(), headers=None):
        now = int(time.time())
        claims = dict(iss=f"urn:tingle:be:{self.environment}", aud=f"urn:tingle:ai:{self.environment}",
                      sub="tingle-be", iat=now, nbf=now, exp=now + 300, jti=str(uuid4()), scope="ai:execute")
        claims.update(changes or {})
        for name in omit:
            claims.pop(name, None)
        value = jwt.encode(claims, self.private_keys[int(wrong_key)], algorithm="RS256",
                           headers={"typ": "tingle-service+jwt", "kid": KID, **(headers or {})})
        self.tokens.append(value)
        return value

    def post(self, payload=None, *, raw=None, credential="valid", headers=None):
        outgoing = {"Content-Type": "application/json", **(headers or {})}
        if credential is not None:
            outgoing["Authorization"] = "Bearer " + (self.token() if credential == "valid" else credential)
        if raw is None:
            raw = json.dumps(payload if payload is not None else request_for(self.release),
                             ensure_ascii=True, separators=(",", ":")).encode()
        delivered = []
        if isinstance(raw, bytes):
            response = self.client.post(ROUTE, content=raw, headers=outgoing)
        else:
            # TestClient/request.read() coalesces iterables in this runtime.
            # ASGITransport preserves separate http.request receive events.
            async def send_chunks():
                async def stream():
                    for chunk in raw:
                        delivered.append(len(chunk))
                        yield chunk
                transport = httpx.ASGITransport(app=self.client.app, raise_app_exceptions=False)
                async with httpx.AsyncClient(transport=transport, base_url="http://asgi-contract.invalid") as client:
                    return await client.post(ROUTE, content=stream(), headers=outgoing)
            response = self.client.portal.call(send_chunks)
        observation = {"actualStatus": response.status_code, "actualCode": response.json().get("code")}
        if delivered:
            observation["asgiChunkSizes"] = delivered
        self.observations.append(observation)
        response.extensions["contract_observation"] = observation
        assert not any(token in response.text for token in self.tokens), "JWT leaked in HTTP response"
        return response


@pytest.fixture
def main_factory(tmp_path, monkeypatch, synthetic_private_keys, caplog, capsys, record_property):
    """Load actual app.main with isolated in-memory Settings; never replace auth.

    The runtime constructs DevelopmentReleaseCatalog, LocalFileTokenizer and
    LocalRuleRiskEngine itself. Fault tests patch only engine/tokenizer methods;
    catalog/supports, hashes, JWT verifier and main router are never patched.
    """
    import app
    from app import config

    monkeypatch.chdir(tmp_path)  # No repository .env is read by auth settings.
    for name in Settings.model_fields:
        monkeypatch.delenv(name.upper(), raising=False)
    for name in ("SERVICE_AUTH_PUBLIC_KEY_FILES", "ENVIRONMENT", "PUBLIC_KEYS"):
        monkeypatch.delenv(name, raising=False)
    old_main = sys.modules.get("app.main")
    old_attribute = getattr(app, "main", None)
    hosts = []
    stack = ExitStack()

    def make(*, environment="test", enabled=True, release=None, pin=None, keys=True):
        directory = tmp_path / f"main-{len(hosts)}"
        directory.mkdir(exist_ok=True)
        release = release if release is not None else synthetic_release(directory)
        public_file = directory / "synthetic-public-only.pem"
        public_file.write_bytes(synthetic_private_keys[0].public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
        values = dict(app_env=environment, ai_service_public_keys={KID: str(public_file)} if keys else {},
                      risk_release_descriptor_file=str(release.descriptor),
                      risk_local_rules_descriptor_sha256=release.test_descriptor_pin if pin is None else pin)
        if enabled is not None:  # None proves the actual Settings default OFF.
            values["risk_local_rules_enabled"] = enabled
        monkeypatch.setattr(config, "settings", Settings(_env_file=None, **values))
        sys.modules.pop("app.main", None)
        module = import_module("app.main")
        assert module.app.state.service_auth.is_ready is keys
        assert "post" in module.app.openapi()["paths"][ROUTE]
        client = stack.enter_context(TestClient(module.app, raise_server_exceptions=False))
        host = MainHarness(client, release, environment, synthetic_private_keys)
        hosts.append(host)
        return host

    try:
        yield make
    finally:
        stack.close()
        if old_main is None:
            sys.modules.pop("app.main", None)
        else:
            sys.modules["app.main"] = old_main
        if old_attribute is None:
            app.__dict__.pop("main", None)
        else:
            app.main = old_attribute
        captured = capsys.readouterr()
        output = "\n".join(record.getMessage() for phase in ("setup", "call", "teardown")
                           for record in caplog.get_records(phase)) + captured.out + captured.err
        record_property("asgi_observations", json.dumps([item for host in hosts for item in host.observations]))
        assert SECRET_MARKER not in output
        assert "BEGIN PRIVATE KEY" not in output
        assert not any(token in output for host in hosts for token in host.tokens), "JWT leaked in test output"


def expect(response, status=200, code=None):
    """Assertions contain only public response values, never request credentials."""
    response.extensions["contract_observation"].update(expectedStatus=status, expectedCode=code)
    assert response.status_code == status, {"actualStatus": response.status_code,
                                           "actualCode": response.json().get("code"), "expectedStatus": status}
    assert response.headers["cache-control"] == "no-store"
    assert str(UUID(response.headers["x-request-id"])) == response.headers["x-request-id"].lower()
    assert SECRET_MARKER not in response.text
    if code is not None:
        assert response.json() == {"code": code, "message": response.json()["message"]}
        assert response.json()["message"]
    return response.json()
