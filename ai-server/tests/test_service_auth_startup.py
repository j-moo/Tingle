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
from starlette.requests import Request
from unittest.mock import patch
from app.personal_analysis import core
from app.personal_analysis_api.adapter import PersonalAnalysisAdapter
from app.personal_analysis_api.review_samples import synthetic_request
with TestClient(app) as client:
    diagnostic_request_ids = []
    def observe_personal_unavailable(response):
        if response.request.url.path == '/internal/ai/personal-analyses' and response.status_code == 503:
            diagnostic_request_ids.append(response.headers['X-Request-Id'])
    client.event_hooks['response'].append(observe_personal_unavailable)
    paths = client.get('/openapi.json').json()['paths']
    health = client.get('/health')
    denied = client.post('/internal/ai/risk-detections', json={})
    guarded = client.post('/internal/ai/risk-detections', json={}, headers={
        'Authorization': 'Bearer ' + os.environ['STARTUP_TEST_TOKEN'],
    })
    personal = client.post('/internal/ai/personal-analyses', json={})
    personal_guarded = client.post('/internal/ai/personal-analyses', json={}, headers={
        'Authorization': 'Bearer ' + os.environ['STARTUP_TEST_TOKEN'],
    })
    result = {
        'ready': app.state.service_auth.is_ready,
        'health': health.json(), 'healthStatus': health.status_code,
        'cache': health.headers.get('cache-control'),
        'requestId': health.headers.get('x-request-id'),
        'deniedStatus': denied.status_code, 'denied': denied.json(),
        'guardedStatus': guarded.status_code, 'guarded': guarded.json(),
        'guardedCache': guarded.headers.get('cache-control'),
        'personalStatus': personal.status_code, 'personal': personal.json(),
        'personalGuardedStatus': personal_guarded.status_code,
        'personalGuarded': personal_guarded.json(),
        'personalRuntime': hasattr(app.state, 'personal_analysis_runtime'),
        'personalPath': '/internal/ai/personal-analyses' in paths,
        'paths': list(paths), 'environment': os.environ['APP_ENV'],
    }
    with (
        patch.object(PersonalAnalysisAdapter, 'analyze', autospec=True,
                     side_effect=PersonalAnalysisAdapter.analyze) as adapter_call,
        patch.object(core, 'classify_answers', wraps=core.classify_answers) as core_call,
    ):
        outcomes = {}
        for kind in ('succeeded', 'insufficient'):
            response = client.post('/internal/ai/personal-analyses', json=synthetic_request(kind), headers={
                'Authorization': 'Bearer ' + os.environ['STARTUP_TEST_TOKEN'],
            })
            body = response.json()
            outcomes[kind] = {
                'http': response.status_code, 'status': body.get('status'),
                'surveyVersion': body.get('surveyVersion'), 'analysisVersion': body.get('analysisVersion'),
            }
        result['personalOutcomes'] = outcomes
        result['analysisCalls'] = {'adapter': adapter_call.call_count, 'core': core_call.call_count}
    with (
        patch.object(Request, 'body', side_effect=AssertionError('Authentication read the body')) as body_call,
        patch.object(Request, 'json', side_effect=AssertionError('Authentication parsed JSON')) as json_call,
        patch.object(PersonalAnalysisAdapter, 'analyze', side_effect=AssertionError('Authentication ran analysis')) as adapter_call,
        patch.object(core, 'classify_answers', side_effect=AssertionError('Authentication ran calculation')) as core_call,
    ):
        failures = {}
        for case, headers in (
            ('missing', {}),
            ('invalid', {'Authorization': 'Bearer not.a.jwt'}),
            ('scope', {'Authorization': 'Bearer ' + os.environ['STARTUP_TEST_FORBIDDEN_TOKEN']}),
        ):
            response = client.post('/internal/ai/personal-analyses', content='{synthetic-sensitive-sentinel', headers={
                **headers, 'Content-Type': 'application/json',
            })
            failures[case] = {'http': response.status_code, 'code': response.json()['code']}
        with patch.object(app.state.service_auth, '_verifier', None):
            response = client.post('/internal/ai/personal-analyses', content='{synthetic-sensitive-sentinel', headers={
                'Authorization': 'Bearer ' + os.environ['STARTUP_TEST_TOKEN'], 'Content-Type': 'application/json',
            })
            failures['unavailable'] = {'http': response.status_code, 'code': response.json()['code']}
        result['authFailures'] = failures
        result['authGuardCalls'] = {
            'body': body_call.call_count, 'json': json_call.call_count,
            'adapter': adapter_call.call_count, 'core': core_call.call_count,
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
        after_personal = client.post('/internal/ai/personal-analyses', json={})
        result['afterPersonalStatus'] = after_personal.status_code
        result['afterPersonal'] = after_personal.json()
    result['personalDiagnosticRequestIds'] = diagnostic_request_ids
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
    claims = {
        "iss": f"urn:tingle:be:{environment}", "aud": f"urn:tingle:ai:{environment}", "sub": "tingle-be",
        "iat": now, "nbf": now, "exp": now + 300,
        "jti": str(uuid4()), "scope": "ai:execute",
    }
    env.update({
        "APP_ENV": environment,
        "AI_SERVICE_PUBLIC_KEYS": json.dumps(keys),
        "RISK_RELEASE_DESCRIPTOR_FILE": "",
        "PYTHONPATH": str(ROOT),
        "PYTHONIOENCODING": "utf-8",
        "PYTHONDONTWRITEBYTECODE": "1",
        "STARTUP_TEST_RELOAD": "1" if reload else "0",
        "STARTUP_TEST_TOKEN": jwt.encode(
            claims, private, algorithm="RS256", headers={"typ": "tingle-service+jwt", "kid": KID},
        ),
        "STARTUP_TEST_FORBIDDEN_TOKEN": jwt.encode(
            {**claims, "scope": "other"}, private, algorithm="RS256",
            headers={"typ": "tingle-service+jwt", "kid": KID},
        ),
    })
    completed = subprocess.run(
        [sys.executable, "-c", PROBE], cwd=tmp_path, env=env,
        capture_output=True, text=True, encoding="utf-8", timeout=30, check=False,
    )
    result = json.loads(completed.stdout)
    request_ids = result.get("personalDiagnosticRequestIds", [])
    assert len(request_ids) == len(set(request_ids))
    assert all(str(UUID(value)) == value for value in request_ids)
    assert completed.stderr.splitlines() == [
        f"personal_analysis_error requestId={value} templateId=UNRESOLVED errorType=AUTH_VERIFIER_UNAVAILABLE"
        for value in request_ids
    ]
    for secret in (env["STARTUP_TEST_TOKEN"], env["STARTUP_TEST_FORBIDDEN_TOKEN"], "synthetic-sensitive-sentinel"):
        assert secret not in completed.stdout + completed.stderr
    return completed.returncode, result


def assert_live(result):
    assert result["healthStatus"] == 200
    assert result["health"] == {"status": "ok"}
    assert result["cache"] == "no-store"
    assert str(UUID(result["requestId"])) == result["requestId"]
    assert result["guardedCache"] == "no-store"
    assert {"/health", "/internal/ai/risk-detections", "/internal/ai/explanations"} <= set(result["paths"])
    assert result["personalRuntime"] is True
    assert result["personalPath"] is True
    assert result["personalStatus"] == (401 if result["ready"] else 503)
    assert result["personal"]["code"] == ("AUTH_REQUIRED" if result["ready"] else "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
def test_personal_analysis_environment_boundary_with_real_service_jwt(tmp_path, synthetic_private, environment):
    public = write_public(tmp_path / "synthetic-public.pem", synthetic_private)
    code, result = probe(tmp_path, synthetic_private, {KID: public}, environment=environment)
    assert code == 0
    assert result["environment"] == environment
    assert result["ready"]
    assert_live(result)
    assert result["authGuardCalls"] == {"body": 0, "json": 0, "adapter": 0, "core": 0}
    assert result["analysisCalls"] == {"adapter": 2, "core": 2}
    for kind, status in (("succeeded", "SUCCEEDED"), ("insufficient", "INSUFFICIENT")):
        assert result["personalOutcomes"][kind] == {
            "http": 200, "status": status, "surveyVersion": "SIS-Q-1.1-100", "analysisVersion": "SIS-A-1.0",
        }
    assert result["authFailures"] == {
        "missing": {"http": 401, "code": "AUTH_REQUIRED"},
        "invalid": {"http": 401, "code": "ACCESS_TOKEN_INVALID"},
        "scope": {"http": 403, "code": "FORBIDDEN"},
        "unavailable": {"http": 503, "code": "SERVICE_UNAVAILABLE"},
    }


def test_default_empty_keys_preserve_health_and_guarded_analysis(tmp_path, synthetic_private):
    code, result = probe(tmp_path, synthetic_private, {})
    assert code == 0
    assert_live(result)
    assert not result["ready"]
    assert result["deniedStatus"] == result["guardedStatus"] == 503
    assert result["guarded"]["code"] == "SERVICE_UNAVAILABLE"
    assert result["personalGuardedStatus"] == 503
    assert result["personalGuarded"]["code"] == "SERVICE_UNAVAILABLE"


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
    assert result["personalGuardedStatus"] == 422
    assert result["personalGuarded"]["code"] == "INVALID_REQUEST"


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
    assert result["afterPersonalStatus"] == 503
    assert result["afterPersonal"]["code"] == "SERVICE_UNAVAILABLE"
