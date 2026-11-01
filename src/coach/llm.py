"""AI 상담·롤플레잉·문장 다듬기 실제 함수. 백엔드 app/llm_client.py 가 LLM_MODE=real 일 때 쓴다.

함수 형식은 목 함수(backend/app/llm_mock.py)와 같다. 백엔드는 예외·형식 오류를 503 AI_UNAVAILABLE 로 바꾼다.
- 대화 내용은 로그·예외 메시지에 넣지 않는다 (api.md 1.6). 이름·전화번호·생년월일은 받지 않는다.
- 상담 답변에 금액이나 자격 단정이 들어가면 고정 안내 문장으로 바꾼다 (계획서 7.3).
- 테스트는 generate 인자로 가짜 LLM 을 넣는다.
"""

import json
import re
from collections.abc import Callable

from coach.prompts import (
    COUNSEL_SYSTEM, INTENT_SYSTEM, LEVEL_GUIDE, POLISH_SYSTEM, ROLEPLAY_SYSTEM, STAGE_STYLE,
)
from rag.guard import has_assertion

# messages → JSON dict
Generate = Callable[[list[dict]], dict]

# LLM 에 넘기는 앞 대화 수 (오래된 것부터 버림)
HISTORY_TURNS = 10
# 상대 역할이 대사 흐름보다 이만큼 더 말하면 마무리한다 (대화가 끝없이 이어지는 것만 막는다)
ROLEPLAY_EXTRA_TURNS = 10
# 사용자가 대화를 끝내려는 말 (이 말이 있을 때만 LLM 의 마무리를 받아들인다)
_CLOSING_WORDS = ("감사합니다", "고맙습니다", "감사해요", "고마워요", "안녕히", "수고하세요", "수고하셨", "끊을게", "끊겠습니다",
                  "들어가세요", "그럼이만", "이만할게", "됐어요", "됐습니다", "알겠습니다", "알겠어요", "그렇게할게요")

SAFE_COUNSEL = (
    "이야기해 줘서 고마워요. 지원 제도의 금액이나 받을 수 있는지는 정책 찾기에서 물어보면 공식 자료로 알려 줄게요. "
    "지금 마음이나 상황을 조금 더 이야기해 줄래요?"
)
ROLEPLAY_END = "네, 오늘은 여기까지 할게요. 수고했어요."

_MONEY_RE = re.compile(r"\d[\d,]*\s*(만\s*)?원")
# 자주 섞이는 반말 끝말 → 해요체 (뜻은 그대로). 문장 끝에서만 바꾼다
_CASUAL_ENDINGS = ((r"구나", "군요"), (r"어때", "어때요"), (r"해 봐", "해 봐요"), (r"볼까", "볼까요"), (r"있어", "있어요"))
_STATUS_TEXT = {"in_care": "보호 중", "leaving_soon": "퇴소 예정", "left_care": "퇴소함"}


class CoachFormatError(RuntimeError):
    pass


# JSON 이 아닌 출력이 오면 다시 요청하는 횟수
JSON_RETRIES = 1


def _openai_generate(messages: list[dict], temperature: float = 0.4) -> dict:
    from rag.openai_client import _client
    from rag.settings import get_rag_settings

    for _ in range(1 + JSON_RETRIES):
        resp = _client().chat.completions.create(
            model=get_rag_settings().coach_model, temperature=temperature, max_tokens=500,
            response_format={"type": "json_object"}, messages=messages,
        )
        try:
            return json.loads(resp.choices[0].message.content or "")
        except json.JSONDecodeError:
            continue
    raise CoachFormatError("LLM 출력이 JSON 이 아닙니다.")  # 원문(대화 포함 가능)은 넣지 않는다


def _history(history: list[dict]) -> list[dict]:
    turns = [h for h in history if h.get("role") in ("user", "assistant") and str(h.get("content") or "").strip()]
    return [{"role": h["role"], "content": str(h["content"])} for h in turns[-HISTORY_TURNS:]]


