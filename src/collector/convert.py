"""정부24 공공서비스 상세 → policies.json 정책 한 건 (docs/db-schema.md 6장, data/policies/README.md 작성 기준).

AI 없이 규칙으로만 옮긴다. 원문에 없는 내용은 만들지 않는다.
- 확실하지 않은 값(나이 상·하한)은 null 로 둔다 (지원조건 코드는 뜻이 확인되지 않아 쓰지 않음)
- 분야(category)·지역(region)은 글자 규칙으로 고르고, 고른 이유를 notes 에 남긴다
- original_text 는 P001 과 같은 머리말 형식: [출처 A: 정부24] → 주소 → [지원대상] [신청방법] [신청서류] …
  (rag/chunking.py 가 이 머리말로 청크를 나누고, 신청방법·신청서류 청크를 신청 안내로 쓴다)
"""

import re
from dataclasses import dataclass, field
from datetime import date

SOURCE_NAME = "정부24"
DETAIL_URL = "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/{sid}"

# db-schema.md 3.2 · 4장 (seed.py 가 같은 값으로 다시 검사한다)
NAME_MAX = 200
AGENCY_MAX = 100
CONTACT_MAX = 200
DOC_TEXT_MAX = 200

# original_text 에 넣는 항목: (API 필드 후보, 머리말). 머리말은 rag/chunking.py 가 항목으로 읽는다
SECTIONS = (
    (("서비스명",), "정책명"),
    (("서비스목적요약", "서비스목적"), "정책 개요"),
    (("소관기관명",), "담당부처"),
    (("지원유형",), "제공유형"),
    (("지원대상",), "지원대상"),
    (("선정기준",), "선정기준"),
    (("지원내용",), "지원내용"),
    (("신청방법",), "신청방법"),
    (("신청기한",), "신청기한"),
    (("구비서류",), "신청서류"),
    (("본인확인필요구비서류",), "본인확인필요구비서류"),
    (("공무원확인구비서류",), "공무원확인구비서류"),
    (("접수기관명", "접수기관"), "접수기관"),
    (("문의처", "전화문의"), "문의처"),
    (("법령",), "근거법령"),
    (("자치법규",), "근거자치법규"),
    (("행정규칙",), "근거행정규칙"),
)

# 분야: 앞에서부터 처음 맞는 것 (서비스명을 먼저, 없으면 분야·지원내용까지 본다)
CATEGORY_RULES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("education", ("장학", "학자금", "교육비", "대학", "학비", "등록금", "교육", "학습")),
    ("housing", ("주거", "임대", "월세", "전세", "주택", "보증금")),
    ("employment", ("취업", "고용", "일자리", "직업훈련", "창업", "구직", "인턴")),
    # 법률 지원은 '의료'가 아니다 ("무료 법률상담 · 소송대리"의 '상담'이 의료에 걸리던 것)
    ("independence", ("소송", "법률")),
    ("medical", ("의료", "건강", "치료", "진료", "심리", "상담", "검진")),
    ("finance", ("자산형성", "적금", "저축", "통장", "대출", "금융", "신용")),
    ("living_cost", ("수당", "생계", "생활비", "급여", "현금", "바우처")),
    ("independence", ("자립",)),
)
DEFAULT_CATEGORY = "independence"

