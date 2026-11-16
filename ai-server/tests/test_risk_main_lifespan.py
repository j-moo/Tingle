"""Real app.main factory/lifespan, real auth/release, pytest-only AsyncModels.

No synthetic FastAPI application, auth bypass, approved file edits or provider
configuration. ASGI probes cover shutdown races; Uvicorn covers real HTTP/exit.
"""

import asyncio
from copy import deepcopy
import json
import shutil
import socket
import subprocess
import sys
import threading
import time

import httpx
import pytest
import uvicorn

from app.match_explanation.gms import GmsClient
from app.risk_detection import local_engine, releases
from app.risk_detection.execution import CapacityExceeded, Deadline, ExecutionLimits
from app.risk_detection.local_engine import LocalRuleRiskEngine
from app.risk_detection.luna_shadow import LunaShadowEngine, ModelIdentity, ModelReply, PrimaryUnavailable
from app.risk_detection.releases import DevelopmentReleaseError
from app.risk_detection.rule_engine import MODEL_VERSION, PROMPT_VERSION
from safety48_runtime_support import isolated_environment, message, request_for, synthetic_release
from safety_asgi_contract_support import (
    ROUTE, SECRET_MARKER, expect, main_factory, no_external_io, synthetic_private_keys,
)
from test_risk_detection_http import finding, verdict


IDENTITY = ModelIdentity("pytest-fake-v1", "pytest-fake-prompt-v1")
DANGER = "은행 담당자야. 수수료를 먼저 송금해. 확인 전화는 하지 마."
LIMITS = ExecutionLimits(request_seconds=1, primary_seconds=0.2, shadow_seconds=0.2, cleanup_seconds=0.02)


class FakeAsyncModel:
    supports_rule_fallback = True

    def __init__(self, kind="NORMAL", *, mode="reply", change=None):
        self.kind, self.mode, self.change = kind, mode, change
        self.started, self.release = asyncio.Event(), asyncio.Event()
        self.entered = threading.Event()
        self.calls = self.active = self.cancelled = 0
        self.deadlines = []

    async def complete(self, messages, manifest, *, deadline) -> ModelReply:
        self.calls += 1
        self.active += 1
        self.deadlines.append(deadline)
        self.started.set()
        self.entered.set()
        try:
            if self.mode == "resist":
                while not self.release.is_set():
                    try:
                        await self.release.wait()
                    except asyncio.CancelledError:
                        self.cancelled += 1
            elif self.mode in {"block", "timeout"}:
                await self.release.wait()
            elif self.mode == "refusal":
                raise PrimaryUnavailable(SECRET_MARKER)
            elif self.mode == "error":
                raise RuntimeError(SECRET_MARKER)
            deadline.check()
            if self.change:
                self.change()
            return ModelReply(deepcopy(manifest), verdict(
                self.kind, [finding()] if self.kind == "RISK" else [],
                modelVersion=IDENTITY.model_version, promptVersion=IDENTITY.prompt_version))
        except asyncio.CancelledError:
            self.cancelled += 1
            raise
        finally:
            self.active -= 1


@pytest.fixture(autouse=True)
def forbid_gms(monkeypatch, no_external_io):
    attempts = []

    def forbidden(*args, **kwargs):
        attempts.append(True)
        raise AssertionError("Provider construction, secret lookup and calls are forbidden")

    # Shared Match/Icebreaker clients now construct at startup; Safety must
    # still neither discover credentials nor send provider requests here.
    for name in ("from_environment", "request_text"):
        monkeypatch.setattr(GmsClient, name, forbidden)
    yield
    assert not attempts


@pytest.fixture
def fake_main(main_factory):
    def make(*, primary=None, review=None, limits=LIMITS, after_construct=None, **kwargs):
        created = []

        def construct(*, count_input_tokens):
            engine = LunaShadowEngine(
                primary=primary if primary is not None else FakeAsyncModel(),
                review=review if review is not None else FakeAsyncModel(),
                rules=LocalRuleRiskEngine(count_input_tokens=count_input_tokens), identity=IDENTITY, limits=limits)
            created.append(engine)
            if after_construct:
                after_construct(engine)
            return engine

        return main_factory(engine_factory=construct, **kwargs), created

    return make


