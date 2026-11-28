"""문서 AI 항목 정의. 앱 서류 양식(app/forms.json)과 정책 신청서 항목(data/forms, src/formguide)을
같은 형식(작성 칸 목록)으로 맞춘다. 문서를 만들 때 이 목록을 documents.spec 에 스냅샷으로 저장한다.

value_source
- profile: 프로필로 미리 채우는 칸 (이름·생년월일·연락처·시/도)
- user: 앱에서 직접 쓰는 칸 (AI 도우미 사용 가능)
- handwrite: 앱에 입력하지 않고 양식에 손으로 쓰는 민감 칸 (주민등록번호·서명)
- 계좌번호는 user 칸(input_type account)으로 받아 양식에 채운다. LLM 에는 보내지 않는다
- office: 담당 공무원 기재란
"""

import re
from datetime import date

from app import ai_content
from app.forms import FormTemplate, get_templates

WRITABLE = ("profile", "user")
SENSITIVE_WORDS = ("주민등록번호", "외국인등록번호", "서명", "(인)", "날인", "도장", "비밀번호", "카드번호")
ESSAY_WORDS = ("사유", "동기", "계획", "소개", "포부", "목표", "경험", "이유", "하고 싶은")
# 앱 서류 양식의 field_id → 프로필 키
TEMPLATE_PROFILE_KEYS = {"name": "name", "birth": "birth_date", "phone": "phone"}
TEMPLATE_INPUT_TYPES = {"birth": "date", "phone": "phone", "end-date": "date", "address": "address"}


def _sensitive(label: str) -> bool:
    flat = label.replace(" ", "")
    return any(w in flat for w in SENSITIVE_WORDS)


def _field(**kw) -> dict:
    base = {
        "field_id": "", "label": "", "easy_label": None, "section": None, "guide": "", "caution": None,
        "example": None, "outline": None, "multiline": False, "essay": False, "required": None,
        "input_type": "text", "value_source": "user", "profile_key": None,
        "box": None,  # 원본 양식에서 값을 쓸 자리 (정책 신청서만, formguide/layout.py)
        "options": None,  # 체크박스 칸 선택지 (원본 양식의 "[ ]선택지")
    }
    base.update(kw)
    if base["value_source"] not in WRITABLE:
        base.update(example=None, profile_key=None, outline=None)
    return base


def from_template(t: FormTemplate) -> list[dict]:
    fields = []
    for f in t.fields:
        profile_key = TEMPLATE_PROFILE_KEYS.get(f.field_id)
        if f.field_id == "account" or "계좌" in f.label:
            source, input_type = "user", "account"
        elif _sensitive(f.label):
            source, input_type = "handwrite", "other"
        else:
            source = "profile" if profile_key else "user"
            input_type = TEMPLATE_INPUT_TYPES.get(f.field_id, "text")
        fields.append(_field(
            field_id=f.field_id, label=f.label, easy_label=f.easy_label, guide=f.guide, caution=f.caution,
            example=f.example, outline=f.outline, multiline=f.multiline, essay=f.essay, required=True,
            input_type=input_type, value_source=source, profile_key=profile_key if source == "profile" else None,
        ))
    return fields


# 신청서 칸 이름 → 프로필 키 (AI 가 profile_key 를 빠뜨린 경우, 양식에서 처음 나오는 칸만)
FORM_PROFILE_KEYS = {"성명": "name", "이름": "name", "생년월일": "birth_date", "연락처": "phone"}


# 신청인 본인이 아닌 사람의 묶음 (대리인 · 가족 · 보호자 …): 내 정보로 미리 채우거나 다른 서류 값을 가져오지 않는다
OTHER_PERSON_WORDS = ("대리인", "가족", "보호자", "부모", "추천인", "보증인", "법정대리", "후견", "위탁", "배우자", "세대원")


def is_other_person(spec: dict) -> bool:
    section = spec.get("section") or ""
    return any(w in section for w in OTHER_PERSON_WORDS) or _NORM_RE.sub("", spec.get("label") or "").startswith("대리인")


# 내 칸이 아닌 묶음 (가구원 · 부양의무자 · 바꿀 계좌 …)
NOT_MINE_WORDS = ("가구원", "부양의무", "세대주", "동거", "변경", "새 ", "신규")
# 다른 사람 · 기관 칸에 넣지 않을 내 개인정보 열쇠
PERSONAL_KEYS = {"성명", "연락처", "주소", "이메일", "계좌번호", "은행", "학교", "생년월일"}