# ---------------- 대상 고르기: 자립준비청년 · 보호대상아동이 직접 받을 수 있는 제도만 ----------------
# 정부24 검색어(가정위탁 · 아동복지시설 · 자립지원)는 위탁부모 양육보조금 · 입양축하금 · 요금 감면처럼
# 앱 사용자가 아닌 사람 대상 제도도 많이 가져온다 (실제 응답 257건 중 162건). 띄어쓰기는 지우고 비교한다
TARGET_WORDS = (
    "자립준비청년", "보호종료", "보호종결", "위탁종료", "위탁종결", "종결아동", "퇴소아동", "시설퇴소", "만기퇴소",
    "퇴소청소년", "보호대상아동", "자립지원전담", "가정위탁아동", "시설아동", "생활아동", "보호아동", "소년소녀가정",
    "자준청",
)
# 다른 대상 (이름에 있으면 늘 뺀다. "장애인거주시설 퇴소자 자립지원"처럼 '자립'이 있어도)
OTHER_GROUPS = (
    "입양", "장애인", "탈시설", "노숙", "한부모", "미혼", "북한이탈", "가정폭력", "임산부", "영유아", "어린이집",
    "조손", "국가유공자", "노인", "가정밖", "학교밖",
)
# 앱 사용자가 신청하지 않는 지급 · 일반 감면 (이름에 있으면 뺀다. 단 자립 · 대학 · 장학 · 진학 · 정착이 같이 있으면 넣는다)
NOT_FOR_USER = (
    "감면", "요금", "체험", "보험료", "기능보강", "위문", "결연", "격려금", "축하금", "위로금", "생일", "선물", "명절",
    "제수비", "양육보조", "양육수당", "양육비", "양육지원", "보호비", "급식", "바우처", "오케스트라", "PC", "정보화",
    "택시", "고충", "전기안전", "의료서비스", "보건의료", "치과", "영양제", "기저귀",
)
KEEP_ANYWAY = ("자립", "대학", "장학", "진학", "정착")
# 이름에 대상 낱말이 없어도, 지원대상에 있고 이름이 자립에 쓰이는 제도면 넣는다 ("기존주택 전세임대주택", "청년도전지원사업")
USEFUL_WORDS = (
    "자립", "장학", "대학", "주거", "임대", "주택", "부동산", "중개", "취업", "일자리", "청년", "정착", "디딤씨앗",
    "자산형성", "통장", "진학", "멘토", "사례관리",
)


def relevance(detail: dict) -> tuple[bool, str]:
    """(넣을지, 이유). 이름 · 지원대상 · 선정기준의 낱말 규칙 (AI 없음)"""
    name = (text(detail, "서비스명") or "").replace(" ", "")
    target = " ".join(t for t in (text(detail, "지원대상"), text(detail, "선정기준")) if t).replace(" ", "")
    if other := next((w for w in OTHER_GROUPS if w in name), None):
        return False, f"다른 대상 '{other}'"
    if (skip := next((w for w in NOT_FOR_USER if w in name), None)) and not any(w in name for w in KEEP_ANYWAY):
        return False, f"사용자가 신청하는 제도가 아님 '{skip}'"
    if hit := next((w for w in TARGET_WORDS if w in name), None):
        return True, f"이름의 '{hit}'"
    in_target = next((w for w in TARGET_WORDS if w in target), None)
    useful = next((w for w in USEFUL_WORDS if w in name), None)
    if in_target and useful:
        return True, f"지원대상의 '{in_target}' + 이름의 '{useful}'"
    if in_target:
        return False, f"지원대상에 '{in_target}'가 있지만 일반 제도"
    return False, "자립준비청년 · 보호대상아동 대상이 아님"

# 시/도 이름 → 지역 코드 (db-schema.md 4장 약칭). 바뀐 정식 명칭(특별자치도)도 같이
REGION_NAMES: tuple[tuple[str, str], ...] = (
    ("서울", "서울"), ("부산", "부산"), ("대구", "대구"), ("인천", "인천"), ("광주", "광주"),
    ("대전", "대전"), ("울산", "울산"), ("세종", "세종"), ("경기", "경기"), ("강원", "강원"),
    ("충청북도", "충북"), ("충북", "충북"), ("충청남도", "충남"), ("충남", "충남"),
    ("전라북도", "전북"), ("전북", "전북"), ("전라남도", "전남"), ("전남", "전남"),
    ("경상북도", "경북"), ("경북", "경북"), ("경상남도", "경남"), ("경남", "경남"), ("제주", "제주"),
)
NATIONWIDE = "전국"
# 시/도 다음에 오는 시·군·구 (예: "서울특별시 강남구", "경기도 수원시")
_SIGUNGU_RE = re.compile(r"\S+(시|군|구)$")

# 금액: 원문 표현 그대로 뽑는다 ("월 50만 원", "최대 1,000만원", "연 200만원")
# "15백만원"(1,500만 원) · "1천만원" 처럼 백만 · 천만 단위도 (실제 응답에서 놓쳤던 모양)
_AMOUNT_RE = re.compile(r"(?:(?:월|연|매월|최대|1회|일시금|1인당)\s*)?[\d,]+(?:\.\d+)?\s*(?:백만|천만|만|천|백|억)?\s*원")
_AMOUNT_MAX = 3

