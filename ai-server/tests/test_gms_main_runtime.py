"""Real app.main + real adapter/client; synthetic releases and MockTransport only."""

import asyncio
from dataclasses import asdict, replace
import hashlib
import json
import shutil
from unittest.mock import Mock

import httpx
import httpx2 as provider_httpx
import pytest

from app.match_explanation.gms import ENDPOINT, MODEL, GmsClient
from app.risk_detection import gms_runtime
from app.risk_detection.execution import ExecutionLimits
from app.risk_detection.gms_primary import PRIMARY_PROMPT, GmsPrimaryAdapter
from app.risk_detection.gms_runtime import GmsPrimaryConfiguration, GmsPrimaryEngine
from app.risk_detection.local_engine import LocalRuleRiskEngine
from app.risk_detection.releases import DevelopmentReleaseError
from app.risk_detection.rule_engine import MODEL_VERSION, PROMPT_VERSION
from safety48_runtime_support import message, request_for, synthetic_release
from safety_asgi_contract_support import (
    ROUTE, SECRET_MARKER, expect, main_factory, no_external_io, synthetic_private_keys,
)
from test_match_explanation_gms import envelope
from test_risk_detection_http import finding


TOKEN = "mock-runtime-bearer-only"
DANGER = "은행 담당자야. 수수료를 먼저 송금해. 확인 전화는 하지 마."
LIMITS = ExecutionLimits(request_seconds=1, primary_seconds=0.1, shadow_seconds=0.1, cleanup_seconds=0.02)


@pytest.fixture(autouse=True)
def isolation(monkeypatch, no_external_io, caplog):
    def forbidden(*args, **kwargs):
        pytest.fail("GMS credential lookup is forbidden")
    monkeypatch.setattr(GmsClient, "from_environment", forbidden)
    yield
    logs = "\n".join(record.getMessage() for phase in ("setup", "call", "teardown")
                     for record in caplog.get_records(phase))
    assert TOKEN not in logs and SECRET_MARKER not in logs


def candidate(kind):
    return {"kind": kind, "findings": [finding()] if kind == "RISK" else [], "confidence": None}


class ClosingTransport(provider_httpx.MockTransport):
    closed = False

    async def aclose(self):
        self.closed = True
        await super().aclose()


@pytest.fixture
def configured_main(main_factory, tmp_path):
    created = []

    def make(handler=None, *, edit=None, **kwargs):
        directory = tmp_path / f"gms-{len(created)}"
        directory.mkdir()
        release = synthetic_release(directory, development_only=True)
        approval = directory / "synthetic-only-gms-approval.json"
        binding = {
            "format": "safety48-gms-primary-v1", "scope": "development-only",
            "manifest": release.manifest, "rulesDescriptorSha256": release.test_descriptor_pin,
            "endpoint": ENDPOINT, "modelVersion": MODEL, "promptVersion": gms_runtime.PROMPT_VERSION,
            "promptSha256": hashlib.sha256(PRIMARY_PROMPT.encode("utf-8")).hexdigest(),
            "executionLimits": asdict(LIMITS), "sources": gms_runtime.gms_source_hashes(),
        }
        if edit:
            edit(binding)
        approval.write_text(json.dumps(binding, ensure_ascii=False), encoding="utf-8")
        provider = Mock(side_effect=handler or (
            lambda _: provider_httpx.Response(200, json=envelope(json.dumps(candidate("NORMAL"))))))
        transports = []

        def construct():
            transport = ClosingTransport(provider)
            transports.append(transport)
            return GmsClient(TOKEN, transport=transport)

        factory = Mock(side_effect=construct)
        factory.transports = transports
        configuration = GmsPrimaryConfiguration(
            enabled=True, approval_file=str(approval),
            approval_sha256=hashlib.sha256(approval.read_bytes()).hexdigest(), client_factory=factory, limits=LIMITS)
        configuration = replace(configuration, **kwargs.pop("configuration", {}))
        created.append(configuration)
        host = main_factory(release=release, gms_primary=configuration, **kwargs)
        return host, provider, factory, approval
    return make