def _text(v: object) -> str:
    return v.strip() if isinstance(v, str) else ""


def classify_intent(message: str, history: list[dict], generate: Generate | None = None) -> str:
    out = (generate or _openai_generate)([
        {"role": "system", "content": INTENT_SYSTEM},
        *_history(history)[-4:],
        {"role": "user", "content": message},
    ])
    intent = out.get("intent") if isinstance(out, dict) else None
    return intent if intent in ("policy", "counsel") else "counsel"


def _context(profile: dict) -> str:
    lines = []
    if profile.get("status") in _STATUS_TEXT:
        lines.append(f"상태: {_STATUS_TEXT[profile['status']]}")
    d_day = profile.get("d_day")
    if isinstance(d_day, int) and d_day >= 0:
        lines.append(f"퇴소·목표일까지 {d_day}일")
    if not lines:
        return ""
    return "\n[사용자 상황] " + ", ".join(lines) + " (필요할 때만 자연스럽게 참고하고, 먼저 꺼내지 않는다)\n"


def polite(text: str) -> str:
    """문장 끝 반말("~구나", "~어때?")을 해요체로 바꾼다."""
    for casual, formal in _CASUAL_ENDINGS:
        text = re.sub(rf"{casual}(?=[.!?]|$)", formal, text)
    return text


def safe_counsel(reply: str) -> str:
    """금액이나 자격 단정이 들어간 답변은 고정 안내로 바꾸고, 반말 끝말은 해요체로 고친다."""
    if _MONEY_RE.search(reply) or has_assertion(reply):
        return SAFE_COUNSEL
    return polite(reply)


def counsel_reply(message: str, history: list[dict], profile: dict, generate: Generate | None = None) -> str:
    stage = profile.get("stage") if profile.get("stage") in STAGE_STYLE else "youth"
    out = (generate or _openai_generate)([
        {"role": "system", "content": COUNSEL_SYSTEM.format(style=STAGE_STYLE[stage], context=_context(profile))},
        *_history(history),
        {"role": "user", "content": message},
    ])
    reply = _text(out.get("reply") if isinstance(out, dict) else None)
    if not reply:
        raise CoachFormatError("상담 답변이 비어 있습니다.")
    return safe_counsel(reply)


def _situation(scenario: dict) -> str:
    text = _text(scenario.get("situation"))
    return f"[이번 전개] {text} 이 전개대로 반응한다.\n" if text else ""


def _surprises(scenario: dict) -> str:
    """돌발 상황: 대사 흐름에 이미 끼워 두었다. 꼭 한 번씩 꺼내고, 사용자의 대처에 따라 반응하게 한다"""
    items = [_text(x) for x in scenario.get("surprises") or [] if _text(x)]
    if not items:
        return ""
    listed = "\n".join(f"- {x}" for x in items)
    return (
        "[돌발 상황] 대화 중간에 아래 말을 한 번씩 꺼낸다 (상황에 맞게 바꿔 말해도 된다). "
        "사용자가 분명하게 대처하면 받아들이고, 얼버무리면 한 번 더 확인한다.\n"
        f"{listed}\n"
    )


# 음성 인식 글은 문장부호가 없다. 질문으로 보이면 (질문) 을 붙여 상대 역할이 질문에 먼저 답하게 한다
_ASK_WORDS = re.compile(r"(뭐|무엇|무슨|언제|어디|얼마|몇|왜|어떻게|어떤|누구|어느)")
_ASK_ENDINGS = re.compile(r"(나요|까요|까|는지요|는지|죠|지요|니|냐|어때요|어때)$")
_STATEMENT_ENDINGS = re.compile(r"(주세요|드려요|드릴게요|게요|했어요|됐어요|왔어요|싶어요|합니다|습니다)$")