async def settle(predicate):
    async def wait():
        while not predicate():
            await asyncio.sleep(0.001)
    await asyncio.wait_for(wait(), 2)


def assert_reclaimed(resources):
    engine = resources.engine
    assert engine.primary.active == engine.review.active == 0
    for boundary in (resources.execution, engine.boundary, engine.shadow):
        assert boundary.pending == boundary.executor.active == 0


async def request_on_main(host, *, payload=None, credential="valid"):
    headers = {} if credential is None else {"Authorization": "Bearer " + host.token()}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=host.client.app),
                                base_url="http://pytest-main.invalid", trust_env=False) as client:
        return await client.post(ROUTE, json=payload or request_for(host.release), headers=headers)


@pytest.mark.parametrize("environment,enabled", [
    ("local", None), ("test", None), ("dev", None), ("prod", None), ("prod", True),
])
def test_default_off_health_auth_and_zero_model_construction(fake_main, monkeypatch, environment, enabled):
    def forbidden(*args, **kwargs):
        pytest.fail("OFF main reached development release or rule data")
    monkeypatch.setattr(local_engine, "LocalRuleRiskEngine", forbidden)
    monkeypatch.setattr(releases, "DevelopmentReleaseCatalog", forbidden)
    host, created = fake_main(environment=environment, enabled=enabled)
    assert host.client.get("/health").status_code == 200
    expect(host.post(raw=b"unread", credential=None), 401, "AUTH_REQUIRED")
    expect(host.post(raw=b"unread"), 422, "POLICY_UNRESOLVED")
    assert created == [] and host.client.app.state.risk_runtime.resources.engine is None


def test_import_builds_routes_but_does_not_publish_an_engine(fake_main):
    host, created = fake_main(start=False)
    runtime = host.client.app.state.risk_runtime
    assert runtime.resources is None and not created

    async def scenario():
        response = await request_on_main(host)
        assert response.status_code == 503
        async with host.client.app.router.lifespan_context(host.client.app):
            assert runtime.resources.engine is created[0]
            assert (await request_on_main(host)).status_code == 200
        assert runtime.resources is None and runtime.remaining == 0
    asyncio.run(scenario())


@pytest.mark.parametrize("pin", ["", "0" * 64])
def test_bad_startup_pin_never_calls_factory_or_publishes(fake_main, pin):
    host, created = fake_main(start=False, pin=pin)

    async def scenario():
        with pytest.raises(DevelopmentReleaseError):
            async with host.client.app.router.lifespan_context(host.client.app):
                pytest.fail("Unapproved release reached serving state")
        runtime = host.client.app.state.risk_runtime
        assert runtime.resources is None and runtime.remaining == 0 and not created
        assert await runtime.aclose() == await runtime.aclose() == 0
        assert (await request_on_main(host)).status_code == 503
    asyncio.run(scenario())


def test_startup_rechecks_release_and_closes_an_already_constructed_engine(fake_main, tmp_path):
    release = synthetic_release(tmp_path)
    host, created = fake_main(start=False, release=release,
                             after_construct=lambda _: release.artifact.write_bytes(b"changed"))

    async def scenario():
        with pytest.raises(DevelopmentReleaseError):
            async with host.client.app.router.lifespan_context(host.client.app):
                pytest.fail("Changed release was published")
        runtime = host.client.app.state.risk_runtime
        assert runtime.resources is None and runtime.remaining == 0
        for boundary in (created[0].boundary, created[0].shadow):
            with pytest.raises(CapacityExceeded):
                await boundary.executor.run(lambda: None, deadline=Deadline.start(1))
    asyncio.run(scenario())


def test_factory_failure_is_sanitized_and_repeated_close_is_safe(main_factory):
    def broken(**kwargs):
        raise RuntimeError(SECRET_MARKER)
    host = main_factory(engine_factory=broken, start=False)

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        with pytest.raises(RuntimeError, match="^Safety Guard runtime startup failed$"):
            async with host.client.app.router.lifespan_context(host.client.app):
                pass
        assert runtime.resources is None and runtime.remaining == 0
        assert await runtime.aclose() == 0
    asyncio.run(scenario())


