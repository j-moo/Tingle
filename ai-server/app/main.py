from fastapi import FastAPI

from app.config import settings
from app.match_explanation.runtime import create_runtime_router
from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.risk_detection.http_api import ReleaseCatalog, create_router
from app.security.service_auth import ServiceAuthenticator, ServiceAuthSettings

app = FastAPI(
    title="Suddenly I See AI Server",
    version="0.1.0",
)
install_error_handlers(app)
app.add_middleware(RequestIdMiddleware)
service_auth = ServiceAuthenticator(ServiceAuthSettings(
    environment=settings.app_env, public_keys=settings.ai_service_public_keys,
))
if settings.ai_service_public_keys and not service_auth.is_ready:
    raise RuntimeError("Invalid service authentication configuration") from None
app.state.service_auth = service_auth
app.include_router(create_router(
    authenticate=service_auth,
    catalog=ReleaseCatalog(settings.risk_release_descriptor_file),
))
app.include_router(create_runtime_router(
    service_auth, settings.explanation_release_descriptor_files,
))


@app.get("/health", tags=["health"])
def health_check() -> dict[str, str]:
    """Return a small response that can be used to check server availability."""
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn

    from app.config import settings

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.app_env == "local",
    )
