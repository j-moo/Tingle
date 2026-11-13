"""BE가 검증한 직접 contextBundle과 등록 release를 받는 경계. 기본 경로는 규칙 전용이다.

인증 신원을 열람자로 쓰거나 callback·토큰 전달·신뢰 loader 재조회를 하지 않는다.
실제 현재 권한·시도·과거 사실과 최종 저장 검증은 BE가 담당한다.
"""

import asyncio
import json
import re
from collections.abc import Callable
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from .dto import DisclosureScope, Explanation, ResolvedContext
from .releases import ReleaseError, ReleaseRegistry
from .service import MatchExplanationService
from .validation import unique_json_object

TOPICS = frozenset({"contact", "planning", "activity", "experience"})
UUID_PATTERN = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
UuidText = Annotated[str, StringConstraints(strict=True, pattern=rf"^{UUID_PATTERN}$")]
VersionText = Annotated[str, StringConstraints(strict=True, min_length=1, max_length=100)]


class Manifest(BaseModel):
    """기존 11필드 계약. 등록 descriptor와 실제 artifact bytes를 대조한다."""

    model_config = ConfigDict(extra="forbid", frozen=True)
    releaseId: UuidText
    surveyVersion: VersionText
    wordingVersion: VersionText
    scoringVersion: VersionText
    typeModelVersion: VersionText
    analysisVersion: VersionText
    typeReferenceVersion: VersionText
    matchingVersion: VersionText
    productPolicyVersion: VersionText
    engineVersion: VersionText
    contentHash: Annotated[str, StringConstraints(strict=True, pattern=r"^[0-9a-f]{64}$")]


class AiInput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    attemptId: UuidText
    contextId: Annotated[int, Field(strict=True, ge=1, le=2**63 - 1)]
    manifest: Manifest
    contextBundle: dict[str, object]


class BoundaryError(Exception):
    def __init__(self, status: int = 503, code: str = "SERVICE_UNAVAILABLE") -> None:
        super().__init__(code)
        self.status = status
        self.code = code


