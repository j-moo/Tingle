"""Default-OFF Safety Guard registration for local/test/dev environments only."""

import asyncio
from collections.abc import Callable
from typing import TYPE_CHECKING

from fastapi import APIRouter

from app.risk_detection.execution import ExecutionBoundary
from app.risk_detection.http_api import ReleaseCatalog, RuntimeResources, _error, create_router

if TYPE_CHECKING:
    from app.risk_detection.gms_runtime import GmsPrimaryConfiguration


class DevelopmentRuntime:
    """Own one worker's HTTP boundary and explicitly assembled engine.

    The optional Python factory transfers engine ownership only on return. It is
    a test seam, not a setting, HTTP input, plugin loader or model registry.
    A factory must clean up its own partial construction if it raises.
    """

    def __init__(self, *, environment: str, enabled: bool = False,
                 descriptor_file: str = "", descriptor_sha256: str = "",
                 engine_factory: Callable | None = None,
                 gms_primary: "GmsPrimaryConfiguration | None" = None):
        self.environment, self.enabled = environment, enabled
        self.descriptor_file, self.descriptor_sha256 = descriptor_file, descriptor_sha256
        self._engine_factory = engine_factory
        self._gms_primary = gms_primary
        self._state = "new"
        self._owned: RuntimeResources | None = None
        self._close_task: asyncio.Task | None = None
        self._close_waiters = 0

    @property
    def resources(self) -> RuntimeResources | None:
        return self._owned if self._state == "running" else None

    def resolve(self) -> RuntimeResources:
        resources = self.resources
        if resources is None:
            raise _error(503, "SERVICE_UNAVAILABLE")
        return resources

    def _boundaries(self):
        if self._owned is None:
            return ()
        engine = self._owned.engine
        return tuple(boundary for boundary in (
            self._owned.execution, getattr(engine, "boundary", None), getattr(engine, "shadow", None),
        ) if isinstance(boundary, ExecutionBoundary))

    @property
    def remaining(self) -> int:
        # Async cancellation does not release a running synchronous worker slot.
        return sum(boundary.pending + boundary.executor.active for boundary in self._boundaries()) + int(
            self._close_task is not None and not self._close_task.done())

    async def startup(self):
        if self._state in {"starting", "running", "closing"} or self.remaining or self._close_waiters:
            raise RuntimeError("Safety Guard runtime already active")
        self._state, self._owned, self._close_task = "starting", None, None
        engine = None
        execution = ExecutionBoundary()
        catalog = ReleaseCatalog(self.descriptor_file)
        try:
            # OFF/prod never reads development artifacts, imports a tokenizer,
            # calls the injected factory or looks up provider credentials.
            if self.enabled is True and self.environment in {"local", "test", "dev"}:
                from app.risk_detection.local_engine import LocalRuleRiskEngine
                from app.risk_detection.releases import DevelopmentReleaseCatalog, DevelopmentReleaseError

                catalog = DevelopmentReleaseCatalog(
                    self.descriptor_file, descriptor_sha256=self.descriptor_sha256)
                if self._gms_primary is not None:
                    from app.risk_detection.gms_runtime import (
                        GmsPrimaryConfiguration, GmsPrimaryEngine, GmsReleaseCatalog,
                    )

                    if type(self._gms_primary) is not GmsPrimaryConfiguration:
                        raise DevelopmentReleaseError()
                    if self._gms_primary.enabled is True:
                        if self._engine_factory is not None:
                            raise DevelopmentReleaseError()
                        catalog = GmsReleaseCatalog(catalog, self._gms_primary)
                        engine = GmsPrimaryEngine(catalog=catalog, configuration=self._gms_primary)
                if engine is None:
                    factory = self._engine_factory or LocalRuleRiskEngine
                    engine = factory(count_input_tokens=catalog.tokenizer.count)
                execution = ExecutionBoundary(getattr(engine, "execution_limits", None))
                # Recheck the catalog's pinned snapshot after dependency assembly.
                if not catalog.supports(catalog._manifest):
                    raise DevelopmentReleaseError()
            self._owned = RuntimeResources(catalog, engine, execution)
            self._state = "running"  # Publish only a fully validated instance.
        except (asyncio.CancelledError, Exception) as error:
            self._owned = RuntimeResources(catalog, engine, execution)
            try:
                await self.aclose()
            except Exception:
                pass  # A secondary cleanup failure must not replace the startup error.
            if isinstance(error, asyncio.CancelledError):
                raise
            from app.risk_detection.releases import DevelopmentReleaseError

            if isinstance(error, DevelopmentReleaseError):
                raise
            raise RuntimeError("Safety Guard runtime startup failed") from None

    @staticmethod
    def _retrieve(task):
        if not task.cancelled():
            task.exception()  # Retrieve privately; never log input or exception text.

    async def _dispose(self, resources, stop_at):
        try:
            # Cancel request work before closing engine/Shadow-owned tasks.
            try:
                await resources.execution.aclose()
            finally:
                close = getattr(resources.engine, "aclose", None)
                if close is not None:
                    await close()
            # Wait only within the same finite shutdown budget. Late workers keep
            # their slots until they exit; their results cannot reach a response.
            while any(b.pending or b.executor.active for b in self._boundaries()):
                if asyncio.get_running_loop().time() >= stop_at:
                    break
                await asyncio.sleep(0.001)
        finally:
            self._state = "closed"

    async def aclose(self) -> int:
        self._state = "closing"  # Revoke admission before the first await.
        if self._owned is None:
            self._state = "closed"
            return 0
        budget = self._owned.execution.limits.cleanup_seconds * 4
        if self._close_task is None:
            self._close_task = asyncio.create_task(
                self._dispose(self._owned, asyncio.get_running_loop().time() + budget),
                name="risk-runtime-close")
            self._close_task.add_done_callback(self._retrieve)
        # Caller cancellation cannot orphan cleanup. Never use wait_for/gather,
        # which can wait forever if a task suppresses cancellation.
        # Keep the old generation owned until every close caller has resumed;
        # a completed cleanup task alone is not permission to replace its state.
        self._close_waiters += 1
        try:
            done, _ = await asyncio.wait({self._close_task}, timeout=budget)
            if not done:
                self._close_task.cancel()
            else:
                self._state = "closed"
                if not self._close_task.cancelled() and self._close_task.exception() is not None:
                    raise RuntimeError("Safety Guard runtime shutdown failed") from None
            return self.remaining
        finally:
            self._close_waiters -= 1
            if self._close_task.done() and not self.remaining:
                self._state = "closed"  # Also restore fully disposed state on caller cancellation.


def create_development_router(authenticate: Callable, *, environment: str, enabled: bool = False,
                              descriptor_file: str = "", descriptor_sha256: str = "",
                              runtime: DevelopmentRuntime | None = None) -> APIRouter:
    if runtime is not None:
        return create_router(authenticate, runtime=runtime.resolve)
    # Check before importing rule data, reading releases or loading optional
    # tokenizers. Production ignores opt-in and retains engine=None.
    if enabled is not True or environment not in {"local", "test", "dev"}:
        return create_router(authenticate, ReleaseCatalog(descriptor_file))

    from app.risk_detection.local_engine import LocalRuleRiskEngine
    from app.risk_detection.releases import DevelopmentReleaseCatalog

    catalog = DevelopmentReleaseCatalog(descriptor_file, descriptor_sha256=descriptor_sha256)
    return create_router(authenticate, catalog, LocalRuleRiskEngine(count_input_tokens=catalog.tokenizer.count))
