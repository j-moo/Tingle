"""Explicit development composition for Match Insight and Icebreaker GMS.

Run with ``uvicorn app.development:create_app --factory``. The default
``app.main:app`` and Safety Guard's Python-only approval boundary are unchanged.
"""

import os

from app.config import Settings
from app.match_explanation.gms import GmsClient


def _enabled(name: str) -> bool:
    value = os.environ.get(name, "false")
    if value not in {"true", "false"}:
        raise RuntimeError(f"Invalid development opt-in: {name}")
    return value == "true"


def create_app():
    from app.main import create_app as build_app

    configured = Settings()
    match_enabled = _enabled("MATCH_INSIGHT_GMS_DEV_ENABLED")
    icebreaker_enabled = _enabled("ICEBREAKER_GMS_DEV_ENABLED")
    client = None
    if match_enabled or icebreaker_enabled:
        if configured.app_env not in {"local", "test", "dev"}:
            raise RuntimeError("GMS integration requires a development environment")
        if icebreaker_enabled and configured.app_env == "dev" and not configured.icebreaker_dev_enabled:
            raise RuntimeError("Icebreaker GMS requires ICEBREAKER_DEV_ENABLED")
        client = GmsClient.from_environment()
        if client is None:
            raise RuntimeError("Development GMS credentials are missing")

    # GmsClient owns no persistent session/tasks: each request closes its own
    # AsyncClient, including on cancellation. Keep the shared Safety lifespan.
    # No Safety credentials, tokenizer, approval descriptor or source pin is
    # discovered or generated here; build_app retains its existing checks.
    return build_app(
        configured,
        _match_gms_client=client if match_enabled else None,
        _icebreaker_gms_client=client if icebreaker_enabled else None,
    )
