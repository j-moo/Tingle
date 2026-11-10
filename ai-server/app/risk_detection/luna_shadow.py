"""Explicitly constructed async orchestration; never registered by runtime flags.

No GMS SDK, network, persistent storage, or external experiment imports. A real
model needs its own approved release/adapter; a rules artifact is not approval.
"""

import asyncio
from collections import deque
from copy import deepcopy
from dataclasses import dataclass, field, replace
from typing import Protocol

from app.risk_detection.execution import CapacityExceeded, Deadline, ExecutionBoundary, ExecutionLimits
from app.risk_detection.http_api import Manifest
from app.risk_detection.rule_engine import MODEL_VERSION, PROMPT_VERSION
from app.risk_detection.validation import approved_messages, validated_verdict


@dataclass(frozen=True)
class ModelReply:
    manifest: dict = field(repr=False)
    verdict: dict = field(repr=False)


class AsyncModel(Protocol):
    async def complete(self, messages: list[dict], manifest: dict, *, deadline) -> ModelReply: ...


@dataclass(frozen=True)
class ModelIdentity:
    model_version: str
    prompt_version: str

    def __post_init__(self):
        if any(type(value) is not str or not value.strip() or len(value) > 100
               for value in (self.model_version, self.prompt_version)):
            raise ValueError("Invalid model identity")


@dataclass(frozen=True)
class ShadowRecord:
    # Fixed, content-free categories only; no attemptId/contextId, messages,
    # credentials, exception text, findings, identities or complete responses.
    primary_kind: str
    rule_status: str
    rule_kind: str | None
    review_status: str
    review_kind: str | None


class LunaShadowEngine:
    def __init__(self, *, primary: AsyncModel, review: AsyncModel, rules,
                 identity: ModelIdentity, limits: ExecutionLimits | None = None):
        self.execution_limits = limits or ExecutionLimits()
        self.boundary = ExecutionBoundary(self.execution_limits)
        # Optional work cannot occupy a worker or task slot used by the official path.
        self.shadow = ExecutionBoundary(replace(self.execution_limits, workers=1))
        self.primary, self.review, self.rules, self.identity = primary, review, rules, identity
        self.records = deque(maxlen=128)

    async def count_tokens(self, messages, manifest, *, deadline):
        return await self.boundary.executor.run(self.rules.count_tokens, deepcopy(messages),
                                                deepcopy(manifest), deadline=deadline)

    def _reply(self, reply, messages, manifest):
        if not isinstance(reply, ModelReply) or Manifest.model_validate(reply.manifest).model_dump() != manifest:
            raise ValueError("Unapproved model release")
        result = validated_verdict(reply.verdict, approved_messages(messages))
        if (result["modelVersion"], result["promptVersion"]) != (self.identity.model_version, self.identity.prompt_version):
            raise ValueError("Unapproved model version")
        return result

    async def analyze(self, messages, manifest, *, deadline):
        source, release = deepcopy(messages), deepcopy(manifest)
        first = await self.boundary.run(
            self.primary.complete(deepcopy(source), deepcopy(release), deadline=deadline),
            deadline=deadline, cap=self.execution_limits.primary_seconds)
        return self._reply(first, source, release)

    async def start_shadow(self, messages, manifest, primary_kind, *, deadline: Deadline):
        # Called after the official response body is sent. Never await the comparison here.
        shadow_deadline = deadline.capped(self.execution_limits.shadow_seconds)
        if shadow_deadline.remaining() <= self.execution_limits.cleanup_seconds:
            status = "SKIPPED_BUDGET"
        elif self.shadow.pending or self.shadow.executor.active:
            status = "SKIPPED_CAPACITY"
        else:
            try:
                self.shadow.start(self._compare(messages, manifest, primary_kind, deadline=shadow_deadline))
                return
            except CapacityExceeded:
                status = "SKIPPED_CAPACITY"
        self.records.append(ShadowRecord(primary_kind, status, None, "NOT_RUN", None))

    async def _compare(self, messages, manifest, primary_kind, *, deadline: Deadline):
        rule_status, rule_kind, review_status, review_kind = "FAILED", None, "NOT_RUN", None
        if deadline.remaining() <= self.execution_limits.cleanup_seconds:
            rule_status = "SKIPPED_BUDGET"
        else:
            try:
                result = await self.shadow.executor.run(
                    self.rules.analyze, deepcopy(messages), deepcopy(manifest), deadline=deadline)
                result = validated_verdict(result, approved_messages(messages))
                if (result["modelVersion"], result["promptVersion"]) != (MODEL_VERSION, PROMPT_VERSION):
                    raise ValueError("Unapproved rule version")
                rule_status, rule_kind = "OK", result["kind"]
            except TimeoutError:
                rule_status = "TIMEOUT"
            except asyncio.CancelledError:
                # A worker can raise CancelledError without cancelling this job.
                if asyncio.current_task().cancelling():
                    raise
            except Exception:
                pass
        if primary_kind == "NORMAL" and rule_kind == "RISK":
            if deadline.remaining() <= self.execution_limits.cleanup_seconds:
                review_status = "SKIPPED_BUDGET"
            else:
                review_status = "FAILED"
                try:
                    reply = await self.shadow.run(
                        self.review.complete(deepcopy(messages), deepcopy(manifest), deadline=deadline),
                        deadline=deadline)
                    result = self._reply(reply, messages, manifest)
                    review_status, review_kind = "OK", result["kind"]
                except TimeoutError:
                    review_status = "TIMEOUT"
                except Exception:
                    pass
        self.records.append(ShadowRecord(primary_kind, rule_status, rule_kind, review_status, review_kind))

    async def aclose(self):
        remaining = await self.shadow.aclose()
        return remaining + await self.boundary.aclose()
