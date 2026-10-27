"""위기 신호 규칙 (api.md 1.6, 3.15). LLM 안 씀"""

import re

# 띄어쓰기 지우고 비교. "죽" 단독처럼 일상 표현과 겹치는 말은 제외
CRISIS_WORDS = (
    # 자살·자해
    "자살", "자해", "죽고싶", "죽어버리", "죽을래", "죽을까", "살기싫", "살고싶지않", "사는게의미",
    "사라지고싶", "없어지고싶", "목숨", "극단적선택", "손목을긋", "손목긋", "뛰어내리", "목을매",
    # 폭력·학대
    "학대", "폭행", "성폭력", "성추행", "성폭행", "협박", "스토킹",
)

CRISIS_MESSAGE = "많이 힘드셨겠어요. 혼자 견디지 않아도 괜찮아요. 지금 바로 이야기할 수 있는 곳이에요."

CRISIS_CONTACTS = (
    {"name": "자살예방상담전화", "phone": "109"},
    {"name": "청소년상담전화", "phone": "1388"},
    {"name": "보건복지상담센터", "phone": "129"},
    {"name": "경찰", "phone": "112"},
)


# "혼자 살면서"를 붙여 쓰면 "혼자살면서" 안에 "자살"이 생긴다. "자살"은 앞 글자가 "혼"이 아닐 때만 위기로 본다
_SUICIDE_RE = re.compile(r"(?<!혼)자살")


def is_crisis(message: str) -> bool:
    text = "".join(message.split())
    if _SUICIDE_RE.search(text):
        return True
    return any(w in text for w in CRISIS_WORDS if w != "자살")
