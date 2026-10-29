"""Load the real application in isolated processes with synthetic key files only."""

import json
import os
from pathlib import Path
import subprocess
import sys
import time
from uuid import UUID, uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import jwt
import pytest


ROOT = Path(__file__).resolve().parents[1]
KID = "synthetic-startup-key"
FAILURE_MESSAGE = "Invalid service authentication configuration"
PROBE = """
import json
import os
from pathlib import Path
try:
    from app.main import app
except Exception as error:
    print(json.dumps({"error": type(error).__name__, "message": str(error)}))
    raise SystemExit(7)
from fastapi.testclient import TestClient
with TestClient(app) as client:
    health = client.get('/health')
    denied = client.post('/internal/ai/risk-detections', json={})
    guarded = client.post('/internal/ai/risk-detections', json={}, headers={
        'Authorization': 'Bearer ' + os.environ['STARTUP_TEST_TOKEN'],
    })
    unregistered = client.post('/internal/ai/personal-analyses', json={})
    result = {
        'ready': app.state.service_auth.is_ready,
        'health': health.json(), 'healthStatus': health.status_code,
        'cache': health.headers.get('cache-control'),
        'requestId': health.headers.get('x-request-id'),
        'deniedStatus': denied.status_code, 'denied': denied.json(),
        'guardedStatus': guarded.status_code, 'guarded': guarded.json(),
        'guardedCache': guarded.headers.get('cache-control'),
        'unregisteredStatus': unregistered.status_code,
    }
    if os.environ.get('STARTUP_TEST_RELOAD') == '1':
        configured = json.loads(os.environ['AI_SERVICE_PUBLIC_KEYS'])
        Path(next(iter(configured.values()))).write_text('broken-synthetic-key')
        result['reload'] = app.state.service_auth.reload_keys()
        after = client.post('/internal/ai/risk-detections', json={})
        result['afterStatus'] = after.status_code
        result['after'] = after.json()
        result['afterCache'] = after.headers.get('cache-control')
        result['afterHealthStatus'] = client.get('/health').status_code
    print(json.dumps(result))
"""


@pytest.fixture(scope="module")
def synthetic_private():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def write_public(path, private):
    path.write_bytes(private.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ))
    return str(path)


def probe(tmp_path, private, keys, *, environment="test", reload=False):
    # Do not inherit AI settings, real tokens, or a project .env from the caller.
    env = {key: value for key, value in os.environ.items()
           if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    now = int(time.time())
    env.update({
        "APP_ENV": environment,
        "AI_SERVICE_PUBLIC_KEYS": json.dumps(keys),
        "RISK_RELEASE_DESCRIPTOR_FILE": "",
        "PYTHONPATH": str(ROOT),
        "PYTHONIOENCODING": "utf-8",
        "PYTHONDONTWRITEBYTECODE": "1",
        "STARTUP_TEST_RELOAD": "1" if reload else "0",
        "STARTUP_TEST_TOKEN": jwt.encode({
            "iss": "urn:tingle:be:test", "aud": "urn:tingle:ai:test", "sub": "tingle-be",
            "iat": now, "nbf": now, "exp": now + 300,
            "jti": str(uuid4()), "scope": "ai:execute",
        }, private, algorithm="RS256", headers={"typ": "tingle-service+jwt", "kid": KID}),
    })
    completed = subprocess.run(
        [sys.executable, "-c", PROBE], cwd=tmp_path, env=env,
        capture_output=True, text=True, encoding="utf-8", timeout=30, check=False,
    )
    assert completed.stderr == ""
    return completed.returncode, json.loads(completed.stdout)


def assert_live(result):
    assert result["healthStatus"] == 200
    assert result["health"] == {"status": "ok"}
    assert result["cache"] == "no-store"
    assert str(UUID(result["requestId"])) == result["requestId"]
    assert result["guardedCache"] == "no-store"
    assert result["unregisteredStatus"] == 404


def test_default_empty_keys_preserve_health_and_guarded_analysis(tmp_path, synthetic_private):
    code, result = probe(tmp_path, synthetic_private, {})
    assert code == 0
    assert_live(result)
    assert not result["ready"]
    assert result["deniedStatus"] == result["guardedStatus"] == 503
    assert result["guarded"]["code"] == "SERVICE_UNAVAILABLE"


def test_valid_synthetic_keys_start_without_enabling_model(tmp_path, synthetic_private):
    public = write_public(tmp_path / "synthetic-public.pem", synthetic_private)
    code, result = probe(tmp_path, synthetic_private, {KID: public})
    assert code == 0
    assert_live(result)
    assert result["ready"]
    assert result["deniedStatus"] == 401
    assert result["denied"]["code"] == "AUTH_REQUIRED"
    assert result["guardedStatus"] == 422
    assert result["guarded"]["code"] == "POLICY_UNRESOLVED"


@pytest.mark.parametrize("case", ["missing", "malformed", "weak", "kid", "environment", "partial"])
def test_explicit_invalid_configuration_fails_actual_application_startup(
    tmp_path, synthetic_private, case,
):
    public = write_public(tmp_path / "private-location-must-not-leak.pem", synthetic_private)
    keys = {KID: public}
    environment = "test"
    if case == "missing":
        keys[KID] = str(tmp_path / "missing-private-location.pem")
    elif case == "malformed":
        Path(public).write_text("malformed-synthetic-public-material")
    elif case == "weak":
        weak = rsa.generate_private_key(public_exponent=65537, key_size=1024)
        write_public(Path(public), weak)
    elif case == "kid":
        keys = {"untrusted/key-identifier": public}
    elif case == "environment":
        environment = "unsupported-environment"
    elif case == "partial":
        keys["other-synthetic-key"] = str(tmp_path / "missing-other-key.pem")
    code, result = probe(tmp_path, synthetic_private, keys, environment=environment)
    assert code == 7
    assert result == {"error": "RuntimeError", "message": FAILURE_MESSAGE}


def test_runtime_key_loss_remains_fail_closed_without_breaking_health(tmp_path, synthetic_private):
    public = write_public(tmp_path / "synthetic-reload.pem", synthetic_private)
    code, result = probe(tmp_path, synthetic_private, {KID: public}, reload=True)
    assert code == 0
    assert_live(result)
    assert result["ready"]
    assert not result["reload"]
    assert result["afterStatus"] == 503
    assert result["after"]["code"] == "SERVICE_UNAVAILABLE"
    assert result["afterCache"] == "no-store"
    assert result["afterHealthStatus"] == 200
