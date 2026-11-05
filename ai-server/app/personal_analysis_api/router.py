"""Private adapter: authenticate before parsing sensitive request bodies."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from inspect import isawaitable

from fastapi import APIRouter, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from starlette.exceptions import HTTPException

from app.security.service_auth import ServiceAuthError
from app.middleware.request_id import request_id_from_headers

from app.personal_analysis.service import CalculationFailure
from app.personal_analysis.validation import AnalysisInputError

from .adapter import PersonalAnalysisAdapter, PresentationUnavailable, VersionUnsupported
from .dto import ErrorResponse, PersonalAnalysisRequest, PersonalAnalysisResponse

MESSAGES = {
    "AUTH_REQUIRED": "내부 서비스 인증이 필요합니다.",
    "FORBIDDEN": "내부 서비스 접근 권한이 없습니다.",
    "INVALID_REQUEST": "개인 분석 요청 형식이나 답변이 올바르지 않습니다.",
    "ANALYSIS_VERSION_UNSUPPORTED": "지원하지 않는 설문·분석 버전 조합입니다.",
    "SERVICE_UNAVAILABLE": "개인 분석 서비스의 계약 또는 인증 설정을 확인해야 합니다.",
    "INTERNAL_ERROR": "개인 분석 처리 중 오류가 발생했습니다.",
}

# Defensive transport ceiling candidate, not a new DTO/version contract.
# Largest canonical 100-answer JSON: 4,650 B; pretty JSON: 7,970 B.
# 16 KiB leaves >2x the latter. See scripts/measure_personal_body.py and README.
PERSONAL_ANALYSIS_BODY_LIMIT = 16 * 1024


def limited_body_request(request: Request) -> Request:
    """Bound each receive before FastAPI accumulates it; called only after auth."""
    for length in request.headers.getlist("content-length"):
        try:
            declared = int(length)
        except ValueError:
            raise HTTPException(status_code=400) from None
        if declared < 0 or declared > PERSONAL_ANALYSIS_BODY_LIMIT:
            raise HTTPException(status_code=400)

    received = 0

    async def receive():
        nonlocal received
        message = await request.receive()
        if message["type"] == "http.request":
            received += len(message.get("body", b""))
            if received > PERSONAL_ANALYSIS_BODY_LIMIT:
                # Never pass the overflowing chunk to the body parser.
                raise HTTPException(status_code=400)
        return message

    # Share scope/state (including request ID), without mutating Request internals.
    return Request(request.scope, receive=receive)


class InternalAPIError(Exception):
    """Existing service verifiers may distinguish 401 from 403 using fixed codes."""

    def __init__(self, status_code: int, code: str) -> None:
        if (status_code, code) not in ((401, "AUTH_REQUIRED"), (403, "FORBIDDEN")):
            raise ValueError("Unsupported authentication error")
        self.status_code, self.code = status_code, code
        super().__init__(code)


@dataclass(frozen=True)
class PersonalAnalysisRuntime:
    # Must return True after real authentication; None/configuration errors deny access.
    authorize: Callable[[Request], bool | Awaitable[bool]] | None = None
    adapter: PersonalAnalysisAdapter = field(default_factory=PersonalAnalysisAdapter)


def get_runtime(request: Request) -> PersonalAnalysisRuntime:
    return getattr(request.app.state, "personal_analysis_runtime", PersonalAnalysisRuntime())


def error_response(status: int, code: str) -> JSONResponse:
    if code in {"AUTH_REQUIRED", "FORBIDDEN"}:
        return ServiceAuthError(code).response()
    return JSONResponse(
        status_code=status, content={"code": code, "message": MESSAGES[code]},
        headers={"Cache-Control": "no-store"},
    )


def receive_request_id(request: Request) -> None:
    """Keep outer middleware's ID, or establish one for an explicit test host."""
    if not getattr(request.state, "request_id", None):
        request.state.request_id = request_id_from_headers(request.headers)


class PersonalAnalysisRoute(APIRoute):
    def get_route_handler(self):
        original_handler = super().get_route_handler()

        async def handler(request: Request) -> Response:
            receive_request_id(request)
            response = await authenticated_handler(request)
            response.headers["X-Request-Id"] = request.state.request_id
            response.headers["Cache-Control"] = "no-store"
            return response

        async def authenticated_handler(request: Request) -> Response:
            # Authenticate before FastAPI parses/validates the sensitive body.
            runtime = get_runtime(request)
            if runtime.authorize is None:
                return error_response(503, "SERVICE_UNAVAILABLE")
            try:
                authorized = runtime.authorize(request)
                if isawaitable(authorized):
                    authorized = await authorized
                if authorized is not True:
                    return error_response(401, "AUTH_REQUIRED")
            except ServiceAuthError as error:
                return error.response()
            except InternalAPIError as error:
                return error_response(error.status_code, error.code)
            except Exception:
                return error_response(503, "SERVICE_UNAVAILABLE")
            try:
                response = await original_handler(limited_body_request(request))
            except RequestValidationError as error:
                # Never return errors(): it can contain input values and the body.
                status = 400 if any(e["type"] == "json_invalid" for e in error.errors()) else 422
                return error_response(status, "INVALID_REQUEST")
            except HTTPException as error:
                if error.status_code == 400:
                    return error_response(400, "INVALID_REQUEST")
                if error.status_code == 503:
                    return error_response(503, "SERVICE_UNAVAILABLE")
                # Unexpected framework/dependency errors are not client JSON errors.
                return error_response(500, "INTERNAL_ERROR")
            except VersionUnsupported:
                return error_response(409, "ANALYSIS_VERSION_UNSUPPORTED")
            except AnalysisInputError as error:
                if error.code in ("UNSUPPORTED_SURVEY_VERSION", "UNSUPPORTED_CALCULATION_VERSION"):
                    return error_response(409, "ANALYSIS_VERSION_UNSUPPORTED")
                return error_response(422, "INVALID_REQUEST")
            except PresentationUnavailable:
                return error_response(503, "SERVICE_UNAVAILABLE")
            except CalculationFailure:
                return error_response(500, "INTERNAL_ERROR")
            except Exception:
                # No exception text, body, token or answers are logged/returned.
                return error_response(500, "INTERNAL_ERROR")
            response.headers["Cache-Control"] = "no-store"
            return response

        return handler


router = APIRouter(route_class=PersonalAnalysisRoute)


@router.post(
    "/internal/ai/personal-analyses", response_model=PersonalAnalysisResponse,
    tags=["internal-personal-analysis"],
    description="BE internal calculation. Requires a configured service verifier and approved version/presentation contract.",
    responses={status: {"model": ErrorResponse} for status in (400, 401, 403, 409, 422, 500, 503)},
)
def personal_analysis(payload: PersonalAnalysisRequest, request: Request) -> PersonalAnalysisResponse:
    request.state.analysis_id = payload.analysisId
    return get_runtime(request).adapter.analyze(payload)