@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
@pytest.mark.parametrize("injection", ["absent", "disabled"])
def test_local_rules_do_not_enable_a_safety_provider(main_factory, monkeypatch, environment, injection):
    original_init = GmsClient.__init__
    shared_clients = []
    def construct_shared(client, key, **kwargs):
        assert key == "synthetic-safety-startup-only"
        shared_clients.append(True)
        original_init(client, key, **kwargs)
    request = Mock(side_effect=AssertionError("OFF called GMS"))
    monkeypatch.setattr(GmsClient, "__init__", construct_shared)
    monkeypatch.setattr(GmsClient, "request_text", request)
    factory = Mock(side_effect=AssertionError("OFF resolved a provider"))
    configuration = None if injection == "absent" else GmsPrimaryConfiguration(
        enabled=False, approval_file="must-not-read",
        approval_sha256="must-not-read", client_factory=factory)
    monkeypatch.setenv("RISK_GMS_PRIMARY_ENABLED", "true")  # No Settings field consumes this.
    host = main_factory(environment=environment, gms_primary=configuration)
    assert host.client.get("/health").status_code == 200
    # 환경과 무관하게 같은 로컬 규칙 엔진이 뜬다(운영·개발 동일).
    assert type(host.client.app.state.risk_runtime.resources.engine) is LocalRuleRiskEngine
    assert expect(host.post(request_for(host.release, [message(DANGER)])))["verdict"]["kind"] == "RISK"
    factory.assert_not_called()
    assert shared_clients  # Only the shared Match/Icebreaker startup path ran.
    request.assert_not_called()


@pytest.mark.parametrize("kind", ["NORMAL", "RISK", "INSUFFICIENT"])
@pytest.mark.parametrize("environment", ["local", "test", "dev", "prod"])
def test_real_adapter_on_main_preserves_wire_contract_without_shadow(configured_main, kind, environment):
    def handler(request):
        assert str(request.url) == ENDPOINT and request.method == "POST"
        assert request.headers["authorization"] == "Bearer " + TOKEN
        body = json.loads(request.content)
        assert body["model"] == MODEL
        assert body["input"][0] == {"role": "system", "content": PRIMARY_PROMPT}
        assert json.loads(body["input"][1]["content"]) == {"messages": payload["contextBundle"]}
        return provider_httpx.Response(200, json=envelope(json.dumps(candidate(kind))))

    host, provider, factory, _ = configured_main(handler, environment=environment)
    engine = host.client.app.state.risk_runtime.resources.engine
    assert type(engine) is GmsPrimaryEngine and type(engine.primary) is GmsPrimaryAdapter
    rules = Mock(wraps=engine.rules.analyze)
    engine.rules.analyze = rules
    payload = request_for(host.release, [message(DANGER)])
    assert expect(host.post(payload)) == {
        "attemptId": payload["attemptId"], "manifest": payload["manifest"],
        "verdict": {**candidate(kind), "modelVersion": MODEL, "promptVersion": gms_runtime.PROMPT_VERSION},
    }
    factory.assert_called_once_with()
    provider.assert_called_once()
    rules.assert_not_called()
    assert engine.start_shadow is None and not engine.records and engine.shadow.pending == 0
    assert factory.transports[0].closed


