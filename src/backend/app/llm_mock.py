"""LLM 목 함수 (api.md 4.2). 실제 함수 준비 전까지 사용

- 랜덤 없음, 항상 같은 결과
- 지원 금액·자격·신청 여부 문장 만들지 않음 (계획서 7.3)
"""

_POLICY_WORDS = (
    "지원", "수당", "정책", "제도", "혜택", "신청", "자격", "대상", "금액", "얼마",
    "월세", "전세", "보증금", "임대", "주거", "장학", "대출", "적금", "통장", "보조금", "받을수",
)

COUNSEL_MESSAGE = (
    "이야기해 줘서 고마워요. 지금 상황을 하나씩 정리해 보면 다음에 할 일이 보일 거예요. "
    "지원 제도가 궁금하면 필요한 도움을 넣어서 다시 물어봐 주세요."
)
ROLEPLAY_END = "네, 오늘은 여기까지 할게요. 수고했어요."


def classify_intent(message: str, history: list[dict]) -> str:
    text = message.replace(" ", "")
    return "policy" if any(w in text for w in _POLICY_WORDS) else "counsel"


def counsel_reply(message: str, history: list[dict], profile: dict) -> str:
    return COUNSEL_MESSAGE


def roleplay_reply(scenario: dict, history: list[dict], message: str) -> dict:
    """상황 대사(scenario["lines"])를 순서대로. 첫 대사는 history 에 있으므로 제외"""
    lines = scenario.get("lines", [])
    turn = sum(1 for h in history if h["role"] == "assistant") - 1
    if 0 <= turn < len(lines):
        return {"reply": lines[turn], "is_finished": False}
    return {"reply": ROLEPLAY_END, "is_finished": True}


def polish_sentences(doc_type: str, sentences: list[str]) -> list[str]:
    return list(sentences)


# ---------------- 문서 AI (라우터·칸 작성 도우미) ----------------

_DOC_WORDS = ("신청서", "이력서", "자기소개서", "자소서", "지원서", "계획서", "전입신고서")
_WRITE_WORDS = ("써", "쓰고", "쓸래", "작성", "채워")
_FIELD_WORDS = (
    "써", "쓰", "작성", "예시", "구조", "틀", "내용", "넣", "다듬", "고쳐", "바꿔", "줄여", "짧게", "길게",
    "늘려", "구체", "자연스럽", "추천", "문장", "어떻게",
)
_POLICY_QUESTION_WORDS = (
    "기준", "자격", "금액", "얼마", "기한", "언제까지", "대상", "조건", "받을수", "필요한서류", "무슨서류", "어디에",
)
TIP_INTRO = "이 칸에는 이런 내용을 넣으면 좋아요."
MOCK_DRAFT_TAIL = "[구체적인 경험이나 사실을 적어 주세요]"


def route_message(message: str, history: list[dict], context: dict) -> dict:
    text = message.replace(" ", "")
    if context.get("screen") == "document":
        # 지금 칸에 대한 요청이 먼저 (예: "지원 동기 써 줘" 는 '지원'이 있어도 칸 요청)
        if any(w in text for w in _FIELD_WORDS):
            return {"route": "field", "doc_hint": None}
        if any(w in text for w in _POLICY_QUESTION_WORDS) or any(w in text for w in _POLICY_WORDS):
            return {"route": "policy", "doc_hint": None}
        if any(w in text for w in ("여기", "이칸", "칸에", "뭐야", "뭐예요")):
            return {"route": "field", "doc_hint": None}
        return {"route": "counsel", "doc_hint": None}  # 서류와 관계없는 이야기 (예: "오늘 날씨 어때")
    hint = next((w for w in _DOC_WORDS if w in text), None)
    if hint and any(w in text for w in _WRITE_WORDS):
        return {"route": "document", "doc_hint": hint}
    return {"route": "other", "doc_hint": None}


