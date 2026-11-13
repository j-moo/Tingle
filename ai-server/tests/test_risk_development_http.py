"""Fresh app.main startup and real loopback HTTP; synthetic credentials/data only."""

from contextlib import contextmanager
import http.client
import ipaddress
import json
import socket
import subprocess
import sys
import time
from uuid import UUID, uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import jwt
import pytest

from safety48_runtime_support import isolated_environment, message, request_for, synthetic_release
from test_risk_detection_http import ROUTE


KID = "synthetic-safety48-runtime-key"
PROBE = """
import json, os, sys
try:
    from app.main import app
    from fastapi.testclient import TestClient
    with TestClient(app) as client:
        response = client.post('/internal/ai/risk-detections', json=json.loads(os.environ['SYNTHETIC_REQUEST']),
                               headers={'Authorization': 'Bearer ' + os.environ['SYNTHETIC_TOKEN']})
        print(json.dumps({'http': response.status_code, 'body': response.json(),
                          'tokenizersLoaded': 'tokenizers' in sys.modules,
                          'rulesLoaded': 'app.risk_detection.rule_engine' in sys.modules}))
except Exception as error:
    print(json.dumps({'error': type(error).__name__, 'message': str(error)}))
    raise SystemExit(7)
"""
SERVER = """
import ipaddress, socket, sys
original = socket.socket.connect
def loopback_only(connection, address):
    if not isinstance(address, tuple) or not ipaddress.ip_address(address[0]).is_loopback:
        raise RuntimeError('External network forbidden in synthetic smoke server')
    return original(connection, address)
socket.socket.connect = loopback_only
from app.main import app
import uvicorn
uvicorn.run(app, host='127.0.0.1', port=int(sys.argv[1]), access_log=False, log_level='error')
"""


@pytest.fixture(scope="module")
def synthetic_private():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture(autouse=True)
def no_external_connections(monkeypatch):
    original = socket.socket.connect

    def loopback_only(connection, address):
        assert isinstance(address, tuple) and ipaddress.ip_address(address[0]).is_loopback
        return original(connection, address)

    monkeypatch.setattr(socket.socket, "connect", loopback_only)


def public_key_file(directory, private):
    path = directory / "synthetic-public-only.pem"
    path.write_bytes(private.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ))
    return {KID: str(path)}


def token(private, environment="test", scope="ai:execute"):
    now = int(time.time())
    return jwt.encode({
        "iss": f"urn:tingle:be:{environment}", "aud": f"urn:tingle:ai:{environment}", "sub": "tingle-be",
        "iat": now, "nbf": now, "exp": now + 300, "jti": str(uuid4()), "scope": scope,
    }, private, algorithm="RS256", headers={"typ": "tingle-service+jwt", "kid": KID})


@pytest.mark.parametrize("environment,enabled,status", [
    ("local", False, 422), ("test", False, 422), ("dev", False, 422),
    ("prod", False, 422), ("prod", True, 422),
    ("local", True, 200), ("test", True, 200), ("dev", True, 200),
])
def test_real_main_honors_opt_in_and_environment(tmp_path, synthetic_private, environment, enabled, status):
    release = synthetic_release(tmp_path)
    keys = public_key_file(tmp_path, synthetic_private)
    env = isolated_environment(tmp_path, environment=environment, enabled=enabled, release=release, public_keys=keys)
    env.update(SYNTHETIC_TOKEN=token(synthetic_private, environment), SYNTHETIC_REQUEST=json.dumps(request_for(release)))
    completed = subprocess.run([sys.executable, "-c", PROBE], cwd=tmp_path, env=env,
                               capture_output=True, encoding="utf-8", timeout=30)
    assert completed.returncode == 0 and not completed.stderr
    result = json.loads(completed.stdout)
    assert result["http"] == status
    assert result["tokenizersLoaded"] is (status == 200)
    assert result["rulesLoaded"] is (status == 200)
    if status == 200:
        assert result["body"]["verdict"]["kind"] == "NORMAL"
    else:
        assert result["body"]["code"] == "POLICY_UNRESOLVED"


def test_real_main_rejects_missing_opt_in_release_without_disclosing_paths(tmp_path, synthetic_private):
    env = isolated_environment(tmp_path, public_keys=public_key_file(tmp_path, synthetic_private))
    completed = subprocess.run([sys.executable, "-c", PROBE], cwd=tmp_path, env=env,
                               capture_output=True, encoding="utf-8", timeout=30)
    assert completed.returncode == 7 and not completed.stderr
    assert json.loads(completed.stdout) == {
        "error": "DevelopmentReleaseError", "message": "Safety Guard development runtime unavailable"}


@pytest.mark.parametrize("environment", ["local", "test", "dev"])
def test_real_main_rejects_unreviewed_files_even_with_opt_in(tmp_path, synthetic_private, environment):
    release = synthetic_release(tmp_path)
    env = isolated_environment(tmp_path, environment=environment, release=release,
                               public_keys=public_key_file(tmp_path, synthetic_private))
    env["RISK_LOCAL_RULES_DESCRIPTOR_SHA256"] = ""
    completed = subprocess.run([sys.executable, "-c", PROBE], cwd=tmp_path, env=env,
                               capture_output=True, encoding="utf-8", timeout=30)
    assert completed.returncode == 7 and not completed.stderr
    assert json.loads(completed.stdout) == {
        "error": "DevelopmentReleaseError", "message": "Safety Guard development runtime unavailable"}


