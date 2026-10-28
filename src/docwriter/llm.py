"""문서 AI 실제 함수. 백엔드 app/doc_llm.py 가 LLM_MODE=real 일 때 쓴다.

함수 형식은 목 함수(backend/app/llm_mock.py 의 route_message·assist_field)와 같다.
- 이름·전화번호·생년월일·주소·민감 칸(주민등록번호·계좌·서명) 값은 받지 않는다 (api.md 1.6).
- 대화·작성 내용은 로그·예외 메시지에 넣지 않는다.
- 테스트는 generate 인자로 가짜 LLM 을 넣는다.
"""

import json
import re
from collections.abc import Callable

from docwriter.prompts import ASSIST_SYSTEM, EXTRACT_ANSWER_SYSTEM, ROUTE_CHAT_SYSTEM, ROUTE_DOCUMENT_SYSTEM

Generate = Callable[[list[dict]], dict]

ACTIONS = ("tip", "outline", "draft", "revise")
CHAT_ROUTES = ("document", "other")
DOCUMENT_ROUTES = ("field", "policy", "counsel")
HISTORY_TURNS = 4
DEFAULT_MAX_CHARS = 1000
OTHER_FIELDS_MAX = 3  # 앞뒤 맥락용으로 넘기는 다른 칸 수
OTHER_FIELD_CHARS = 300
JSON_RETRIES = 1

_STATUS_TEXT = {"in_care": "보호 중", "leaving_soon": "퇴소 예정", "left_care": "퇴소함"}
_STAGE_TEXT = {"child": "아동", "teen": "청소년", "youth": "자립준비청년"}


class DocwriterFormatError(RuntimeError):
    pass


def _openai_generate(messages: list[dict], max_tokens: int = 900) -> dict:
    from rag.openai_client import _client
    from rag.settings import get_rag_settings

    for _ in range(1 + JSON_RETRIES):
        resp = _client().chat.completions.create(
            model=get_rag_settings().coach_model, temperature=0.4, max_tokens=max_tokens,
            response_format={"type": "json_object"}, messages=messages,
        )
        try:
            out = json.loads(resp.choices[0].message.content or "")
        except json.JSONDecodeError:
            continue
        if isinstance(out, dict):
            return out
    raise DocwriterFormatError("LLM 출력이 JSON 이 아닙니다.")  # 원문(작성 내용 포함 가능)은 넣지 않는다


def _text(v: object) -> str:
    return v.strip() if isinstance(v, str) else ""


def _history(history: list[dict]) -> list[dict]:
    turns = [h for h in history if h.get("role") in ("user", "assistant") and _text(h.get("content"))]
    return [{"role": h["role"], "content": str(h["content"])} for h in turns[-HISTORY_TURNS:]]


# ---------------- 라우터 ----------------


def route_message(message: str, history: list[dict], context: dict, generate: Generate | None = None) -> dict:
    """context["screen"] == "chat": {"route": document|other, "doc_hint"}
    context["screen"] == "document": {"route": field|policy|counsel, "doc_hint": None}
    형식이 틀리면 안전한 기본값(other / field)."""
    gen = generate or _openai_generate
    if context.get("screen") == "document":
        system = ROUTE_DOCUMENT_SYSTEM.format(
            document_title=context.get("document_title") or "서류",
            field_label=context.get("field_label") or "선택한 칸",
        )
        out = gen([{"role": "system", "content": system}, {"role": "user", "content": message}])
        route = out.get("route") if isinstance(out, dict) else None
        return {"route": route if route in DOCUMENT_ROUTES else "field", "doc_hint": None}

    out = gen([
        {"role": "system", "content": ROUTE_CHAT_SYSTEM},
        *_history(history),
        {"role": "user", "content": message},
    ])
    route = out.get("route") if isinstance(out, dict) else None
    if route not in CHAT_ROUTES:
        route = "other"
    hint = _text(out.get("doc_hint")) if isinstance(out, dict) and route == "document" else ""
    return {"route": route, "doc_hint": hint[:50] or None}


# ---------------- 칸 작성 도우미 ----------------


def _extra(field: dict) -> str:
    lines = []
    if _text(field.get("caution")):
        lines.append(f"- 주의: {field['caution']}")
    outline = [o for o in field.get("outline") or [] if _text(o)]
    if outline:
        lines.append("- 권장 순서: " + " → ".join(outline))
    if _text(field.get("example")):
        lines.append(f"- 양식 예시(형식 참고만): {field['example']}")
    if field.get("essay") or field.get("multiline"):
        lines.append("- 여러 문장으로 쓰는 서술형 칸이다.")
    else:
        lines.append("- 한 줄로 짧게 쓰는 칸이다.")
    return "\n".join(lines) + "\n"


