"""Safety Guard adapter tests with no provider, credentials or real dialogue."""

import asyncio
import json
import logging
import traceback

import httpx2 as httpx
import pytest

from app.match_explanation.gms import ENDPOINT, MODEL, GmsClient, GmsError
from app.risk_detection.execution import Deadline
from app.risk_detection.gms_primary import PRIMARY_PROMPT, GmsPrimaryAdapter
from app.risk_detection.luna_shadow import AsyncModel, ModelReply
from safety48_runtime_support import message, request_for
from safety_asgi_contract_support import (
    SECRET_MARKER, expect, main_factory, no_external_io, synthetic_private_keys,
)
from test_luna_shadow_asgi import IDENTITY, async_host, model_verdict
from test_match_explanation_gms import envelope
from test_risk_detection_http import finding, release


TOKEN = "mock-bearer-only"


@pytest.fixture(autouse=True)
def isolate_gms(monkeypatch, no_external_io, caplog):
    def forbidden(*args, **kwargs):
        pytest.fail("GMS environment lookup is forbidden")

    monkeypatch.setattr(GmsClient, "from_environment", forbidden)
    caplog.set_level(logging.DEBUG)
    yield
    logs = "\n".join(record.getMessage() for phase in ("setup", "call", "teardown")
                     for record in caplog.get_records(phase))
    assert SECRET_MARKER not in logs
    assert TOKEN not in logs


def adapter(handler) -> AsyncModel:
    client = GmsClient(TOKEN, transport=httpx.MockTransport(handler))
    return GmsPrimaryAdapter(client=client, identity=IDENTITY)


def provider_text(kind):
    official = model_verdict(kind)
    return json.dumps({key: official[key] for key in ("kind", "findings", "confidence")})


@pytest.mark.parametrize("kind", ["NORMAL", "RISK", "INSUFFICIENT", "FAILED"])
def test_adapter_builds_model_reply_and_sends_only_validated_messages(release, kind):
    manifest = release[2]
    messages = [message("합성 메시지", fresh=False), message(SECRET_MARKER, 2, "B")]

    def handler(request):
        assert request.method == "POST" and str(request.url) == ENDPOINT
        assert request.headers["Authorization"] == f"Bearer {TOKEN}"
        body = json.loads(request.content)
        assert body["model"] == MODEL
        assert body["input"][0] == {"role": "system", "content": PRIMARY_PROMPT}
        assert body["input"][1]["role"] == "user"
        assert json.loads(body["input"][1]["content"]) == {"messages": messages}
        output = json.loads(provider_text(kind))
        if kind == "RISK":
            output["findings"] = [finding(sequence=2)]
        return httpx.Response(200, json=envelope(json.dumps(output)))

    result = asyncio.run(adapter(handler).complete(messages, manifest, deadline=Deadline.start(3)))
    expected = model_verdict(kind)
    if kind == "RISK":
        expected["findings"] = [finding(sequence=2)]
    assert result == ModelReply(manifest=manifest, verdict=expected)
    assert result.manifest is not manifest


@pytest.mark.parametrize("text", [
    "not json",
    '{"kind":"NORMAL","findings":[]}',
    '{"kind":"RISK","kind":"NORMAL","findings":[],"confidence":null}',
    '{"kind":"NORMAL","findings":[],"confidence":NaN}',
    '{"kind":"NORMAL","findings":[],"confidence":null,"modelVersion":"spoofed"}',
    '{"kind":"RISK","findings":[],"confidence":null}',
    json.dumps({"kind": "RISK", "findings": [finding(sequence=2)], "confidence": None}),
], ids=["malformed", "missing-field", "duplicate-kind", "nonfinite", "version-spoof",
        "missing-finding", "non-fresh-finding"])
def test_invalid_candidate_is_never_a_normal_reply(release, text):
    client = adapter(lambda _: httpx.Response(200, json=envelope(text)))
    with pytest.raises(GmsError, match="^Invalid Safety Guard output$"):
        asyncio.run(client.complete([message("합성", 2, fresh=False), message(SECRET_MARKER, 3)],
                                    release[2], deadline=Deadline.start(3)))


