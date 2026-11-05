"""Default-OFF Safety Guard registration for local/test/dev environments only."""

from collections.abc import Callable

from fastapi import APIRouter

from app.risk_detection.http_api import ReleaseCatalog, create_router


def create_development_router(authenticate: Callable, *, environment: str, enabled: bool = False,
                              descriptor_file: str = "", descriptor_sha256: str = "") -> APIRouter:
    # Check before importing rule data, reading releases or loading optional
    # tokenizers. Production ignores opt-in and retains engine=None.
    if enabled is not True or environment not in {"local", "test", "dev"}:
        return create_router(authenticate, ReleaseCatalog(descriptor_file))

    from app.risk_detection.local_engine import LocalRuleRiskEngine
    from app.risk_detection.releases import DevelopmentReleaseCatalog

    catalog = DevelopmentReleaseCatalog(descriptor_file, descriptor_sha256=descriptor_sha256)
    return create_router(authenticate, catalog, LocalRuleRiskEngine(count_input_tokens=catalog.tokenizer.count))
