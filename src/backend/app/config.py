from functools import lru_cache
from pathlib import Path
from typing import Literal

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
    simulation_criteria_path: Path = PROJECT_ROOT / "docs" / "simulation-criteria.json"
    # AI 담당 결과물: 쉬운 말 단계별 변환(src/easytext/), 신청서 작성 항목(src/formguide/)
    easy_text_dir: Path = PROJECT_ROOT / "data" / "easy_text"
    forms_dir: Path = PROJECT_ROOT / "data" / "forms"
    # mock: app/rag_mock.py (기본), real: src/rag/ 실제 RAG (OPENAI_API_KEY·Chroma 인덱스 필요)
    rag_mode: Literal["mock", "real"] = "mock"


@lru_cache
def get_settings() -> Settings:
    return Settings()