# 내 정보가 아니라 기관 · 시설 · 회사 정보인 칸 (보호기관 주소, 회사 전화번호): 뜻으로 묶지 않고 칸 이름이 같을 때만
ORG_WORDS = ("기관", "시설", "회사", "직장", "사업장", "사업자", "중개", "센터", "운영", "단체", "법인", "가정")


MY_ADDRESS_LABELS = {
    "주소", "현주소", "주소지", "사는곳", "사는곳주소", "거주지", "거주지주소", "실거주지", "실거주지주소", "현거주지",
    "주민등록상주소", "주민등록주소", "주민등록지", "주소지주소", "자택주소", "집주소",
}


def _meaning_key(spec: dict, flat: str) -> str | None:
    """칸 이름이 달라도 같은 내 정보인 칸 ("사는 곳 주소" · "주소(소재지)" → 주소)"""
    low = flat.lower()
    if flat in MY_ADDRESS_LABELS:  # "새 주소" · "전 주소" · "희망 주소" 처럼 다른 곳은 묶지 않는다
        return "주소"
    if "이메일" in flat or "email" in low or low.endswith("mail"):
        return "이메일"
    if spec.get("input_type") == "account" or "계좌번호" in flat:
        return "계좌번호"
    if re.search(r"(휴대전화|휴대폰|핸드폰|연락처)", flat):
        return "연락처"
    if re.fullmatch(r"(은행|은행명|금융기관|금융기관명|거래은행)", flat):
        return "은행"
    if re.fullmatch(r"(학교|학교명|재학학교|재학중인학교|소속학교)", flat):
        return "학교"
    return None


def reuse_key(spec: dict) -> str | None:
    """다른 서류와 값을 나누는 열쇠. 다른 사람 칸은 None (내 이름이 대리인 성명에 들어가지 않게).
    내 주소 · 연락처 · 이메일 · 은행 · 계좌번호 · 학교는 칸 이름이 달라도 같은 열쇠로 묶는다"""
    if is_other_person(spec):
        return None
    base = label_key(spec["label"])
    section = spec.get("section") or ""
    where = section + spec["label"]
    not_mine = (any(w in where for w in NOT_MINE_WORDS) or any(w in spec["label"] for w in OTHER_PERSON_WORDS)
                or bool(re.search(r"\s?[2-9]$|^[2-9]번", section)))  # "동의자 2" · "급여계좌 2" · "부양의무자 3"
    if not_mine or any(w in where for w in ORG_WORDS):
        # 다른 사람 · 기관 · 시설 칸: 내 주소 · 연락처 · 이름 같은 개인정보는 넣지 않고, 그 밖의 칸만 이름이 같을 때
        return None if base in PERSONAL_KEYS or _meaning_key(spec, _NORM_RE.sub("", spec["label"])) else base
    return _meaning_key(spec, _NORM_RE.sub("", spec["label"]).replace("*", "").replace(":", "")) or base


def _guess_profile_key(label: str, taken: set[str]) -> str | None:
    key = FORM_PROFILE_KEYS.get(label_key(label.replace(":", "").replace("*", "").replace("(한글)", "")))
    if key == "phone" and "휴대" not in label and "연락처" not in label and "핸드폰" not in label:
        key = None  # 집 전화일 수 있다
    return key if key and key not in taken else None


def from_form_spec(spec) -> list[dict]:
    """formguide FormSpec → 작성 칸. 민감·공무원 칸 구분은 formguide/validate.py 결과를 따른다"""
    fields = []
    # 이미 같은 이름의 칸(성명 등)에 프로필 키가 있으면 추측하지 않는다 (예금주처럼 다른 칸의 name 은 상관없음)
    taken = {f.profile_key for f in spec.fields if f.profile_key and _guess_profile_key(f.label, set()) == f.profile_key}
    for f in spec.fields:
        if f.value_source == "user" and f.profile_key is None and f.input_type not in ("checkbox", "account"):
            guessed = _guess_profile_key(f.label, taken)
            if guessed:
                taken.add(guessed)
                f = f.model_copy(update={"value_source": "profile", "profile_key": guessed})
        is_account = f.input_type == "account" or "계좌번호" in f.label.replace(" ", "")
        source = "user" if is_account else "handwrite" if f.sensitive else f.value_source
        essay = source in WRITABLE and f.input_type == "text" and any(w in f.label for w in ESSAY_WORDS)
        fields.append(_field(
            field_id=f.id, label=f.label, section=f.section, guide=f.guide, example=None if f.sensitive else f.example,
            multiline=essay, essay=essay, required=f.required, input_type="account" if is_account else f.input_type,
            value_source=source,
            profile_key=f.profile_key if source == "profile" else None, box=f.box,
            options=(f.box or {}).get("options") if f.input_type == "checkbox" else None,
        ))
    return fields


