"""Actual app.main ASGI + real JWT/releases/tokenizer, synthetic providers only."""

import asyncio
import gc
from copy import deepcopy
from dataclasses import asdict, replace
import json
import logging
import threading
import time
import weakref
from unittest.mock import AsyncMock, Mock

import httpx
import pytest

from app.risk_detection import local_engine
from app.risk_detection.execution import Deadline, ExecutionBoundary, ExecutionLimits
from app.risk_detection.local_engine import LocalRuleRiskEngine
from app.risk_detection.luna_shadow import LunaShadowEngine, ModelIdentity, ModelReply
from app.risk_detection.rule_engine import MODEL_VERSION, PROMPT_VERSION
from safety48_runtime_support import message, request_for
from safety_asgi_contract_support import (
    ROUTE, SECRET_MARKER, expect, main_factory, no_external_io, synthetic_private_keys,
)
from test_risk_detection_http import finding, verdict


IDENTITY = ModelIdentity("synthetic-luna-v1", "synthetic-primary-v1")


def model_verdict(kind):
    return verdict(kind, [finding()] if kind == "RISK" else [],
                   modelVersion=IDENTITY.model_version, promptVersion=IDENTITY.prompt_version)


class SyntheticModel:
    def __init__(self, kind="NORMAL", *, delay=0, error=None, change=None, resist=False):
        self.kind, self.delay, self.error, self.change, self.resist = kind, delay, error, change, resist
        self.started, self.release = asyncio.Event(), asyncio.Event()
        self.active = self.calls = self.cancelled = 0
        self.deadlines = []

    async def complete(self, messages, manifest, *, deadline):
        self.calls += 1
        self.active += 1
        self.started.set()
        self.deadlines.append(deadline)
        try:
            if self.resist:
                while not self.release.is_set():
                    try:
                        await self.release.wait()
                    except asyncio.CancelledError:
                        self.cancelled += 1
            else:
                await asyncio.sleep(self.delay)
            if self.error is not None:
                raise self.error(SECRET_MARKER)
            reply = ModelReply(deepcopy(manifest), model_verdict(self.kind))
            if self.change:
                self.change(reply, messages, manifest)
            return reply
        except asyncio.CancelledError:
            self.cancelled += 1
            raise
        finally:
            self.active -= 1


@pytest.fixture
def async_host(main_factory, monkeypatch):
    created = []

    def make(*, primary=None, review=None, rule_kind="NORMAL", rule_error=False,
             limits=None, environment="test", enabled=True):
        primary = primary or SyntheticModel()
        review = review or SyntheticModel()

        def construct(*, count_input_tokens):
            rules = LocalRuleRiskEngine(count_input_tokens=count_input_tokens)

            def analyze(messages, manifest):
                if rule_error:
                    raise RuntimeError(SECRET_MARKER)
                return verdict(rule_kind, [finding()] if rule_kind == "RISK" else [],
                               modelVersion=MODEL_VERSION, promptVersion=PROMPT_VERSION)

            rules.analyze = analyze
            engine = LunaShadowEngine(primary=primary, review=review, rules=rules,
                                      identity=IDENTITY, limits=limits)
            created.append(engine)
            return engine

        # Test-only dependency injection at engine construction, not auth,
        # catalog/supports, hashes, tokenizer, app or route replacement.
        monkeypatch.setattr(local_engine, "LocalRuleRiskEngine", construct)
        before = len(created)
        host = main_factory(environment=environment, enabled=enabled)
        return host, created[-1] if len(created) > before else None, primary, review

    yield make
    for engine in created:
        assert engine.boundary.pending == 0
        assert engine.boundary.executor.active == 0
        assert engine.shadow.pending == engine.shadow.executor.active == 0