@dataclass
class Converted:
    policy: dict | None  # None 이면 필수 내용이 없어 넣지 못함 (skip_reason)
    service_id: str
    notes: list[str] = field(default_factory=list)  # 사람이 확인할 것
    skip_reason: str | None = None
    off_target: bool = False  # 앱 사용자(자립준비청년 · 보호대상아동) 대상이 아니라 뺌


def _squash(s: str) -> str:
    """같은 내용인지 비교용: 띄어쓰기 · 기호를 뺀다"""
    return re.sub(r"[\W_]", "", s)


def text(row: dict, *names: str) -> str | None:
    for n in names:
        v = row.get(n)
        if isinstance(v, str) and v.strip():
            return _clean(v)
    return None


def _clean(v: str) -> str:
    lines = [ln.strip() for ln in v.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    return "\n".join(ln for ln in lines if ln)


def _cut(v: str | None, limit: int) -> str | None:
    if v is None or len(v) <= limit:
        return v
    return v[: limit - 1] + "…"


def pick_category(row: dict) -> tuple[str, str]:
    """(코드, 고른 이유)"""
    name = text(row, "서비스명") or ""
    rest = " ".join(t for t in (text(row, "서비스분야"), text(row, "지원내용"), text(row, "서비스목적요약")) if t)
    for where, hay in (("서비스명", name), ("분야·지원내용", rest)):
        for code, words in CATEGORY_RULES:
            hit = next((w for w in words if w in hay), None)
            if hit:
                return code, f"{where}의 '{hit}'"
    return DEFAULT_CATEGORY, "맞는 낱말이 없어 기본값"


# ---------------- 나이 (지원자의 만 나이) ----------------
# 원문에 지원자 나이를 분명히 쓴 것만 넣는다. 애매하면 비운다 — 비면 앱은 '나이 제한 없음'으로 봐서 더 많이 보이고
# (조건 확인은 카드 · 체크리스트에서), 잘못 넣으면 받을 수 있는 사람에게 아예 안 보이므로 비우는 쪽이 안전하다.
_AGE_RANGE_RE = re.compile(r"(?:만\s*)?(\d{1,2})\s*세?\s*(?:이상)?\s*[~∼〜\-–]\s*(?:만\s*)?(\d{1,2})\s*세(?!대)\s*(?:이하|까지)?")
_AGE_BOUND_RE = re.compile(r"(?:만\s*)?(\d{1,2})\s*세(?!대)\s*(이상|이하|미만|초과|까지)")
# 나이가 아니라 시점: "만 18세 이후 보호가 종료된" · "18세가 된 때로부터" → 지원자의 지금 나이 조건이 아님
_AGE_EVENT_RE = re.compile(r"\d{1,2}\s*세(?!대)\s*(?:이\s*후|이\s*전|가\s*된|이\s*된|가\s*되는|부터|에\s|때)")
# 예외가 붙은 나이: "18세 미만(18세 이상인 경우에도 고등학교 재학 중이면 포함)" · "보호연장 가능" · "15세 이후 조기 종료"
_AGE_EXCEPTION_RE = re.compile(r"경우에도|예외|보호\s*연장|연장\s*가능|조기|추가\s*지정")
_AGE_ANY_RE = re.compile(r"\d{1,2}\s*세(?!대)")
_OTHER_PERSON_RE = re.compile(r"자녀|영유아|손자")  # "6세 이하 자녀" 는 지원자가 아니라 자녀의 나이
_GROUP_SPLIT_RE = re.compile(r"(?m)^\s*(?:선정기준:\s*)?○")
NEAR_OR = 40  # 나이 표현 앞뒤 이 글자 수 안의 '또는'
MIN_CHILD_AGE = 7  # 이보다 작은 상한("2세 이하 아동 등")은 지원 유형 하나의 조건이지 정책 전체의 나이가 아니다 (G057)


def _age_intervals(text: str) -> list[tuple[int | None, int | None, int, int]]:
    """[(하한, 상한, 시작, 끝)] 지원자 나이로 보이는 표현"""
    out, taken = [], []
    for m in _AGE_RANGE_RE.finditer(text):
        taken.append(m.span())
        out.append((m, int(m.group(1)), int(m.group(2))))
    for m in _AGE_BOUND_RE.finditer(text):
        if any(s <= m.start() < e for s, e in taken):
            continue
        n, word = int(m.group(1)), m.group(2)
        lo, hi = {"이상": (n, None), "초과": (n + 1, None), "이하": (None, n), "까지": (None, n), "미만": (None, n - 1)}[word]
        out.append((m, lo, hi))
    keep = []
    for m, lo, hi in out:
        around = text[max(0, m.start() - 6): m.end() + 6]
        if _OTHER_PERSON_RE.search(around) or (hi is not None and hi < MIN_CHILD_AGE):
            continue
        line = text[text.rfind("\n", 0, m.start()) + 1: m.start()]
        if line.lstrip().startswith("*"):
            # 각주("* 구직단념청년: … 만 18세 이상~34세 이하")는 대상 하나의 풀이라 정책 전체의 나이가 아니다 (G019)
            continue
        keep.append((lo, hi, m.start(), m.end()))
    return keep


def pick_ages(target: str) -> tuple[int | None, int | None, str | None]:
    """(age_min, age_max, 보고서 메모). 지원대상(+선정기준) 원문에서 지원자 나이를 보수적으로 뽑는다.
    - 나이 표현이 여럿이면 모두 감싸는 범위 (한 대상 묶음이라도 제한이 없으면 그쪽도 없음)
    - 시점 표현 · 예외가 있거나, 대상 묶음(○) 중 나이가 없는 묶음이 있으면 비운다"""
    if not _AGE_ANY_RE.search(target):
        return None, None, None
    if _AGE_EVENT_RE.search(target):
        return None, None, "나이: '18세 이후 보호종료' 같은 시점 표현이라 비움 (나이 제한 없음으로 보임)"
    if _AGE_EXCEPTION_RE.search(target):
        return None, None, "나이: 예외 · 보호연장 조건이 붙어 비움 (나이 제한 없음으로 보임)"
    groups = [g for g in _GROUP_SPLIT_RE.split(target) if g.strip()]
    if len(groups) > 1 and any(not _age_intervals(g) for g in groups):
        return None, None, "나이: 대상 묶음 중 나이가 없는 것이 있어 비움"
    found = _age_intervals(target)
    if not found:
        return None, None, None
    # "19~39세 청년 또는 자립준비청년" · "퇴소 5년 이내의 청년 또는 … 만 18~34세": 나이 없는 다른 대상이 함께 있다 (G025 · G033)
    if any("또는" in target[max(0, s - NEAR_OR): e + NEAR_OR] for *_, s, e in found):
        return None, None, "나이: 나이 없는 다른 대상과 '또는'으로 묶여 비움"
    lo = None if any(a is None for a, *_ in found) else min(a for a, *_ in found)
    hi = None if any(b is None for _, b, *_ in found) else max(b for _, b, *_ in found)
    if (lo is not None and hi is not None and lo > hi) or (lo is None and hi is None):
        return None, None, "나이: 범위를 정할 수 없어 비움"
    return lo, hi, f"나이 {lo if lo is not None else ''}~{hi if hi is not None else ''}세 (원문에서 뽑음, 맞는지 확인)"


# 원문에 나이가 없을 때: 누구를 위한 정책인지로 법에 정해진 범위를 쓴다 (11/23)
# - 보호 중인 아동 → ~24세: 아동복지법상 아동은 18세 미만이지만 보호연장은 25세가 될 때까지 (그래서 17 이 아니라 24)
# - 보호가 끝난 청년 → 18세~ (조기 보호종료 · 퇴소 예정이 있으면 15세~): 보호종료는 아무리 일러도 15세 이후.
#   상한은 비운다 (보호종료 나이가 18~25세로 달라 '5년 이내'의 끝 나이가 정해지지 않음)
# - 둘이 섞였거나 다른 대상(수급자 · 장애인 · 신혼부부 …)이 함께 있으면 비운다
_CHILD_RE = re.compile(r"가정위탁\s*(?:보호)?\s*(?:아동|중)|위탁\s*아동|보호\s*대상\s*아동|보호\s*아동|입소\s*아동|생활\s*아동|"
                       r"시설\s*(?:보호\s*|이용\s*)?아동|소년소녀|초[·.\s]*중[·.\s]*고|중[·.\s]*고등|재학|요보호")
_LEAVER_RE = re.compile(r"보호\s*종료|보호종결|퇴소|종결|자립준비|자립정착|홀로서기|만기|연령\s*초과|종료\s*아동|종료된")
# 다른 대상 묶음 (무주택 · 미혼은 대상이 아니라 조건이라 넣지 않는다: "퇴소 5년 이내 미혼 무주택자", W002)
_OTHER_TARGET_RE = re.compile(r"수급자|수급권자|장애인|고령자|국가유공|한부모|신혼|차상위")
_EARLY_LEAVE_RE = re.compile(r"조기|예정")  # 15세 이후 조기 보호종료 · 퇴소 예정(아직 보호 중)
_UNIVERSITY_RE = re.compile(r"대학|진학|입학")  # 대학 진학 지원은 입학 나이가 다양
_AGE18_LEAVE_RE = re.compile(r"18\s*세|만기|연령\s*초과")
CHILD_AGE_MAX = 24  # 보호연장: 25세가 될 때까지 (아동복지법 제16조의3)
LEAVER_AGE_MIN, EARLY_LEAVER_AGE_MIN = 18, 15


def infer_ages(name: str, target: str, content: str = "") -> tuple[int | None, int | None, str | None]:
    """원문에 지원자 나이가 없을 때 대상 종류로 범위를 정한다 (pick_ages 가 비웠을 때만 쓴다)"""
    head = f"{name} {target}"
    child, leaver = bool(_CHILD_RE.search(head)), bool(_LEAVER_RE.search(head))
    if _OTHER_TARGET_RE.search(target) or (child and leaver):
        return None, None, None
    if child:
        if _LEAVER_RE.search(content) or _UNIVERSITY_RE.search(head):  # 지원내용에 보호종료자 지원이 섞임 (G057) · 대학 진학
            return None, None, None
        return None, CHILD_AGE_MAX, f"나이 ~{CHILD_AGE_MAX}세 (보호 중인 아동 · 보호연장 25세 전까지, 대상 종류로 정함)"
    if leaver:
        # 18세는 원문이 18세 · 만기 · 연령초과 종료라고 할 때만. 아니면(조기 종료 · 퇴소 예정 · 말 없음) 가장 이른 15세
        both = f"{name} {target} {content}"
        at18 = _AGE18_LEAVE_RE.search(both) and not _EARLY_LEAVE_RE.search(both)
        lo = LEAVER_AGE_MIN if at18 else EARLY_LEAVER_AGE_MIN
        return lo, None, f"나이 {lo}세~ (보호가 끝난 청년, 대상 종류로 정함)"
    return None, None, None


def policy_ages(name: str, target: str, content: str = "") -> tuple[int | None, int | None, str | None]:
    """(age_min, age_max, 메모): 원문에 분명한 나이 → 없으면 대상 종류. 정부24 · 웹 수집기가 함께 쓴다"""
    lo, hi, note = pick_ages(target or "")
    if lo is None and hi is None:
        lo, hi, inferred = infer_ages(name or "", target or "", content or "")
        note = inferred or note
    return lo, hi, note


def pick_region(row: dict) -> tuple[str, str | None]:
    """(지역 코드, 확인할 것). 소관기관이 지방자치단체면 그 시/도, 아니면 전국"""
    agency = text(row, "소관기관명") or ""
    kind = text(row, "소관기관유형") or ""
    # 법인 표시를 떼고 본다 ("(재)인천인재평생교육진흥원" → 인천, 전에는 '전국'으로 들어감)
    agency = re.sub(r"^\s*(?:\((?:재|사|주)\)|재단법인|사단법인)\s*", "", agency)
    head = agency.split()[0] if agency.split() else ""
    code = next((c for n, c in REGION_NAMES if head.startswith(n)), None)
    if code is None:
        if "지방" in kind or "자치" in kind:
            return NATIONWIDE, f"지방자치단체 정책인데 시/도를 못 찾음 ('{agency}') → 지역 확인 필요"
        return NATIONWIDE, None
    parts = agency.split()
    if len(parts) > 1 and _SIGUNGU_RE.match(parts[1]):
        return code, f"시·군·구 정책 ('{agency}') → 앱은 시/도까지만 나눠 '{code}' 전체에 보임. 넣을지 확인 필요"
    return code, None


def _single_amount(text: str | None) -> str | None:
    """지원 금액 칸(support_amount)에는 금액 표현이 하나일 때만 넣는다.
    여러 개면(예: 디딤씨앗통장 "월 10만 원, 10만 원, 월 50만 원" — 50만 원은 지원금이 아니라 추가 적립 한도) 이어 붙이면
    뜻이 틀어지므로 비우고, 자세한 금액은 지원내용(support_content)의 원문에 맡긴다 (쉬운 말 변환에서 찾음)"""
    amounts = find_amounts(text)
    return amounts if amounts and ", " not in amounts else None


def find_amounts(*texts: str | None) -> str | None:
    """원문의 금액 표현을 그대로 (최대 3개). "3원"처럼 단위·앞말 없는 작은 숫자는 금액으로 보지 않는다"""
    found: list[str] = []
    for t in texts:
        for m in _AMOUNT_RE.finditer(t or ""):
            s = " ".join(m.group().split())
            digits = re.sub(r"\D", "", s)
            if s not in found and (re.search(r"[백만천억]|월|연|최대|1회|일시금|1인당", s) or len(digits) >= 4):
                found.append(s)
    return ", ".join(found[:_AMOUNT_MAX]) or None


# 서류 목록 나누기. 정부24 구비서류는 기관마다 쓰는 모양이 달라 줄의 구조를 읽는다 (실제 응답에서 본 모양):
#   "o 제출서류" / "○ 공통서류" / "※ 추가제출서류(해당 대상자)"  → 묶음 제목 (서류 아님)
#   "6. 자립준비청년" 다음 줄들 "- 보호종료확인서"              → 묶음 이름이 아래 서류들의 조건
#   "2. 신혼부부 : 혼인관계증명서"                              → 앞 말이 조건
#   "- 참여신청서, 개인정보동의서, …"                          → 쉼표로 여러 서류 (괄호 안 쉼표는 무시)
# 큰 글머리표(번호 · ○ 등)와 작은 글머리표(- · •)를 나눠 본다
_TOP_RE = re.compile(r"^\s*(?:\d+\s*[.)]|[①-⑳]|[○●■□◎▶▷◆◇★☞]|[oOㅇ](?=\s)|[가-하][.)])\s*")
_SUB_RE = re.compile(r"^\s*[-–·•◦*]\s*")
_NOTE_RE = re.compile(r"^\s*※\s*")
_BULLET_RE = re.compile(r"^\s*(?:[-–○●◦·•※▶▷■□◎◆◇★☞*]|[oOㅇ](?=\s)|\d+\s*[.)]|[①-⑳]|[가-하][.)])\s*")
_NO_DOCS = {"없음", "해당없음", "해당 없음", "-", "별도 없음", "구비서류 없음", "해당사항없음", "해당사항 없음"}
# 묶음 제목: 이 말로 끝나면 서류가 아니다 ("신청인 제출서류", "공통서류", "추가제출서류(해당 대상자)")
_HEADING_TAIL = ("제출서류", "구비서류", "공통서류", "필요서류", "준비서류", "첨부서류")
# 앞 줄에 이어지는 줄 (글머리표 없이 이 말로 시작하거나, 앞 줄이 이 말로 끝남)
_CONTINUE_HEAD = ("및 ", "또는 ", "이용 ", "이용및")
_CONTINUE_TAIL = ("수집", " 및", " 또는", ",")
# 끝 괄호가 '언제 필요한지'면 condition 으로 ("가족관계증명서 (대리 신청 시)")
_CONDITION_RE = re.compile(r"^(.+?)\s*\(([^()]*(?:시|경우|해당자|한함|만 해당|대상자)[^()]*)\)$")
# "신혼부부 : 혼인관계증명서" — 앞 말이 짧고 괄호 · 쉼표 · / 가 없을 때만 조건으로 본다
_LABEL_RE = re.compile(r"^([^:：(),/]{1,15}?)\s*[:：]\s*(.+)$")
# 앞 괄호가 '언제 필요한지'면 condition 으로 ("(자립교육신청·수료 후) 교육수료 확인서")
_LEAD_CONDITION_RE = re.compile(r"^\(([^()]*(?:시|후|경우|해당자|한함|대상자)[^()]*)\)\s*(.+)$")
LABEL_MAX = 15


def _heading(item: str) -> tuple[bool, str | None]:
    """(묶음 제목인지, 아래 서류들의 조건). "추가제출서류(해당 대상자)" → (True, "해당 대상자")"""
    base = re.sub(r"\s*\(([^()]*)\)\s*$", "", item).strip()
    if base.replace(" ", "") in {"서류", "제출서류", "구비서류"} or base.endswith(_HEADING_TAIL):
        m = re.search(r"\(([^()]*)\)\s*$", item)
        return True, (m.group(1).strip() if m else None)
    return False, None


def _split_commas(item: str) -> list[str]:
    """괄호 밖 쉼표로 나눈다. 나눈 조각이 너무 짧으면 (한 서류 안의 쉼표) 나누지 않는다"""
    if "/" in item or ":" in item or "：" in item:
        return [item]
    parts, depth, cur = [], 0, ""
    for ch in item:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    parts.append(cur.strip())
    parts = [p for p in parts if p]
    return parts if len(parts) > 1 and all(len(p) >= 3 for p in parts) else [item]


def _lines(raw: str) -> list[tuple[str, str]]:
    """구비서류 글 → [(종류, 글)]. 종류: top(큰 글머리표) · sub(작은 글머리표) · note(※) · plain.
    원문이 한 서류를 두 줄로 끊은 경우 다시 잇는다 ("개인정보 수집" + "이용 및 제3자 제공 동의서")"""
    out: list[tuple[str, str]] = []
    for line in raw.split("\n"):
        kind = "note" if _NOTE_RE.match(line) else "top" if _TOP_RE.match(line) else "sub" if _SUB_RE.match(line) else "plain"
        item = _BULLET_RE.sub("", line).strip()
        if not item or item in _NO_DOCS:
            continue
        if out and kind == "plain" and (item.startswith(_CONTINUE_HEAD) or out[-1][1].endswith(_CONTINUE_TAIL)):
            joiner = "·" if out[-1][1].endswith("수집") and item.startswith("이용") else " "
            out[-1] = (out[-1][0], f"{out[-1][1]}{joiner}{item}")
        else:
            out.append((kind, item))
    return out


def split_docs(raw: str | None) -> tuple[list[dict], list[str]]:
    """구비서류 글 → [{name, condition}], 확인할 것"""
    notes: list[str] = []
    if not raw:
        return [], notes
    lines = _lines(raw)
    found: list[tuple[str, str | None]] = []
    section: str | None = None  # "※ 추가제출서류(해당 대상자)" 아래 서류들의 조건
    group: str | None = None  # "6. 자립준비청년" 아래 "- …" 서류들의 조건
    for i, (kind, item) in enumerate(lines):
        is_heading, heading_cond = _heading(item)
        if is_heading:
            section, group = heading_cond, None
            continue
        if kind == "note":
            continue  # ※ 안내 글 (서류 아님)
        if item.endswith((":", "：")):
            group = item.rstrip(":： ").strip() or None  # "3. 우선공급 청년 :"
            continue
        next_is_sub = i + 1 < len(lines) and lines[i + 1][0] == "sub"
        if kind in ("top", "plain") and next_is_sub and len(item) <= LABEL_MAX and not _split_commas(item)[1:]:
            group = item  # "6. 자립준비청년" 다음에 "- 서류"가 오면 묶음 이름
            continue
        if kind in ("top", "plain"):
            group = None
        cond = group or section
        if m := _LABEL_RE.match(item):
            # "신혼부부 : 혼인관계증명서" — 더 구체적인 앞 말을 조건으로 (묶음 이름이 있으면 함께)
            label, item = m.group(1).strip(), m.group(2).strip()
            cond = f"{group} · {label}" if group else label
        for name in _split_commas(item):
            c = cond
            if m := _CONDITION_RE.match(name):  # 끝 괄호 "(대리 신청 시)"
                name, inner = m.group(1).strip(), m.group(2).strip()
                c = inner if not c else f"{c} · {inner}"
            elif m := _LEAD_CONDITION_RE.match(name):  # 앞 괄호 "(자립교육신청·수료 후) 교육수료 확인서"
                inner, name = m.group(1).strip(), m.group(2).strip()
                c = inner if not c else f"{c} · {inner}"
            found.append((name, c))
    docs: list[dict] = []
    for name, cond in found:
        if len(name) > DOC_TEXT_MAX or (cond and len(cond) > DOC_TEXT_MAX):
            notes.append(f"서류 이름이 {DOC_TEXT_MAX}자를 넘어 줄임: '{name[:30]}…'")
        doc = {"name": _cut(name, DOC_TEXT_MAX), "condition": _cut(cond, DOC_TEXT_MAX)}
        if doc not in docs:
            docs.append(doc)
    return docs, notes


def build_original_text(row: dict, source_url: str) -> str:
    parts = [f"[출처 A: {SOURCE_NAME}]", source_url]
    for names, header in SECTIONS:
        v = text(row, *names)
        if v:
            parts += [f"[{header}]", v]
    return "\n".join(parts)


def to_policy(detail: dict, policy_key: str, today: date, conditions: dict | None = None) -> Converted:
    # conditions(지원조건)는 원본 보관용. JA0110·JA0111 같은 코드는 뜻이 확인되지 않아 정책 칸에 쓰지 않는다
    # (실제 응답: 자립준비청년 정책이 17~18 로 와서 나이로 볼 수 없었음)
    """상세 한 건 → 정책. 필수 내용(지원대상 · 지원내용 · 신청방법)이 원문에 없으면 넣지 않는다"""
    sid = text(detail, "서비스ID", "svcId") or ""
    name = text(detail, "서비스명", "svcNm")
    if not sid or not name:
        return Converted(None, sid, skip_reason="서비스ID·서비스명 없음")

    missing = [label for names, label in ((("지원대상",), "지원대상"), (("지원내용",), "지원내용"),
                                          (("신청방법",), "신청방법")) if not text(detail, *names)]
    if missing:
        return Converted(None, sid, skip_reason=f"원문에 {'·'.join(missing)} 없음")
    relevant, why_relevant = relevance(detail)
    if not relevant:
        return Converted(None, sid, skip_reason=f"{name}: {why_relevant}", off_target=True)

    notes: list[str] = [f"대상: {why_relevant}"]
    source_url = text(detail, "상세조회URL", "상세조회url") or DETAIL_URL.format(sid=sid)

    agency = " ".join(t for t in (text(detail, "소관기관명"), text(detail, "부서명")) if t) or SOURCE_NAME
    if len(agency) > AGENCY_MAX:
        agency = _cut(agency, AGENCY_MAX)

    category, why = pick_category(detail)
    notes.append(f"분야 {category} ({why})")
    region, region_note = pick_region(detail)
    if region_note:
        notes.append(region_note)

    target = text(detail, "지원대상")
    criteria = text(detail, "선정기준")
    # 정부24 는 선정기준 칸에 지원대상을 그대로 되풀이하는 일이 많다 → 같은 내용이면 붙이지 않음
    # (G018 등 6개: 대상 문단이 두 번 들어가 쉬운 말 검사가 "같은 값을 두 번 쓰라"고 하던 것)
    if criteria and _squash(criteria) not in _squash(target or ""):
        target = f"{target}\n선정기준: {criteria}" if target else criteria
    age_min, age_max, age_note = policy_ages(name, target or "", text(detail, "지원내용") or "")
    if age_note:
        notes.append(age_note)
    apply_method = text(detail, "신청방법")
    if deadline := text(detail, "신청기한"):
        apply_method = f"{apply_method}\n신청기한: {deadline}"
    if office := text(detail, "접수기관명", "접수기관"):
        apply_method = f"{apply_method}\n접수기관: {office}"

    # 신청인이 낼 서류: 구비서류 + 본인확인필요구비서류 (공무원확인구비서류는 담당 공무원이 확인 → 원문에만 남김)
    docs, doc_notes = split_docs("\n".join(t for t in (text(detail, "구비서류"), text(detail, "본인확인필요구비서류")) if t))
    notes += doc_notes
    if not docs:
        notes.append("구비서류가 원문에 없음 → 서류 준비 단계가 비어 보임")

    apply_url = text(detail, "온라인신청사이트URL")
    if apply_url and not apply_url.startswith(("http://", "https://")):
        apply_url = None

    policy = {
        "policy_key": policy_key,
        "name": _cut(name, NAME_MAX),
        "agency": agency,
        "contact": _cut(text(detail, "문의처", "전화문의"), CONTACT_MAX),
        "category": category,
        "region": region,
        "age_min": age_min,
        "age_max": age_max,
        "target_description": target,
        "support_content": text(detail, "지원내용"),
        "support_amount": _single_amount(text(detail, "지원내용")),
        "support_period": None,
        "apply_method": apply_method,
        "required_docs": docs,
        "apply_url": apply_url,
        "source_url": source_url,
        "original_text": build_original_text(detail, source_url),
        "easy_text": None,
        "easy_text_verified": False,
        "source_type": "api",
        "checked_at": today.isoformat(),
    }
    return Converted(policy, sid, notes)
