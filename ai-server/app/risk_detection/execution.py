"""Bounded local execution; no provider, activation switch, or unbounded joins."""

import asyncio
from dataclasses import dataclass, field
import inspect
import math
import threading
import time
from typing import Callable


@dataclass(frozen=True)
class ExecutionLimits:
    # Internal proposal, not a claim about the BE caller's remaining budget.
    request_seconds: float = 8.0
    primary_seconds: float = 5.0
    shadow_seconds: float = 1.0
    cleanup_seconds: float = 0.05
    workers: int = 4
    tasks: int = 16

    def __post_init__(self):
        values = (self.request_seconds, self.primary_seconds, self.shadow_seconds, self.cleanup_seconds)
        if any(type(value) not in (int, float) or not math.isfinite(value) or value <= 0 for value in values):
            raise ValueError("Invalid execution limits")
        if not self.cleanup_seconds < self.request_seconds <= 8:
            raise ValueError("Invalid request deadline")
        if any(type(value) is not int or not 1 <= value <= 64 for value in (self.workers, self.tasks)):
            raise ValueError("Invalid execution capacity")


@dataclass(frozen=True)
class Deadline:
    expires_at: float
    clock: Callable[[], float] = field(default=time.monotonic, repr=False, compare=False)

    @classmethod
    def start(cls, seconds, *, clock=time.monotonic):
        return cls(clock() + seconds, clock)

    def remaining(self):
        return max(0.0, self.expires_at - self.clock())

    def capped(self, seconds):
        return Deadline(min(self.expires_at, self.clock() + seconds), self.clock)

    def check(self):
        if self.remaining() <= 0:
            raise TimeoutError("Risk execution deadline")


class CapacityExceeded(RuntimeError):
    pass


class ClientDisconnected(asyncio.CancelledError):
    pass


class BoundedExecutor:
    """No queue; admission stays occupied until the actual worker exits.

    Daemon workers cannot hold process shutdown open. Python cannot kill a
    running thread: real uncooperative providers need supervised processes.
    Late results are discarded, never published to a cancelled Future.
    """

    def __init__(self, workers=4):
        self._slots = threading.BoundedSemaphore(workers)
        self._lock = threading.Lock()
        self._active = 0
        self._closed = False

    @property
    def active(self):
        with self._lock:
            return self._active

    def close(self):
        with self._lock:
            self._closed = True

    async def run(self, function, *args, deadline):
        deadline.check()
        with self._lock:
            if self._closed or not self._slots.acquire(blocking=False):
                raise CapacityExceeded("Risk worker capacity")
            self._active += 1
        loop = asyncio.get_running_loop()
        future = loop.create_future()

        def publish(value, error):
            if not future.done():
                if error is None:
                    future.set_result(value)
                else:
                    future.set_exception(error)

        def work():
            value, error = None, None
            try:
                value = function(*args)
            except BaseException as caught:
                error = caught
            finally:
                with self._lock:
                    self._active -= 1
                self._slots.release()
            try:
                loop.call_soon_threadsafe(publish, value, error)
            except RuntimeError:
                pass  # Owning loop closed; late results have no recipient.

        try:
            threading.Thread(target=work, daemon=True, name="risk-bounded-worker").start()
        except BaseException:
            with self._lock:
                self._active -= 1
            self._slots.release()
            raise
        try:
            done, _ = await asyncio.wait({future}, timeout=deadline.remaining())
            if not done:
                raise TimeoutError("Risk worker deadline")
            deadline.check()
            return future.result()
        finally:
            if not future.done():
                future.cancel()
            elif not future.cancelled():
                # Completion may race with timeout/cancellation before result() is read.
                future.exception()  # Retrieve privately, including discarded late errors.


class ExecutionBoundary:
    """Own async tasks, retrieve exceptions, bound cleanup and quarantine stragglers."""

    def __init__(self, limits=None):
        self.limits = limits or ExecutionLimits()
        self.executor = BoundedExecutor(self.limits.workers)
        self._tasks = set()
        self._closed = False

    @property
    def pending(self):
        return len(self._tasks)

    def _done(self, task):
        self._tasks.discard(task)
        if not task.cancelled():
            task.exception()  # Retrieve privately; never log exception details.

    async def cleanup(self, tasks, *, deadline=None):
        pending = {task for task in tasks if not task.done()}
        for task in pending:
            task.cancel()
        if pending:
            # wait_for(gather(...)) can wait indefinitely on suppressed cancellation.
            await asyncio.wait(pending, timeout=min(self.limits.cleanup_seconds, deadline.remaining())
                               if deadline is not None else self.limits.cleanup_seconds)

    def start(self, coroutine):
        if self._closed or len(self._tasks) >= self.limits.tasks:
            coroutine.close()
            raise CapacityExceeded("Risk task capacity")
        task = asyncio.create_task(coroutine, name="risk-owned-task")
        self._tasks.add(task)
        task.add_done_callback(self._done)
        return task

    async def run(self, coroutine, *, deadline, cap=None):
        try:
            deadline.check()
            if cap is not None and cap <= 0:
                raise TimeoutError("No risk stage budget")
        except BaseException:
            coroutine.close()
            raise
        task = self.start(coroutine)
        try:
            budget = deadline.remaining() if cap is None else min(cap, deadline.remaining())
            done, _ = await asyncio.wait({task}, timeout=budget)
            if not done:
                raise TimeoutError("Risk stage deadline")
            deadline.check()
            if task.cancelled():
                raise RuntimeError("Risk stage cancelled")
            return task.result()
        finally:
            if not task.done():
                await self.cleanup({task}, deadline=deadline)

    async def invoke(self, function, *args, deadline):
        if inspect.iscoroutinefunction(function):
            return await self.run(function(*args, deadline=deadline), deadline=deadline)
        return await self.executor.run(function, *args, deadline=deadline)

    async def until_disconnect(self, coroutine, request, *, deadline):
        async def watch():
            while True:
                event = await request.receive()
                if event["type"] == "http.disconnect":
                    return

        # Only start after the body is completely consumed: one receive owner.
        work = self.start(coroutine)
        watcher = asyncio.create_task(watch())
        try:
            await asyncio.wait({work, watcher}, timeout=deadline.remaining(), return_when=asyncio.FIRST_COMPLETED)
            if watcher.done():
                watcher.result()
                raise ClientDisconnected()
            if not work.done():
                raise TimeoutError("Risk request deadline")
            deadline.check()
            return work.result()
        finally:
            await self.cleanup({work, watcher}, deadline=deadline)
            for task in (work, watcher):
                if task.done() and not task.cancelled():
                    task.exception()
                elif not task.done() and task not in self._tasks:
                    # Keep capacity occupied until the late task really exits.
                    self._tasks.add(task)
                    task.add_done_callback(self._done)

    async def aclose(self):
        self._closed = True
        self.executor.close()
        await self.cleanup(set(self._tasks))
        return self.pending  # Supervisor can detect remaining noncooperative tasks.
