"""LLM 함수 연결 지점 (api.md 4.2).

.env 의 LLM_MODE 로 고른다. mock(기본)은 app/llm_mock.py, real 은 src/coach/llm.py (OPENAI_API_KEY 필요).
"""

import logging
import sys
from collections.abc import Callable
from dataclasses import dataclass
from functools import lru_cache

from app import llm_mock
from app.config import PROJECT_ROOT, get_settings
from app.errors import AppError

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Llm:
    classify_intent: Callable[..., str]
    counsel_reply: Callable[..., str]
    roleplay_reply: Callable[..., dict]
    polish_sentences: Callable[..., list[str]]


@lru_cache
def _real_llm() -> Llm:
    # openai 는 real 일 때만 불러온다
    src = str(PROJECT_ROOT / "src")
    if src not in sys.path:
        sys.path.insert(0, src)
    from coach import llm

    return Llm(llm.classify_intent, llm.counsel_reply, llm.roleplay_reply, llm.polish_sentences)


def get_llm() -> Llm:
    """FastAPI 의존성. 테스트에서는 dependency_overrides 로 교체"""
    if get_settings().llm_mode == "real":
        return _real_llm()
    return Llm(llm_mock.classify_intent, llm_mock.counsel_reply, llm_mock.roleplay_reply, llm_mock.polish_sentences)


def ai_unavailable() -> AppError:
    return AppError(503, "AI_UNAVAILABLE", "AI 답변 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")


def call_llm[T](name: str, fn: Callable[..., object], check: Callable[[object], bool], *args) -> T:
    """예외·형식 오류는 503. 대화 내용이 섞일 수 있어 로그에는 함수 이름과 예외 종류만"""
    try:
        result = fn(*args)
    except Exception as e:
        logger.error("LLM 함수 오류: %s %s", name, type(e).__name__)
        raise ai_unavailable() from None
    if not check(result):
        logger.error("LLM 함수 출력 형식 오류: %s", name)
        raise ai_unavailable()
    return result  # type: ignore[return-value]