@pytest.mark.parametrize("failure", ["release", "ordinary", "cancelled"])
def test_startup_failure_survives_a_secondary_cleanup_failure(main_factory, monkeypatch, failure):
    original = {"release": DevelopmentReleaseError(), "ordinary": ValueError(SECRET_MARKER),
                "cancelled": asyncio.CancelledError()}[failure]

    def broken(**kwargs):
        raise original

    host = main_factory(engine_factory=broken, start=False)

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        close = runtime.aclose

        async def broken_cleanup():
            await close()
            raise RuntimeError("Synthetic secondary cleanup failure")

        monkeypatch.setattr(runtime, "aclose", broken_cleanup)
        expected = RuntimeError if failure == "ordinary" else type(original)
        with pytest.raises(expected) as caught:
            async with host.client.app.router.lifespan_context(host.client.app):
                pytest.fail("Failed startup published resources")
        if failure == "ordinary":
            assert str(caught.value) == "Safety Guard runtime startup failed"
            assert caught.value.__context__ is original
            assert caught.value.__suppress_context__ is True
        else:
            assert caught.value is original
        assert runtime.resources is None and runtime.remaining == 0 and runtime._state == "closed"
        assert runtime._close_waiters == 0
    asyncio.run(scenario())


def test_startup_keyboard_interrupt_propagates_in_an_isolated_process(tmp_path):
    release = synthetic_release(tmp_path)
    probe = """
import asyncio
import json
from app.main import create_app

def interrupt(**kwargs):
    raise KeyboardInterrupt()

application = create_app(_risk_engine_factory=interrupt)

async def scenario():
    try:
        async with application.router.lifespan_context(application):
            raise AssertionError('Interrupted startup published resources')
    except KeyboardInterrupt:
        assert application.state.risk_runtime.resources is None
        print(json.dumps({'exception': 'KeyboardInterrupt', 'published': False}))

asyncio.run(scenario())
"""
    completed = subprocess.run([sys.executable, "-B", "-c", probe], cwd=tmp_path,
                               env=isolated_environment(tmp_path, release=release),
                               capture_output=True, encoding="utf-8", timeout=15)
    assert completed.returncode == 0 and not completed.stderr
    assert json.loads(completed.stdout) == {"exception": "KeyboardInterrupt", "published": False}


@pytest.mark.parametrize("kind", ["NORMAL", "RISK"])
def test_fake_reply_uses_real_main_auth_release_and_wire_contract(fake_main, kind):
    host, created = fake_main(primary=FakeAsyncModel(kind))
    payload = request_for(host.release)
    body = expect(host.post(payload))
    assert set(body) == {"attemptId", "manifest", "verdict"}
    assert body["attemptId"] == payload["attemptId"] and body["manifest"] == payload["manifest"]
    assert body["verdict"] == verdict(kind, [finding()] if kind == "RISK" else [],
                                     modelVersion=IDENTITY.model_version, promptVersion=IDENTITY.prompt_version)
    engine = created[0]
    host.client.portal.call(settle, lambda: not engine.shadow.pending)
    assert len(engine.records) == 1


@pytest.mark.parametrize("mode", ["timeout", "error"])
def test_fake_failures_stay_unavailable_and_release_slots(fake_main, mode):
    primary = FakeAsyncModel(mode=mode)
    host, created = fake_main(primary=primary)
    expect(host.post(), 503, "SERVICE_UNAVAILABLE")
    assert primary.active == 0 and primary.cancelled == (mode == "timeout")
    assert created[0].review.calls == 0 and not created[0].records
    host.client.portal.call(settle, lambda: host.client.app.state.risk_runtime.remaining == 0)


@pytest.mark.parametrize("danger", [True, False])
def test_refusal_preserves_296_rule_risk_only_fallback_and_excludes_shadow(fake_main, danger):
    primary = FakeAsyncModel(mode="refusal")
    host, created = fake_main(primary=primary)
    payload = request_for(host.release, [message(DANGER if danger else SECRET_MARKER)])
    body = expect(host.post(payload), 200 if danger else 503, None if danger else "SERVICE_UNAVAILABLE")
    if danger:
        assert body["verdict"] == verdict("RISK", [finding()], confidence=None, modelVersion=MODEL_VERSION,
                                          promptVersion=PROMPT_VERSION)
    assert primary.calls == 1 and not created[0].records and created[0].review.calls == 0


