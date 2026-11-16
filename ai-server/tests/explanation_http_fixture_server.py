"""Loopback-only explanation fixture; uses the production rule/auth adapters."""

import argparse
from pathlib import Path

import uvicorn
from fastapi import FastAPI

from app.match_explanation.runtime import create_runtime_router
from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.security.service_auth import ServiceAuthenticator, ServiceAuthSettings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--descriptor", type=Path, help="Legacy caller compatibility; unused")
    arguments = parser.parse_args()
    app = FastAPI()
    install_error_handlers(app)
    app.add_middleware(RequestIdMiddleware)
    auth = ServiceAuthenticator(ServiceAuthSettings(
        environment="test", public_keys={"synthetic-key": arguments.public_key},
    ))
    app.include_router(create_runtime_router(auth))

    @app.get("/health")
    def health():
        return {"status": "ok"}

    uvicorn.run(app, host="127.0.0.1", port=arguments.port, log_level="error", access_log=False)


if __name__ == "__main__":
    main()
