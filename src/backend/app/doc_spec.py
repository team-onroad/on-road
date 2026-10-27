"""문서 AI 항목 정의. 앱 서류 양식(app/forms.json)과 정책 신청서 항목(data/forms, src/formguide)을
같은 형식(작성 칸 목록)으로 맞춘다. 문서를 만들 때 이 목록을 documents.spec 에 스냅샷으로 저장한다.

value_source
- profile: 프로필로 미리 채우는 칸 (이름·생년월일·연락처·시/도)
- user: 앱에서 직접 쓰는 칸 (AI 도우미 사용 가능)
- handwrite: 앱에 입력하지 않고 양식에 손으로 쓰는 민감 칸 (주민등록번호·계좌·서명)
- office: 담당 공무원 기재란
"""

import re
from datetime import date

from app import ai_content
from app.forms import FormTemplate, get_templates

WRITABLE = ("profile", "user")
SENSITIVE_WORDS = ("주민등록번호", "외국인등록번호", "계좌", "서명", "(인)", "날인", "도장", "비밀번호", "카드번호")
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
        if _sensitive(f.label) or f.field_id == "account":
            source, input_type = "handwrite", "account" if "계좌" in f.label or f.field_id == "account" else "other"
        else:
            source = "profile" if profile_key else "user"
            input_type = TEMPLATE_INPUT_TYPES.get(f.field_id, "text")
        fields.append(_field(
            field_id=f.field_id, label=f.label, easy_label=f.easy_label, guide=f.guide, caution=f.caution,
            example=f.example, outline=f.outline, multiline=f.multiline, essay=f.essay, required=True,
            input_type=input_type, value_source=source, profile_key=profile_key if source == "profile" else None,
        ))
    return fields


def from_form_spec(spec) -> list[dict]:
    """formguide FormSpec → 작성 칸. 민감·공무원 칸 구분은 formguide/validate.py 결과를 따른다"""
    fields = []
    for f in spec.fields:
        source = "handwrite" if f.sensitive else f.value_source
        essay = source in WRITABLE and f.input_type == "text" and any(w in f.label for w in ESSAY_WORDS)
        fields.append(_field(
            field_id=f.id, label=f.label, section=f.section, guide=f.guide, example=None if f.sensitive else f.example,
            multiline=essay, essay=essay, required=f.required, input_type=f.input_type, value_source=source,
            profile_key=f.profile_key if source == "profile" else None, box=f.box,
            options=(f.box or {}).get("options") if f.input_type == "checkbox" else None,
        ))
    return fields


def template_spec(template_id: str) -> tuple[str, list[dict]]:
    """(제목, 칸 목록). 없으면 KeyError"""
    t = get_templates()[template_id]
    return t.name, from_template(t)


def policy_form_spec(policy_key: str) -> tuple[str, str, list[dict]] | None:
    """(form_id, 제목, 칸 목록). 정책 신청서 항목이 없으면 None.
    칸마다 checked(사람이 원본 양식과 대조했는지)를 넣어 둔다"""
    spec = ai_content.find_form(policy_key)
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
