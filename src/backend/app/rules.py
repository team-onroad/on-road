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
    if status == "left_care":
        return "youth"
    if status == "leaving_soon" and age >= 15:
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