def _mock_action(prompt: str, current: str) -> str:
    text = prompt.replace(" ", "")
    if any(w in text for w in ("어떤내용", "뭘써", "뭘쓰", "무엇을", "뭐써", "뭐쓰", "뭐넣", "어떤걸", "내용추천")):
        return "tip"
    if any(w in text for w in ("구조", "틀", "순서", "목차")):
        return "outline"
    if current and any(w in text for w in ("다듬", "고쳐", "줄여", "짧게", "길게", "늘려", "구체", "바꿔", "자연스럽")):
        return "revise"
    return "draft"


def assist_field(req: dict) -> dict:
    """규칙 기반. 사실을 지어내지 않고 대괄호 빈칸만 남긴다"""
    field = req.get("field") or {}
    label = field.get("label") or "이 칸"
    outline = [o for o in field.get("outline") or [] if o] or [f"{label}에 대한 핵심 내용"]
    current = (req.get("current_text") or "").strip()
    action = req.get("force_action") or _mock_action(req.get("prompt") or "", current)
    if action == "draft" and req.get("force_action"):
        # 대화형 작성: 사용자가 말한 내용을 그대로 문장으로 (사실을 더하지 않음)
        said = (req.get("prompt") or "").split("내가 넣고 싶은 내용:", 1)[-1].strip()
        return {"action": "draft", "text": f"{said.rstrip('.')}입니다." if said else f"[{label}]"}
    if action == "tip":
        lines = [TIP_INTRO, *(f"- {o}" for o in outline)]
        if field.get("guide"):
            lines.append(f"- {field['guide']}")
        return {"action": "tip", "text": "\n".join(lines)}
    if action == "outline":
        return {"action": "outline", "text": "\n".join(f"{i}. {o}: [작성]" for i, o in enumerate(outline, 1))}
    if action == "revise":
        return {"action": "revise", "text": current}
    if not (field.get("essay") or field.get("multiline")):
        return {"action": "draft", "text": f"[{label}]"}
    return {"action": "draft", "text": " ".join(f"{o}에 대해 말씀드리겠습니다. [{o}]" for o in outline)}


def extract_answer(message: str, field: dict) -> dict:
    """규칙 기반: 말끝만 정리하고, 대화 문장(동사로 끝나는 긴 말)이면 답을 못 찾은 것으로 본다"""
    import re

    from app.answer_extract import is_noise

    from app.answer_extract import is_small_talk, looks_sentence

    if is_noise(message) or is_small_talk(message):
        return {"answer": None}
    text = re.sub(r"\s*(이에요|예요|이요|입니다|이야|요)[.!~]*$", "", message.strip()).strip()
    if not text or (len(text.split()) >= 3) or looks_sentence(text):
        return {"answer": None}
    return {"answer": text}


def read_form(form_text: str | None, images: list[str]) -> dict:
    """규칙 기반 (LLM_MODE=mock): PDF 글자에서 아는 칸 이름만 찾는다. 사진은 기본 칸 몇 개를 돌려준다"""
    known = [
        ("성명", "text", "이름을 적어요."), ("생년월일", "date", "태어난 날을 적어요."),
        ("연락처", "phone", "연락받을 휴대폰 번호를 적어요."), ("주소", "address", "지금 사는 곳 주소를 적어요."),
        ("주민등록번호", "id_number", "출력한 뒤 직접 적어요."), ("신청 사유", "text", "신청하는 까닭을 적어요."),
        ("서명", "signature", "출력한 뒤 직접 서명해요."),
    ]
    if form_text:
        flat = form_text.replace(" ", "")
        hits = [k for k in known if k[0].replace(" ", "") in flat]
        source = form_text
    else:
        hits = [known[0], known[2], known[3], known[5]]
        source = " ".join(k[0] for k in hits)
    return {
        "title": "",
        "form_text": source,
        "fields": [{"label": k[0], "section": None, "input_type": k[1], "options": None, "required": None,
                    "guide": k[2], "example": None} for k in hits],
    }
