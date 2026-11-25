"""나이·성장 단계·D-day·조건 판정·오래된 정보 계산 (docs/db-schema.md 5장, docs/api.md 1.3·1.4·3.2).

날짜가 필요한 함수는 오늘 날짜(한국 시간)를 인자로 받는다. 결과는 저장하지 않고 필요할 때 계산한다.
(stage 만 예외로 users.stage 에 저장한다)
"""

import re
from collections.abc import Iterable
from datetime import date

from app.codes import NATIONWIDE

OUTDATED_DAYS = 180  # api.md 1.4


def calc_age(birth_date: date, today: date) -> int:
    """만 나이. 올해 생일이 지나지 않았으면 (올해 - 출생연도 - 1), 지났으면 (올해 - 출생연도)."""
    before_birthday = (today.month, today.day) < (birth_date.month, birth_date.day)
    return today.year - birth_date.year - (1 if before_birthday else 0)


def calc_stage(age: int, status: str) -> str:
    """성장 단계 (db-schema.md 5.2). 위에서부터 먼저 해당하는 단계로 정한다."""
    # 만 18세부터 자립준비청년 화면. 18세 미만은 퇴소 예정이어도 아동 · 청소년 화면
    # (이미 퇴소한 18세 미만은 보호가 조기 종료된 자립준비청년이라 청년 화면)
    if status == "left_care":
        return "youth"
    if age >= 18:
        return "youth"
    if age >= 11:
        return "teen"
    return "child"


def calc_d_day(d_date: date | None, today: date) -> int | None:
    """d_date 까지 남은 일수. 오늘이면 0, 지났으면 음수, 없으면 None."""
    if d_date is None:
        return None
    return (d_date - today).days


def is_outdated(checked_at: date, today: date) -> bool:
    """최종 확인일이 오늘 기준 180일보다 오래됐으면 True (정확히 180일 전은 False)."""
    return (today - checked_at).days > OUTDATED_DAYS


# 아동 대상 정책: 나이 상한이 아동복지법상 아동(18세 미만) 안. 가족(family_members) 나이가 맞으면 가족 대상 정책으로 보인다
CHILD_AGE_MAX = 17


def is_child_policy(age_max: int | None) -> bool:
    return age_max is not None and age_max <= CHILD_AGE_MAX


def judge_age(age: int, age_min: int | None, age_max: int | None) -> str:
    """만 나이가 정책 나이 범위 안이면 match, 밖이면 excluded. NULL 은 제한 없음 (db-schema.md 5.3)."""
    if age_min is not None and age < age_min:
        return "excluded"
    if age_max is not None and age > age_max:
        return "excluded"
    return "match"


# 시/도 다음에 오는 시 · 군 · 구 ("서울특별시 서초구", "경기도 광주시", "충청북도 단양군 복지과")
_SIDO_TAIL = ("특별시", "광역시", "특별자치시", "특별자치도", "통합특별시", "도")
_SIGUNGU_RE = re.compile(r"\S+(시|군|구)$")


def policy_district(agency: str | None) -> str | None:
    """담당기관이 시 · 군 · 구면 그 이름 ("서울특별시 서초구 …" → "서초구"). 중앙부처 · 시/도는 None"""
    parts = (agency or "").split()
    if len(parts) > 1 and parts[0].endswith(_SIDO_TAIL) and _SIGUNGU_RE.match(parts[1]):
        return parts[1]
    return None


def is_local_agency(agency: str | None) -> bool:
    return policy_district(agency) is not None


def judge_region(user_region: str, policy_region: str,
                 district: str | None = None, user_district: str | None = None) -> str:
    """정책 지역이 전국이거나 사용자 지역과 같으면 match, 다르면 excluded (db-schema.md 5.3).

    district: 시 · 군 · 구 정책이면 그 시 · 군 · 구 (policies.region 은 시/도까지라 "서울 서초구" 정책도 '서울').
    사용자 시 · 군 · 구(users.district)가 같으면 match, 다르면 excluded, 사용자가 정하지 않았으면 needs_check.
    """
    if policy_region not in (NATIONWIDE, user_region):
        return "excluded"
    if district is None or policy_region == NATIONWIDE:
        return "match"
    if user_district is None:
        return "needs_check"
    return "match" if user_district == district else "excluded"


def judge_eligibility(
    age: int, user_region: str, age_min: int | None, age_max: int | None, policy_region: str,
    district: str | None = None, user_district: str | None = None, family_ages: Iterable[int] = (),
) -> dict[str, str]:
    """조건 판정. 하나라도 excluded 면 excluded, 하나라도 needs_check 면 needs_check, 모두 match 면 match.

    needs_check: 시 · 군 · 구 정책인데 사용자 시 · 군 · 구를 모를 때 (지역),
    아동 대상 정책이 본인 나이로는 안 되지만 가족(자녀 · 동생) 나이가 맞을 때 (연령)
    """
    parts = {
        "age": judge_age(age, age_min, age_max),
        "region": judge_region(user_region, policy_region, district, user_district),
    }
    if parts["age"] == "excluded" and is_child_policy(age_max) and any(
        judge_age(a, age_min, age_max) == "match" for a in family_ages
    ):
        parts["age"] = "needs_check"
    values = parts.values()
    if "excluded" in values:
        overall = "excluded"
    elif "needs_check" in values:
        overall = "needs_check"
    else:
        overall = "match"
    return {"status": overall, **parts}


