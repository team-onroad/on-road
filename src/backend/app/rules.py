"""나이·성장 단계·D-day·조건 판정·오래된 정보 계산 (docs/db-schema.md 5장, docs/api.md 1.3·1.4·3.2).

날짜가 필요한 함수는 오늘 날짜(한국 시간)를 인자로 받는다. 결과는 저장하지 않고 필요할 때 계산한다.
(stage 만 예외로 users.stage 에 저장한다)
"""

import re
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


# 아동 대상 정책: 나이 상한이 아동복지법상 아동(18세 미만) 안. 자녀 · 동생이 있는 사용자에게 '가족을 위한 정책'으로 보인다
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


def judge_region(user_region: str, policy_region: str, local: bool = False) -> str:
    """정책 지역이 전국이거나 사용자 지역과 같으면 match, 다르면 excluded (db-schema.md 5.3).

    local=True (시 · 군 · 구 정책): policies.region 은 시/도까지라 "서울 서초구" 정책도 region 이 '서울'이다.
    users.region 도 시/도까지라 같은 구에 사는지 알 수 없으므로, 시/도가 같으면 match 대신 needs_check.
    """
    if policy_region not in (NATIONWIDE, user_region):
        return "excluded"
    return "needs_check" if local and policy_region != NATIONWIDE else "match"


# 시/도 다음에 오는 시 · 군 · 구 ("서울특별시 서초구", "경기도 광주시", "충청북도 단양군 복지과")
_SIDO_TAIL = ("특별시", "광역시", "특별자치시", "특별자치도", "통합특별시", "도")
_SIGUNGU_RE = re.compile(r"\S+(시|군|구)$")


def is_local_agency(agency: str | None) -> bool:
    """담당기관이 시 · 군 · 구면 True (정부24 소관기관명 형식: "서울특별시 서초구 …"). 중앙부처 · 시/도는 False"""
    parts = (agency or "").split()
    return len(parts) > 1 and parts[0].endswith(_SIDO_TAIL) and bool(_SIGUNGU_RE.match(parts[1]))


def judge_eligibility(
    age: int, user_region: str, age_min: int | None, age_max: int | None, policy_region: str,
    local: bool = False, family: bool = False,
) -> dict[str, str]:
    """조건 판정. 하나라도 excluded 면 excluded, 모두 match 면 match.

    needs_check 는 시 · 군 · 구 정책(local=True)의 지역 판정에서만 나온다 (같은 구에 사는지 앱이 알 수 없음).
    """
    parts = {
        "age": judge_age(age, age_min, age_max),
        "region": judge_region(user_region, policy_region, local),
    }
    # 자녀 · 동생이 있으면 아동 대상 정책은 본인 나이로 빼지 않고 '가족 기준 확인'(needs_check)으로 둔다
    if family and parts["age"] == "excluded" and is_child_policy(age_max):
        parts["age"] = "needs_check"
    values = parts.values()
    if "excluded" in values:
        overall = "excluded"
    elif "needs_check" in values:
        overall = "needs_check"
    else:
        overall = "match"
    return {"status": overall, **parts}
