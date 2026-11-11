"""Synthetic provider failures through the real Safety Guard ASGI contract."""

import asyncio
import json
import threading
from unittest.mock import Mock

import httpx2 as httpx
import pytest

from app.risk_detection.execution import Deadline, ExecutionLimits
from app.risk_detection.local_engine import LocalRuleRiskEngine
from app.risk_detection.luna_shadow import ModelIdentity
from app.risk_detection.rule_engine import MODEL_VERSION, PROMPT_VERSION
from safety48_runtime_support import message, request_for
from safety_asgi_contract_support import SECRET_MARKER, expect
from test_gms_primary import adapter, isolate_gms, provider_text
from test_luna_shadow_asgi import (
    async_host, main_factory, model_verdict, no_external_io, request_on_app, shadow_record,
    synthetic_private_keys,
)
from test_match_explanation_gms import envelope
from test_risk_detection_http import finding


DANGER = "은행 담당자야. 수수료를 먼저 송금해. 확인 전화는 하지 마."


def provider_failure(mode, request):
    if mode == "429":
        return httpx.Response(429, text=SECRET_MARKER)
    if mode == "5xx":
        return httpx.Response(502, text=SECRET_MARKER)
    if mode == "network":
        raise httpx.ConnectError(SECRET_MARKER, request=request)
    if mode == "timeout":
        raise httpx.ReadTimeout(SECRET_MARKER, request=request)
    if mode == "duplicate-envelope":
        response = json.dumps(envelope(provider_text("NORMAL")))
        return httpx.Response(200, text='{"status":"incomplete",' + response[1:])
    if mode == "duplicate-candidate":
        return httpx.Response(200, json=envelope(
            '{"kind":"RISK","kind":"NORMAL","findings":[],"confidence":null}'))
    if mode == "malformed":
        return httpx.Response(200, json=envelope(SECRET_MARKER))
    if mode == "refusal":
        body = envelope(provider_text("NORMAL"))
        body["output"][-1]["content"] = [{"type": "refusal", "refusal": SECRET_MARKER}]
        return httpx.Response(200, json=body)
    assert mode == "failed-verdict"
    return httpx.Response(200, json=envelope(provider_text("FAILED")))


def real_rules(engine):
    """Keep the fixture's real tokenizer and observe the real rule entry point."""
    engine.rules.analyze = Mock(wraps=LocalRuleRiskEngine.analyze.__get__(engine.rules))
    return engine.rules.analyze


def risk_payload(host, danger=True, *, fresh_risk=True):
    return request_for(host.release, [
        message(DANGER if not fresh_risk else "이전 합성 인사", 1, fresh=False),
        message(DANGER if danger and fresh_risk else SECRET_MARKER, 2, "B"),
    ])


def expect_rule_risk(response, payload):
    assert expect(response) == {
        "attemptId": payload["attemptId"],
        "manifest": payload["manifest"],
        "verdict": {
            "kind": "RISK", "findings": [finding(sequence=2)], "confidence": None,
            "modelVersion": MODEL_VERSION, "promptVersion": PROMPT_VERSION,
        },
    }


@pytest.mark.parametrize("mode", [
    "refusal", "timeout", "429", "5xx", "network", "malformed", "duplicate-candidate", "duplicate-envelope",
    "failed-verdict",
])
@pytest.mark.parametrize("danger", [True, False], ids=["risk", "no-finding"])
def test_only_rule_risk_replaces_failed_gms(async_host, mode, danger):
    provider = Mock(side_effect=lambda request: provider_failure(mode, request))
    host, engine, _, review = async_host(primary=adapter(provider))
    rules = real_rules(engine)
    payload = risk_payload(host, danger)

    response = host.post(payload)
    if danger:
        expect_rule_risk(response, payload)
    else:
        expect(response, 503, "SERVICE_UNAVAILABLE")
        assert "verdict" not in response.json()
    provider.assert_called_once()
    rules.assert_called_once_with(payload["contextBundle"], payload["manifest"])
    assert review.calls == engine.shadow.pending == 0
    assert not engine.records


