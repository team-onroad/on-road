"""양식 원문 → 작성 항목 초안 (OpenAI). 키·모델은 RAG 와 같은 .env 설정을 쓴다.

여기에는 양식 원문만 보낸다. 사용자 프로필·입력값은 보내지 않는다 (api.md 1.6).
"""

import json

from formguide.extract import FormText
from formguide.prompts import EXTRACT_SYSTEM, EXTRACT_USER, MAX_FORM_CHARS
from rag.openai_client import RagFormatError, _client
from rag.settings import get_rag_settings


def draft_fields(form: FormText) -> dict:
    text = form.as_prompt_text()[:MAX_FORM_CHARS]
    resp = _client().chat.completions.create(
        model=get_rag_settings().rag_chat_model,
        temperature=0,
        max_tokens=4000,
        response_format={"type": "json_object"},
        messages=[
            {"role": "system", "content": EXTRACT_SYSTEM},
            {"role": "user", "content": EXTRACT_USER.format(form_text=text)},
        ],
    )
    try:
        draft = json.loads(resp.choices[0].message.content or "")
    except json.JSONDecodeError:
        raise RagFormatError("LLM 출력이 JSON 이 아닙니다.") from None
    if not isinstance(draft, dict):
        raise RagFormatError("LLM 출력 형식이 다릅니다.")
    return draft
