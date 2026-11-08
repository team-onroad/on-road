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
    # 정책 핵심 사실·카드 요약 (src/easytext/facts.py)
    policy_facts_dir: Path = PROJECT_ROOT / "data" / "policy_facts"
    # mock: app/rag_mock.py (기본), real: src/rag/ 실제 RAG (OPENAI_API_KEY·Chroma 인덱스 필요)
    rag_mode: Literal["mock", "real"] = "mock"
    # mock: app/llm_mock.py (기본), real: src/coach/ 실제 AI 상담·롤플레잉 (OPENAI_API_KEY 필요)
    llm_mode: Literal["mock", "real"] = "mock"
    # 서류 내용 암호화 키 (app/sealing.py). 없으면 평문 저장. 쉼표로 여러 개 = 키 교체
    doc_secret_key: str | None = None
    # 오래된 서류의 개인정보 칸 비우기 (services/writer.py clear_stale)
    doc_keep_exported_days: int = 90  # 내보낸 뒤
    doc_keep_writing_days: int = 180  # 쓰다가 손대지 않은 채로


@lru_cache
def get_settings() -> Settings:
    return Settings()
