from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings loaded from environment variables or a local .env file."""

    app_env: str = "local"
    host: str = "127.0.0.1"
    port: int = 8000
    ai_service_public_keys: dict[str, str] = {}
    gms_api_key: SecretStr = SecretStr("")
    icebreaker_release_descriptor_file: str = ""
    risk_release_descriptor_file: str = ""
    risk_local_rules_descriptor_sha256: str = ""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
