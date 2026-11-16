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
    def make(handler, *, environment="test"):
        configured = Settings(_env_file=None, app_env=environment,
            ai_service_public_keys={KID: str(public)},
            icebreaker_release_descriptor_file=str(release[0]),
            risk_local_rules_enabled=False)
        provider = GmsClient("synthetic-only", transport=httpx.MockTransport(handler))
        return TestClient(create_app(configured, _icebreaker_gms_client=provider, _match_gms_client=object()))
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


@pytest.mark.parametrize("gate", ["no-topic", "auth", "manifest"])
def test_gate_never_calls_provider(host, make_headers, payload, gate):
    def forbidden(_): pytest.fail("Gate must stop provider")
    if gate != "no-topic": payload["commonTopicKey"] = "walk"
    if gate == "manifest": payload["manifest"]["engineVersion"] = "not-registered"
    environment = "test"
    with host(forbidden, environment=environment) as client:
        response = client.post(PATH, json=payload, headers={} if gate == "auth" else make_headers(environment=environment))
    assert response.status_code == {"auth": 401, "manifest": 503}.get(gate, 200)


def test_release_revoked_while_provider_runs(host, make_headers, payload, release):
    def handler(_):
        release[1].write_bytes(b"revoked synthetic artifact")
        return httpx.Response(200, json=envelope(json.dumps({"questions": QUESTIONS})))
    payload["commonTopicKey"] = "walk"
    with host(handler) as client:
        response = client.post(PATH, json=payload, headers=make_headers())
    assert response.status_code == 503


@pytest.mark.parametrize("question", [
    "산책하면서 종교 이야기를 해 보고 싶으세요?",
    "산책하면서 건강 상태를 이야기할까요?",
    "산책하면서 정치 이야기를 해 보고 싶으세요?",
    "산책할 때 전화번호를 알려 주실래요?",
    "산책하면서 송금 이야기를 해 볼까요?",
    "산책하면서 믿는 신앙에 대해 이야기할까요?",
    "산책하면서 복용하는 약이 있나요?",
    "산책하면서 진료받는 병원이 있나요?",
    "산책하면서 지지하는 정당을 이야기할까요?",
    "산책할 때 투자 금액은 어느 정도인가요?",
    "산책하면서 사는 동네는 어디인가요?",
])
def test_topic_word_does_not_authorize_sensitive_questions(host, make_headers, payload, question):
    def handler(_):
        return httpx.Response(200, json=envelope(json.dumps({"questions": [question, *QUESTIONS[1:]]})))
    payload["commonTopicKey"] = "walk"
    with host(handler) as client:
        response = client.post(PATH, json=payload, headers=make_headers())
    assert response.status_code == 200
    assert response.json() == _select_questions("walk").model_dump()


@pytest.mark.parametrize("topic,questions", [
    ("rest", ["어떤 휴식이 가장 즐거우세요?", "쉬는 날 즐기는 활동은 무엇인가요?", "여유가 생기면 무엇을 해 보고 싶으세요?"]),
    ("talk", ["어떤 대화가 즐거우세요?", "요즘 재미있었던 이야기가 있나요?", "즐겨 이야기하는 주제는 무엇인가요?"]),
    ("culture", ["기억에 남는 공연이 있나요?", "어떤 음악을 즐겨 들으세요?", "최근 재미있게 읽은 책이 있나요?"]),
    ("walk", QUESTIONS),
    ("active", ["어떤 활동이 가장 즐거우세요?", "새로 해 보고 싶은 운동이 있나요?", "최근 시작한 취미가 있나요?"]),
])
def test_ordinary_questions_still_generate_for_all_topics(host, make_headers, payload, topic, questions):
    def handler(_):
        return httpx.Response(200, json=envelope(json.dumps({"questions": questions})))
    payload["commonTopicKey"] = topic
    with host(handler) as client:
        response = client.post(PATH, json=payload, headers=make_headers())
    assert response.status_code == 200
    assert response.json()["source"] == "GENERATED"
    assert response.json()["promptVersion"] == "icebreakers-gms-ffb273caf96e6059"
    assert response.json()["questions"] == questions


def test_provider_cancellation_propagates_instead_of_returning_fallback():
    import asyncio
    from app.icebreakers.gms import GmsIcebreakerGenerator
    class CancelledClient:
        async def request_text(self, *_): raise asyncio.CancelledError()
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(GmsIcebreakerGenerator(CancelledClient()).suggest("walk", _select_questions("walk")))


@pytest.mark.parametrize("char", ["\u200b", "\u200c", "\u2060", "\ufeff", "\u00ad", "\u202e", "\u2066", "\U000e0001"])
@pytest.mark.parametrize("contact", [False, True])
def test_cf_generation_uses_existing_fallback(host, make_headers, payload, char, contact):
    first = "산책하며 연" + char + "락처를 나눌까요?" if contact else "산책" + char + "할 때 어떤 풍경을 좋아하세요?"
    def handler(request):
        assert json.loads(json.loads(request.content)["input"][1]["content"]) == {"topic": "walk"}
        return httpx.Response(200, json=envelope(json.dumps({"questions": [first, *QUESTIONS[1:]]})))
    payload["commonTopicKey"] = "walk"
    with host(handler) as client:
        response = client.post(PATH, json=payload, headers=make_headers())
    assert response.status_code == 200
    assert response.json() == _select_questions("walk").model_dump()