def post(host, status=200, code=None, *, payload=None):
    payload = payload or request_for(host.release, [message(SECRET_MARKER)])
    body = expect(host.post(payload), status, code)
    if status == 200:
        assert set(body) == {"attemptId", "manifest", "verdict"}
        assert body["attemptId"] == payload["attemptId"]
        assert body["manifest"] == payload["manifest"]
        assert set(body["verdict"]) == {"kind", "findings", "confidence", "modelVersion", "promptVersion"}
    return body


def shadow_record(host, engine):
    async def finished():
        while engine.shadow.pending:
            await asyncio.sleep(0.001)
        return engine.records[-1]

    async def scenario():
        return await asyncio.wait_for(finished(), 2)

    return host.client.portal.call(scenario)


@pytest.mark.parametrize("primary_kind,rule_kind,review_kind,rule_error,review_delay,expected_rule,expected_review", [
    ("RISK", "NORMAL", "NORMAL", False, 0, "OK", "NOT_RUN"),
    ("RISK", "NORMAL", "NORMAL", True, 0, "FAILED", "NOT_RUN"),
    ("NORMAL", "RISK", "RISK", False, 0, "OK", "OK"),
    ("NORMAL", "RISK", "INSUFFICIENT", False, 0, "OK", "OK"),
    ("NORMAL", "RISK", "NORMAL", False, 0.3, "OK", "TIMEOUT"),
    ("NORMAL", "NORMAL", "RISK", False, 0, "OK", "NOT_RUN"),
    ("INSUFFICIENT", "RISK", "RISK", False, 0, "OK", "NOT_RUN"),
    ("FAILED", "RISK", "RISK", False, 0, "OK", "NOT_RUN"),
])
def test_primary_is_the_only_official_asgi_verdict(async_host, primary_kind, rule_kind, review_kind,
                                                 rule_error, review_delay, expected_rule, expected_review):
    limits = ExecutionLimits(shadow_seconds=0.2 if review_delay else 1, cleanup_seconds=0.01)
    host, engine, primary, review = async_host(primary=SyntheticModel(primary_kind),
        review=SyntheticModel(review_kind, delay=review_delay), rule_kind=rule_kind,
        rule_error=rule_error, limits=limits)
    body = post(host)
    assert body["verdict"] == model_verdict(primary_kind)
    record = shadow_record(host, engine)
    assert record.rule_status == expected_rule and record.review_status == expected_review
    assert primary.active == review.active == 0
    assert set(asdict(record)) == {"primary_kind", "rule_status", "rule_kind", "review_status", "review_kind"}
    assert SECRET_MARKER not in json.dumps(asdict(record))
    if review.calls:
        assert review.deadlines[0].expires_at < primary.deadlines[0].expires_at


@pytest.mark.parametrize("stage", ["rule", "rule-self-cancel", "review", "review-self-cancel"])
def test_auxiliary_exceptions_preserve_primary_normal(async_host, caplog, capsys, stage):
    error = asyncio.CancelledError if stage == "review-self-cancel" else RuntimeError
    host, engine, _, _ = async_host(rule_kind="RISK", rule_error=stage == "rule",
                                   review=SyntheticModel(error=error))
    if stage == "rule-self-cancel":
        def cancel_rule(*_):
            raise asyncio.CancelledError(SECRET_MARKER)
        engine.rules.analyze = cancel_rule
    assert post(host)["verdict"] == model_verdict("NORMAL")
    record = shadow_record(host, engine)
    assert (record.rule_status if stage.startswith("rule") else record.review_status) == "FAILED"
    captured = capsys.readouterr()
    assert SECRET_MARKER not in repr(record) + caplog.text + captured.out + captured.err


@pytest.mark.parametrize("failure", ["timeout", "exception", "self-cancel"])
def test_primary_failure_has_no_normal_fallback(async_host, failure):
    model = SyntheticModel(delay=0.3 if failure == "timeout" else 0,
                           error={"exception": RuntimeError, "self-cancel": asyncio.CancelledError}.get(failure))
    host, engine, _, review = async_host(primary=model, limits=ExecutionLimits(primary_seconds=0.05))
    post(host, 503, "SERVICE_UNAVAILABLE")
    assert not engine.records and review.calls == 0 and model.active == 0