def _context(req: dict) -> str:
    lines = []
    if req.get("stage") in _STAGE_TEXT:
        lines.append(f"쓰는 사람: {_STAGE_TEXT[req['stage']]}")
    if req.get("status") in _STATUS_TEXT:
        lines.append(f"상태: {_STATUS_TEXT[req['status']]}")
    if _text(req.get("context")):
        lines.append(f"상담에서 넘어온 요청: {req['context'][:200]}")
    others = [
        o for o in req.get("other_fields") or [] if _text(o.get("label")) and _text(o.get("text"))
    ][:OTHER_FIELDS_MAX]
    for o in others:
        lines.append(f"같은 서류의 다른 칸 「{o['label']}」: {o['text'][:OTHER_FIELD_CHARS]}")
    if not lines:
        return ""
    return "\n[참고 맥락] (사실을 지어내는 데 쓰지 말고, 앞뒤가 맞게 쓰는 데만 참고)\n" + "\n".join(lines) + "\n"


def _user_message(req: dict) -> str:
    current = _text(req.get("current_text"))
    return json.dumps({"지금_쓴_내용": current or "(비어 있음)", "요청": req.get("prompt", "")}, ensure_ascii=False)


_BRACKET_RE = re.compile(r"\[[^\[\]\n]{1,30}\]")


def assist_field(req: dict, generate: Generate | None = None) -> dict:
    """req: document_title, field{label, guide, caution, outline, example, essay, multiline},
    current_text, prompt, stage, status, context, other_fields[{label, text}], max_chars
    → {"action": tip|outline|draft|revise, "text"}"""
    field = req.get("field") or {}
    max_chars = int(req.get("max_chars") or DEFAULT_MAX_CHARS)
    system = ASSIST_SYSTEM.format(
        document_title=req.get("document_title") or "서류",
        label=field.get("label") or "",
        guide=field.get("guide") or "",
        extra=_extra(field),
        max_chars=max_chars,
        context=_context(req),
    )
    force = req.get("force_action")
    if force in ("draft", "revise"):
        # 대화형 작성: 사용자가 말한 내용으로 바로 칸을 채우거나 고친다 (넣을 내용 안내·문장 구조가 아님)
        system += f'\n이번 요청 종류는 "{force}"로 정해져 있다. 사용자가 말한 사실만 써서 칸에 들어갈 글을 쓴다.'
    out = (generate or _openai_generate)([
        {"role": "system", "content": system},
        {"role": "user", "content": _user_message(req)},
    ])
    action = out.get("action") if isinstance(out, dict) else None
    if force in ("draft", "revise") and action in ("outline", "draft", "revise"):
        action = force
    text = _text(out.get("text") if isinstance(out, dict) else None)
    if action not in ACTIONS or not text:
        raise DocwriterFormatError("작성 도우미 출력 형식이 다릅니다.")
    current = _text(req.get("current_text"))
    if action == "revise" and not current:
        action = "draft"
    if action == "revise":
        # 사용자가 남긴 대괄호 빈칸이 고치면서 사라지면(지어낸 값으로 채운 것) 원래 글을 지킨다
        lost = sorted(set(_BRACKET_RE.findall(current)) - set(_BRACKET_RE.findall(text)))
        if lost:
            return {"action": "tip", "text": f"{', '.join(lost)} 빈칸을 먼저 채워 주시면 그 내용을 살려서 고쳐 드릴게요."}
    return {"action": action, "text": text[:max_chars]}


# ---------------- 대화로 채우기: 답 뽑기 ----------------


def extract_answer(message: str, field: dict, generate: Generate | None = None) -> dict:
    """일반 칸(기관명·관계·은행 등)만. 이름·주소·전화·계좌 같은 개인정보 칸은 백엔드가 규칙으로 뽑고 여기 보내지 않는다.
    → {"answer": str | None}"""
    system = EXTRACT_ANSWER_SYSTEM.format(label=field.get("label") or "", guide=field.get("guide") or "")
    out = (generate or _openai_generate)([
        {"role": "system", "content": system},
        {"role": "user", "content": message},
    ])
    answer = _text(out.get("answer")) if isinstance(out, dict) else ""
    return {"answer": answer[:200] or None}
