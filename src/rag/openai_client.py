"""OpenAI 임베딩·답변 생성 함수. 질문 내용은 로그에 남기지 않는다 (api.md 1.6)."""

import json
from functools import lru_cache

from openai import OpenAI

from rag.prompts import ANSWER_SYSTEM, ANSWER_USER, format_evidence
from rag.settings import get_rag_settings


class RagConfigError(RuntimeError):
    pass


class RagFormatError(RuntimeError):
    pass


@lru_cache
def _client() -> OpenAI:
    key = get_rag_settings().openai_api_key
    if not key:
        raise RagConfigError("OPENAI_API_KEY 가 .env 에 없습니다.")
    return OpenAI(api_key=key, timeout=20.0, max_retries=1)


# 답이 짧은 판단(질문 종류 · 서류 쪽인지 · 칸 답 뽑기 · 챙긴 것)은 오래 기다리지 않는다.
# OpenAI 가 멈춘 듯 느릴 때 20초씩 기다리지 않고 8초에 끊고 한 번 바로 다시 요청한다
QUICK_TIMEOUT = 8.0
# 스트리밍: 첫 글자 · 다음 글자를 이만큼 못 받으면 끊는다 (연결은 5초)
STREAM_TIMEOUT = 15.0


def quick_client() -> OpenAI:
    return _client().with_options(timeout=QUICK_TIMEOUT, max_retries=1)


def stream_client() -> OpenAI:
    import httpx

    return _client().with_options(timeout=httpx.Timeout(STREAM_TIMEOUT, connect=5.0), max_retries=1)


def embed(texts: list[str]) -> list[list[float]]:
    resp = _client().embeddings.create(model=get_rag_settings().rag_embedding_model, input=texts)
    return [d.embedding for d in sorted(resp.data, key=lambda d: d.index)]


def generate_answer(question: str, hits: list[dict]) -> dict | None:
    """근거로 답변 초안을 만든다. {"sentences": [{"text", "evidence"}], "missing"}.

    답할 문장이 하나도 없으면 None. 검사는 guard.finalize 가 한다.
    """
    resp = _client().chat.completions.create(
        model=get_rag_settings().rag_chat_model,
        temperature=0,
        max_tokens=600,
        response_format={"type": "json_object"},
        messages=[
            {"role": "system", "content": ANSWER_SYSTEM},
            {"role": "user", "content": ANSWER_USER.format(question=question, evidence=format_evidence(hits))},
        ],
    )
    try:
        draft = json.loads(resp.choices[0].message.content or "")
    except json.JSONDecodeError:
        raise RagFormatError("LLM 출력이 JSON 이 아닙니다.") from None  # 원문(질문 포함 가능)은 넣지 않는다
    if not isinstance(draft, dict) or not draft.get("sentences"):
        return None
    return draft
