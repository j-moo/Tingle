"""Shared dev/prod composition and real providers; synthetic offline transport only."""

import asyncio
import json
from pathlib import Path
import runpy

from cryptography.hazmat.primitives import serialization
from fastapi.testclient import TestClient
import httpx as asgi_httpx
import httpx2 as provider_httpx
import pytest

from app import development
from app.config import Settings
from app.main import create_app as build_main
from app.icebreakers.http_api import _select_questions
from app.match_explanation.gms import ENDPOINT, MODEL, GmsClient
from app.risk_detection.releases import DevelopmentReleaseError
from security.synthetic_jwt import NOW
from test_icebreakers_http import KID, make_headers, signing_key
from test_match_explanation_gms import envelope
from test_match_explanation_http import payload as match_payload, release


MATCH = "/internal/ai/explanations"
ICE = "/internal/ai/icebreakers"
QUESTIONS = ["산책할 때 어떤 풍경을 좋아하세요?", "여행 중 가장 즐거운 순간은 언제인가요?", "걷기 좋은 계절은 언제라고 생각하세요?"]
MATCH_VERSION = "7bc0071304423684119cacfa5b44a69ac3e4e613678bd00a91d87c64950f5280"
ICE_VERSION = "icebreakers-gms-ffb273caf96e6059"
FLAGS = ("MATCH_INSIGHT_GMS_DEV_ENABLED", "ICEBREAKER_GMS_DEV_ENABLED")


class ClosingTransport(provider_httpx.MockTransport):
    closes = 0

    async def aclose(self):
        self.closes += 1
        await super().aclose()


