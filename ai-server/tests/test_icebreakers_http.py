"""Exercise the registered HTTP route with an offline service JWT and release."""

import hashlib
import json
import logging
from pathlib import Path
import runpy
from uuid import uuid4

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient
import jwt
import pytest

from app.config import Settings
from app.icebreakers import templates
from app.match_explanation.releases import ReleaseRegistry
from security.synthetic_jwt import NOW


AI_ROOT = Path(__file__).resolve().parents[1]
BE_FALLBACK = AI_ROOT.parent / "back/src/main/resources/icebreakers/general-ko-v1.json"
PATH = "/internal/ai/icebreakers"
KID = "synthetic-icebreakers"


@pytest.fixture(scope="module")
def signing_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def release(tmp_path):
    artifact = tmp_path / "synthetic-release.txt"
    artifact.write_bytes(b"synthetic icebreakers release; no survey answers\n")
    manifest = {
        "releaseId": "10000000-0000-4000-8000-000000000001",
        "surveyVersion": "synthetic-survey",
        "wordingVersion": "synthetic-wording",
        "scoringVersion": "synthetic-scoring",
        "typeModelVersion": "synthetic-type-model",
        "analysisVersion": "synthetic-analysis",
        "typeReferenceVersion": "synthetic-type-reference",
        "matchingVersion": "synthetic-matching",
        "productPolicyVersion": "synthetic-policy",
        "engineVersion": "synthetic-engine",
        "contentHash": hashlib.sha256(artifact.read_bytes()).hexdigest(),
    }
    descriptor = tmp_path / "synthetic-descriptor.json"
    descriptor.write_text(json.dumps({"manifest": manifest, "artifact": artifact.name}), encoding="utf-8")
    return descriptor, artifact, manifest


@pytest.fixture
def payload(release):
    return {
        "attemptId": "20000000-0000-4000-8000-000000000002",
        "contextId": 42,
        "manifest": release[2].copy(),
    }


@pytest.fixture
def make_headers(signing_key):
    def headers(*, scope="ai:execute", environment="test", kid=KID):
        claims = {
            "iss": f"urn:tingle:be:{environment}", "aud": f"urn:tingle:ai:{environment}",
            "sub": "tingle-be", "iat": NOW, "nbf": NOW, "exp": NOW + 300,
            "jti": str(uuid4()), "scope": scope,
        }
        token = jwt.encode(claims, signing_key, algorithm="RS256",
                           headers={"typ": "tingle-service+jwt", "kid": kid})
        return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    return headers


@pytest.fixture
def make_client(monkeypatch, tmp_path, signing_key, release):
    def client(*, configure_keys=True, configure_release=True, environment="test", enable_dev=False):
        public = tmp_path / "synthetic-public.pem"
        public.write_bytes(signing_key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
        ))
        # Load the real app registration without replacing the module shared by other tests.
        monkeypatch.delenv("ICEBREAKER_RELEASE_DESCRIPTOR_FILE", raising=False)
        monkeypatch.delenv("ICEBREAKER_DEV_ENABLED", raising=False)
        if enable_dev:
            monkeypatch.setenv("ICEBREAKER_DEV_ENABLED", "true")
        settings = Settings(
            _env_file=None,
            app_env=environment,
            ai_service_public_keys={KID: str(public)} if configure_keys else {},
            explanation_release_descriptor_files=[],
            risk_release_descriptor_file="",
            **({"icebreaker_release_descriptor_file": str(release[0])} if configure_release else {}),
        )
        monkeypatch.setattr("app.config.settings", settings)
        monkeypatch.setattr("app.security.service_auth.time", lambda: NOW)
        return TestClient(runpy.run_path(str(AI_ROOT / "app/main.py"))["app"])

    return client


@pytest.fixture
def client(make_client):
    with make_client() as active_client:
        yield active_client


def assert_error(response, status, code):
    assert response.status_code == status
    assert set(response.json()) == {"code", "message"}
    assert response.json()["code"] == code
    assert response.headers["Cache-Control"] == "no-store"