@pytest.mark.parametrize("status", [301, 400, 401, 403, 404, 422])
@pytest.mark.parametrize("danger", [True, False], ids=["risk", "no-finding"])
def test_provider_auth_or_configuration_failure_is_unavailable(async_host, caplog, status, danger):
    provider = Mock(return_value=httpx.Response(status, text=SECRET_MARKER))
    host, engine, _, review = async_host(primary=adapter(provider))
    rules = real_rules(engine)

    response = host.post(risk_payload(host, danger))

    expect(response, 503, "SERVICE_UNAVAILABLE")
    assert "verdict" not in response.json()
    assert SECRET_MARKER not in response.text + caplog.text
    provider.assert_called_once()
    rules.assert_not_called()
    assert review.calls == engine.shadow.pending == 0
    assert not engine.records


def test_old_message_risk_does_not_replace_refusal(async_host):
    host, engine, _, _ = async_host(primary=adapter(lambda req: provider_failure("refusal", req)))
    rules = real_rules(engine)
    expect(host.post(risk_payload(host, fresh_risk=False)), 503, "SERVICE_UNAVAILABLE")
    rules.assert_called_once()


@pytest.mark.parametrize("kind", ["NORMAL", "RISK", "INSUFFICIENT"])
def test_successful_primary_keeps_its_verdict_and_shadow(async_host, kind):
    primary = adapter(lambda _: httpx.Response(200, json=envelope(provider_text(kind))))
    host, engine, _, review = async_host(primary=primary)
    rules = real_rules(engine)
    payload = request_for(host.release, [message(DANGER)])
    body = expect(host.post(payload))
    assert body["verdict"] == model_verdict(kind)
    record = shadow_record(host, engine)
    assert (record.primary_kind, record.rule_status, record.rule_kind) == (kind, "OK", "RISK")
    assert review.calls == (1 if kind == "NORMAL" else 0)
    rules.assert_called_once()


@pytest.mark.parametrize("gate", ["missing-auth", "wrong-scope", "unknown-release", "token-limit"])
def test_request_gates_run_before_primary_or_rules(async_host, gate):
    provider = Mock(side_effect=lambda req: provider_failure("refusal", req))
    host, engine, _, review = async_host(primary=adapter(provider))
    rules = real_rules(engine)
    payload = risk_payload(host)
    credential = "valid"
    if gate == "missing-auth":
        credential, status, code = None, 401, "AUTH_REQUIRED"
    elif gate == "wrong-scope":
        credential, status, code = host.token({"scope": "other"}), 403, "FORBIDDEN"
    elif gate == "unknown-release":
        payload["manifest"]["engineVersion"] = "unapproved"
        status, code = 422, "POLICY_UNRESOLVED"
    else:
        engine.rules.count_tokens = lambda *_: 3001
        status, code = 400, "INVALID_REQUEST"
    expect(host.post(payload, credential=credential), status, code)
    provider.assert_not_called()
    rules.assert_not_called()
    assert review.calls == 0 and not engine.records


def test_unapproved_model_identity_does_not_enter_fallback(async_host):
    host, engine, _, _ = async_host(
        primary=adapter(lambda _: httpx.Response(200, json=envelope(provider_text("FAILED")))),
    )
    rules = real_rules(engine)
    engine.identity = ModelIdentity("unapproved-model", "unapproved-prompt")
    expect(host.post(risk_payload(host)), 503, "SERVICE_UNAVAILABLE")
    rules.assert_not_called()


def test_changed_artifact_rejects_fallback_before_response_and_shadow(async_host):
    def handler(request):
        host.release.artifact.write_bytes(b'{"synthetic":"changed"}')
        return provider_failure("5xx", request)

    host, engine, _, review = async_host(primary=adapter(handler))
    real_rules(engine)
    expect(host.post(risk_payload(host)), 422, "POLICY_UNRESOLVED")
    assert review.calls == 0 and not engine.records