@pytest.mark.parametrize("invalid", ["unknown-sequence", "overflow", "mapping", "model", "prompt", "release", "extra"])
def test_invalid_primary_findings_and_provenance_fail_closed(async_host, invalid):
    def change(reply, messages, manifest):
        item = reply.verdict["findings"][0]
        if invalid == "unknown-sequence":
            item["sequence"] = 99
        elif invalid == "overflow":
            item["sequence"] = 2**63
        elif invalid == "mapping":
            item["audience"] = "SENDER"
        elif invalid == "model":
            reply.verdict["modelVersion"] = "unapproved"
        elif invalid == "prompt":
            reply.verdict["promptVersion"] = "unapproved"
        elif invalid == "release":
            reply.manifest["contentHash"] = "0" * 64
        else:
            reply.verdict["shadow"] = SECRET_MARKER
    host, engine, _, _ = async_host(primary=SyntheticModel("RISK", change=change))
    post(host, 503, "SERVICE_UNAVAILABLE")
    assert not engine.records


@pytest.mark.parametrize("status", [401, 403])
def test_actual_jwt_gate_precedes_async_engine(async_host, status):
    host, _, primary, review = async_host()
    token = host.token(wrong_key=True) if status == 401 else host.token({"scope": "other"})
    expect(host.post(credential=token), status, "ACCESS_TOKEN_INVALID" if status == 401 else "FORBIDDEN")
    assert primary.calls == review.calls == 0


def test_actual_manifest_gate_precedes_async_engine(async_host):
    host, _, primary, _ = async_host()
    payload = request_for(host.release)
    payload["manifest"]["engineVersion"] = "unapproved"
    post(host, 422, "POLICY_UNRESOLVED", payload=payload)
    assert primary.calls == 0


@pytest.mark.parametrize("environment,enabled", [("local", None), ("test", None), ("dev", None), ("prod", True)])
def test_no_runtime_setting_registers_synthetic_luna(async_host, environment, enabled):
    host, engine, primary, review = async_host(environment=environment, enabled=enabled)
    expect(host.post(), 422, "POLICY_UNRESOLVED")
    assert engine is None and primary.calls == review.calls == 0


def test_actual_release_is_rechecked_after_primary_and_before_shadow(async_host):
    host, engine, primary, review = async_host(rule_kind="RISK")
    primary.change = lambda *_: host.release.artifact.write_bytes(host.release.artifact.read_bytes() + b" ")
    post(host, 422, "POLICY_UNRESOLVED")
    assert not engine.records and engine.shadow.pending == review.calls == 0


@pytest.mark.parametrize("value", [True, 3001])
def test_async_path_preserves_invalid_token_count_contract(async_host, value):
    host, engine, primary, _ = async_host()
    engine.rules.count_tokens = lambda *_: value
    post(host, 400, "INVALID_REQUEST")
    assert primary.calls == 0


def test_shadow_cannot_mutate_primary_context_or_public_result(async_host):
    def mutate(reply, messages, manifest):
        messages.clear()
        manifest.clear()
        reply.verdict["findings"] = [finding()]
    host, engine, _, _ = async_host(rule_kind="RISK", review=SyntheticModel("RISK", change=mutate))
    assert post(host)["verdict"] == model_verdict("NORMAL")
    assert shadow_record(host, engine).review_kind == "RISK"
    assert SECRET_MARKER not in repr(engine.records)