@pytest.fixture
def host(tmp_path, monkeypatch, signing_key, release):
    monkeypatch.chdir(tmp_path)  # Never discover a developer's .env or real keys.
    public = tmp_path / "synthetic-public.pem"
    public.write_bytes(signing_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    values = {
        "APP_ENV": "dev", "GMS_API_KEY": "synthetic-only",
        "AI_SERVICE_PUBLIC_KEYS": json.dumps({KID: str(public)}),
        "ICEBREAKER_RELEASE_DESCRIPTOR_FILE": str(tmp_path / "synthetic-descriptor.json"),
        "RISK_LOCAL_RULES_ENABLED": "false",
        "RISK_RELEASE_DESCRIPTOR_FILE": str(tmp_path / "missing-safety.json"),
        "RISK_LOCAL_RULES_DESCRIPTOR_SHA256": "",

    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)
    monkeypatch.setattr("app.security.service_auth.time", lambda: NOW)
    calls, credentials = [], []
    mode = {"fault": None, "after": None}

    async def forbid_network(*args, **kwargs):
        pytest.fail("A real outbound transport is forbidden in integration tests")
    monkeypatch.setattr(provider_httpx.AsyncHTTPTransport, "handle_async_request", forbid_network)

    def handler(request):
        assert str(request.url) == ENDPOINT
        body = json.loads(request.content)
        assert body["model"] == MODEL
        data = json.loads(body["input"][1]["content"])
        calls.append(data)
        assert not {"attemptId", "contextId", "manifest", "reference", "scope"} & data.keys()
        fault = mode["fault"]
        if fault == "timeout":
            raise provider_httpx.ReadTimeout("synthetic-private-marker", request=request)
        if fault == "cancel":
            raise asyncio.CancelledError()
        if isinstance(fault, int):
            return provider_httpx.Response(fault, text="synthetic-private-marker")
        if mode["after"]:
            mode["after"]()
        if set(data) == {"topic"}:
            assert data == {"topic": "walk"}
            result = {"questions": QUESTIONS}
        elif "candidate" in data:
            result = {"supported": fault != "review", "directionCorrect": True, "privacySafe": True, "evidenceIds": ["e1"]}
        else:
            assert set(data) == {"viewer", "evidence", "allowedPhrasings"}
            result = {"text": "상대의 연락 빈도가 당신의 선호와 맞아요.", "evidenceIds": ["e1"]}
        response = envelope("{invalid" if fault == "json" else json.dumps(result))
        if fault == "version":
            response["model"] = "unapproved-model"
        return provider_httpx.Response(200, json=response)

    transport = ClosingTransport(handler)
    original_init = GmsClient.__init__

    def initialize(client, api_key, **kwargs):
        credentials.append(True)
        original_init(client, api_key, transport=transport)
    monkeypatch.setattr(GmsClient, "__init__", initialize)
    match = match_payload(release)
    match["contextBundle"]["scope"]["model_evidence_ids"] = ["e1"]
    ice = {name: match[name] for name in ("attemptId", "contextId", "manifest")}
    ice["commonTopicKey"] = "walk"

    class Host:
        def build(self):
            return build_main(Settings())

    result = Host()
    result.calls, result.credentials, result.mode = calls, credentials, mode
    result.transport, result.match, result.ice = transport, match, ice
    result.artifact = release[2]
    return result


def assert_fallback(response, route):
    assert response.status_code == 200
    body = response.json()
    if route == MATCH:
        assert body["source"] == "RULE_FALLBACK" and body["promptVersion"] is None
    else:
        assert body == _select_questions("walk").model_dump()
    assert "synthetic-private-marker" not in response.text


def test_both_real_clients_share_the_existing_safety_lifespan(host, make_headers):
    app = host.build()
    with TestClient(app) as client:
        runtime = app.state.risk_runtime
        assert runtime.resources.engine is None
        assert client.get("/health").json() == {"status": "ok"}
        match = client.post(MATCH, json=host.match, headers=make_headers(environment="dev"))
        assert match.status_code == 200
        assert match.json()["source"] == "GENERATED"
        assert match.json()["promptVersion"] == MATCH_VERSION
        assert match.json()["evidenceKeys"] == []
        assert len(host.calls) == 2
        ice = client.post(ICE, json=host.ice, headers=make_headers(environment="dev"))
        assert ice.json() == {"questions": QUESTIONS, "source": "GENERATED", "promptVersion": ICE_VERSION}
        assert host.calls[-1] == {"topic": "walk"}
        # Safety OFF needs neither an approved tokenizer nor a release mount.
        risk = client.post("/internal/ai/risk-detections", json={}, headers=make_headers(environment="dev"))
        assert risk.status_code == 422 and len(host.calls) == 3
    assert host.credentials == [True]
    assert host.transport.closes == 3
    assert runtime.resources is None and runtime.remaining == 0


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
def test_both_providers_generate_identical_responses_without_opt_ins(host, monkeypatch, make_headers, environment):
    monkeypatch.setenv("APP_ENV", environment)
    for name in FLAGS:
        monkeypatch.delenv(name, raising=False)
    with TestClient(host.build()) as client:
        match = client.post(MATCH, json=host.match, headers=make_headers(environment=environment))
        ice = client.post(ICE, json=host.ice, headers=make_headers(environment=environment))
    assert match.json() == {
        "source": "GENERATED", "text": "상대의 연락 빈도가 당신의 선호와 맞아요.",
        "evidenceKeys": [], "promptVersion": MATCH_VERSION, "encounterId": 1001,
    }
    assert ice.json() == {"questions": QUESTIONS, "source": "GENERATED", "promptVersion": ICE_VERSION}
    assert len(host.calls) == host.transport.closes == 3
    assert host.credentials == [True]


@pytest.mark.parametrize("entrypoint", ["main", "development", "match-development"])
@pytest.mark.parametrize("environment", ["dev", "prod"])
def test_all_entrypoints_share_the_main_providers(host, monkeypatch, make_headers, entrypoint, environment):
    from app import main
    from app.match_explanation import development as legacy_match

    monkeypatch.setenv("APP_ENV", environment)
    if entrypoint == "main":
        monkeypatch.setattr("app.config.settings", Settings())
        app = runpy.run_path(str(Path(main.__file__)))['app']
    else:
        app = (development if entrypoint == "development" else legacy_match).create_app()
    with TestClient(app) as client:
        for route, payload in ((MATCH, host.match), (ICE, host.ice)):
            assert client.post(route, json=payload, headers=make_headers(environment=environment)).json()["source"] == "GENERATED"
    assert len(host.calls) == 3 and host.credentials == [True]


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
@pytest.mark.parametrize("key", [None, "", " ", "\t\n", "synthetic-private-marker\rkey"])
def test_invalid_credentials_fail_at_startup_without_leaking_values(host, monkeypatch, environment, key):
    monkeypatch.setenv("APP_ENV", environment)
    if key is None:
        monkeypatch.delenv("GMS_API_KEY")
    else:
        monkeypatch.setenv("GMS_API_KEY", key)
    with pytest.raises(RuntimeError, match="GMS_API_KEY") as error:
        host.build()
    assert "synthetic-private-marker" not in str(error.value)
    assert not host.calls


@pytest.mark.parametrize("flag", [*FLAGS, "ICEBREAKER_DEV_ENABLED", "EXPLANATION_RELEASE_DESCRIPTOR_FILES"])
def test_removed_configuration_does_not_control_startup_or_providers(host, monkeypatch, make_headers, flag):
    monkeypatch.setenv(flag, "invalid-ignored-legacy-value")
    with TestClient(host.build()) as client:
        for route, payload in ((MATCH, host.match), (ICE, host.ice)):
            assert client.post(route, json=payload, headers=make_headers(environment="dev")).json()["source"] == "GENERATED"
    assert len(host.calls) == 3


@pytest.mark.parametrize("route", [MATCH, ICE])
@pytest.mark.parametrize("gate", ["auth", "scope", "environment", "manifest", "artifact", "consent"])
def test_existing_auth_release_and_public_scope_gates(host, make_headers, route, gate):
    payload = host.match if route == MATCH else host.ice
    headers = make_headers(environment="dev")
    expected = 200
    if gate == "auth":
        headers, expected = {}, 401
    elif gate == "scope":
        headers, expected = make_headers(environment="dev", scope="other"), 403
    elif gate == "environment":
        headers, expected = make_headers(environment="test"), 401
    elif gate == "manifest":
        payload["manifest"]["engineVersion"], expected = " ", 400
    elif gate == "consent":
        if route == MATCH:
            payload["contextBundle"]["scope"]["model_evidence_ids"] = []
        else:
            payload.pop("commonTopicKey")
    with TestClient(host.build()) as client:
        if gate == "artifact":
            host.artifact.write_bytes(b"revoked-synthetic-release")
            expected = 503 if route == ICE else 200
        response = client.post(route, json=payload, headers=headers)
    assert response.status_code == expected
    if gate == "consent":
        assert response.json()["source"] == ("RULE_FALLBACK" if route == MATCH else "GENERAL_FALLBACK")
    assert len(host.calls) == (2 if gate == "artifact" and route == MATCH else 0)


@pytest.mark.parametrize("route", [MATCH, ICE])
@pytest.mark.parametrize("fault", [401, 403, 503, "timeout", "json", "version"])
def test_provider_failure_never_claims_generated_success(host, make_headers, route, fault):
    host.mode["fault"] = fault
    with TestClient(host.build()) as client:
        response = client.post(route, json=host.match if route == MATCH else host.ice,
                               headers=make_headers(environment="dev"))
    assert_fallback(response, route)
    assert len(host.calls) == host.transport.closes == 1


@pytest.mark.parametrize("route", [MATCH, ICE])
def test_only_icebreaker_keeps_release_reverification_during_generation(host, make_headers, route):
    host.mode["after"] = lambda: host.artifact.write_bytes(b"revoked-synthetic-release")
    with TestClient(host.build()) as client:
        response = client.post(route, json=host.match if route == MATCH else host.ice,
                               headers=make_headers(environment="dev"))
    assert response.status_code == (503 if route == ICE else 200)
    assert len(host.calls) == host.transport.closes == (1 if route == ICE else 2)


@pytest.mark.parametrize("route", [MATCH, ICE])
def test_provider_cancellation_propagates_and_closes_transport(host, make_headers, route):
    host.mode["fault"] = "cancel"
    app = host.build()

    async def scenario():
        async with app.router.lifespan_context(app):
            async with asgi_httpx.AsyncClient(transport=asgi_httpx.ASGITransport(app), base_url="http://synthetic") as client:
                with pytest.raises(asyncio.CancelledError):
                    await client.post(route, json=host.match if route == MATCH else host.ice,
                                      headers=make_headers(environment="dev"))
    asyncio.run(scenario())
    assert len(host.calls) == host.transport.closes == 1
    assert app.state.risk_runtime.resources is None and app.state.risk_runtime.remaining == 0


def test_cancelled_lifespan_still_closes_the_common_runtime(host):
    app = host.build()

    async def scenario():
        with pytest.raises(asyncio.CancelledError):
            async with app.router.lifespan_context(app):
                raise asyncio.CancelledError()
    asyncio.run(scenario())
    assert app.state.risk_runtime.resources is None and app.state.risk_runtime.remaining == 0
    assert not host.calls


def test_safety_opt_in_without_approval_still_fails_closed(host, monkeypatch):
    monkeypatch.setenv("RISK_LOCAL_RULES_ENABLED", "true")
    app = host.build()
    with pytest.raises(DevelopmentReleaseError):
        with TestClient(app):
            pytest.fail("Unapproved Safety runtime unexpectedly started")
    assert app.state.risk_runtime.resources is None and not host.calls
