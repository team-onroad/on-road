"""외부 AI 로 보내기 전 대화 문장의 개인정보 가리기.

- 상담 · 실전 연습 · 문서 AI 길 찾기 · 정책 검색 질문처럼 사용자가 자유롭게 쓴 문장이 대상이다.
- 주민등록번호 · 전화번호 · 이메일 · 카드번호 · 계좌번호 · 상세 주소(도로명 번호, 지번, 동·호)를 자리표시로 바꾼다.
- 우리 서버 안의 규칙(프로필 변경 감지 · 개인정보 칸 추출 · 채점)은 원래 문장을 그대로 쓴다. 여기서 바꾸는 건 AI 로 나가는 글자뿐이다.
- 규칙으로 찾는 방식이라 모든 경우를 잡지는 못한다 (예: 띄어 쓴 번호, 이름). 앱의 입력 주의 문구와 함께 쓴다.
"""

import re
from collections.abc import Callable
from functools import wraps

RRN = "[주민등록번호]"
PHONE = "[전화번호]"
EMAIL = "[이메일]"
CARD = "[카드번호]"
ACCOUNT = "[계좌번호]"
ADDRESS = "[주소]"

# 숫자에 붙으면 주소 번호가 아닌 말 ("정도로 3번", "그걸로 2개", "월세로 50만 원")
_NOT_ADDR = "번개년월일시분초살명원만천억회층주%점차위배대장권병잔"

_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"(?<!\d)\d{6}\s?-\s?[1-8]\d{6}(?!\d)"), RRN),
    (re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+"), EMAIL),
    (re.compile(r"(?<!\d)\d{4}[- ]\d{4}[- ]\d{4}[- ]\d{4}(?!\d)"), CARD),
    (re.compile(r"(?<!\d)(?:\+82[- ]?)?0?1[016789][-. ]?\d{3,4}[-. ]?\d{4}(?!\d)"), PHONE),
    (re.compile(r"(?<!\d)0(?:2|[3-6]\d)[-. ]\d{3,4}[-. ]\d{4}(?!\d)"), PHONE),
    (re.compile(r"(?<!\d)\d{2,6}-\d{2,6}-\d{2,8}(?:-\d{1,3})?(?!\d)"), ACCOUNT),
    (re.compile(r"(?<!\d)\d{10,14}(?!\d)"), ACCOUNT),
    (re.compile(r"(?<!\d)\d{1,4}\s?동\s?\d{1,4}\s?호"), ADDRESS),
    (re.compile(rf"[가-힣A-Za-z0-9]+(?:대로|로|길)\s?\d{{1,4}}(?:-\d{{1,4}})?(?:번길\s?\d{{1,4}}(?:-\d{{1,4}})?)?(?![\d{_NOT_ADDR}])"), ADDRESS),
    (re.compile(rf"[가-힣]+(?:동|리|가)\s?\d{{1,5}}-\d{{1,5}}(?:번지)?(?![\d{_NOT_ADDR}])"), ADDRESS),
    (re.compile(r"[가-힣]+(?:동|리|가)\s?\d{1,5}\s?번지"), ADDRESS),
]


def mask(text: str) -> str:
    if not text:
        return text
    for pattern, label in _PATTERNS:
        text = pattern.sub(label, text)
    return text


def _mask_value(v: object) -> object:
    if isinstance(v, str):
        return mask(v)
    if isinstance(v, list):
        return [_mask_value(x) for x in v]
    if isinstance(v, dict) and "content" in v:
        # 대화 기록 한 줄 ({"role", "content"}) 만 바꾼다. 상황 · 프로필 같은 dict 는 그대로
        return {**v, "content": _mask_value(v["content"])}
    return v


def masked[F: Callable](fn: F) -> F:
    """문자열 인자와 대화 기록(list[{"role","content"}])의 내용을 가린 뒤 fn 을 부른다"""

    @wraps(fn)
    def wrapper(*args, **kwargs):
        return fn(*(_mask_value(a) for a in args), **{k: _mask_value(v) for k, v in kwargs.items()})

    return wrapper  # type: ignore[return-value]