@pytest.mark.parametrize("mode", [
    "http-denied", "refusal", "incomplete", "duplicate-envelope", "empty-message", "oversized",
])
def test_provider_response_failures_are_sanitized(release, mode):
    class Oversized(httpx.AsyncByteStream):
        closed = False

        async def __aiter__(self):
            yield b"x" * 16_384
            yield b"x" * 16_385
            pytest.fail("Oversized response was read beyond the limit")

        async def aclose(self):
            self.closed = True

    calls = 0
    stream = Oversized()

    def handler(_):
        nonlocal calls
        calls += 1
        if mode == "http-denied":
            return httpx.Response(403, text=SECRET_MARKER)
        if mode == "oversized":
            return httpx.Response(200, stream=stream)
        body = envelope(provider_text("NORMAL"))
        if mode == "duplicate-envelope":
            return httpx.Response(200, text='{"status":"incomplete",' + json.dumps(body)[1:])
        if mode == "refusal":
            body["output"][-1]["content"] = [{"type": "refusal", "refusal": SECRET_MARKER}]
        if mode == "incomplete":
            body["output"][-1]["status"] = "incomplete"
        if mode == "empty-message":
            body["output"].append({"type": "message", "role": "assistant", "content": []})
        return httpx.Response(200, json=body)

    with pytest.raises(GmsError) as caught:
        asyncio.run(adapter(handler).complete([message(SECRET_MARKER)], release[2], deadline=Deadline.start(3)))
    assert calls == 1
    assert mode != "oversized" or stream.closed
    assert SECRET_MARKER not in "".join(traceback.format_exception(caught.value))


def test_invalid_input_and_expired_deadline_do_not_reach_transport(release):
    client = adapter(lambda _: pytest.fail("Transport called before validation"))
    with pytest.raises(GmsError, match="^Invalid Safety Guard input$"):
        asyncio.run(client.complete([message("https://synthetic.invalid")],
                                    release[2], deadline=Deadline.start(3)))
    with pytest.raises(TimeoutError):
        asyncio.run(client.complete([message(SECRET_MARKER)], release[2], deadline=Deadline.start(0)))


def test_provider_timeout_is_sanitized(release):
    def handler(request):
        raise httpx.ReadTimeout(SECRET_MARKER, request=request)

    with pytest.raises(TimeoutError, match="^GMS timeout$") as caught:
        asyncio.run(adapter(handler).complete([message(SECRET_MARKER)], release[2], deadline=Deadline.start(3)))
    assert SECRET_MARKER not in "".join(traceback.format_exception(caught.value))
    assert TOKEN not in "".join(traceback.format_exception(caught.value))


@pytest.mark.parametrize("cancel", [False, True], ids=["deadline", "caller-cancellation"])
def test_in_flight_request_is_cancelled(release, cancel):
    async def scenario():
        started, stopped = asyncio.Event(), asyncio.Event()

        async def handler(_):
            started.set()
            try:
                await asyncio.Future()
            finally:
                stopped.set()

        task = asyncio.create_task(adapter(handler).complete(
            [message(SECRET_MARKER)], release[2], deadline=Deadline.start(3 if cancel else 0.5)))
        await asyncio.wait_for(started.wait(), 2)
        if cancel:
            task.cancel()
        with pytest.raises(asyncio.CancelledError if cancel else TimeoutError) as caught:
            await task
        assert stopped.is_set()
        assert SECRET_MARKER not in "".join(traceback.format_exception(caught.value))
        assert TOKEN not in "".join(traceback.format_exception(caught.value))

    asyncio.run(scenario())


@pytest.mark.parametrize("kind,status,code", [
    ("NORMAL", 200, None), ("RISK", 200, None), ("invalid", 503, "SERVICE_UNAVAILABLE"),
])
def test_real_asgi_auth_and_release_checks_precede_mock_provider(async_host, kind, status, code):
    calls = 0

    def handler(_):
        nonlocal calls
        calls += 1
        text = SECRET_MARKER if kind == "invalid" else provider_text(kind)
        return httpx.Response(200, json=envelope(text))

    host, *_ = async_host(primary=adapter(handler))
    payload = request_for(host.release, [message(SECRET_MARKER)])
    expect(host.post(payload, credential=None), 401, "AUTH_REQUIRED")
    unsupported = {**payload, "manifest": {**payload["manifest"], "engineVersion": "unknown"}}
    expect(host.post(unsupported), 422, "POLICY_UNRESOLVED")
    assert calls == 0

    response = expect(host.post(payload), status, code)
    assert calls == 1
    if status == 200:
        assert response == {"attemptId": payload["attemptId"], "manifest": payload["manifest"],
                            "verdict": model_verdict(kind)}


def test_real_asgi_rechecks_artifact_after_mock_provider(async_host):
    def handler(_):
        host.release.artifact.write_bytes(b'{"synthetic":"changed"}')
        return httpx.Response(200, json=envelope(provider_text("NORMAL")))

    host, *_ = async_host(primary=adapter(handler))
    expect(host.post(), 422, "POLICY_UNRESOLVED")
