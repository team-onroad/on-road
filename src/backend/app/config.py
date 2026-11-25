from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import field_validator
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

    @field_validator("database_url", "test_database_url")
    @classmethod
    def _psycopg_driver(cls, v: str | None) -> str | None:
        """Render · Heroku 처럼 postgres:// 나 postgresql:// 로 주는 주소를 psycopg 3 드라이버 주소로"""
        if v and v.startswith(("postgres://", "postgresql://")):
            return "postgresql+psycopg://" + v.split("://", 1)[1]
        return v
    timezone: str = "Asia/Seoul"
    policies_json_path: Path = PROJECT_ROOT / "data" / "policies" / "policies.json"
    simulation_criteria_path: Path = PROJECT_ROOT / "docs" / "simulation-criteria.json"
    # AI 담당 결과물: 쉬운 말 단계별 변환(src/easytext/), 신청서 작성 항목(src/formguide/)
    easy_text_dir: Path = PROJECT_ROOT / "data" / "easy_text"
    forms_dir: Path = PROJECT_ROOT / "data" / "forms"
    # 정책 핵심 사실·카드 요약 (src/easytext/facts.py)
    policy_facts_dir: Path = PROJECT_ROOT / "data" / "policy_facts"
    apply_guide_path: Path = PROJECT_ROOT / "data" / "apply_guide.json"
    # mock: app/rag_mock.py (기본), real: src/rag/ 실제 RAG (OPENAI_API_KEY·Chroma 인덱스 필요)
    rag_mode: Literal["mock", "real"] = "mock"
    # mock: app/llm_mock.py (기본), real: src/coach/ 실제 AI 상담·롤플레잉 (OPENAI_API_KEY 필요)
    llm_mode: Literal["mock", "real"] = "mock"
    # 서류 내용 암호화 키 (app/sealing.py). 없으면 평문 저장. 쉼표로 여러 개 = 키 교체
    doc_secret_key: str | None = None
    # 오래된 서류의 개인정보 칸 비우기 (services/writer.py clear_stale)
    doc_keep_exported_days: int = 90  # 내보낸 뒤
    doc_keep_writing_days: int = 180  # 쓰다가 손대지 않은 채로
    # 진로 외부 정보 (app/services/external.py). 키가 없으면 해당 항목은 available=false 로 응답
    qnet_service_key: str | None = None  # 공공데이터포털 큐넷 국가자격 시험일정
    careernet_api_key: str | None = None  # 커리어넷 OpenAPI 직업백과·학과정보
    work24_training_key: str | None = None  # 고용24 국민내일배움카드 훈련과정
    work24_jobs_key: str | None = None  # 고용24 채용정보
    external_timeout_seconds: float = 8.0


@lru_cache
def get_settings() -> Settings:
    return Settings()
