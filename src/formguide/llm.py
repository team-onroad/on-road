"""양식 원문 → 작성 항목 초안 (OpenAI). 키·모델은 RAG 와 같은 .env 설정을 쓴다.

여기에는 양식 원문만 보낸다. 사용자 프로필·입력값은 보내지 않는다 (api.md 1.6).
"""

import json

from formguide.extract import FormText
from formguide.prompts import EXTRACT_SYSTEM, EXTRACT_USER, MAX_FORM_CHARS
from rag.openai_client import RagFormatError, _client
from rag.settings import get_rag_settings

# 양식 하나를 통째로 읽고 항목을 길게 써서 앱 상담용 기본 20초로는 모자란다
TIMEOUT_SECONDS = 180.0


# 칸이 20개를 넘는 양식은 항목 JSON 이 길어 4000 토큰에서 잘렸다 (끝이 잘린 JSON → 형식 오류)
MAX_OUTPUT_TOKENS = 12000
RETRIES = 1  # 형식이 틀리면 한 번 더 요청


def draft_fields(form: FormText) -> dict:
    text = form.as_prompt_text()[:MAX_FORM_CHARS]
    for attempt in range(1 + RETRIES):
        resp = _client().with_options(timeout=TIMEOUT_SECONDS).chat.completions.create(
            model=get_rag_settings().rag_chat_model,
            temperature=0,
            max_tokens=MAX_OUTPUT_TOKENS,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": EXTRACT_SYSTEM},
                {"role": "user", "content": EXTRACT_USER.format(form_text=text)},
            ],
        )
        choice = resp.choices[0]
        try:
            draft = json.loads(choice.message.content or "")
        except json.JSONDecodeError:
            if attempt < RETRIES:
                continue
            if choice.finish_reason == "length":
                raise RagFormatError(
                    f"LLM 출력이 {MAX_OUTPUT_TOKENS} 토큰에서 잘렸습니다. 양식이 너무 길면 쪽을 나눠 PDF 로 넣어 주세요."
                ) from None
            raise RagFormatError("LLM 출력이 JSON 이 아닙니다.") from None
        if not isinstance(draft, dict):
            raise RagFormatError("LLM 출력 형식이 다릅니다.")
        return draft
    raise RagFormatError("LLM 출력이 JSON 이 아닙니다.")