# 지금 보호받고 있는 아동만 받는 정책 (가정위탁아동 지원, 소년소녀가정 지원 등).
# 정책 데이터에 나이 조건이 비어 있는 경우가 많아 이름 · 대상 문장으로 알아본다.
# 자립 · 퇴소 · 보호종료 · 대학 진학처럼 보호가 끝난 뒤를 말하면 아동 전용으로 보지 않는다
_IN_CARE_CHILD = re.compile(r"가정위탁\s*(보호)?\s*아동|보호\s*대상\s*아동|요보호\s*아동|보호\s*아동|위탁\s*아동|생활\s*시설\s*아동|시설\s*아동|소년소녀\s*가정")
_AFTER_CARE = re.compile(r"자립|퇴소|보호\s*종료|종결|대학|진학|입학|청년")


# 보호가 끝나야 받는 정책: 대상 문장에 보호종료 · 퇴소 · 자립준비가 들어감 (맞춤 신청 로드맵의 '퇴소하면 신청'과 같은 기준)
LEAVER = re.compile(r"보호\s*종료|퇴소|자립\s*준비")


# 보호를 연장해 아직 시설 · 위탁에 있는 사람(18~24세)도 받는다고 적힌 정책 ("퇴소일을 연장한 … 포함", "보호연장아동 포함").
# "만기 · 연장보호 종료"처럼 연장했다가 끝난 경우는 아니다
_EXTENDED_OK = re.compile(r"연장한|연장\s*중|보호\s*연장\s*아동|연장\s*아동|퇴소일을\s*연장|보호\s*기간\s*(을\s*)?연장")


def extended_care_ok(target: str | None) -> bool:
    return bool(_EXTENDED_OK.search(target or ""))


def leave_status(user_status: str | None, policy_name: str, target: str | None, age: int | None = None) -> str | None:
    """after_leave: 아직 보호 중(in_care · leaving_soon)인데 퇴소해야 받는 정책. now: 퇴소했고 퇴소한 사람이 받는 정책.
    지금 보호받는 아동 전용 정책(가정위탁아동 지원 등)이나 대상에 보호 상태가 없는 정책은 None"""
    if user_status is None or not LEAVER.search(target or "") or is_in_care_child_policy(policy_name, target):
        return None
    if user_status == "left_care":
        return "now"
    # 18세 이상으로 아직 보호 중이면 보호연장 중: 연장한 사람도 받는 정책은 지금 해당
    if age is not None and age >= 18 and extended_care_ok(target):
        return "now"
    return "after_leave"


# 보호받던 곳에 따라 갈리는 정책 (users.care_type 판정)
_YOUTH_FACILITY = re.compile(r"청소년\s*복지\s*시설|청소년\s*쉼터|가정\s*밖\s*청소년|청소년\s*자립\s*지원관")
_CHILD_PROTECTION = re.compile(r"자립\s*준비\s*청년|아동\s*복지\s*시설|아동\s*양육\s*시설|공동\s*생활\s*가정|그룹홈|가정\s*위탁|위탁\s*가정|보호\s*종료\s*아동|보호종료아동")
_FOSTER_ONLY = re.compile(r"가정\s*위탁|위탁\s*아동|위탁\s*가정")
_FACILITY = re.compile(r"아동\s*복지\s*시설|아동\s*양육\s*시설|공동\s*생활\s*가정|그룹홈|시설")


def care_requirement(name: str, target: str | None) -> set[str] | None:
    """이 정책을 받는 보호 경로 (users.care_type 값의 집합). 이름 · 대상에 보호받던 곳이 안 나오면 None (가르지 않음).
    - 청소년복지시설(쉼터 · 자립지원관)만 나오면 youth_facility
    - 아동복지시설 · 가정위탁만 나오면 child_facility · foster (이름이 가정위탁 전용이고 시설이 없으면 foster 만)
    - 둘 다 나오면 셋 다
    이름에 청소년복지시설만 나오면 대상 문장과 상관없이 youth_facility (자립준비청년은 아동보호체계 출신이라 child_facility · foster)"""
    # 이름에 보호받던 곳이 나오면 이름이 기준 (대상 문장이 잘못 수집된 경우가 있다: G038)
    if _YOUTH_FACILITY.search(name or "") and not _CHILD_PROTECTION.search(_YOUTH_FACILITY.sub(" ", name or "")):
        return {"youth_facility"}
    text = f"{name or ''} {target or ''}"
    youth = bool(_YOUTH_FACILITY.search(text))
    child = bool(_CHILD_PROTECTION.search(_YOUTH_FACILITY.sub(" ", text)))
    if youth and child:
        return {"child_facility", "foster", "youth_facility"}
    if youth:
        return {"youth_facility"}
    if not child:
        return None
    if _FOSTER_ONLY.search(name or "") and not _FACILITY.search(text):
        return {"foster"}
    return {"child_facility", "foster"}


def care_status(care_type: str | None, name: str, target: str | None) -> str | None:
    """보호받던 곳 판정: match · excluded. 사용자가 모르거나(null) 정책이 가르지 않으면 None"""
    need = care_requirement(name, target)
    if care_type is None or need is None:
        return None
    return "match" if care_type in need else "excluded"


def is_in_care_child_policy(name: str, target: str | None) -> bool:
    """보호 중인 아동 전용 정책인지. 퇴소한 사용자에게는 본인 정책으로 보이지 않게 한다"""
    if not _IN_CARE_CHILD.search(name or ""):
        return False
    return not (_AFTER_CARE.search(name or "") or _AFTER_CARE.search(target or ""))


_CHILD_HOUSEHOLD = re.compile(r"소년소녀\s*가정")
_OTHER_GROUPS = re.compile(r"위탁|시설|퇴소|보호|및|등")


def is_child_household_policy(name: str) -> bool:
    """소년소녀가정(아동이 가정을 이끄는 세대)만 받는 정책. 시설 · 위탁 보호 중인 아동은 소년소녀가정이 아니다"""
    return bool(_CHILD_HOUSEHOLD.search(name or "")) and not _OTHER_GROUPS.search(name or "")