@pytest.mark.parametrize("failure", ["refusal", "timeout", "408", "429", "500", "502", "503", "malformed-envelope", "malformed-json", "FAILED"])
@pytest.mark.parametrize("danger", [True, False])
def test_failure_only_returns_risk_with_rule_evidence(configured_main, failure, danger):
    def handler(request):
        if failure.isdigit():
            return provider_httpx.Response(int(failure), text=SECRET_MARKER)
        if failure == "timeout":
            raise provider_httpx.ReadTimeout(SECRET_MARKER, request=request)
        if failure == "malformed-envelope":
            return provider_httpx.Response(200, text="{invalid")
        body = envelope(json.dumps(candidate("FAILED" if failure == "FAILED" else "NORMAL")))
        if failure == "refusal":
            body["output"][-1]["content"] = [{"type": "refusal", "refusal": SECRET_MARKER}]
        if failure == "malformed-json":
            body = envelope("{invalid")
        return provider_httpx.Response(200, json=body)

    host, provider, _, _ = configured_main(handler)
    engine = host.client.app.state.risk_runtime.resources.engine
    engine.rules.analyze = Mock(wraps=engine.rules.analyze)
    body = expect(host.post(request_for(host.release, [message(DANGER if danger else SECRET_MARKER)])),
                  200 if danger else 503, None if danger else "SERVICE_UNAVAILABLE")
    if danger:
        assert body["verdict"] == {**candidate("RISK"), "modelVersion": MODEL_VERSION, "promptVersion": PROMPT_VERSION}
    else:
        assert "verdict" not in body
    provider.assert_called_once()
    engine.rules.analyze.assert_called_once()
    assert not engine.records and engine.shadow.pending == 0


@pytest.mark.parametrize("status", [400, 401, 403])
@pytest.mark.parametrize("danger", [True, False])
def test_unknown_400_and_auth_rejection_never_enter_fallback(configured_main, status, danger):
    host, provider, _, _ = configured_main(lambda _: provider_httpx.Response(status, text=SECRET_MARKER))
    engine = host.client.app.state.risk_runtime.resources.engine
    engine.rules.analyze = Mock(wraps=engine.rules.analyze)
    expect(host.post(request_for(host.release, [message(DANGER if danger else SECRET_MARKER)])),
           503, "SERVICE_UNAVAILABLE")
    provider.assert_called_once()
    engine.rules.analyze.assert_not_called()
    assert not engine.records


@pytest.mark.parametrize("gate,status,code", [
    ("missing-auth", 401, "AUTH_REQUIRED"), ("invalid-auth", 401, "ACCESS_TOKEN_INVALID"),
    ("scope", 403, "FORBIDDEN"), ("manifest", 422, "POLICY_UNRESOLVED"),
    ("raw-url", 400, "INVALID_REQUEST"), ("tokens", 400, "INVALID_REQUEST"),
])
def test_real_auth_manifest_and_input_gates_precede_transport(configured_main, gate, status, code):
    host, provider, _, _ = configured_main()
    payload, credential = request_for(host.release), "valid"
    if gate == "missing-auth":
        credential = None
    elif gate == "invalid-auth":
        credential = host.token(wrong_key=True)
    elif gate == "scope":
        credential = host.token({"scope": "other"})
    elif gate == "manifest":
        payload["manifest"]["engineVersion"] = "unapproved"
    elif gate == "raw-url":
        payload["contextBundle"][0]["text"] = "https://synthetic.invalid"
    elif gate == "tokens":
        host.client.app.state.risk_runtime.resources.engine.rules.count_tokens = lambda *_: 3001
    expect(host.post(payload, credential=credential), status, code)
    provider.assert_not_called()


@pytest.mark.parametrize("field", ["format", "scope", "manifest", "rulesDescriptorSha256", "endpoint", "modelVersion", "promptVersion", "promptSha256", "executionLimits", "sources"])
def test_separately_pinned_but_incompatible_approval_cannot_construct_client(configured_main, field):
    host, provider, factory, _ = configured_main(edit=lambda value: value.update({field: "unapproved"}), start=False)
    with pytest.raises(DevelopmentReleaseError):
        with host.client:
            pytest.fail("Incompatible approval published a runtime")
    assert host.client.app.state.risk_runtime.resources is None
    provider.assert_not_called()
    factory.assert_not_called()


