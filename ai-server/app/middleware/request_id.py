"""Request correlation and safe common error bodies without logging secrets."""

from __future__ import annotations

import re
from uuid import UUID, uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.datastructures import MutableHeaders
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send


_UUID = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\Z", re.ASCII)


def _request_id(scope: Scope) -> str:
    values = [value for name, value in scope.get("headers", []) if name.lower() == b"x-request-id"]
    if len(values) == 1:
        try:
            supplied = values[0].decode("ascii")
            if _UUID.fullmatch(supplied) is not None:
                UUID(supplied)
                return supplied
        except (ValueError, UnicodeDecodeError):
            pass
    return str(uuid4())


class RequestIdMiddleware:
    """Set correlation and no-store headers even on failures raised by a route."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        request_id = _request_id(scope)
        scope.setdefault("state", {})["request_id"] = request_id
        started = False

        async def send_response(message: Message) -> None:
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
                headers = MutableHeaders(scope=message)
                headers["Cache-Control"] = "no-store"
                headers["X-Request-Id"] = request_id
            await send(message)

        try:
            await self.app(scope, receive, send_response)
        except Exception:
            if started:
                # HTTP headers are already committed; never emit a second response.
                raise
            response = JSONResponse(
                status_code=500,
                content={"code": "INTERNAL_ERROR", "message": "서버 오류가 발생했습니다."},
            )
            await response(scope, receive, send_response)


def install_error_handlers(app: FastAPI) -> None:
    """Register common envelopes before adding domain-specific handler overrides."""

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, exception: HTTPException) -> JSONResponse:
        detail = exception.detail
        if isinstance(detail, dict) and isinstance(detail.get("code"), str):
            code = detail["code"]
        else:
            code = {
                400: "INVALID_REQUEST",
                401: "AUTH_REQUIRED",
                403: "FORBIDDEN",
                404: "RESOURCE_NOT_FOUND",
                422: "POLICY_UNRESOLVED",
                503: "SERVICE_UNAVAILABLE",
            }.get(exception.status_code, "INTERNAL_ERROR")
        # Only the known code is retained. Exception text, claims and key paths
        # must never become public response messages, including in debug tests.
        return JSONResponse(
            status_code=exception.status_code,
            content={"code": code, "message": _message(exception.status_code)},
            headers=exception.headers,
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exception: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content={"code": "INVALID_REQUEST", "message": "요청 형식이 올바르지 않습니다."},
        )


def _message(status: int) -> str:
    if status in (401, 403):
        return "내부 서비스 인증에 실패했습니다."
    if status == 400:
        return "요청 형식이 올바르지 않습니다."
    if status == 503:
        return "일시적으로 서비스를 사용할 수 없습니다."
    if status == 422:
        return "정책 확인 전에는 요청을 처리할 수 없습니다."
    return "요청을 처리할 수 없습니다."