def looks_question(text: str) -> bool:
    """음성 인식 글(문장부호 없음)이 질문 같은지. 문장부호가 있으면 그대로 따른다"""
    t = text.strip()
    if not t:
        return False
    if t.endswith("?"):
        return True
    if t.endswith((".", "!")):
        return False
    tail = t.split()[-1]
    if _STATEMENT_ENDINGS.search(tail):
        return False
    if _ASK_ENDINGS.search(tail):
        return True
    # "~요" 로 끝나도 의문사가 있으면 질문 ("보증금 얼마예요", "언제 와요")
    return tail.endswith("요") and bool(_ASK_WORDS.search(t))


def voice_message(message: str) -> str:
    return f"{message} (질문)" if looks_question(message) and not message.strip().endswith("?") else message


def roleplay_reply(scenario: dict, history: list[dict], message: str, generate: Generate | None = None) -> dict:
    """history 첫 대사(상대 역할의 첫마디)는 백엔드가 넣어 둔다."""
    lines = [str(x) for x in scenario.get("lines") or []]
    spoken = sum(1 for h in history if h.get("role") == "assistant")
    if spoken >= len(lines) + ROLEPLAY_EXTRA_TURNS:
        return {"reply": ROLEPLAY_END, "is_finished": True}
    system = ROLEPLAY_SYSTEM.format(
        partner=scenario.get("partner") or "상대",
        setting=scenario.get("setting") or "",
        goal=scenario.get("goal") or "",
        level_guide=LEVEL_GUIDE.get(str(scenario.get("level")), LEVEL_GUIDE["NORMAL"]),
        situation=_situation(scenario),
        surprises=_surprises(scenario),
        lines="\n".join(f"{i}. {x}" for i, x in enumerate(lines, 1)) or "(없음)",
    )
    # 앞 대화의 사용자 말에도 같은 표시를 붙인다 (앞에서 무엇을 물었는지 알게)
    turns = [
        {**h, "content": voice_message(h["content"])} if h["role"] == "user" else h for h in _history(history)
    ]
    messages = [{"role": "system", "content": system}, *turns, {"role": "user", "content": voice_message(message)}]
    # 사람처럼 말하게 하되, 엉뚱한 말은 프롬프트 규칙으로 막는다
    out = generate(messages) if generate else _openai_generate(messages, temperature=0.5)
    reply = _text(out.get("reply") if isinstance(out, dict) else None)
    if not reply:
        raise CoachFormatError("롤플레잉 답변이 비어 있습니다.")
    # 상대 역할이 혼자 대화를 끝내지 않게: 질문이 아니고, 사용자가 끝맺는 말을 했거나 대사 흐름을 다 했을 때만 받아들인다
    closing = any(w in re.sub(r"\s+", "", message) for w in _CLOSING_WORDS)
    # 사용자가 끝맺는 말을 했을 때만 받아들인다 (대사 흐름을 다 했다고 혼자 끝내지 않음)
    llm_done = out.get("is_finished") is True and closing and not looks_question(message)
    finished = llm_done or spoken + 1 >= len(lines) + ROLEPLAY_EXTRA_TURNS
    return {"reply": reply, "is_finished": finished}


def polish_sentences(doc_type: str, sentences: list[str], generate: Generate | None = None) -> list[str]:
    """문장 수가 달라지거나 형식이 틀리면 원래 문장을 그대로 돌려준다 (뜻이 바뀌는 것보다 안전)."""
    if not sentences:
        return []
    out = (generate or _openai_generate)([
        {"role": "system", "content": POLISH_SYSTEM.format(doc_type=doc_type or "서류")},
        {"role": "user", "content": json.dumps({"sentences": sentences}, ensure_ascii=False)},
    ])
    polished = out.get("sentences") if isinstance(out, dict) else None
    if not isinstance(polished, list) or len(polished) != len(sentences) or not all(_text(s) for s in polished):
        return list(sentences)
    return [_text(s) for s in polished]