def test_new_approval_pin_cannot_reuse_pre_integration_main_source(configured_main):
    # Actual source worktree main.py bytes before shared GMS composition.
    previous = "eb4d80be5373c48fe30212b7a55b8ebebbb4d1ff787f0e8850aac66b67f95634"
    assert gms_runtime.gms_source_hashes()["main.py"] != previous
    host, provider, factory, _ = configured_main(
        edit=lambda binding: binding["sources"].update({"main.py": previous}), start=False)
    with pytest.raises(DevelopmentReleaseError):
        with host.client:
            pytest.fail("Old main.py provenance started a Safety GMS provider")
    assert host.client.app.state.risk_runtime.resources is None
    provider.assert_not_called()
    factory.assert_not_called()


@pytest.mark.parametrize("configuration", [
    {"approval_file": ""}, {"approval_sha256": ""}, {"approval_sha256": "0" * 64},
])
def test_missing_or_bad_approval_pin_does_not_construct_client(configured_main, configuration):
    host, provider, factory, _ = configured_main(start=False, configuration=configuration)
    with pytest.raises(DevelopmentReleaseError):
        with host.client:
            pass
    assert host.client.app.state.risk_runtime.resources is None
    provider.assert_not_called()
    factory.assert_not_called()


@pytest.mark.parametrize("failure", ["no-factory", "fake-client", "factory-error", "engine-conflict", "rules-pin"])
def test_invalid_assembly_never_publishes_or_calls_transport(configured_main, failure):
    configuration, kwargs = {}, {}
    if failure == "no-factory":
        configuration["client_factory"] = None
    elif failure == "fake-client":
        configuration["client_factory"] = lambda: object()
    elif failure == "factory-error":
        def broken():
            raise RuntimeError(SECRET_MARKER)
        configuration["client_factory"] = broken
    elif failure == "engine-conflict":
        kwargs["engine_factory"] = Mock(side_effect=AssertionError("Conflicting factory was called"))
    elif failure == "rules-pin":
        kwargs["pin"] = "0" * 64
    host, provider, factory, _ = configured_main(start=False, configuration=configuration, **kwargs)
    expected = RuntimeError if failure == "factory-error" else DevelopmentReleaseError
    with pytest.raises(expected) as error:
        with host.client:
            pass
    assert SECRET_MARKER not in str(error.value)
    assert host.client.app.state.risk_runtime.resources is None
    assert host.client.app.state.risk_runtime.remaining == 0
    provider.assert_not_called()
    factory.assert_not_called()
    if "engine_factory" in kwargs:
        kwargs["engine_factory"].assert_not_called()


def test_only_new_message_rule_evidence_can_replace_refusal(configured_main):
    body = envelope(json.dumps(candidate("NORMAL")))
    body["output"][-1]["content"] = [{"type": "refusal", "refusal": SECRET_MARKER}]
    host, provider, _, _ = configured_main(lambda _: provider_httpx.Response(200, json=body))
    expect(host.post(request_for(host.release, [message(DANGER, fresh=False), message(SECRET_MARKER, 2)])),
           503, "SERVICE_UNAVAILABLE")
    provider.assert_called_once()


@pytest.mark.parametrize("target", ["descriptor", "artifact", "tokenizer", "approval", "source"])
@pytest.mark.parametrize("during", [False, True])
def test_provenance_is_rechecked_before_and_after_gms(configured_main, tmp_path, monkeypatch, target, during):
    source_copy = tmp_path / "source-copy"
    for name in gms_runtime.GMS_SOURCE_FILES:
        destination = source_copy / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(gms_runtime.SOURCE_ROOT / name, destination)
    monkeypatch.setattr(gms_runtime, "SOURCE_ROOT", source_copy)

    def mutate():
        path = source_copy / "risk_detection/gms_primary.py" if target == "source" else (
            approval if target == "approval" else getattr(host.release, target))
        path.write_bytes(path.read_bytes() + b"\n ")

    def handler(_):
        if during:
            mutate()
        return provider_httpx.Response(200, json=envelope(json.dumps(candidate("NORMAL"))))

    host, provider, _, approval = configured_main(handler)
    if not during:
        mutate()
    expect(host.post(), 422, "POLICY_UNRESOLVED")
    assert provider.call_count == int(during)
    assert not host.client.app.state.risk_runtime.resources.engine.records


