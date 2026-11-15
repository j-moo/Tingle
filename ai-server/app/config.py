from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings loaded from environment variables or a local .env file."""

    app_env: str = "local"
    host: str = "127.0.0.1"
    port: int = 8000
    ai_service_public_keys: dict[str, str] = {}
    icebreaker_release_descriptor_file: str = ""
    icebreaker_dev_enabled: bool = False
    risk_release_descriptor_file: str = ""
    risk_local_rules_enabled: bool = False
    risk_local_rules_descriptor_sha256: str = ""
    explanation_release_descriptor_files: list[str] = []
    # Reuse the current presentation candidate only after operational review.
    personal_analysis_prod_enabled: bool = False

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