def test_real_auth_and_unknown_release_precede_fake(fake_main):
    host, created = fake_main()
    expect(host.post(credential=None), 401, "AUTH_REQUIRED")
    expect(host.post(credential=host.token(wrong_key=True)), 401, "ACCESS_TOKEN_INVALID")
    payload = request_for(host.release)
    payload["manifest"]["engineVersion"] = "unapproved"
    expect(host.post(payload), 422, "POLICY_UNRESOLVED")
    assert created[0].primary.calls == created[0].review.calls == 0


@pytest.mark.parametrize("target", ["descriptor", "artifact", "tokenizer", "source"])
@pytest.mark.parametrize("during", [False, True], ids=["preflight", "postflight"])
def test_release_pre_and_post_verification_cannot_be_bypassed(fake_main, tmp_path, monkeypatch, target, during):
    source_copy = tmp_path / "source-copy"
    source_copy.mkdir()
    for name in releases.SOURCE_FILES:
        shutil.copyfile(releases.SOURCE_ROOT / name, source_copy / name)
    monkeypatch.setattr(releases, "SOURCE_ROOT", source_copy)
    primary = FakeAsyncModel()
    host, created = fake_main(primary=primary)

    def mutate():
        path = source_copy / "runtime.py" if target == "source" else getattr(host.release, target)
        path.write_bytes(path.read_bytes() + b"\n ")
    if during:
        primary.change = mutate
    else:
        mutate()
    expect(host.post(), 422, "POLICY_UNRESOLVED")
    assert primary.calls == int(during) and not created[0].records


def test_request_cancellation_preserves_original_deadline_and_reclaims(fake_main):
    primary = FakeAsyncModel(mode="block")
    host, _ = fake_main(primary=primary)

    async def scenario():
        resources = host.client.app.state.risk_runtime.resources
        before = set(asyncio.all_tasks())
        started = time.monotonic()
        request = asyncio.create_task(request_on_main(host))
        await asyncio.wait_for(primary.started.wait(), 2)
        assert primary.deadlines[0].expires_at <= started + LIMITS.request_seconds + 0.1
        request.cancel()
        with pytest.raises(asyncio.CancelledError):
            await request
        await settle(lambda: not host.client.app.state.risk_runtime.remaining)
        assert primary.cancelled == 1 and not resources.engine.records
        assert_reclaimed(resources)
        assert set(asyncio.all_tasks()) == before
    host.client.portal.call(scenario)


@pytest.mark.parametrize("stage", ["body", "primary"])
def test_asgi_disconnect_sends_no_response_and_reclaims(fake_main, stage):
    primary = FakeAsyncModel(mode="block")
    host, _ = fake_main(primary=primary)

    async def scenario():
        resources = host.client.app.state.risk_runtime.resources
        events = asyncio.Queue()
        await events.put({"type": "http.request", "body": json.dumps(request_for(host.release)).encode(),
                          "more_body": stage == "body"})
        sent = []
        body_read = asyncio.Event()

        async def receive():
            event = await events.get()
            if event["type"] == "http.request":
                body_read.set()
            return event

        async def send(event):
            sent.append(event["type"])

        scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST",
                 "scheme": "http", "path": ROUTE, "raw_path": ROUTE.encode(), "query_string": b"",
                 "headers": [(b"authorization", ("Bearer " + host.token()).encode())],
                 "server": ("pytest-main.invalid", 80), "client": ("127.0.0.1", 1), "root_path": ""}
        task = asyncio.create_task(host.client.app(scope, receive, send))
        await asyncio.wait_for(primary.started.wait() if stage == "primary" else body_read.wait(), 2)
        await events.put({"type": "http.disconnect"})
        await asyncio.wait_for(task, 2)
        await settle(lambda: not host.client.app.state.risk_runtime.remaining)
        assert sent == [] and primary.cancelled == int(stage == "primary")
        assert_reclaimed(resources)
    host.client.portal.call(scenario)


