"""개발용 별도 entrypoint만 Match GMS를 명시적으로 주입한다."""

import httpx2 as httpx
import pytest

from app.config import Settings
from app.match_explanation import development
from app.match_explanation.gms import GmsClient


@pytest.mark.parametrize("environment", ["local", "dev", "test", "prod"])
def test_factory_is_off_by_default_even_when_a_key_exists(monkeypatch, environment):
    monkeypatch.delenv("MATCH_INSIGHT_GMS_DEV_ENABLED", raising=False)
    monkeypatch.setenv("GMS_API_KEY", "synthetic-only")
    monkeypatch.setattr(development, "Settings", lambda: Settings(_env_file=None, app_env=environment))
    def forbidden():
        pytest.fail("개발 factory도 명시적 opt-in 전에는 키를 읽으면 안 됩니다")
    monkeypatch.setattr(GmsClient, "from_environment", forbidden)
    captured = []
    monkeypatch.setattr("app.main.create_app", lambda configured, **kwargs: captured.append(kwargs) or object())
    development.create_app()
    assert captured == [{"_match_gms_client": None}]


@pytest.mark.parametrize("environment", ["local", "dev", "test"])
def test_explicit_development_factory_injects_only_match_client(monkeypatch, environment):
    monkeypatch.setenv("MATCH_INSIGHT_GMS_DEV_ENABLED", "true")
    monkeypatch.setattr(development, "Settings", lambda: Settings(_env_file=None, app_env=environment))
    client = GmsClient("synthetic-only", transport=httpx.MockTransport(lambda _: pytest.fail("기동만으로 외부 전송하면 안 됩니다")))
    monkeypatch.setattr(GmsClient, "from_environment", lambda: client)
    captured = []
    monkeypatch.setattr("app.main.create_app", lambda configured, **kwargs: captured.append(kwargs) or object())
    development.create_app()
    assert captured == [{"_match_gms_client": client}]


@pytest.mark.parametrize("environment", ["prod", "production", "unknown"])
def test_production_cannot_read_key_or_enable_gms(monkeypatch, environment):
    monkeypatch.setenv("MATCH_INSIGHT_GMS_DEV_ENABLED", "true")
    monkeypatch.setattr(development, "Settings", lambda: Settings(_env_file=None, app_env=environment))
    monkeypatch.setattr(GmsClient, "from_environment", lambda: pytest.fail("운영 환경에서는 키를 읽으면 안 됩니다"))
    with pytest.raises(RuntimeError, match="development environment"):
        development.create_app()


def test_missing_key_fails_explicit_opt_in_without_exposing_configuration(monkeypatch):
    monkeypatch.setenv("MATCH_INSIGHT_GMS_DEV_ENABLED", "true")
    monkeypatch.setattr(development, "Settings", lambda: Settings(_env_file=None, app_env="dev"))
    monkeypatch.setattr(GmsClient, "from_environment", lambda: None)
    with pytest.raises(RuntimeError, match="credentials are missing"):
        development.create_app()


@pytest.mark.parametrize("value", ["1", "yes", "TRUE", " "])
def test_malformed_opt_in_is_rejected(monkeypatch, value):
    monkeypatch.setenv("MATCH_INSIGHT_GMS_DEV_ENABLED", value)
    with pytest.raises(RuntimeError, match="Invalid Match Insight"):
        development.create_app()
