"""RAG 환경변수 설정. 백엔드와 같은 프로젝트 루트의 .env 를 읽는다 (실제 키는 .env 에만 둔다)."""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

RAG_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = RAG_DIR.parents[1]


class RagSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=PROJECT_ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    openai_api_key: str | None = None
    # 임베딩·답변 모델 (프로젝트 기획서: text-embedding-3-small, GPT-4o-mini)
    rag_embedding_model: str = "text-embedding-3-small"
    rag_chat_model: str = "gpt-4o-mini"
    # Chroma 저장 위치 (커밋하지 않음, python -m rag.index 로 다시 만들 수 있음)
    rag_chroma_dir: Path = PROJECT_ROOT / "data" / "chroma"
    rag_collection: str = "policy_chunks"
    # 코사인 거리 상한. 이보다 먼 청크는 근거로 쓰지 않는다 (평가셋으로 조정 필요)
    rag_max_distance: float = 0.65
    # 질문 단어가 그대로 들어 있는 청크는 이 거리까지 근거로 인정 (하이브리드 검색: "청년"처럼 짧은 질문 대비)
    rag_keyword_max_distance: float = 0.8
    # 쉬운 말 변환 (src/easytext/). 2차 의미 검수는 변환 모델과 다른 모델로
    easytext_model: str = "gpt-4o-mini"
    easytext_judge_model: str = "gpt-4o"


@lru_cache
def get_rag_settings() -> RagSettings:
    return RagSettings()