def test_shutdown_revokes_admission_then_closes_http_and_engine_once(fake_main, monkeypatch):
    primary = FakeAsyncModel(mode="block")
    host, _ = fake_main(primary=primary)

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        resources = runtime.resources
        order, closing = [], asyncio.Event()
        close_http, close_engine = resources.execution.aclose, resources.engine.aclose

        async def http_close():
            order.append("http")
            closing.set()
            await close_http()

        async def engine_close():
            order.append("engine")
            await close_engine()

        monkeypatch.setattr(resources.execution, "aclose", http_close)
        monkeypatch.setattr(resources.engine, "aclose", engine_close)
        request = asyncio.create_task(request_on_main(host))
        await asyncio.wait_for(primary.started.wait(), 2)
        shutdown = asyncio.create_task(runtime.aclose())
        await asyncio.wait_for(closing.wait(), 2)
        assert runtime.resources is None
        response = await request_on_main(host)
        assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"
        assert (await request_on_main(host, credential=None)).status_code == 401
        with pytest.raises(asyncio.CancelledError):
            await request
        await shutdown
        # The finite shutdown budget may expire before final cancellation callbacks.
        await settle(lambda: runtime.remaining == 0)
        assert await runtime.aclose() == 0
        assert order == ["http", "engine"] and primary.calls == primary.cancelled == 1
        assert_reclaimed(resources)
    host.client.portal.call(scenario)


def test_duplicate_startup_and_repeated_lifespans_keep_single_ownership(fake_main):
    host, created = fake_main(start=False)

    async def scenario():
        application = host.client.app
        runtime = application.state.risk_runtime
        previous = None
        for _ in range(2):
            async with application.router.lifespan_context(application):
                resources = runtime.resources
                assert resources is not previous
                if previous:
                    assert resources.engine is not previous.engine
                with pytest.raises(RuntimeError, match="already active"):
                    async with application.router.lifespan_context(application):
                        pass
                assert runtime.resources is resources
                assert (await request_on_main(host)).status_code == 200
            assert runtime.resources is None and await runtime.aclose() == 0
            assert_reclaimed(resources)
            previous = resources
        assert len(created) == 2
    asyncio.run(scenario())


def test_cancelling_one_shutdown_caller_keeps_shared_cleanup_owned(fake_main, monkeypatch):
    host, _ = fake_main()

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        resources = runtime.resources
        entered, release = asyncio.Event(), asyncio.Event()
        close_engine = resources.engine.aclose
        calls = []

        async def closing():
            calls.append(True)
            entered.set()
            await release.wait()
            await close_engine()

        monkeypatch.setattr(resources.engine, "aclose", closing)
        first = asyncio.create_task(runtime.aclose())
        await asyncio.wait_for(entered.wait(), 2)
        second = asyncio.create_task(runtime.aclose())
        first.cancel()
        with pytest.raises(asyncio.CancelledError):
            await first
        assert runtime.resources is None and runtime.remaining > 0
        release.set()
        assert await second == await runtime.aclose() == 0
        assert len(calls) == 1
        assert_reclaimed(resources)
    host.client.portal.call(scenario)


def test_cancelled_repeat_close_preserves_closed_and_allows_restart(fake_main):
    host, created = fake_main(start=False)

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        await runtime.startup()
        previous = runtime.resources
        assert await runtime.aclose() == 0
        repeat = asyncio.create_task(runtime.aclose())
        await asyncio.sleep(0)
        if not repeat.done():
            repeat.cancel()
            with pytest.raises(asyncio.CancelledError):
                await repeat
        else:
            assert await repeat == 0
        assert runtime._state == "closed" and runtime.remaining == runtime._close_waiters == 0
        assert_reclaimed(previous)
        try:
            await runtime.startup()
            assert runtime.resources is not previous and len(created) == 2
            assert (await request_on_main(host)).status_code == 200
        finally:
            await runtime.aclose()
    asyncio.run(scenario())


def test_restart_racing_the_final_close_callback_cannot_replace_owned_state(fake_main):
    host, _ = fake_main(start=False)

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        await runtime.startup()
        previous = runtime.resources
        close = asyncio.create_task(runtime.aclose())

        async def restart():
            while runtime._close_task is None or not runtime._close_task.done():
                await asyncio.sleep(0)
            try:
                await runtime.startup()
            except RuntimeError:
                # The old shutdown caller may still be resuming its final wait.
                await close
                await runtime.startup()

        try:
            await asyncio.wait_for(asyncio.gather(close, restart()), 2)
            assert runtime.resources is not None and runtime.resources is not previous
            assert (await request_on_main(host)).status_code == 200
        finally:
            await runtime.aclose()
        assert_reclaimed(previous)
        assert runtime.remaining == 0
    asyncio.run(scenario())