async def request_on_app(host, *, payload=None):
    transport = httpx.ASGITransport(app=host.client.app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://asgi-contract.invalid") as client:
        if payload is None:
            payload = request_for(host.release)
        response = await client.post(ROUTE, json=payload,
                                     headers={"Authorization": "Bearer " + host.token()})
        if response.status_code == 200:
            assert response.json()["attemptId"] == payload["attemptId"]
            assert response.json()["manifest"] == payload["manifest"]
        return response


def test_caller_cancellation_propagates_and_cleans_cooperative_tasks(async_host):
    host, engine, primary, review = async_host(primary=SyntheticModel(delay=5), rule_kind="RISK")

    async def scenario():
        before = set(asyncio.all_tasks())
        task = asyncio.create_task(request_on_app(host))
        await asyncio.wait_for(primary.started.wait(), 1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.sleep(0)
        assert primary.active == review.active == 0 and primary.cancelled == 1 and not engine.records
        assert set(asyncio.all_tasks()) == before

    host.client.portal.call(scenario)


@pytest.mark.parametrize("stage", ["body", "primary"])
def test_actual_asgi_disconnect_ends_quietly(async_host, caplog, stage):
    import h11
    from uvicorn.protocols.http.h11_impl import FlowControl, RequestResponseCycle

    host, engine, primary, review = async_host(primary=SyntheticModel(delay=5), rule_kind="RISK")
    token = host.token()
    caplog.set_level(logging.INFO, logger="uvicorn.access")

    async def scenario():
        body = json.dumps(request_for(host.release)).encode()
        scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST",
                 "scheme": "http", "path": ROUTE, "raw_path": ROUTE.encode(), "query_string": b"",
                 "headers": [(b"authorization", ("Bearer " + token).encode()), (b"content-type", b"application/json")],
                 "server": ("asgi-contract.invalid", 80), "client": ("127.0.0.1", 1), "root_path": ""}
        transport = Mock(spec=asyncio.Transport)
        event = asyncio.Event()
        cycle = RequestResponseCycle(
            scope=scope, conn=h11.Connection(h11.SERVER), transport=transport,
            flow=FlowControl(transport), logger=logging.getLogger("uvicorn.error"),
            access_logger=logging.getLogger("uvicorn.access"), access_log=True,
            default_headers=[], message_event=event, on_response=lambda: None)
        cycle.send = AsyncMock(wraps=cycle.send)
        cycle.body, cycle.more_body = body, stage == "body"
        event.set()
        task = asyncio.create_task(cycle.run_asgi(host.client.app))
        if stage == "primary":
            await asyncio.wait_for(primary.started.wait(), 1)
        else:
            # Wait until Starlette has consumed the first, incomplete body event.
            async def body_started():
                while cycle.body:
                    await asyncio.sleep(0)
            await asyncio.wait_for(body_started(), 1)
        cycle.disconnected = True
        event.set()
        await asyncio.wait_for(task, 1)
        cycle.send.assert_not_awaited()
        transport.write.assert_not_called()
        assert not cycle.response_started and not cycle.response_complete
        assert primary.active == review.calls == 0 and not engine.records
        assert primary.cancelled == (1 if stage == "primary" else 0)
        assert not any(record.name.startswith("uvicorn.") for record in caplog.records)

    host.client.portal.call(scenario)


def test_body_upload_consumes_same_request_deadline(async_host):
    host, _, primary, _ = async_host(limits=ExecutionLimits(request_seconds=0.15, cleanup_seconds=0.01))
    async def scenario():
        async def slow_body():
            await asyncio.sleep(1)
            yield b"{}"
        transport = httpx.ASGITransport(app=host.client.app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://asgi-contract.invalid") as client:
            started = time.monotonic()
            response = await client.post(ROUTE, content=slow_body(), headers={"Authorization": "Bearer " + host.token()})
            assert response.status_code == 503 and response.json()["code"] == "SERVICE_UNAVAILABLE"
            assert time.monotonic() - started < 0.5 and primary.calls == 0
    host.client.portal.call(scenario)


def test_noncooperative_async_cleanup_is_bounded_and_keeps_capacity(async_host):
    model = SyntheticModel(resist=True)
    host, engine, _, _ = async_host(primary=model, limits=ExecutionLimits(primary_seconds=0.04, cleanup_seconds=0.01))
    engine.boundary.limits = replace(engine.boundary.limits, tasks=1)
    try:
        started = time.monotonic()
        post(host, 503, "SERVICE_UNAVAILABLE")
        assert time.monotonic() - started < 0.5
        assert model.active == 1 and engine.boundary.pending == 1
        post(host, 503, "SERVICE_UNAVAILABLE")
        assert model.calls == 1
        assert host.client.portal.call(engine.aclose) == 1
    finally:
        host.client.portal.call(model.release.set)
        host.client.portal.call(asyncio.sleep, 0.02)
    assert model.active == 0 and engine.boundary.pending == 0 and not engine.records


def test_sync_worker_limit_survives_timeout_and_discards_late_result(async_host):
    host, engine, primary, _ = async_host(limits=ExecutionLimits(request_seconds=0.15, cleanup_seconds=0.01, workers=1))
    entered, release = threading.Event(), threading.Event()
    counter = engine.rules.count_tokens
    def blocked(messages, manifest):
        entered.set()
        release.wait(2)
        return counter(messages, manifest)
    engine.rules.count_tokens = blocked
    try:
        post(host, 503, "SERVICE_UNAVAILABLE")
        assert entered.is_set() and engine.boundary.executor.active == 1
        post(host, 503, "SERVICE_UNAVAILABLE")
        assert engine.boundary.executor.active == 1 and primary.calls == 0
    finally:
        release.set()
        for _ in range(100):
            if engine.boundary.executor.active == 0:
                break
            time.sleep(0.005)
    assert engine.boundary.executor.active == 0
    assert post(host)["verdict"] == model_verdict("NORMAL")
    assert shadow_record(host, engine).rule_status == "OK"


def test_sync_engine_does_not_block_event_loop(main_factory, monkeypatch):
    original = LocalRuleRiskEngine.analyze
    def slow(engine, messages, manifest):
        time.sleep(0.08)
        return original(engine, messages, manifest)
    monkeypatch.setattr(LocalRuleRiskEngine, "analyze", slow)
    host = main_factory()
    async def scenario():
        ticks = 0
        async def heartbeat():
            nonlocal ticks
            while True:
                ticks += 1
                await asyncio.sleep(0.005)
        beat = asyncio.create_task(heartbeat())
        try:
            response = await request_on_app(host)
            assert response.status_code == 200
            assert ticks >= 5
            assert response.json()["manifest"] == host.release.manifest
        finally:
            beat.cancel()
            with pytest.raises(asyncio.CancelledError):
                await beat
    host.client.portal.call(scenario)


@pytest.mark.parametrize("release_duration", [0.15, 0.51])
def test_slow_shadow_cannot_starve_the_final_release_check(async_host, monkeypatch, release_duration):
    from app.risk_detection.releases import DevelopmentReleaseCatalog

    now = 0.0
    release_times = []
    start = Deadline.start
    supports = DevelopmentReleaseCatalog.supports
    host, engine, primary, review = async_host(rule_kind="RISK")
    rule = engine.rules.analyze

    def finish_primary(*_):
        nonlocal now
        now = 7.50

    def slow_rule(*args):
        nonlocal now
        now += 0.20
        return rule(*args)

    def slow_review(*_):
        nonlocal now
        now += 0.18

    def verify_release(*args):
        nonlocal now
        release_times.append(now)
        if len(release_times) == 1:
            now += 3.0  # Preflight and primary each stay within their own stage limits.
        else:
            now += release_duration
        return supports(*args)

    primary.change, review.change = finish_primary, slow_review
    engine.rules.analyze = slow_rule
    monkeypatch.setattr(Deadline, "start", lambda seconds: start(seconds, clock=lambda: now))
    monkeypatch.setattr(DevelopmentReleaseCatalog, "supports", verify_release)
    if release_duration < 0.50:
        assert post(host)["verdict"] == model_verdict("NORMAL")
        assert shadow_record(host, engine).review_status == "TIMEOUT"
    else:
        post(host, 503, "SERVICE_UNAVAILABLE")
        assert engine.shadow.pending == review.calls == 0 and not engine.records
    assert release_times == [0.0, 7.50]


@pytest.mark.parametrize("skip_stage", ["rule", "review"])
def test_shadow_skips_a_stage_without_enough_budget(async_host, monkeypatch, skip_stage):
    now = 0.0
    start = Deadline.start
    host, engine, primary, review = async_host(rule_kind="RISK")
    rule = engine.rules.analyze
    rule_calls = 0

    def finish_primary(*_):
        nonlocal now
        now = 7.98 if skip_stage == "rule" else 7.50

    def slow_rule(*args):
        nonlocal now, rule_calls
        rule_calls += 1
        now = 7.98
        return rule(*args)

    primary.change = finish_primary
    engine.rules.analyze = slow_rule
    monkeypatch.setattr(Deadline, "start", lambda seconds: start(seconds, clock=lambda: now))
    assert post(host)["verdict"] == model_verdict("NORMAL")
    record = shadow_record(host, engine)
    assert review.calls == 0
    if skip_stage == "rule":
        assert record.rule_status == "SKIPPED_BUDGET" and rule_calls == 0
    else:
        assert record.rule_status == "OK" and record.review_status == "SKIPPED_BUDGET" and rule_calls == 1


def test_response_finishes_before_shadow_and_busy_shadow_cannot_delay_next_request(async_host):
    review = SyntheticModel(resist=True)
    host, engine, _, _ = async_host(rule_kind="RISK", review=review, limits=ExecutionLimits(workers=1))
    entered, release = threading.Event(), threading.Event()
    rule = engine.rules.analyze

    def blocked_rule(*args):
        entered.set()
        release.wait(2)
        return rule(*args)

    engine.rules.analyze = blocked_rule

    async def scenario():
        try:
            # This must finish even though nothing has allowed the shadow rule to finish.
            first = await asyncio.wait_for(request_on_app(host), 0.75)
            assert first.status_code == 200 and first.json()["verdict"] == model_verdict("NORMAL")

            async def rule_started():
                while not entered.is_set():
                    await asyncio.sleep(0.001)
            await asyncio.wait_for(rule_started(), 0.25)
            assert engine.shadow.executor.active == 1 and review.calls == 0
            second = await asyncio.wait_for(request_on_app(host), 0.75)
            assert second.status_code == 200 and second.json()["verdict"] == model_verdict("NORMAL")
            assert engine.records[-1].rule_status == "SKIPPED_CAPACITY"
            release.set()

            await asyncio.wait_for(review.started.wait(), 0.5)
            third = await asyncio.wait_for(request_on_app(host), 0.75)
            assert third.status_code == 200 and review.active == 1
            assert engine.records[-1].rule_status == "SKIPPED_CAPACITY"
        finally:
            release.set()
            review.release.set()

    host.client.portal.call(scenario)
    assert shadow_record(host, engine).review_status == "OK"


@pytest.mark.parametrize("field,value", [("request_seconds", 0), ("request_seconds", 9),
    ("primary_seconds", float("nan")), ("shadow_seconds", True), ("cleanup_seconds", -1),
    ("workers", 0), ("workers", True), ("tasks", 65)])
def test_execution_limits_reject_invalid_configuration(field, value):
    with pytest.raises(ValueError):
        ExecutionLimits(**{field: value})


def test_previous_mr243_http_source_is_not_implicitly_reapproved(main_factory, tmp_path):
    from app.risk_detection.releases import DevelopmentReleaseError
    from safety48_runtime_support import synthetic_release
    release = synthetic_release(tmp_path)
    artifact = json.loads(release.artifact.read_text(encoding="utf-8"))
    artifact["sources"]["http_api.py"] = "6714ac2c4a902798cb00c805d20ad0dedd879ef4bee7ef4035d1919e62a59363"
    release.rewrite_artifact(artifact)
    with pytest.raises(DevelopmentReleaseError):
        main_factory(release=release, pin=release.test_descriptor_pin)


def test_zero_stage_budget_never_starts_provider(main_factory):
    host = main_factory()
    async def scenario():
        execution = ExecutionBoundary()
        model = SyntheticModel()
        with pytest.raises(TimeoutError):
            await execution.run(model.complete([], {}, deadline=Deadline.start(1)),
                                deadline=Deadline.start(1), cap=0)
        assert model.calls == execution.pending == 0
    host.client.portal.call(scenario)


def test_cancel_before_pipeline_start_closes_owned_coroutine(main_factory):
    host = main_factory()
    async def scenario():
        execution = ExecutionBoundary()
        entered = False
        class Request:
            async def receive(self):
                await asyncio.Event().wait()
        async def process():
            nonlocal entered
            entered = True
            await asyncio.Event().wait()
        task = asyncio.create_task(execution.until_disconnect(process(), Request(), deadline=Deadline.start(1)))
        await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.sleep(0)
        assert execution.pending == 0
    host.client.portal.call(scenario)


@pytest.mark.parametrize("stop", ["timeout", "cancellation", "deadline"])
@pytest.mark.parametrize("fails", [False, True], ids=["result", "exception"])
def test_worker_future_completion_race_is_privately_retrieved(monkeypatch, caplog, capsys, stop, fails):
    from app.risk_detection.execution import BoundedExecutor

    original_wait = asyncio.wait
    expired = False
    observed_future = None

    def worker():
        if fails:
            raise RuntimeError(SECRET_MARKER)
        return SECRET_MARKER

    async def raced_wait(futures, **kwargs):
        nonlocal expired, observed_future
        done, pending = await original_wait(futures, **kwargs)
        assert done and not pending
        observed_future = weakref.ref(next(iter(futures)))
        # Force completion after wait's observation but before run's cleanup.
        if stop == "timeout":
            return set(), set(futures)
        if stop == "cancellation":
            raise asyncio.CancelledError()
        expired = True
        return done, pending

    monkeypatch.setattr(asyncio, "wait", raced_wait)
    if stop == "deadline":
        monkeypatch.setattr(Deadline, "remaining", lambda _: 0.0 if expired else 1.0)

    async def scenario():
        loop = asyncio.get_running_loop()
        previous = loop.get_exception_handler()
        loop_errors = []
        # Keep only booleans: retaining context would keep the failed Future alive.
        loop.set_exception_handler(lambda _, context: loop_errors.append(
            ("never retrieved" in context.get("message", ""), SECRET_MARKER in repr(context))))
        executor = BoundedExecutor(workers=1)
        expected = asyncio.CancelledError if stop == "cancellation" else TimeoutError

        async def invoke():
            try:
                await executor.run(worker, deadline=Deadline.start(1))
            except expected as error:
                assert SECRET_MARKER not in str(error)
            else:
                pytest.fail("A completed worker bypassed the timeout/cancellation boundary")

        try:
            await invoke()
            for _ in range(100):
                gc.collect()  # Exercise finalization after the worker thread drops its references.
                await asyncio.sleep(0.001)
                if observed_future() is None:
                    break
            assert observed_future() is None
            assert executor.active == 0
            assert not loop_errors
        finally:
            executor.close()
            loop.set_exception_handler(previous)

    asyncio.run(scenario())
    captured = capsys.readouterr()
    assert SECRET_MARKER not in caplog.text + captured.out + captured.err


def test_cancelled_worker_future_keeps_slot_until_late_exception_exits(monkeypatch, caplog, capsys):
    from app.risk_detection.execution import BoundedExecutor, CapacityExceeded

    entered, release = threading.Event(), threading.Event()

    def worker():
        entered.set()
        release.wait(2)
        raise RuntimeError(SECRET_MARKER)

    async def cancelled_wait(futures, **kwargs):
        for _ in range(100):
            if entered.is_set():
                break
            await asyncio.sleep(0.001)
        assert entered.is_set()
        future, = futures
        assert future.cancel()
        return set(), set(futures)

    monkeypatch.setattr(asyncio, "wait", cancelled_wait)

    async def scenario():
        loop = asyncio.get_running_loop()
        previous = loop.get_exception_handler()
        loop_errors = []
        loop.set_exception_handler(lambda _, context: loop_errors.append(True))
        executor = BoundedExecutor(workers=1)
        try:
            with pytest.raises(TimeoutError):
                await executor.run(worker, deadline=Deadline.start(1))
            assert executor.active == 1
            with pytest.raises(CapacityExceeded):
                await executor.run(lambda: None, deadline=Deadline.start(1))
        finally:
            release.set()
            for _ in range(200):
                if executor.active == 0:
                    break
                await asyncio.sleep(0.005)
            # Let the late publish callback observe the cancelled Future.
            await asyncio.sleep(0)
            gc.collect()
            await asyncio.sleep(0)
            executor.close()
            loop.set_exception_handler(previous)
        assert executor.active == 0 and not loop_errors

    asyncio.run(scenario())
    captured = capsys.readouterr()
    assert SECRET_MARKER not in caplog.text + captured.out + captured.err


def test_tokenizer_timeout_completion_race_is_private_and_stays_503(async_host, monkeypatch, caplog, capsys):
    host, engine, primary, review = async_host()
    original_wait = asyncio.wait
    entered = threading.Event()
    raced = False

    def failed_counter(*_):
        entered.set()
        raise RuntimeError(SECRET_MARKER)

    engine.rules.count_tokens = failed_counter

    async def raced_wait(futures, **kwargs):
        nonlocal raced
        done, pending = await original_wait(futures, **kwargs)
        if (not raced and entered.is_set() and done and not pending
                and all(not isinstance(future, asyncio.Task) for future in futures)):
            raced = True
            return set(), set(futures)
        return done, pending

    monkeypatch.setattr(asyncio, "wait", raced_wait)

    async def scenario():
        loop = asyncio.get_running_loop()
        previous = loop.get_exception_handler()
        loop_errors = []
        loop.set_exception_handler(lambda _, context: loop_errors.append(
            ("never retrieved" in context.get("message", ""), SECRET_MARKER in repr(context))))
        try:
            response = await request_on_app(host)
            assert response.status_code == 503
            assert response.json()["code"] == "SERVICE_UNAVAILABLE"
            assert SECRET_MARKER not in response.text
            await asyncio.sleep(0)
            gc.collect()
            await asyncio.sleep(0)
            assert not loop_errors
        finally:
            loop.set_exception_handler(previous)

    host.client.portal.call(scenario)
    assert raced and primary.calls == review.calls == 0 and not engine.records
    assert engine.boundary.pending == engine.boundary.executor.active == 0
    captured = capsys.readouterr()
    assert SECRET_MARKER not in caplog.text + captured.out + captured.err


def test_previous_execution_source_is_not_implicitly_reapproved(main_factory, tmp_path):
    from app.risk_detection.releases import DevelopmentReleaseError
    from safety48_runtime_support import synthetic_release

    release = synthetic_release(tmp_path)
    artifact = json.loads(release.artifact.read_text(encoding="utf-8"))
    # execution.py at 26f5c19; a new implementation needs a new reviewed artifact.
    artifact["sources"]["execution.py"] = "25cf8f4321469c8e2773a841966f1762b89402204a748709314e3d076b3ace6c"
    release.rewrite_artifact(artifact)
    with pytest.raises(DevelopmentReleaseError):
        main_factory(release=release, pin=release.test_descriptor_pin)
