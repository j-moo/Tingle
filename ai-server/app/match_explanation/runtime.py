"""공통 서비스 인증과 고정 등록 파일만 연결하는 규칙 설명 실행 접점."""

from collections.abc import Callable, Iterable
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request

from .http_api import BoundaryError, ContextResolver, create_router
from .releases import ReleaseRegistry


_AUTH_ERRORS = {
    400: frozenset({"INVALID_REQUEST"}),
    401: frozenset({"AUTH_REQUIRED", "ACCESS_TOKEN_INVALID", "ACCESS_TOKEN_EXPIRED"}),
    403: frozenset({"FORBIDDEN"}),
    503: frozenset({"SERVICE_UNAVAILABLE"}),
}


def create_runtime_router(
    authenticate: Callable[[Request], object],
    descriptor_files: Iterable[str | Path] = (),
) -> APIRouter:
    """인증·HTTP·재시도를 복제하지 않고 85의 검증 결과와 공개 오류 코드만 전달한다."""
    def authorize(request: Request) -> object:
        try:
            return authenticate(request)
        except HTTPException as error:
            detail = error.detail
            code = detail.get("code") if isinstance(detail, dict) else None
            if isinstance(code, str) and code in _AUTH_ERRORS.get(error.status_code, ()):
                raise BoundaryError(error.status_code, code) from None
            raise BoundaryError() from None

    return create_router(ContextResolver(ReleaseRegistry(descriptor_files)), authorize)
