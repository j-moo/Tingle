from collections.abc import Callable
from contextlib import asynccontextmanager
from typing import TYPE_CHECKING

from fastapi import FastAPI, HTTPException, Request

from app.config import Settings, settings
from app.icebreakers.http_api import create_router as create_icebreaker_router
from app.match_explanation.runtime import create_runtime_router
from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.risk_detection.runtime import DevelopmentRuntime, create_development_router
from app.security.service_auth import ServiceAuthenticator, ServiceAuthError, ServiceAuthSettings

if TYPE_CHECKING:
    from app.risk_detection.gms_runtime import GmsPrimaryConfiguration

def create_app(app_settings: Settings | None = None, *, _risk_engine_factory: Callable | None = None,
               _risk_gms_primary: "GmsPrimaryConfiguration | None" = None,
               _match_gms_client=None, _icebreaker_gms_client=None) -> FastAPI:
    """Build the real application with explicit Python-only dependency injection.

    No setting or HTTP input selects a model. The default application keeps
    Safety Guard OFF. Development opt-in uses local rules unless a separately
    pinned GMS approval and an explicit internal configuration are supplied.
    """
    configured = app_settings if app_settings is not None else settings
    risk_runtime = DevelopmentRuntime(
        environment=configured.app_env, enabled=configured.risk_local_rules_enabled,
        descriptor_file=configured.risk_release_descriptor_file,
        descriptor_sha256=configured.risk_local_rules_descriptor_sha256,
        engine_factory=_risk_engine_factory,
        gms_primary=_risk_gms_primary,
    )

    @asynccontextmanager
    async def lifespan(application):
        await risk_runtime.startup()
        try:
            yield
        finally:
            await risk_runtime.aclose()

    application = FastAPI(title="Suddenly I See AI Server", version="0.1.0", lifespan=lifespan)
    install_error_handlers(application)
    application.add_middleware(RequestIdMiddleware)
    service_auth = ServiceAuthenticator(ServiceAuthSettings(
        environment=configured.app_env, public_keys=configured.ai_service_public_keys,
    ))
    if configured.ai_service_public_keys and not service_auth.is_ready:
        raise RuntimeError("Invalid service authentication configuration") from None
    application.state.service_auth = service_auth
    application.state.risk_runtime = risk_runtime
    application.include_router(create_icebreaker_router(
        service_auth, configured.icebreaker_release_descriptor_file, dev_enabled=configured.icebreaker_dev_enabled,
        gms_client=_icebreaker_gms_client if configured.app_env in {"local", "test", "dev"} else None,
    ))
    application.include_router(create_development_router(
        authenticate=service_auth, environment=configured.app_env, runtime=risk_runtime,
    ))
    application.include_router(create_runtime_router(
        service_auth, configured.explanation_release_descriptor_files,
        gms_client=_match_gms_client if configured.app_env in {"local", "test", "dev"} else None,
    ))

    def authorize_personal_analysis(request: Request) -> bool:
        # The personal route expects exact True and ServiceAuthError, not claims/HTTPException.
        try:
            service_auth(request)
        except HTTPException as error:
            raise ServiceAuthError(error.detail["code"]) from None
        return True

    from app.personal_analysis_api.adapter import PersonalAnalysisAdapter
    from app.personal_analysis_api.proposals import proposed_label_only_policy
    from app.personal_analysis_api.router import PersonalAnalysisRuntime, router as personal_analysis_router

    # The reviewed v2 wording is shared by every environment. Authentication,
    # version/reference integrity and per-request evidence validation stay intact.
    application.state.personal_analysis_runtime = PersonalAnalysisRuntime(
        authorize=authorize_personal_analysis,
        adapter=PersonalAnalysisAdapter(presentation=proposed_label_only_policy()),
    )
    application.include_router(personal_analysis_router)

    application.get("/health", tags=["health"])(health_check)
    return application


def health_check() -> dict[str, str]:
    """Return a small response that can be used to check server availability."""
    return {"status": "ok"}


app = create_app()


if __name__ == "__main__":
    import uvicorn

    from app.config import settings

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.app_env == "local",
    )