async def request_on_main(host):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=host.client.app),
                                base_url="http://synthetic.invalid", trust_env=False) as client:
        return await client.post(ROUTE, json=request_for(host.release, [message(DANGER)]),
                                 headers={"Authorization": "Bearer " + host.token()})


@pytest.mark.parametrize("stop", ["primary-deadline", "cancel", "shutdown"])
def test_real_transport_cancellation_and_lifespan_cleanup(configured_main, stop):
    started, stopped = asyncio.Event(), asyncio.Event()

    async def handler(_):
        started.set()
        try:
            await asyncio.Future()
        finally:
            stopped.set()

    host, provider, factory, _ = configured_main(handler)

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        resources = runtime.resources
        engine = resources.engine
        engine.rules.analyze = Mock(wraps=engine.rules.analyze)
        task = asyncio.create_task(request_on_main(host))
        await asyncio.wait_for(started.wait(), 2)
        if stop == "cancel":
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        elif stop == "shutdown":
            assert await runtime.aclose() == 0
            with pytest.raises(asyncio.CancelledError):
                await task
            response = await request_on_main(host)
            assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"
        else:
            response = await task
            assert response.status_code == 200 and response.json()["verdict"]["kind"] == "RISK"
        assert stopped.is_set() and not engine.records
        assert engine.rules.analyze.call_count == int(stop == "primary-deadline")
        assert await runtime.aclose() == await runtime.aclose() == 0
        assert runtime.resources is None and runtime.remaining == 0
        for boundary in (resources.execution, engine.boundary, engine.shadow):
            assert boundary.pending == boundary.executor.active == 0
        provider.assert_called_once()
        assert factory.transports[0].closed
    host.client.portal.call(scenario)


def test_actual_asgi_disconnect_cancels_provider_and_sends_no_response(configured_main):
    started, stopped = asyncio.Event(), asyncio.Event()

    async def handler(_):
        started.set()
        try:
            await asyncio.Future()
        finally:
            stopped.set()

    host, provider, factory, _ = configured_main(handler)

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        engine = runtime.resources.engine
        engine.rules.analyze = Mock(wraps=engine.rules.analyze)
        events = asyncio.Queue()
        await events.put({"type": "http.request", "body": json.dumps(request_for(host.release)).encode(),
                          "more_body": False})
        sent = []

        async def receive():
            return await events.get()

        async def send(event):
            sent.append(event["type"])

        scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST",
                 "scheme": "http", "path": ROUTE, "raw_path": ROUTE.encode(), "query_string": b"",
                 "headers": [(b"authorization", ("Bearer " + host.token()).encode())],
                 "server": ("synthetic.invalid", 80), "client": ("127.0.0.1", 1), "root_path": ""}
        task = asyncio.create_task(host.client.app(scope, receive, send))
        await asyncio.wait_for(started.wait(), 2)
        await events.put({"type": "http.disconnect"})
        await asyncio.wait_for(task, 2)
        assert stopped.is_set() and sent == []
        engine.rules.analyze.assert_not_called()
        assert not engine.records and runtime.remaining == 0
        assert factory.transports[0].closed
        provider.assert_called_once()
    host.client.portal.call(scenario)


@pytest.mark.parametrize("environment", ["dev", "prod"])
def test_injected_gms_without_release_fails_startup_in_every_environment(main_factory, environment):
    # 주입된 GMS는 환경과 무관하게 같은 경로를 탄다. Release가 없으면 조용히 꺼지지 않고 기동을 거절한다.
    factory = Mock(side_effect=AssertionError("constructed a client without a release"))
    configuration = GmsPrimaryConfiguration(
        enabled=True, approval_file="must-not-read", approval_sha256="must-not-read", client_factory=factory)
    host = main_factory(environment=environment, gms_primary=configuration, configured=False, start=False)
    with pytest.raises(DevelopmentReleaseError):
        with host.client:
            pass
    assert host.client.app.state.risk_runtime.resources is None
    factory.assert_not_called()
