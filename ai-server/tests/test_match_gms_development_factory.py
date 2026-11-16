"""Legacy entrypoints delegate; required keys also work through Settings/.env."""

import pytest

from app import development
from app.config import Settings
from app.main import create_app
from app.match_explanation import development as legacy_match


def test_legacy_match_factory_is_the_common_compatibility_factory():
    assert legacy_match.create_app is development.create_app


def test_compatibility_factory_delegates_once_with_fresh_settings(monkeypatch):
    configured = Settings(_env_file=None, app_env="prod", gms_api_key="synthetic-only")
    monkeypatch.setattr(development, "Settings", lambda: configured)
    calls = []
    sentinel = object()
    monkeypatch.setattr("app.main.create_app", lambda value: calls.append(value) or sentinel)
    assert development.create_app() is sentinel
    assert calls == [configured]


def test_key_is_redacted_and_loads_from_dotenv(tmp_path, monkeypatch):
    monkeypatch.delenv("GMS_API_KEY", raising=False)
    example = tmp_path / ".env"
    example.write_text("GMS_API_KEY=synthetic-private-marker\n", encoding="utf-8")
    configured = Settings(_env_file=example)
    assert "synthetic-private-marker" not in repr(configured)
    assert create_app(configured) is not None


@pytest.mark.parametrize("environment", ["dev", "prod"])
@pytest.mark.parametrize("value", ["", " "])
def test_dependency_injection_cannot_bypass_required_startup_key(environment, value):
    configured = Settings(_env_file=None, app_env=environment, gms_api_key=value)
    with pytest.raises(RuntimeError, match="GMS_API_KEY"):
        create_app(configured, _match_gms_client=object(), _icebreaker_gms_client=object())