def http_request(port, path, *, method="POST", payload=None, service_token=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        headers = {"Content-Type": "application/json"}
        if service_token is not None:
            headers["Authorization"] = "Bearer " + service_token
        connection.request(method, path, body=json.dumps(payload).encode() if payload is not None else None,
                           headers=headers)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), json.loads(response.read())
    finally:
        connection.close()


@contextmanager
def loopback_server(directory, env):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    process = subprocess.Popen([sys.executable, "-c", SERVER, str(port)], cwd=directory, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, encoding="utf-8")
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            assert process.poll() is None, "Synthetic FastAPI server exited before readiness"
            try:
                if http_request(port, "/health", method="GET")[0] == 200:
                    break
            except (OSError, http.client.HTTPException):
                time.sleep(0.05)
        else:
            pytest.fail("Synthetic FastAPI server did not become ready")
        yield port
    finally:
        process.terminate()
        try:
            stdout, stderr = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            stdout, stderr = process.communicate(timeout=5)
        assert stdout == "" and stderr == ""


def test_real_loopback_http_with_synthetic_jwt_release_and_actual_tokens(tmp_path, synthetic_private, caplog):
    release = synthetic_release(tmp_path)
    env = isolated_environment(tmp_path, release=release, public_keys=public_key_file(tmp_path, synthetic_private))
    service_token = token(synthetic_private)
    results = {}
    with loopback_server(tmp_path, env) as port:
        for kind, messages in [
            ("NORMAL", [message("합성 점심값은 반씩 나누자.")]),
            ("RISK", [message("원금 보장 투자에 인증금을 보내.", fresh=False), message("지금 보내.", 2)]),
            ("INSUFFICIENT", [message("그거 보내 줘. 알지?")]),
        ]:
            payload = request_for(release, messages)
            status, headers, body = http_request(port, ROUTE, payload=payload, service_token=service_token)
            assert status == 200 and body["verdict"]["kind"] == kind
            assert body["attemptId"] == payload["attemptId"] and body["manifest"] == payload["manifest"]
            assert headers["cache-control"] == "no-store" and UUID(headers["x-request-id"])
            assert body["verdict"]["confidence"] is None
            assert all(m["text"] not in json.dumps(body, ensure_ascii=False) for m in messages)
            assert "contextId" not in body
            if kind == "RISK":
                assert body["verdict"]["findings"] == [
                    dict(sequence=2, category="FRAUD", noticeType="MONEY_REQUEST", audience="RECIPIENT")]
            else:
                assert body["verdict"]["findings"] == []
            results[kind] = status

        status, _, body = http_request(port, ROUTE, payload=request_for(release, [
            message("어제 모르는 사람이 협박했어. 그런데 내가 널 때릴 거야.", 7, speaker="B"),
        ]), service_token=service_token)
        assert status == 200
        assert body["verdict"]["findings"] == [
            dict(sequence=7, category="THREAT", noticeType="HARASSMENT", audience="BOTH")]

        wrong_manifest = request_for(release)
        wrong_manifest["manifest"]["wordingVersion"] = "unregistered-synthetic-version"
        crowded = ("원금 보장하니 인증금 송금해. 너 같은 멍청이. 네 사진을 퍼뜨리겠다. "
                   "비밀번호를 보내. 보안 경고 무시하고 앱 설치해.")
        status, _, body = http_request(port, ROUTE, payload=request_for(
            release, [message(crowded, i + 1) for i in range(9)]), service_token=service_token)
        assert status == 200 and body["verdict"]["kind"] == "RISK"
        assert len(body["verdict"]["findings"]) == 36
        assert {(f["sequence"], f["noticeType"], f["audience"]) for f in body["verdict"]["findings"]} == {
            (sequence, notice, audience) for sequence in range(1, 10)
            for notice, audience in [("MONEY_REQUEST", "RECIPIENT"), ("HARASSMENT", "BOTH"),
                                     ("PERSONAL_INFO", "RECIPIENT"), ("OFF_PLATFORM", "RECIPIENT")]
        }
        assert all(f["category"] == "THREAT" for f in body["verdict"]["findings"]
                   if f["noticeType"] == "HARASSMENT")
        results["OVERFLOW_RISK_45_TO_36"] = status
        for code, expected, payload, credential in [
            ("AUTH_REQUIRED", 401, {}, None),
            ("FORBIDDEN", 403, {}, token(synthetic_private, scope="ai:risk")),
            ("INVALID_REQUEST", 400, request_for(release, [message("https://example.invalid")]), service_token),
            ("POLICY_UNRESOLVED", 422, wrong_manifest, service_token),
        ]:
            status, headers, body = http_request(port, ROUTE, payload=payload, service_token=credential)
            assert status == expected and body["code"] == code
            assert set(body) == {"code", "message"}
            assert headers["cache-control"] == "no-store" and UUID(headers["x-request-id"])
            results[code] = status
        release.tokenizer.write_bytes(release.tokenizer.read_bytes() + b" ")
        status, _, body = http_request(port, ROUTE, payload=request_for(release), service_token=service_token)
        assert status == 422 and body["code"] == "POLICY_UNRESOLVED"
    assert service_token not in caplog.text
    print(json.dumps({"syntheticOnly": True, "transport": "actual-loopback-HTTP", "outcomes": results}))
