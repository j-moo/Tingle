"""Loopback-only Java/Python smoke fixture. This module is never imported by app.main."""

import argparse
from pathlib import Path

import uvicorn
from fastapi import FastAPI

from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.risk_detection.http_api import ReleaseCatalog, create_router
from app.security.service_auth import ServiceAuthenticator, ServiceAuthSettings


class SyntheticEngine:
    def count_tokens(self, messages, manifest):
        if not all(message["text"].startswith("가상 대화:") for message in messages):
            raise ValueError("Only the synthetic fixture is supported")
        return len(messages)

    def analyze(self, messages, manifest):
        findings = [{"sequence": message["sequence"], "category": "FRAUD",
                     "noticeType": "MONEY_REQUEST", "audience": "RECIPIENT"}
                    for message in messages if message["fresh"] and "송금" in message["text"]]
        return {"kind": "RISK" if findings else "NORMAL", "findings": findings, "confidence": 0.9,
                "modelVersion": "synthetic-model-v1", "promptVersion": "synthetic-prompt-v1"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--descriptor", type=Path, required=True)
    arguments = parser.parse_args()
    app = FastAPI()
    install_error_handlers(app)
    app.add_middleware(RequestIdMiddleware)
    auth = ServiceAuthenticator(ServiceAuthSettings(
        environment="test", public_keys={"synthetic-key": arguments.public_key},
    ))
    app.include_router(create_router(auth, ReleaseCatalog(str(arguments.descriptor)), SyntheticEngine()))

    @app.get("/health")
    def health():
        return {"status": "ok"}

    uvicorn.run(app, host="127.0.0.1", port=arguments.port, log_level="error", access_log=False)


if __name__ == "__main__":
    main()
