"""Authenticate first, then serve the fixed fallback for a registered release."""

import json
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool

from app.match_explanation.releases import ReleaseError, ReleaseRegistry
from app.security.service_auth import ServiceAuthenticator

from . import templates
from .contract import IcebreakerRequest, IcebreakerResponse


_MAX_BODY_BYTES = 16 * 1024


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def _reject_constant(_: str) -> None:
    raise ValueError("Non-JSON numeric constant")


def _unavailable() -> HTTPException:
    return HTTPException(503, detail={"code": "SERVICE_UNAVAILABLE"})


async def _parse_request(request: Request) -> IcebreakerRequest:
    raw = bytearray()
    async for chunk in request.stream():
        if len(raw) + len(chunk) > _MAX_BODY_BYTES:
            raise HTTPException(400, detail={"code": "INVALID_REQUEST"})
        raw.extend(chunk)
    try:
        body = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                          parse_constant=_reject_constant)
        return IcebreakerRequest.model_validate(body)
    except (ValueError, RecursionError):
        raise HTTPException(400, detail={"code": "INVALID_REQUEST"}) from None


def create_router(authenticate: ServiceAuthenticator, descriptor_file: str = "") -> APIRouter:
    router = APIRouter()
    releases = None
    # This transport is for local synthetic verification until the integration is approved.
    if authenticate.settings.environment in {"local", "test"} and descriptor_file:
        try:
            releases = ReleaseRegistry([Path(descriptor_file)])
        except (ReleaseError, RecursionError):
            # Invalid icebreaker configuration must not disable other AI routes.
            pass

    @router.post("/internal/ai/icebreakers", tags=["internal"], response_model=IcebreakerResponse)
    async def icebreakers(request: Request) -> IcebreakerResponse:
        authenticate(request)  # Includes the shared RS256 and ai:execute checks.
        if releases is None:
            raise _unavailable()
        payload = await _parse_request(request)
        try:
            await run_in_threadpool(releases.verify, payload.manifest.model_dump())
            return IcebreakerResponse(
                questions=list(templates.GENERAL),
                source="GENERAL_FALLBACK",
                promptVersion=templates.GENERAL_VERSION,
            )
        except (ReleaseError, ValidationError):
            raise _unavailable() from None

    return router