@pytest.mark.parametrize("danger", [True, False], ids=["risk", "no-finding"])
def test_primary_stage_cap_cancels_gms_and_uses_remaining_request_budget(async_host, danger):
    stopped = []

    async def handler(_):
        try:
            await asyncio.Future()
        finally:
            stopped.append(True)

    host, engine, _, _ = async_host(primary=adapter(handler), limits=ExecutionLimits(primary_seconds=0.15))
    rules = real_rules(engine)
    payload = risk_payload(host, danger)
    response = host.post(payload)
    if danger:
        expect_rule_risk(response, payload)
    else:
        expect(response, 503, "SERVICE_UNAVAILABLE")
    assert stopped == [True]
    rules.assert_called_once()


@pytest.mark.parametrize("phase", ["before-rules", "during-rules"])
def test_fallback_cannot_publish_without_remaining_request_budget(async_host, monkeypatch, phase):
    now = 0.0
    start = Deadline.start

    def handler(request):
        nonlocal now
        if phase == "before-rules":
            now = 7.98
        return provider_failure("refusal", request)

    host, engine, _, review = async_host(primary=adapter(handler))
    rules = real_rules(engine)
    if phase == "during-rules":
        def exhaust(*args):
            nonlocal now
            result = LocalRuleRiskEngine.analyze(engine.rules, *args)
            now = 8.01
            return result

        rules.side_effect = exhaust
    monkeypatch.setattr(Deadline, "start", lambda seconds: start(seconds, clock=lambda: now))
    expect(host.post(risk_payload(host)), 503, "SERVICE_UNAVAILABLE")
    assert rules.call_count == (0 if phase == "before-rules" else 1)
    assert review.calls == 0 and not engine.records


@pytest.mark.parametrize("stop", ["cancel", "shutdown"])
def test_cancellation_and_engine_shutdown_do_not_trigger_fallback(async_host, stop):
    started, stopped = asyncio.Event(), asyncio.Event()

    async def handler(_):
        started.set()
        try:
            await asyncio.Future()
        finally:
            stopped.set()

    host, engine, _, review = async_host(primary=adapter(handler))
    rules = real_rules(engine)

    async def run():
        task = asyncio.create_task(request_on_app(host))
        try:
            await asyncio.wait_for(started.wait(), 1)
            if stop == "cancel":
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task
            else:
                assert await engine.aclose() == 0
                response = await task
                assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"
            assert stopped.is_set()
            rules.assert_not_called()
            assert review.calls == 0 and not engine.records
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    host.client.portal.call(run)


@pytest.mark.parametrize("stop", ["cancel", "shutdown"])
def test_late_rule_risk_is_discarded_during_cancellation_or_shutdown(async_host, stop):
    entered, release = threading.Event(), threading.Event()
    host, engine, _, review = async_host(primary=adapter(lambda req: provider_failure("refusal", req)))
    rules = real_rules(engine)
    payload = risk_payload(host)

    def blocked(*args):
        entered.set()
        if not release.wait(2):
            raise RuntimeError(SECRET_MARKER)
        return LocalRuleRiskEngine.analyze(engine.rules, *args)

    rules.side_effect = blocked

    async def run():
        async def wait_for_rules():
            while not entered.is_set():
                await asyncio.sleep(0.001)

        task = asyncio.create_task(request_on_app(host, payload=payload))
        try:
            await asyncio.wait_for(wait_for_rules(), 1)
            if stop == "cancel":
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task
                assert engine.boundary.executor.active == 1
            else:
                assert await engine.aclose() == 0
                release.set()
                response = await asyncio.wait_for(task, 1)
                assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"
            assert review.calls == 0 and not engine.records
        finally:
            release.set()
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            while engine.boundary.executor.active:
                await asyncio.sleep(0.001)

    host.client.portal.call(run)
