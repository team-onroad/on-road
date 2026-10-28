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
_NOT_NAME = {"이름", "성명", "모르", "몰라", "없어", "없음", "비밀", "그거", "뭐야", "누구", "아무거나", "그냥", "글쎄", "싫어"}
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


# 답이 아닌 짧은 말: 감탄·맞장구·거절·모름 (칸에 적지 않는다)
NO_WORDS = {"아니", "아니요", "아뇨", "아니야", "아니에요", "ㄴㄴ", "노", "no", "싫어", "싫어요", "안돼", "아니다"}
NOISE_WORDS = NO_WORDS | {
    "음", "음음", "어", "엥", "에", "응", "웅", "ㅇㅇ", "ㅇ", "네", "넵", "예", "글쎄", "글쎄요", "그냥", "몰라", "몰라요",
    "모름", "아무거나", "아무거나요", "뭐", "왜", "헐", "와", "오", "아", "흠", "ok", "오케이", "막", "진짜", "그래", "그래요",
    "됐어", "됐어요", "알았어", "알겠어", "알겠어요", "ㅋ", "ㅎ", "ㅠ", "ㅜ", "test", "테스트", "asdf",
}
_JAMO_RE = re.compile(r"^[ㄱ-ㅎㅏ-ㅣ.,!?~^\s]+$")


def is_noise(message: str) -> bool:
    """답이 아닌 말인지: 자음·모음만("ㅋㅋ", "ㅠㅠ"), 감탄·맞장구·거절("음", "아니", "막 아니"), 기호만"""
    text = message.strip().lower()
    if not text or _JAMO_RE.match(text) or not re.search(r"[가-힣A-Za-z0-9]", text):
        return True
    words = [re.sub(r"[.,!?~^]+$", "", w) for w in text.split()]
    return all(w in NOISE_WORDS or _JAMO_RE.match(w or "ㅋ") for w in words)


def is_no(message: str) -> bool:
    return re.sub(r"[\s.,!?~^]", "", message.strip().lower()) in NO_WORDS


# 칸과 상관없는 잡담 ("나 배고파", "졸려", "심심해"): 칸에 적지 않고, LLM 도 부르지 않는다
_SMALL_TALK = ("배고", "배불", "졸려", "졸리", "졸립", "피곤", "심심", "귀찮", "놀고싶", "놀래", "자고싶", "잘래", "밥먹",
               "밥뭐", "뭐먹", "뭐해", "안녕", "하이", "ㅎㅇ", "잘자", "고마워", "고맙", "감사해", "사랑해", "짜증", "지루")


def is_small_talk(message: str) -> bool:
    flat = re.sub(r"[\s.,!?~^]", "", message.strip().lower())
    return len(flat) <= 12 and any(w in flat for w in _SMALL_TALK)


# 문장(말하는 투)인지: 칸 답(명사)이 아니라 "나 배고파", "이모가 내 줄 거예요" 같은 말
_PRONOUN_HEADS = ("나", "저", "난", "전", "내가", "제가", "나는", "저는", "우리", "저희", "그냥", "아마")
# 띄어쓰기가 있는 말의 끝
_SENTENCE_ENDS = ("어", "아", "야", "지", "래", "까", "니", "냐", "네", "요", "해", "줘", "다", "음", "파", "워", "와",
                  "봐", "돼", "져", "려", "자", "라", "게", "죠", "거든", "는데", "잖아", "ㅋ", "ㅎ", "ㅠ", "ㅜ")
# 한 낱말이어도 문장인 끝 ("배고파", "몰라요", "했어요"). 명사가 거의 안 끝나는 것만
_WORD_SENTENCE_ENDS = ("파", "워", "봐", "돼", "져", "줘", "싶어", "없어", "있어", "몰라", "했어", "했다", "거든", "잖아",
                       "는데", "어요", "아요", "해요", "네요", "지요", "죠", "겠어", "할래", "갈래", "ㅋ", "ㅎ", "ㅠ", "ㅜ")
_TALK_PARTS = ("했는데", "인데", "는데 ", "해서 ", "했어", "해 줘", "해줘", "해 주세요", "해주세요", "거예요", "거에요")


def looks_sentence(message: str) -> bool:
    t = message.strip().rstrip(".!~?^ ")
    if not t:
        return False
    words = t.split()
    if len(words) == 1:
        return t.endswith(_WORD_SENTENCE_ENDS)
    return (words[0] in _PRONOUN_HEADS or t.endswith(_SENTENCE_ENDS) or any(p in t for p in _TALK_PARTS))


RETRY = {
    "name": "이름만 알려 주세요. (예: 홍길동)",
    "address": "주소를 다시 알려 주세요. 시·구·도로명과 번호까지요. (예: 서울시 OO구 OO로 12)",
    "email": "이메일 주소를 다시 알려 주세요. (예: name@example.com)",
    "bizno": "사업자등록번호(고유번호) 10자리를 알려 주세요. (예: 123-45-67890)",
}


def extract(spec: dict, message: str) -> tuple[str | None, str]:
    """(뽑은 답 또는 None, kind). text 칸은 말끝만 정리해서 돌려준다 (대화 문장인지는 부르는 쪽이 본다)"""
    kind = kind_of(spec)
    if is_noise(message):
        return None, kind
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
