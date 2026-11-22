"""LLM 함수 연결 지점 (api.md 4.2).

.env 의 LLM_MODE 로 고른다. mock(기본)은 app/llm_mock.py, real 은 src/coach/llm.py (OPENAI_API_KEY 필요).
"""

import logging
import sys
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from functools import lru_cache

from app import llm_mock
from app.config import PROJECT_ROOT, get_settings
from app.errors import AppError
from app.pii import masked

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Llm:
    classify_intent: Callable[..., str]
    counsel_reply: Callable[..., str]
    roleplay_reply: Callable[..., dict]
    polish_sentences: Callable[..., list[str]]
    # 롤플레잉 '챙길 것' 뜻 판정 (낱말 규칙에 안 걸린 항목만). 없으면 낱말 규칙만
    judge_checks: Callable[..., list[str]] = llm_mock.judge_checks
    # 스트리밍: {"delta": 문장} … {"final": …} 를 차례로 내는 함수
    # 없으면 보통 답변(counsel_reply · roleplay_reply)을 한 번에 보낸다
    counsel_stream: Callable[..., Iterator[dict]] | None = None
    roleplay_stream: Callable[..., Iterator[dict]] | None = None


@lru_cache
def _real_llm() -> Llm:
    # openai 는 real 일 때만 불러온다
    src = str(PROJECT_ROOT / "src")
    if src not in sys.path:
        sys.path.insert(0, src)
    from coach import llm

    # 사용자가 쓴 문장은 개인정보(전화 · 주소 · 계좌 등)를 가린 뒤 OpenAI 로 보낸다 (app/pii.py)
    # 서류 문장 다듬기(polish_sentences)는 사용자가 쓴 서류 내용 그대로여야 해서 가리지 않는다
    return Llm(
        masked(llm.classify_intent), masked(llm.counsel_reply), masked(llm.roleplay_reply), llm.polish_sentences,
        masked(llm.judge_checks), masked(llm.counsel_stream), masked(llm.roleplay_stream),
    )


def get_llm() -> Llm:
    """FastAPI 의존성. 테스트에서는 dependency_overrides 로 교체"""
    if get_settings().llm_mode == "real":
        return _real_llm()
    return Llm(
        llm_mock.classify_intent, llm_mock.counsel_reply, llm_mock.roleplay_reply, llm_mock.polish_sentences,
        llm_mock.judge_checks, llm_mock.counsel_stream, llm_mock.roleplay_stream,
    )


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
