"""나이·성장 단계·D-day·조건 판정·오래된 정보 계산 (docs/db-schema.md 5장, docs/api.md 1.3·1.4·3.2).

날짜가 필요한 함수는 오늘 날짜(한국 시간)를 인자로 받는다. 결과는 저장하지 않고 필요할 때 계산한다.
(stage 만 예외로 users.stage 에 저장한다)
"""

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


def judge_age(age: int, age_min: int | None, age_max: int | None) -> str:
    """만 나이가 정책 나이 범위 안이면 match, 밖이면 excluded. NULL 은 제한 없음 (db-schema.md 5.3)."""
    if age_min is not None and age < age_min:
        return "excluded"
    if age_max is not None and age > age_max:
        return "excluded"
    return "match"


def judge_region(user_region: str, policy_region: str) -> str:
    """정책 지역이 전국이거나 사용자 지역과 같으면 match, 다르면 excluded (db-schema.md 5.3)."""
    return "match" if policy_region in (NATIONWIDE, user_region) else "excluded"


def judge_eligibility(
    age: int, user_region: str, age_min: int | None, age_max: int | None, policy_region: str
) -> dict[str, str]:
    """조건 판정. 하나라도 excluded 면 excluded, 모두 match 면 match.

    needs_check 는 현재 연령·지역 판정에서는 나오지 않는다 (이후 다른 조건을 추가할 때 사용).
    """
    parts = {
        "age": judge_age(age, age_min, age_max),
        "region": judge_region(user_region, policy_region),
    }
    values = parts.values()
    if "excluded" in values:
        overall = "excluded"
    elif "needs_check" in values:
        overall = "needs_check"
    else:
        overall = "match"
    return {"status": overall, **parts}
