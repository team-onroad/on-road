"""나이·성장 단계·D-day 계산 (docs/db-schema.md 5장, docs/api.md 3.2).

모든 함수는 오늘 날짜(한국 시간)를 인자로 받는다. 결과는 저장하지 않고 필요할 때 계산한다.
(stage 만 예외로 users.stage 에 저장한다)
"""

from datetime import date


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
