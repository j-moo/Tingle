from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings loaded from environment variables or a local .env file."""

    app_env: str = "local"
    host: str = "127.0.0.1"
    port: int = 8000
    ai_service_public_keys: dict[str, str] = {}
    risk_release_descriptor_file: str = ""
    explanation_release_descriptor_files: list[str] = []

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