def test_registered_route_returns_exact_be_fallback(client, make_headers, payload):
    response = client.post(PATH, json=payload, headers={**make_headers(), "X-Request-Id": payload["attemptId"]})

    assert response.status_code == 200
    expected = json.loads(BE_FALLBACK.read_bytes())
    assert response.json() == expected
    for question in expected["questions"]:
        assert question.encode("utf-8") in response.content
    assert response.headers["X-Request-Id"] == payload["attemptId"]
    assert response.headers["Cache-Control"] == "no-store"


@pytest.mark.parametrize(("key", "first_question"), [
    ("rest", "쉬는 날에는 주로 어떻게 휴식을 취하세요?"),
    ("talk", "식사하면서 가장 즐겁게 이야기하는 주제가 있나요?"),
    ("culture", "전시와 공연 중에는 어느 쪽에 더 관심이 가세요?"),
    ("walk", "산책과 여행 중에 요즘 더 끌리는 활동이 있나요?"),
    ("active", "요즘 새롭게 해보고 싶은 활동이 있나요?"),
])
def test_registered_route_returns_q040_template(client, make_headers, payload, key, first_question):
    payload["commonTopicKey"] = key
    response = client.post(PATH, json=payload, headers=make_headers())

    assert response.status_code == 200
    general = json.loads(BE_FALLBACK.read_bytes())
    assert response.json() == {
        "questions": [first_question, *general["questions"][1:]],
        "source": "COMMON_TOPIC_TEMPLATE",
        "promptVersion": "q040-template-v1",
    }
    assert response.headers["Cache-Control"] == "no-store"


@pytest.mark.parametrize("key", [None, "", "unknown", "REST", " rest ", "DATE_WALK", 1, True, ["rest"], {"key": "rest"}])
def test_only_omission_or_exact_q040_codes_are_accepted(client, make_headers, payload, key):
    payload["commonTopicKey"] = key
    response = client.post(PATH, json=payload, headers=make_headers())
    assert_error(response, 400, "INVALID_REQUEST")


@pytest.mark.parametrize("policy", ["template", "response"])
def test_rejected_q040_question_returns_exact_be_fallback(client, make_headers, payload, monkeypatch, policy):
    if policy == "template":
        monkeypatch.setitem(templates.DATE_TOPICS, "DATE_REST", "계좌를 알려주시겠어요?")
    else:
        monkeypatch.setattr(templates, "suggest_date_questions",
                            lambda _: ("https://example.test", *templates.GENERAL[1:]))
    payload["commonTopicKey"] = "rest"
    response = client.post(PATH, json=payload, headers=make_headers())

    assert response.status_code == 200
    assert response.json() == json.loads(BE_FALLBACK.read_bytes())


@pytest.mark.parametrize(("auth", "status", "code"), [
    ("missing", 401, "AUTH_REQUIRED"),
    ("invalid", 401, "ACCESS_TOKEN_INVALID"),
    ("scope", 403, "FORBIDDEN"),
])
def test_service_auth_rejects_before_parsing_body(client, make_headers, auth, status, code):
    headers = {
        "missing": {},
        "invalid": make_headers(kid="unregistered-key"),
        "scope": make_headers(scope="ai:read"),
    }
    response = client.post(PATH, content=b"{malformed", headers=headers[auth])

    assert_error(response, status, code)
    assert response.headers["WWW-Authenticate"].startswith('Bearer realm="tingle-ai"')
    if auth == "scope":
        assert 'scope="ai:execute"' in response.headers["WWW-Authenticate"]


@pytest.mark.parametrize(("field", "value"), [
    ("attemptId", "not-a-uuid"),
    ("attemptId", "AAAAAAAA-0000-4000-8000-000000000002"),
    ("contextId", 0),
    ("contextId", -1),
    ("contextId", True),
    ("contextId", "42"),
    ("contextId", 42.0),
    ("contextId", 2**63),
    ("unexpected", "not-in-the-contract"),
])
def test_request_has_exact_strict_fields(client, make_headers, payload, field, value):
    payload[field] = value
    assert_error(client.post(PATH, json=payload, headers=make_headers()), 400, "INVALID_REQUEST")