class ContextBundle(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    context: ResolvedContext
    scope: DisclosureScope


def _array(value: object) -> list[str]:
    if not isinstance(value, list) or any(type(item) is not str for item in value):
        raise ValueError("Invalid context collection")
    if len(value) != len(set(value)):
        raise ValueError("Duplicate context collection value")
    return value


def _bundle(raw: object, context_id: int, release_id: str) -> ContextBundle:
    if not isinstance(raw, dict) or set(raw) != {"context", "scope"}:
        raise ValueError("Invalid context response")
    context, scope = raw["context"], raw["scope"]
    if not isinstance(context, dict) or set(context) != set(ResolvedContext.model_fields):
        raise ValueError("Invalid resolved context")
    if not isinstance(scope, dict) or set(scope) != set(DisclosureScope.model_fields):
        raise ValueError("Invalid disclosure scope")
    _array(context["conflicting_source_refs"])
    _array(scope["model_evidence_ids"])
    _array(scope["display_evidence_ids"])
    parsed = ContextBundle.model_validate(raw)
    actual, allowed = parsed.context, parsed.scope
    if actual.reference.encounter_id != context_id or actual.reference != allowed.reference:
        raise ValueError("Mismatched context reference")
    if (re.fullmatch(r"[0-9a-f]{64}", actual.reference.evaluation_ref) is None
            or actual.reference.version_ref != release_id):
        raise ValueError("Mismatched evaluation or release reference")
    if actual.viewer != allowed.viewer:
        raise ValueError("Mismatched viewer")
    if not all(value.strip() for value in (
        actual.reference.evaluation_ref, actual.reference.version_ref, allowed.version,
    )):
        raise ValueError("Missing context provenance")
    ids = [record.evidence.id for record in actual.evidence]
    if len(ids) != len(set(ids)) or not allowed.display_evidence_ids <= set(ids):
        raise ValueError("Mismatched evidence scope")
    if not allowed.model_evidence_ids <= allowed.display_evidence_ids:
        raise ValueError("Model scope exceeds public display scope")
    for record in actual.evidence:
        if record.reference != actual.reference or not record.source_ref.strip():
            raise ValueError("Mismatched source reference")
        if record.evidence.topic not in TOPICS:
            raise ValueError("Unapproved evidence topic")
    return parsed


class ContextResolver:
    """직접 bundle의 내부 일치성과 고정 등록 release만 검증한다.

    evaluation_ref는 BE Run.inputFingerprint이며 AI가 원문 해시를 다시 만들지 않는다.
    인증 서비스 신원은 업무 viewer와 무관하며 BE의 현재 권한 조회를 대체하지 않는다.
    """

    def __init__(self, releases: ReleaseRegistry | None = None, *, generator=None, verifier=None) -> None:
        self.releases = releases if releases is not None else ReleaseRegistry()
        # Explicit Python composition only. No request/environment chooses a provider.
        self.generator, self.verifier = generator, verifier
        # 전송 허용 목록은 공급자 활성화와 별개다. OFF여도 목록의 무결성은
        # 똑같이 검증하고, 모델 없는 서비스에서 공개 근거 fallback만 만든다.
        self.model_enabled = generator is not None and verifier is not None

    def resolve(self, payload: AiInput) -> ContextBundle:
        try:
            parsed = _bundle(payload.contextBundle, payload.contextId, payload.manifest.releaseId)
        except (ValueError, TypeError, KeyError):
            raise BoundaryError(400, "INVALID_REQUEST") from None
        try:
            self.releases.verify(payload.manifest.model_dump())
        except ReleaseError:
            raise BoundaryError() from None
        return parsed

    def explain(self, payload: AiInput, identity: object) -> Explanation:
        # identity는 create_router가 확인한 서비스 신원이며 bundle viewer로 변환하지 않는다.
        initial = self.resolve(payload)
        authorization_error: BoundaryError | None = None

        def authorize(context: ResolvedContext) -> bool:
            nonlocal authorization_error
            try:
                current = self.resolve(payload)
                return context == initial.context and current == initial
            except BoundaryError as error:
                authorization_error = error
                return False

        result = asyncio.run(MatchExplanationService(
            authorize=authorize, generator=self.generator, verifier=self.verifier,
        ).explain(
            initial.context, scope=initial.scope,
        ))
        if result.explanation is None:
            raise authorization_error or BoundaryError()
        if (result.explanation.evidenceKeys
                or (not self.model_enabled and result.explanation.source != "RULE_FALLBACK")):
            raise BoundaryError()
        return result.explanation


def _error_response(status: int, code: str, *, authentication: bool = False) -> JSONResponse:
    headers = {"Cache-Control": "no-store"}
    challenge = 'Bearer realm="tingle-ai"'
    if authentication and status in {400, 401, 403}:
        if status == 400:
            challenge += ', error="invalid_request"'
        elif status == 401 and code != "AUTH_REQUIRED":
            challenge += ', error="invalid_token"'
        elif status == 403:
            challenge += ', error="insufficient_scope", scope="ai:execute"'
        headers["WWW-Authenticate"] = challenge
    return JSONResponse(status_code=status, content={"code": code, "message": code}, headers=headers)


def _request_id(request: Request) -> str:
    values = request.headers.getlist("x-request-id")
    if len(values) == 1 and re.fullmatch(UUID_PATTERN, values[0]):
        return values[0]
    return str(uuid4())


def _check_authorization_header(request: Request) -> None:
    values = request.headers.getlist("authorization")
    if not values:
        raise BoundaryError(401, "AUTH_REQUIRED")
    if len(values) != 1 or "," in values[0] or len(re.findall(r"\bBearer\s+", values[0], re.IGNORECASE)) > 1:
        raise BoundaryError(400, "INVALID_REQUEST")
    if re.fullmatch(r"Bearer [^\s,]+", values[0], re.IGNORECASE) is None:
        raise BoundaryError(401, "ACCESS_TOKEN_INVALID")


class ExplanationRequest(Request):
    async def json(self) -> object:
        # DTO 검증 전에 중복 키가 덮어써지면 서로 다른 버전이나 공개 범위를 구분할 수 없다.
        raw = await self.body()
        try:
            return json.loads(raw, object_pairs_hook=unique_json_object)
        except json.JSONDecodeError:
            raise
        except (ValueError, RecursionError):
            # FastAPI의 기존 요청 검증 오류 경로로 보내고 원문은 담지 않는다.
            raise json.JSONDecodeError("Invalid explanation JSON", "", 0) from None


class ExplanationRoute(APIRoute):
    def get_route_handler(self):
        original = super().get_route_handler()

        async def handle(request: Request):
            try:
                response = await original(ExplanationRequest(request.scope, request.receive))
            except RequestValidationError:
                response = _error_response(400, "INVALID_REQUEST")
            response.headers["Cache-Control"] = "no-store"
            response.headers["X-Request-Id"] = _request_id(request)
            return response

        return handle


def create_router(
    resolver: ContextResolver | None = None,
    authenticate: Callable[[Request], object] | None = None,
) -> APIRouter:
    """authenticate는 공통 RS256·필수 claim·scope 검증 후 서비스 신원을 반환해야 한다.

    공통 인증 구현을 중복하지 않는다. 인증 실패 어댑터는 BoundaryError를 전달한다.
    미주입·준비 안 됨은 503이며 토큰 형식만으로 공급자·엔진을 실행하지 않는다.
    """
    router = APIRouter(route_class=ExplanationRoute)

    @router.post("/internal/ai/explanations", response_model=Explanation)
    def explain(body: AiInput, request: Request):
        try:
            _check_authorization_header(request)
            if authenticate is None:
                raise BoundaryError()
            identity = authenticate(request)
            if identity is None or isinstance(identity, bool):
                raise BoundaryError()
        except BoundaryError as error:
            return _error_response(error.status, error.code, authentication=True)
        except Exception:
            return _error_response(503, "SERVICE_UNAVAILABLE")
        try:
            if resolver is None:
                raise BoundaryError()
            return resolver.explain(body, identity)
        except BoundaryError as error:
            return _error_response(error.status, error.code)
        except Exception:
            return _error_response(503, "SERVICE_UNAVAILABLE")

    return router
