"""검색용 용어 사전. 사용자가 쓰는 일상어를 정책 원문 용어로 넓혀 검색한다.

예: "퇴소하고 매달 받는 돈" → 원문은 "보호종료", "매월", "수당"이라고 쓴다.
검색(임베딩)에만 쓰고, LLM 에는 원래 질문을 그대로 보낸다. 쉬운말 변환 용어 사전과 함께 관리할 수 있다.

오탐 방지:
- 한 글자 키는 쓰지 않는다 ("돈" 은 "돈가스" 에도 걸린다). 두 글자 이상의 표현만 키로 둔다.
- 키는 단어 시작에서만 찾는다 ("위탁" 은 "가정위탁" 처럼 단어 중간에서는 찾지 않는다).
"""

import re

MIN_KEY_LEN = 2

# 일상어 → 원문 용어. 질문에 앞쪽 표현이 들어 있으면 뒤쪽 용어를 붙인다.
SYNONYMS: dict[str, str] = {
    "퇴소": "보호종료 보호종료아동 자립준비청년",
    "시설 나": "보호종료 아동복지시설",
    "보육원": "아동복지시설 아동양육시설",
    "그룹홈": "공동생활가정",
    "위탁": "가정위탁",
    "매달": "매월",
    "한 달": "매월",
    "용돈": "자립수당 현금지급",
    "받는 돈": "수당 지원금 현금지급",
    "주는 돈": "수당 지원금 현금지급",
    "돈을 받": "수당 지원금 현금지급",
    "돈 받": "수당 지원금 현금지급",
    "지원금": "수당 현금지급",
    "생활비": "자립수당 생계",
    "월세": "주거비 임대료 주거 지원",
    "살 곳": "주거 주거 지원",
    "서류": "신청서류 제출서류",
    "준비물": "신청서류 제출서류",
    "어디서 신청": "신청방법 행정복지센터",
    "어떻게 신청": "신청방법 신청 절차",
    "신청하려면": "신청방법 신청서류",
    "인터넷": "온라인 신청 복지로",
    "온라인": "복지로 온라인 신청",
    "얼마": "지원금액 지원내용",
    "언제까지": "지원기간",
    "대학": "교육 장학금",
    "취업": "고용 일자리",
}

assert all(len(k.replace(" ", "")) >= MIN_KEY_LEN for k in SYNONYMS), "용어 사전 키는 두 글자 이상"

_PATTERNS = [(re.compile(rf"(?<![0-9A-Za-z가-힣]){re.escape(k)}"), v) for k, v in SYNONYMS.items()]


# 키워드 검색에서 빼는 말: 질문의 끝말·너무 흔한 말 (모든 정책 글에 있어 고르는 힘이 없음)
STOPWORDS = {
    "있나요", "있어요", "있는", "있을까요", "있어", "없어", "알려줘", "알려주세요", "주세요", "해주세요", "궁금해요",
    "어떤", "무엇", "뭐가", "뭐야", "어떻게", "정책", "지원", "관련", "대해", "대한", "받을", "받을수", "할수",
}
_WORD_RE = re.compile(r"[0-9A-Za-z가-힣]+")


def keywords(question: str) -> list[str]:
    """키워드 검색에 쓸 질문 단어 (두 글자 이상, STOPWORDS 제외, 순서 유지·중복 제거)."""
    return list(dict.fromkeys(w for w in _WORD_RE.findall(question) if len(w) >= MIN_KEY_LEN and w not in STOPWORDS))


def keyword_hits(words: list[str], text: str) -> int:
    """text 에 들어 있는 단어 수. 끝 한 글자(조사)를 뗀 형태도 인정한다 ("청년이" → "청년")."""
    return sum(1 for w in words if w in text or (len(w) > MIN_KEY_LEN and w[:-1] in text))


def expand_query(question: str) -> str:
    extra = list(dict.fromkeys(v for pat, v in _PATTERNS if pat.search(question)))
    if not extra:
        return question
    return f"{question}\n(관련 용어: {' '.join(extra)})"