@pytest.mark.parametrize(("field", "value"), [
    ("releaseId", "invalid-uuid"),
    ("contentHash", "A" * 64),
    ("contentHash", "a" * 63),
    ("surveyVersion", "\u3000"),
    ("engineVersion", "가" * 101),
    ("wordingVersion", "🙂" * 51),
    ("scoringVersion", 1),
    ("artifact", "not-selected-by-the-request"),
])
def test_manifest_has_exact_strict_fields(client, make_headers, payload, field, value):
    payload["manifest"][field] = value
    assert_error(client.post(PATH, json=payload, headers=make_headers()), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("missing", ["attemptId", "contextId", "manifest", "matchingVersion"])
def test_missing_fields(client, make_headers, payload, missing):
    (payload if missing in payload else payload["manifest"]).pop(missing)
    assert_error(client.post(PATH, json=payload, headers=make_headers()), 400, "INVALID_REQUEST")


@pytest.mark.parametrize("kind", ["malformed", "array", "duplicate-request", "duplicate-manifest",
                                  "duplicate-topic", "nan", "bad-encoding", "oversized", "too-deep"])
def test_invalid_json_is_rejected(client, make_headers, payload, kind):
    serialized = json.dumps(payload).encode()
    bad = {
        "malformed": serialized[:-1],
        "array": b"[" + serialized + b"]",
        "duplicate-request": b'{"contextId": 42, ' + serialized[1:],
        "duplicate-manifest": serialized.replace(b'"releaseId":', b'"engineVersion": "synthetic-engine", "releaseId":'),
        "duplicate-topic": b'{"commonTopicKey": "rest", "commonTopicKey": "walk", ' + serialized[1:],
        "nan": serialized.replace(b'"contextId": 42', b'"contextId": NaN'),
        "bad-encoding": serialized + b"\xff",
        "oversized": serialized + b" " * (16 * 1024),
        "too-deep": b"[" * 3000 + b"]" * 3000,
    }
    assert_error(client.post(PATH, content=bad[kind], headers=make_headers()), 400, "INVALID_REQUEST")


@pytest.mark.parametrize(("field", "value"), [
    ("releaseId", "10000000-0000-4000-8000-000000000009"),
    ("engineVersion", "another-version"),
    ("contentHash", "f" * 64),
])
def test_unregistered_or_mismatched_release(client, make_headers, payload, field, value):
    payload["commonTopicKey"] = "rest"
    payload["manifest"][field] = value
    assert_error(client.post(PATH, json=payload, headers=make_headers()), 503, "SERVICE_UNAVAILABLE")


@pytest.mark.parametrize("changed", ["descriptor", "artifact"])
def test_registered_files_are_rechecked(client, make_headers, payload, release, changed):
    payload["commonTopicKey"] = "rest"
    target = release[0 if changed == "descriptor" else 1]
    target.write_bytes(target.read_bytes() + b"\n")
    assert_error(client.post(PATH, json=payload, headers=make_headers()), 503, "SERVICE_UNAVAILABLE")


def test_unexpected_verification_failure_uses_common_error(client, make_headers, payload, monkeypatch, caplog):
    def fail_verification(self, manifest):
        raise RuntimeError(manifest["surveyVersion"])

    monkeypatch.setattr(ReleaseRegistry, "verify", fail_verification)
    headers = {**make_headers(), "X-Request-Id": payload["attemptId"]}
    response = client.post(PATH, json=payload, headers=headers)
    assert_error(response, 500, "INTERNAL_ERROR")
    assert response.headers["X-Request-Id"] == payload["attemptId"]
    assert payload["manifest"]["surveyVersion"] not in response.text + caplog.text


def test_dev_opt_in_serves_q040_with_registered_release(make_client, make_headers, payload):
    payload["commonTopicKey"] = "rest"
    with make_client(environment="dev", enable_dev=True) as client:
        response = client.post(PATH, json=payload, headers=make_headers(environment="dev"))

    assert response.status_code == 200
    assert response.json()["source"] == "COMMON_TOPIC_TEMPLATE"
    assert response.json()["promptVersion"] == "q040-template-v1"


@pytest.mark.parametrize(("environment", "enable_dev", "release_state", "configure_keys"), [
    ("test", False, "absent", True),
    ("test", False, "invalid", True),
    ("test", False, "registered", False),
    ("dev", False, "registered", True),
    ("dev", True, "absent", True),
    ("dev", True, "invalid", True),
    ("dev", True, "registered", False),
    ("prod", True, "registered", True),
])
def test_icebreakers_is_unavailable_without_allowed_registration(
    make_client, make_headers, payload, release, environment, enable_dev, release_state, configure_keys,
):
    if release_state == "invalid":
        release[0].write_text("invalid descriptor", encoding="utf-8")
    payload["commonTopicKey"] = "rest"
    with make_client(configure_keys=configure_keys, configure_release=release_state != "absent",
                     environment=environment, enable_dev=enable_dev) as client:
        response = client.post(PATH, json=payload, headers=make_headers(environment=environment))
        assert_error(response, 503, "SERVICE_UNAVAILABLE")
        assert client.get("/health").json() == {"status": "ok"}


@pytest.mark.parametrize("invalid", [
    " ＨＥＬＬＯ  world ", "연락처를 알려주실래요?", "종교가 있나요?", "https://example.test",
    "예시.한국을 아시나요?", "010-0000-0000", "synthetic@example.test", "192.0.2.10",
    "앞\n뒤", " \t ", "🙂" * 201,
])
def test_invalid_questions_fail_closed(client, make_headers, payload, monkeypatch, invalid):
    monkeypatch.setattr(templates, "GENERAL", ("hello world", invalid, "세 번째 질문인가요?"))
    assert_error(client.post(PATH, json=payload, headers=make_headers()), 503, "SERVICE_UNAVAILABLE")


def test_wrong_question_count_fails_closed(client, make_headers, payload, monkeypatch):
    monkeypatch.setattr(templates, "GENERAL", templates.GENERAL[:2])
    assert_error(client.post(PATH, json=payload, headers=make_headers()), 503, "SERVICE_UNAVAILABLE")


def test_question_limit_counts_unicode_code_points_after_strip(client, make_headers, payload, monkeypatch):
    question = "🙂" * 200
    monkeypatch.setattr(templates, "GENERAL", (f" {question} ", *templates.GENERAL[1:]))
    response = client.post(PATH, json=payload, headers=make_headers())

    assert response.status_code == 200
    assert response.json()["questions"][0] == question


def test_domain_suffix_requires_unicode_letters(client, make_headers, payload, monkeypatch):
    question = "예시.〇〇 표기를 본 적 있나요?"
    monkeypatch.setattr(templates, "GENERAL", (question, *templates.GENERAL[1:]))
    response = client.post(PATH, json=payload, headers=make_headers())
    assert response.status_code == 200
    assert response.json()["questions"][0] == question


def test_raw_survey_answer_is_rejected_without_echo_or_logging(client, make_headers, payload, caplog, capsys):
    sentinel = "synthetic-raw-survey-answer"
    payload["answers"] = [{"questionId": "Q040", "value": sentinel}]
    headers = make_headers()

    with caplog.at_level(logging.DEBUG):
        response = client.post(PATH, json=payload, headers=headers)
    assert_error(response, 400, "INVALID_REQUEST")
    captured = capsys.readouterr()
    for output in (response.text, caplog.text, captured.out, captured.err):
        assert sentinel not in output
        assert headers["Authorization"].removeprefix("Bearer ") not in output
