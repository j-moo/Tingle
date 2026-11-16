"""Authenticated risk transport with explicit file provenance and no provider client."""

from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path
import re
from typing import Callable, Literal, Protocol
from uuid import UUID

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
from starlette.background import BackgroundTask
from starlette.requests import ClientDisconnect
from starlette.responses import JSONResponse, Response

from app.risk_detection.execution import CapacityExceeded, ClientDisconnected, Deadline, ExecutionBoundary


_RAW_URL = re.compile(r"https?://|www\.", re.IGNORECASE | re.ASCII)
_RAW_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", re.ASCII)
_RAW_PHONE = re.compile(
    r"(?<![0-9])(?:\+82[- .]?)?0?1[016789][- .]?[0-9]{3,4}[- .]?[0-9]{4}(?![0-9])",
    re.ASCII,
)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Manifest(StrictModel):
    releaseId: str
    surveyVersion: str = Field(min_length=1, max_length=100)
    wordingVersion: str = Field(min_length=1, max_length=100)
    scoringVersion: str = Field(min_length=1, max_length=100)
    typeModelVersion: str = Field(min_length=1, max_length=100)
    analysisVersion: str = Field(min_length=1, max_length=100)
    typeReferenceVersion: str = Field(min_length=1, max_length=100)
    matchingVersion: str = Field(min_length=1, max_length=100)
    productPolicyVersion: str = Field(min_length=1, max_length=100)
    engineVersion: str = Field(min_length=1, max_length=100)
    contentHash: str = Field(pattern=r"^[0-9a-f]{64}$")

    @field_validator("releaseId")
    @classmethod
    def canonical_uuid(cls, value):
        if str(UUID(value)) != value:
            raise ValueError("Invalid identifier")
        return value

    @field_validator("*")
    @classmethod
    def not_blank(cls, value):
        if not value.strip():
            raise ValueError("Empty version")
        return value


class Message(StrictModel):
    sequence: int = Field(gt=0, le=9223372036854775807)
    speaker: Literal["A", "B"]
    text: str = Field(min_length=1, max_length=4000)
    fresh: bool
    urlMasked: bool

    @field_validator("text")
    @classmethod
    def masked_text_only(cls, value):
        # Representative identifiers only: this is not a guarantee that every
        # kind of personal information can be detected from arbitrary prose.
        if any((ord(character) < 32 and character not in "\n\t")
               or 0x7F <= ord(character) <= 0x9F
               or 0xD800 <= ord(character) <= 0xDFFF for character in value):
            raise ValueError("Invalid message characters")
        if _RAW_URL.search(value) or _RAW_EMAIL.search(value) or _RAW_PHONE.search(value):
            raise ValueError("Unmasked message input")
        return value


class Payload(StrictModel):
    attemptId: str
    contextId: int = Field(gt=0, le=9223372036854775807)
    manifest: Manifest
    contextBundle: list[Message] = Field(min_length=1, max_length=10)

    @field_validator("attemptId")
    @classmethod
    def canonical_uuid(cls, value):
        if str(UUID(value)) != value:
            raise ValueError("Invalid identifier")
        return value


class Finding(StrictModel):
    sequence: int = Field(gt=0, le=9223372036854775807)
    category: Literal["FRAUD", "HARASSMENT", "THREAT", "OTHER"]
    noticeType: Literal["MONEY_REQUEST", "PERSONAL_INFO", "OFF_PLATFORM", "HARASSMENT"]
    audience: Literal["SENDER", "RECIPIENT", "BOTH"]


class Verdict(StrictModel):
    kind: Literal["NORMAL", "RISK", "INSUFFICIENT", "FAILED"]
    findings: list[Finding] = Field(max_length=40)
    confidence: float | None = Field(ge=0, le=1, allow_inf_nan=False)
    modelVersion: str = Field(min_length=1, max_length=100)
    promptVersion: str = Field(min_length=1, max_length=100)

    @field_validator("modelVersion", "promptVersion")
    @classmethod
    def nonblank_version(cls, value):
        if not value.strip():
            raise ValueError("Empty version")
        return value


