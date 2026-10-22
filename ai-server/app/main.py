from fastapi import FastAPI

app = FastAPI(
    title="Suddenly I See AI Server",
    version="0.1.0",
)


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
