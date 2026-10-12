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
