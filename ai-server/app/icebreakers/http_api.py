"""Authenticate first, then serve fixed questions for a registered release."""

import json
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool

from app.match_explanation.releases import ReleaseError, ReleaseRegistry
from app.security.service_auth import ServiceAuthenticator

from . import templates
from .contract import CommonTopicKey, IcebreakerRequest, IcebreakerResponse


_MAX_BODY_BYTES = 16 * 1024
_DATE_TOPIC_KEYS: dict[CommonTopicKey, str] = {
    "rest": "DATE_REST",
    "talk": "DATE_TALK",
    "culture": "DATE_CULTURE",
    "walk": "DATE_WALK",
    "active": "DATE_ACTIVE",
}


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


def _select_questions(topic_key: CommonTopicKey | None) -> IcebreakerResponse:
    if topic_key is not None:
        # BE supplies the selected code after checking survey and consent eligibility.
        questions = templates.suggest_date_questions(_DATE_TOPIC_KEYS[topic_key])
        if questions != templates.GENERAL:
            try:
                return IcebreakerResponse(
                    questions=list(questions),
                    source="COMMON_TOPIC_TEMPLATE",
                    promptVersion=templates.Q040_VERSION,
                )
            except ValidationError:
                # Apply the BE question policy even after the template's own validation.
                pass
    return IcebreakerResponse(
        questions=list(templates.GENERAL),
        source="GENERAL_FALLBACK",
        promptVersion=templates.GENERAL_VERSION,
    )


def create_router(
    authenticate: ServiceAuthenticator, descriptor_file: str = "", *, dev_enabled: bool = False,
) -> APIRouter:
    router = APIRouter()
    releases = None
    environment = authenticate.settings.environment
    enabled = environment in {"local", "test"} or (environment == "dev" and dev_enabled)
    if enabled and descriptor_file:
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
            return _select_questions(payload.commonTopicKey)
        except (ReleaseError, ValidationError):
            raise _unavailable() from None

    return router
