from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_DIR = Path(__file__).resolve().parents[1]
PROJECT_ROOT = BACKEND_DIR.parents[1]


class Settings(BaseSettings):
    """환경변수 설정. 프로젝트 루트의 .env 를 읽는다 (실제 값은 .env 에만 둔다)."""

    model_config = SettingsConfigDict(
        env_file=PROJECT_ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    database_url: str | None = None
    test_database_url: str | None = None
    timezone: str = "Asia/Seoul"
    policies_json_path: Path = PROJECT_ROOT / "data" / "policies" / "policies.json"


@lru_cache
def get_settings() -> Settings:
    return Settings()
