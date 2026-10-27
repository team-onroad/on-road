"""문서 AI LLM 함수 연결 지점 (라우터·칸 작성 도우미).

.env 의 LLM_MODE 로 고른다. mock(기본)은 app/llm_mock.py, real 은 src/docwriter/llm.py (OPENAI_API_KEY 필요).
예외·형식 오류 처리는 app/llm_client.call_llm 을 같이 쓴다 (503 AI_UNAVAILABLE).
"""

import sys
from collections.abc import Callable
from dataclasses import dataclass
from functools import lru_cache

from app import llm_mock
from app.config import PROJECT_ROOT, get_settings


@dataclass(frozen=True)
class DocLlm:
    route_message: Callable[..., dict]
    assist_field: Callable[..., dict]


@lru_cache
def _real_doc_llm() -> DocLlm:
    src = str(PROJECT_ROOT / "src")
    if src not in sys.path:
        sys.path.insert(0, src)
    from docwriter import llm

    return DocLlm(llm.route_message, llm.assist_field)


def get_doc_llm() -> DocLlm:
    """FastAPI 의존성. 테스트에서는 dependency_overrides 로 교체"""
    if get_settings().llm_mode == "real":
        return _real_doc_llm()
    return DocLlm(llm_mock.route_message, llm_mock.assist_field)