@pytest.mark.parametrize("stage", ["http-worker", "engine-worker", "resistant-model"])
def test_shutdown_has_finite_budget_and_keeps_straggler_slots_until_real_exit(fake_main, monkeypatch, stage):
    primary = FakeAsyncModel(mode="resist" if stage == "resistant-model" else "reply")
    host, _ = fake_main(primary=primary, limits=ExecutionLimits(
        request_seconds=2, primary_seconds=1, cleanup_seconds=0.02, workers=1))
    release_worker, entered = threading.Event(), threading.Event()

    async def scenario():
        runtime = host.client.app.state.risk_runtime
        resources = runtime.resources
        boundary = resources.execution if stage == "http-worker" else resources.engine.boundary
        if stage != "resistant-model":
            original = resources.catalog.supports if stage == "http-worker" else resources.engine.rules.count_tokens

            def blocked(*args):
                entered.set()
                release_worker.wait(3)
                return original(*args)
            monkeypatch.setattr(resources.catalog if stage == "http-worker" else resources.engine.rules,
                                "supports" if stage == "http-worker" else "count_tokens", blocked)
        request = asyncio.create_task(request_on_main(host))
        try:
            await settle(lambda: primary.entered.is_set() if stage == "resistant-model" else entered.is_set())
            started = time.monotonic()
            assert await runtime.aclose() > 0
            assert time.monotonic() - started < 0.5
            assert runtime.resources is None
            if stage != "resistant-model":
                assert boundary.executor.active == 1
            else:
                assert primary.active == 1 and resources.engine.boundary.pending >= 1
            with pytest.raises(RuntimeError, match="already active"):
                await runtime.startup()
            with pytest.raises(CapacityExceeded):
                await boundary.executor.run(lambda: None, deadline=Deadline.start(1))
            assert (await request_on_main(host)).status_code == 503
            await settle(lambda: runtime._close_task.done())
            repeat = asyncio.create_task(runtime.aclose())
            await asyncio.sleep(0)
            repeat.cancel()
            with pytest.raises(asyncio.CancelledError):
                await repeat
            assert runtime._state == "closing" and runtime.remaining > 0
            with pytest.raises(RuntimeError, match="already active"):
                await runtime.startup()
        finally:
            release_worker.set()
            primary.release.set()
            await asyncio.gather(request, return_exceptions=True)
            await settle(lambda: runtime.remaining == 0)
        assert await runtime.aclose() == 0
        assert_reclaimed(resources)
        assert not resources.engine.records
    host.client.portal.call(scenario)


def test_real_loopback_http_through_uvicorn_and_server_shutdown(fake_main):
    host, created = fake_main(start=False)
    application = host.client.app
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    server = uvicorn.Server(uvicorn.Config(application, lifespan="on", access_log=False,
                                           log_level="critical", timeout_graceful_shutdown=1))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    thread.start()
    try:
        stop_at = time.monotonic() + 5
        while not server.started and thread.is_alive() and time.monotonic() < stop_at:
            time.sleep(0.01)
        assert server.started
        with httpx.Client(base_url=f"http://127.0.0.1:{listener.getsockname()[1]}", trust_env=False, timeout=2) as client:
            assert client.get("/health").status_code == 200
            assert client.post(ROUTE, json={}).status_code == 401
            response = client.post(ROUTE, json=request_for(host.release),
                                   headers={"Authorization": "Bearer " + host.token()})
            assert response.status_code == 200 and response.json()["verdict"]["kind"] == "NORMAL"
        resources = application.state.risk_runtime.resources
    finally:
        server.should_exit = True
        thread.join(5)
        listener.close()
    assert not thread.is_alive() and application.state.risk_runtime.resources is None
    assert application.state.risk_runtime.remaining == 0 and len(created) == 1
    assert_reclaimed(resources)
