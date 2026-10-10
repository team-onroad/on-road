"""LLM 함수 연결 지점 (api.md 4.2). 실제 함수로 바꿀 때 import 한 줄만 수정"""

import logging
from collections.abc import Callable
from dataclasses import dataclass

from app.errors import AppError
from app.llm_mock import classify_intent, counsel_reply, polish_sentences, roleplay_reply  # ← 교체 지점

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Llm:
    classify_intent: Callable[..., str]
    counsel_reply: Callable[..., str]
    roleplay_reply: Callable[..., dict]
    polish_sentences: Callable[..., list[str]]


def get_llm() -> Llm:
    """FastAPI 의존성. 테스트에서는 dependency_overrides 로 교체"""
    return Llm(classify_intent, counsel_reply, roleplay_reply, polish_sentences)


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