@dataclass(frozen=True)
class RuleFallbackResult:
    """Internal origin marker: a rule verdict is not a primary Shadow sample."""

    verdict: dict = field(repr=False)


class AsyncRiskEngine(Protocol):
    async def count_tokens(self, messages: list[dict], manifest: dict, *, deadline: Deadline) -> int: ...

    async def analyze(self, messages: list[dict], manifest: dict, *, deadline: Deadline) -> dict | RuleFallbackResult: ...


class RiskEngine(Protocol):
    def count_tokens(self, messages: list[dict], manifest: dict) -> int: ...

    def analyze(self, messages: list[dict], manifest: dict) -> dict: ...


@dataclass(frozen=True)
class RuntimeResources:
    """One lifespan owns these resources; a request only borrows the snapshot."""

    catalog: "ReleaseCatalog"
    engine: RiskEngine | AsyncRiskEngine | None
    execution: ExecutionBoundary


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate field")
        result[key] = value
    return result


def _json(raw: bytes):
    return json.loads(raw, object_pairs_hook=_pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid number")))


def _read_bounded(path: Path, maximum: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Unusable release file")
    # The actual read is bounded too: a file can grow after metadata checks.
    with path.open("rb") as source:
        raw = source.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError("Release file exceeds its limit")
    return raw


class ReleaseCatalog:
    def __init__(self, descriptor_file: str = ""):
        self.descriptor_file = descriptor_file

    def supports(self, manifest: Manifest) -> bool:
        try:
            if not self.descriptor_file:
                return False
            path = Path(self.descriptor_file)
            raw = _read_bounded(path, 65536)
            try:
                descriptor = _json(raw)
            except RecursionError:
                # Only malformed JSON nesting is a policy rejection. Recursion
                # failures in later validation/verification remain internal errors.
                return False
            if not isinstance(descriptor, dict) or set(descriptor) != {"manifest", "artifact"}:
                return False
            if Manifest.model_validate(descriptor["manifest"]) != manifest:
                return False
            reference = descriptor["artifact"]
            if not isinstance(reference, str) or not reference or Path(reference).is_absolute():
                return False
            if "\\" in reference or ":" in reference or any(part in {"", ".", ".."} for part in reference.split("/")):
                return False
            root = path.resolve().parent
            target = root / reference
            resolved = target.resolve()
            if not resolved.is_relative_to(root) or resolved == root:
                return False
            if any(part.is_symlink() for part in [target, *target.parents] if part != root):
                return False
            return hashlib.sha256(_read_bounded(resolved, 1048576)).hexdigest() == manifest.contentHash
        except (OSError, ValueError, TypeError, ValidationError):
            return False


def _error(status: int, code: str):
    return HTTPException(status, detail={"code": code, "message": "Risk analysis is unavailable."})


class _DisconnectedResponse(Response):
    async def __call__(self, scope, receive, send):
        # The ASGI server already reported http.disconnect; nothing can be sent.
        return


def create_router(authenticate: Callable, catalog: ReleaseCatalog | None = None,
                  engine: RiskEngine | AsyncRiskEngine | None = None, *,
                  runtime: Callable[[], RuntimeResources] | None = None) -> APIRouter:
    router = APIRouter()
    # Preserve direct router callers; app.main supplies lifespan-owned resources.
    standalone = None if runtime else RuntimeResources(
        catalog or ReleaseCatalog(), engine, ExecutionBoundary(getattr(engine, "execution_limits", None)))

    @router.post("/internal/ai/risk-detections", tags=["internal"])
    async def analyze(request: Request):
        entered = Deadline.start(0)
        authenticate(request)
        resources = runtime() if runtime else standalone
        releases, engine, execution = resources.catalog, resources.engine, resources.execution
        # Authentication and runtime lookup must not reset the request's clock.
        deadline = Deadline(entered.expires_at + execution.limits.request_seconds, entered.clock)
        start_shadow = getattr(engine, "start_shadow", None)
        # No runtime switch can turn on a fake model or an external provider.
        if engine is None:
            raise _error(422, "POLICY_UNRESOLVED")
        async def read_body():
            raw = bytearray()
            async for chunk in request.stream():
                raw.extend(chunk)
                if len(raw) > 65536:
                    raise _error(400, "INVALID_REQUEST")
            return bytes(raw)

        try:
            raw = await execution.run(read_body(), deadline=deadline)
        except ClientDisconnect:
            return _DisconnectedResponse()
        except (TimeoutError, CapacityExceeded):
            raise _error(503, "SERVICE_UNAVAILABLE") from None
        try:
            payload = Payload.model_validate(_json(bytes(raw)))
        except (ValueError, TypeError, ValidationError, RecursionError):
            raise _error(400, "INVALID_REQUEST") from None
        async def process():
            try:
                if not await execution.executor.run(releases.supports, payload.manifest, deadline=deadline):
                    raise _error(422, "POLICY_UNRESOLVED")
                messages = [message.model_dump() for message in payload.contextBundle]
                manifest = payload.manifest.model_dump()
                previous, fresh_seen = 0, False
                for message in messages:
                    if message["sequence"] <= previous or (fresh_seen and not message["fresh"]):
                        raise ValueError("Invalid order")
                    previous = message["sequence"]
                    fresh_seen |= message["fresh"]
                    if not message["text"].strip():
                        raise ValueError("Empty message")
                if not fresh_seen or sum(len(message["text"]) for message in messages) > 4000:
                    raise ValueError("Invalid batch")
                try:
                    tokens = await execution.invoke(engine.count_tokens, messages, manifest, deadline=deadline)
                except Exception:
                    raise _error(503, "SERVICE_UNAVAILABLE") from None
                if type(tokens) is not int or not 0 <= tokens <= 3000:
                    raise ValueError("Invalid token count")
            except (ValueError, TypeError, ValidationError):
                raise _error(400, "INVALID_REQUEST") from None
            try:
                outcome = await execution.invoke(engine.analyze, messages, manifest, deadline=deadline)
                used_fallback = isinstance(outcome, RuleFallbackResult)
                result = Verdict.model_validate(outcome.verdict if used_fallback else outcome)
                if used_fallback and result.kind != "RISK":
                    raise ValueError("Invalid fallback verdict")
                fresh_sequences = {m["sequence"] for m in messages if m["fresh"]}
                if (result.kind == "RISK") != bool(result.findings):
                    raise ValueError("Invalid verdict")
                unique = set()
                for finding in result.findings:
                    key = tuple(finding.model_dump().values())
                    valid = ((finding.category in {"HARASSMENT", "THREAT"}
                              and finding.noticeType == "HARASSMENT" and finding.audience == "BOTH")
                             or (finding.category == "FRAUD" and finding.noticeType == "MONEY_REQUEST"
                                 and finding.audience == "RECIPIENT")
                             or (finding.category == "OTHER" and finding.noticeType in {"PERSONAL_INFO", "OFF_PLATFORM"}
                                 and finding.audience == "RECIPIENT"))
                    if not valid or finding.sequence not in fresh_sequences or key in unique:
                        raise ValueError("Invalid finding")
                    unique.add(key)
            except Exception:
                raise _error(503, "SERVICE_UNAVAILABLE") from None
            # Keep this outside the broad engine-error handler so changed provenance
            # is a 422 policy rejection, not a successful or generic failed verdict.
            if not await execution.executor.run(releases.supports, payload.manifest, deadline=deadline):
                raise _error(422, "POLICY_UNRESOLVED")
            body = {"attemptId": payload.attemptId, "manifest": manifest, "verdict": result.model_dump()}
            if start_shadow is None or used_fallback:
                return body
            return JSONResponse(body, background=BackgroundTask(
                start_shadow, messages, manifest, result.kind, deadline=deadline))

        try:
            return await execution.until_disconnect(process(), request, deadline=deadline)
        except ClientDisconnected:
            return _DisconnectedResponse()
        except (TimeoutError, CapacityExceeded):
            raise _error(503, "SERVICE_UNAVAILABLE") from None

    return router
