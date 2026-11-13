"""개발자가 별도 선택하는 Match GMS factory. app.main:app은 계속 기본 OFF다."""

import os

from app.config import Settings

from .gms import GmsClient


def create_app():
    from app.main import create_app as build_app

    configured = Settings()
    enabled = os.environ.get("MATCH_INSIGHT_GMS_DEV_ENABLED", "false")
    if enabled not in {"true", "false"}:
        raise RuntimeError("Invalid Match Insight development opt-in")
    client = None
    if enabled == "true":
        if configured.app_env not in {"local", "test", "dev"}:
            raise RuntimeError("Match Insight GMS requires a development environment")
        client = GmsClient.from_environment()
        if client is None:
            raise RuntimeError("Match Insight development GMS credentials are missing")
    return build_app(configured, _match_gms_client=client)
