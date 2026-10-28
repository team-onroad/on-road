"""대화로 채우기: 사용자 말에서 칸에 넣을 답만 뽑는다 (docs/document-ai.md 3.32).

칸 종류마다 형식을 보고 답을 찾는다. 못 찾으면 None → 다시 묻는다 (아무 말이나 그대로 적지 않는다).
- 이름·주소·이메일·등록번호처럼 개인정보가 들어가는 칸은 규칙으로만 뽑는다 (LLM 에 보내지 않음, api.md 1.6)
- 기관명·관계·은행 같은 일반 칸은 짧은 답이면 그대로, 대화 문장이면 LLM(extract_answer)이 답만 뽑고 확인을 받는다

kind: name / address / email / bizno / text
"""

import re

from app.codes import USER_REGIONS
from app.profile_signal import name_in

# 답 끝의 말버릇 (예: "정서현이에요", "부산이요", "OO아동복지원입니다")
_TAIL_RE = re.compile(
    r"\s*(이라고\s*해요|라고\s*해요|이라고\s*합니다|라고\s*합니다|이에요|예요|이요|입니다|이야|이고요|이구요|요|야|임)[.!~]*$"
)
_LEAD_RE = re.compile(r"^(제|저는|전|나는|난|내|우리|저희)\s*")
_HANGUL_NAME_RE = re.compile(r"^[가-힣]{2,5}$")
_LATIN_NAME_RE = re.compile(r"^[A-Za-z][A-Za-z .'-]{1,40}$")
# "이름은 정서현", "성명: 정서현", "정서현이에요", "저는 정서현"
_NAME_PATTERNS = (
    re.compile(r"(?:이름|성함|성명)\s*(?:은|는|이|:)?\s*([가-힣]{2,5}?)(?:이에요|예요|입니다|이야|야|이요|요)?(?=$|[\s.,!?~])"),
    re.compile(r"(?:저는|전|나는|난)\s*([가-힣]{2,5}?)(?:이에요|예요|입니다|이야|야|이요|요)(?=$|[\s.,!?~])"),
)
_NOT_NAME = {"이름", "성명", "모르", "몰라", "없어", "없음", "비밀", "그거", "뭐야", "누구"}
_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_DIGITS_RE = re.compile(r"\d+")
# 주소에 들어가는 말: 시·도·구·군·읍·면·동·리·로·길·번지·호 또는 숫자
_ADDRESS_TOKEN_RE = re.compile(r"[가-힣0-9]+(?:특별시|광역시|특별자치시|특별자치도|시|도|구|군|읍|면|동|리|로|길|번길|번지|호)(?=\s|$|,|\d)")
_ADDRESS_TAIL_RE = re.compile(r"\s*(?:에서?\s*)?(살아요|살아|살고\s*있어요|살고\s*있어|삽니다|이에요|예요|입니다|이요|요)[.!~]*$")


def kind_of(spec: dict) -> str:
    label = "".join(spec["label"].split()).rstrip(":*")
    low = label.lower()
    if spec.get("input_type") == "address" or label.endswith("주소"):
        return "address"
    if "mail" in low or "이메일" in label:
        return "email"
    if "사업자등록번호" in label or "고유번호" in label:
        return "bizno"
    if spec.get("profile_key") == "name" or label.endswith(("성명", "이름", "예금주", "대표자명", "대표자명(위탁부모)")):
        return "name"
    return "text"


def strip_tail(text: str) -> str:
    t = _TAIL_RE.sub("", text.strip())
    return t.strip() or text.strip()


def _name(message: str) -> str | None:
    renamed = name_in(message)
    if renamed:
        return renamed
    plain = _LEAD_RE.sub("", strip_tail(message)).strip()
    if _HANGUL_NAME_RE.match(plain) and plain not in _NOT_NAME:
        return plain
    if _LATIN_NAME_RE.match(plain) and len(plain.split()) <= 3:
        return plain
    for regex in _NAME_PATTERNS:
        m = regex.search(message)
        if m and m.group(1) not in _NOT_NAME:
            return m.group(1)
    return None


def _address(message: str) -> str | None:
    text = message.strip()
    m = _ADDRESS_TOKEN_RE.search(text)
    if m is None:
        return None
    # 첫 주소 낱말(앞에 "부산"처럼 짧은 시·도 이름이 있으면 거기)부터 끝까지, 뒤의 "에 살아요" 같은 말은 뗀다
    start = m.start()
    regions = [r.start() for r in re.finditer("|".join(USER_REGIONS), text[: m.start()])]
    if regions:
        start = regions[-1]
    addr = _ADDRESS_TAIL_RE.sub("", text[start:]).strip(" .,~!")
    has_number = bool(_DIGITS_RE.search(addr))
    tokens = len(_ADDRESS_TOKEN_RE.findall(addr))
    return addr if (tokens >= 2 or has_number) else None


def _bizno(message: str) -> str | None:
    digits = "".join(_DIGITS_RE.findall(message))
    if len(digits) == 10:
        return f"{digits[:3]}-{digits[3:5]}-{digits[5:]}"
    return None


RETRY = {
    "name": "이름만 알려 주세요. (예: 홍길동)",
    "address": "주소를 다시 알려 주세요. 시·구·도로명과 번호까지요. (예: 서울시 OO구 OO로 12)",
    "email": "이메일 주소를 다시 알려 주세요. (예: name@example.com)",
    "bizno": "사업자등록번호(고유번호) 10자리를 알려 주세요. (예: 123-45-67890)",
}


def extract(spec: dict, message: str) -> tuple[str | None, str]:
    """(뽑은 답 또는 None, kind). text 칸은 말끝만 정리해서 돌려준다 (대화 문장인지는 부르는 쪽이 본다)"""
    kind = kind_of(spec)
    if kind == "name":
        return _name(message), kind
    if kind == "address":
        return _address(message), kind
    if kind == "email":
        m = _EMAIL_RE.search(message)
        return (m.group(0) if m else None), kind
    if kind == "bizno":
        return _bizno(message), kind
    return strip_tail(message), kind