def template_spec(template_id: str) -> tuple[str, list[dict]]:
    """(제목, 칸 목록). 없으면 KeyError"""
    t = get_templates()[template_id]
    return t.name, from_template(t)


def common_form_spec(form_id: str) -> tuple[str, str, list[dict]] | None:
    """공통 양식 (정책 없이 시작). (form_id, 제목, 칸 목록). 공통 양식이 아니면 None"""
    spec = ai_content.common_form(form_id)
    if spec is None:
        return None
    fields = from_form_spec(spec)
    for f in fields:
        f["checked"] = spec.checked
    return spec.form_id, spec.title, fields


def policy_form_spec(policy_key: str, form_id: str | None = None) -> tuple[str, str, list[dict]] | None:
    """(form_id, 제목, 칸 목록). 정책 신청서 항목이 없으면 None.
    form_id 를 주면 그 정책에 연결된 양식 중 그것 (data/forms/index.json), 안 주면 정책 신청서(find_form).
    칸마다 checked(사람이 원본 양식과 대조했는지)를 넣어 둔다"""
    spec = ai_content.form_for_policy(policy_key, form_id) if form_id else ai_content.find_form(policy_key)
    if spec is None:
        return None
    fields = from_form_spec(spec)
    for f in fields:
        f["checked"] = spec.checked
    return spec.form_id, spec.title, fields


# ---------------- 프로필 미리 채우기 (코드로만, LLM 에 보내지 않음) ----------------


def format_phone(phone: str) -> str:
    if len(phone) == 11:
        return f"{phone[:3]}-{phone[3:7]}-{phone[7:]}"
    if len(phone) == 10:
        return f"{phone[:3]}-{phone[3:6]}-{phone[6:]}"
    return phone


def profile_value(profile_key: str | None, name: str, birth_date: date, region: str, phone: str | None) -> str | None:
    if profile_key == "name":
        return name
    if profile_key == "birth_date":
        return birth_date.strftime("%Y.%m.%d")
    if profile_key == "phone":
        return format_phone(phone) if phone else None
    if profile_key == "region":
        return region
    return None


# ---------------- 다른 서류에서 가져오기 ----------------

_NORM_RE = re.compile(r"[\s()·,./]+")
# 같은 내용으로 볼 칸 이름 묶음 (붙여 쓴 칸 이름 기준)
LABEL_GROUPS = (
    ("성명", "이름"),
    ("연락처", "전화번호", "휴대전화", "휴대폰번호", "휴대전화번호"),
    ("주소", "현주소", "주소지"),
    ("학력", "최종학력"),
    ("자격증", "자격면허", "자격사항", "보유자격증"),
    ("경력", "경력사항", "경험활동"),
    ("자기소개", "자기소개서", "자기소개간단히"),
)


def label_key(label: str) -> str:
    flat = _NORM_RE.sub("", label)
    for group in LABEL_GROUPS:
        if flat in group:
            return group[0]
    return flat


# ---------------- 상담 AI → 문서 AI 핸드오프 후보 ----------------

TEMPLATE_KEYWORDS = {
    "scholarship": ("장학",),
    "program": ("프로그램", "캠프", "참가"),
    "allowance": ("자립수당",),
    "move-in": ("전입",),
    "resume-form": ("이력서", "입사", "지원서", "취업"),
}


def template_candidates(text: str, stage: str) -> list[tuple[str, str]]:
    """메시지에 맞는 앱 서류 양식 [(template_id, 이름)]. 단계에 맞는 양식만"""
    flat = text.replace(" ", "")
    out = []
    for t in get_templates().values():
        if stage not in t.stages:
            continue
        words = TEMPLATE_KEYWORDS.get(t.template_id, ()) + (t.name.replace(" ", "").split("(")[0],)
        if any(w and w in flat for w in words):
            out.append((t.template_id, t.name))
    return out


def stage_templates(stage: str) -> list[tuple[str, str]]:
    return [(t.template_id, t.name) for t in get_templates().values() if stage in t.stages]
