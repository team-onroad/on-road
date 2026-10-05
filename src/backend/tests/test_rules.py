"""나이·성장 단계·D-day·조건 판정·오래된 정보 규칙 (db-schema.md 5장 / api.md 1.4, 3.2). DB 불필요."""

from datetime import date

import pytest

from app.rules import (
    calc_age,
    calc_d_day,
    calc_stage,
    is_outdated,
    judge_age,
    judge_eligibility,
    judge_region,
)


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


# --- 조건 판정 (db-schema.md 5.3) ---

@pytest.mark.parametrize(
    ("age", "age_min", "age_max", "expected"),
    [
        (21, None, None, "match"),  # 제한 없음
        (21, 19, None, "match"),  # 하한만
        (18, 19, None, "excluded"),
        (19, 19, None, "match"),  # 하한 경계
        (21, None, 21, "match"),  # 상한 경계
        (22, None, 21, "excluded"),
        (21, 19, 34, "match"),  # 범위 안
        (18, 19, 34, "excluded"),  # 범위 아래
        (35, 19, 34, "excluded"),  # 범위 위
        (34, 19, 34, "match"),
        (21, 21, 21, "match"),  # 한 나이만
        (0, None, 10, "match"),
    ],
)
def test_judge_age(age, age_min, age_max, expected):
    assert judge_age(age, age_min, age_max) == expected


@pytest.mark.parametrize(
    ("user_region", "policy_region", "expected"),
    [
        ("서울", "전국", "match"),
        ("제주", "전국", "match"),
        ("서울", "서울", "match"),
        ("서울", "경기", "excluded"),
        ("광주", "전남", "excluded"),
    ],
)
def test_judge_region(user_region, policy_region, expected):
    assert judge_region(user_region, policy_region) == expected


@pytest.mark.parametrize(
    ("age_range", "policy_region", "expected"),
    [
        ((None, None), "전국", {"status": "match", "age": "match", "region": "match"}),
        ((None, None), "부산", {"status": "excluded", "age": "match", "region": "excluded"}),
        ((25, None), "서울", {"status": "excluded", "age": "excluded", "region": "match"}),
        ((25, None), "부산", {"status": "excluded", "age": "excluded", "region": "excluded"}),
    ],
)
def test_judge_eligibility_overall(age_range, policy_region, expected):
    assert judge_eligibility(21, "서울", *age_range, policy_region) == expected


def test_judge_eligibility_never_returns_needs_check_for_age_region():
    values = {
        v
        for age in range(0, 40)
        for rng in [(None, None), (19, None), (None, 24), (19, 24)]
        for region in ["전국", "서울", "부산"]
        for v in judge_eligibility(age, "서울", *rng, region).values()
    }
    assert values == {"match", "excluded"}


@pytest.mark.parametrize(
    ("checked_at", "expected"),
    [
        (date(2026, 10, 4), False),  # 오늘
        (date(2026, 4, 7), False),  # 정확히 180일 전
        (date(2026, 4, 6), True),  # 181일 전
        (date(2025, 1, 1), True),
        (date(2026, 10, 5), False),  # 미래 날짜
    ],
)
def test_is_outdated(checked_at, expected):
    today = date(2026, 10, 4)
    assert (today - date(2026, 4, 7)).days == 180  # 경계값 확인
    assert is_outdated(checked_at, today) is expected
