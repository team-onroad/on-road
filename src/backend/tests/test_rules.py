"""나이·성장 단계·D-day 계산 규칙 (db-schema.md 5.1, 5.2 / api.md 3.2). DB 불필요."""

from datetime import date

import pytest

from app.rules import calc_age, calc_d_day, calc_stage


@pytest.mark.parametrize(
    ("birth", "today", "age"),
    [
        (date(2005, 3, 15), date(2026, 3, 14), 20),  # 생일 전날
        (date(2005, 3, 15), date(2026, 3, 15), 21),  # 생일 당일
        (date(2005, 3, 15), date(2026, 10, 4), 21),
        (date(2005, 12, 31), date(2026, 1, 1), 20),  # 연초, 생일 전
        (date(2026, 10, 4), date(2026, 10, 4), 0),  # 오늘 태어남
        (date(2008, 2, 29), date(2026, 2, 28), 17),  # 윤일생: 평년 2/28 은 생일 전
        (date(2008, 2, 29), date(2026, 3, 1), 18),
    ],
)
def test_calc_age(birth, today, age):
    assert calc_age(birth, today) == age


@pytest.mark.parametrize(
    ("age", "status", "stage"),
    [
        # 1. 퇴소 후 → 나이와 관계없이 youth
        (5, "left_care", "youth"),
        (14, "left_care", "youth"),
        (25, "left_care", "youth"),
        # 2. 퇴소 예정 + 만 15세 이상 → youth (14/15 경계)
        (14, "leaving_soon", "teen"),
        (15, "leaving_soon", "youth"),
        (17, "leaving_soon", "youth"),
        # 3. 만 18세 이상 → youth (17/18 경계)
        (17, "in_care", "teen"),
        (18, "in_care", "youth"),
        (30, "in_care", "youth"),
        # 4. 그 외 만 11~17세 → teen (10/11 경계)
        (11, "in_care", "teen"),
        (11, "leaving_soon", "teen"),
        # 5. 만 10세 이하 → child
        (10, "in_care", "child"),
        (10, "leaving_soon", "child"),
        (0, "in_care", "child"),
    ],
)
def test_calc_stage(age, status, stage):
    assert calc_stage(age, status) == stage


@pytest.mark.parametrize(
    ("d_date", "d_day"),
    [
        (date(2026, 11, 12), 39),  # api.md 3.2 예시
        (date(2026, 10, 5), 1),
        (date(2026, 10, 4), 0),  # 오늘
        (date(2026, 10, 3), -1),  # 지남
        (date(2025, 10, 4), -365),
        (None, None),
    ],
)
def test_calc_d_day(d_date, d_day):
    assert calc_d_day(d_date, date(2026, 10, 4)) == d_day
