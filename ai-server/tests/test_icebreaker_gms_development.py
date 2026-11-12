"""Optional real GMS adapter on create_app; only MockTransport in pytest."""

import json

from cryptography.hazmat.primitives import serialization
from fastapi.testclient import TestClient
import httpx2 as httpx
import pytest

from app.config import Settings
from app.main import create_app
from app.match_explanation.gms import GmsClient
from app.icebreakers.http_api import _select_questions
from app.icebreakers.prompts import GMS_PROMPT_VERSION
from security.synthetic_jwt import NOW
from test_icebreakers_http import signing_key, release, payload, make_headers, KID, PATH
from test_match_explanation_gms import envelope

QUESTIONS = ["산책할 때 어떤 풍경을 좋아하세요?", "여행 중 가장 즐거운 순간은 언제인가요?", "걷기 좋은 계절은 언제라고 생각하세요?"]


@pytest.fixture
def host(tmp_path, signing_key, release, monkeypatch):
    public = tmp_path / "public.pem"
    public.write_bytes(signing_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    monkeypatch.setattr("app.security.service_auth.time", lambda: NOW)
    def make(handler, *, enabled=True, environment="test"):
        configured = Settings(_env_file=None, app_env=environment,
            ai_service_public_keys={KID: str(public)},
            icebreaker_release_descriptor_file=str(release[0]), icebreaker_dev_enabled=True,
            risk_local_rules_enabled=False, explanation_release_descriptor_files=[])
        provider = GmsClient("synthetic-only", transport=httpx.MockTransport(handler))
        return TestClient(create_app(configured, _gms_client=provider if enabled else None))
    return make


@pytest.mark.parametrize("fault", [None, "unsafe", "topic", "duplicates", "count", "statement", "json", "timeout", "provider"])
def test_generation_and_fallback(host, make_headers, payload, fault):
    calls = []
    def handler(request):
        calls.append(request)
        body = json.loads(request.content)
        assert json.loads(body["input"][1]["content"]) == {"topic": "walk"}
        if fault == "timeout": raise httpx.ReadTimeout("private error", request=request)
        if fault == "provider": return httpx.Response(503, text="private error")
        questions = list(QUESTIONS)
        if fault == "unsafe": questions[0] = "산책 전에 전화번호를 알려 주세요?"
        if fault == "topic": questions[0] = "좋아하는 종교가 있나요?"
        if fault == "duplicates": questions[1] = questions[0]
        if fault == "count": questions.pop()
        if fault == "statement": questions[0] = "두 분은 산책을 좋아하시죠?"
        raw = "{invalid" if fault == "json" else json.dumps({"questions": questions})
        return httpx.Response(200, json=envelope(raw))
    payload["commonTopicKey"] = "walk"
    with host(handler) as client:
        response = client.post(PATH, json=payload, headers=make_headers())
    assert response.status_code == 200 and len(calls) == 1
    if fault is None:
        assert response.json() == {"questions": QUESTIONS, "source": "GENERATED", "promptVersion": GMS_PROMPT_VERSION}
    else:
        assert response.json() == _select_questions("walk").model_dump()


@pytest.mark.parametrize("gate", ["default", "no-topic", "auth", "manifest", "production"])
def test_gate_never_calls_provider(host, make_headers, payload, gate):
    def forbidden(_): pytest.fail("Gate must stop provider")
    if gate != "no-topic": payload["commonTopicKey"] = "walk"
    if gate == "manifest": payload["manifest"]["engineVersion"] = "not-registered"
    environment = "prod" if gate == "production" else "test"
    with host(forbidden, enabled=gate != "default", environment=environment) as client:
        response = client.post(PATH, json=payload, headers={} if gate == "auth" else make_headers(environment=environment))
    assert response.status_code == {"auth": 401, "manifest": 503, "production": 503}.get(gate, 200)


def test_release_revoked_while_provider_runs(host, make_headers, payload, release):
    def handler(_):
        release[1].write_bytes(b"revoked synthetic artifact")
        return httpx.Response(200, json=envelope(json.dumps({"questions": QUESTIONS})))
    payload["commonTopicKey"] = "walk"
    with host(handler) as client:
        response = client.post(PATH, json=payload, headers=make_headers())
    assert response.status_code == 503
