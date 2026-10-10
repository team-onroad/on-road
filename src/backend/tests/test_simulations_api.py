"""GET /simulations/criteria, POST /simulations, GET /simulations/{id} (api.md 3.7, 3.8, 3.14).

대안(alternatives) 규칙은 tests/test_alternatives.py 에서 검증한다.
"""

import uuid
from datetime import date

import pytest
from sqlalchemy import text

from app.criteria import Criteria, get_criteria
from app.main import app
from app.schemas.simulations import INT_MAX
from tests.factories import make_policy, make_user

SPEC_ALLOCATIONS = {"housing": 300000, "food": 300000, "transport": 100000, "telecom": 50000, "other": 100000}
AT_MINIMUM = {"housing": 400000, "food": 250000, "transport": 60000, "telecom": 40000, "other": 100000}
RESPONSE_KEYS = {
    "simulation_id", "criteria_version", "total_income", "allocations", "remaining", "shortages",
    "alternatives", "related_policies", "created_at",
}


def _submit(api, user_id, total_income=900000, allocations=None, **extra):
    body = {"user_id": str(user_id), "total_income": total_income,
            "allocations": allocations or SPEC_ALLOCATIONS, **extra}
    return api.post("/api/simulations", json=body)


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def _count(db_session):
    return db_session.execute(text("SELECT count(*) FROM simulations")).scalar()


# --------------------------------------------------------------- 3.7 기준표


def test_criteria_table(api):
    res = api.get("/api/simulations/criteria")
    assert res.status_code == 200
    assert res.json() == {
        "criteria_version": "TEMP-2026-10-v1",
        "items": [
            {"key": "housing", "label": "주거비", "minimum": 400000},
            {"key": "food", "label": "식비", "minimum": 250000},
            {"key": "transport", "label": "교통비", "minimum": 60000},
            {"key": "telecom", "label": "통신비", "minimum": 40000},
            {"key": "other", "label": "기타", "minimum": 100000},
        ],
    }


# ------------------------------------------------------------- 3.8 제출·저장


def test_submit_spec_example(api, db_session):
    user = make_user(db_session)
    housing = make_policy(db_session, category="housing", name="주거 지원")
    make_policy(db_session, category="living_cost")  # 식비 등은 부족하지 않아 관련 없음

    res = _submit(api, user.id)

    assert res.status_code == 201, res.text
    data = res.json()
    assert set(data) == RESPONSE_KEYS
    assert data["criteria_version"] == "TEMP-2026-10-v1"
    assert data["total_income"] == 900000
    assert data["allocations"] == SPEC_ALLOCATIONS
    assert data["remaining"] == 50000
    assert data["shortages"] == [
        {"item": "housing", "label": "주거비", "input": 300000, "minimum": 400000, "gap": 100000}
    ]
    assert data["related_policies"] == [
        {"policy_id": housing.id, "name": "주거 지원", "agency": "테스트 기관", "category": "housing"}
    ]
    assert data["created_at"].endswith("+09:00")


def test_result_jsonb_follows_db_schema(api, db_session):
    user = make_user(db_session)
    housing = make_policy(db_session, category="housing")
    sim_id = _submit(api, user.id).json()["simulation_id"]

    row = db_session.execute(
        text("SELECT user_id, total_income, housing, food, transport, telecom, other, result, criteria_version "
             "FROM simulations WHERE id = :id"), {"id": sim_id}
    ).mappings().one()

    assert row["user_id"] == user.id
    assert (row["total_income"], row["housing"], row["other"]) == (900000, 300000, 100000)
    assert row["criteria_version"] == "TEMP-2026-10-v1"
    assert set(row["result"]) == {"shortages", "alternatives", "related_policy_ids"}
    # 항목 이름(label)은 저장하지 않고 응답할 때 붙인다
    assert row["result"]["shortages"] == [{"item": "housing", "input": 300000, "minimum": 400000, "gap": 100000}]
    assert row["result"]["related_policy_ids"] == [housing.id]


def test_no_shortage(api, db_session):
    user = make_user(db_session)
    make_policy(db_session, category="housing")
    data = _submit(api, user.id, total_income=850000, allocations=AT_MINIMUM).json()
    # 기준값과 같으면 부족이 아님. 합계 = 총소득도 허용
    assert (data["remaining"], data["shortages"], data["alternatives"], data["related_policies"]) == (0, [], [], [])


def test_sum_exceeds_income(api, db_session):
    user = make_user(db_session)
    _assert_error(_submit(api, user.id, total_income=849999), 422, "SUM_EXCEEDS_INCOME")
    assert _count(db_session) == 0


def test_multiple_shortages_follow_criteria_order(api, db_session):
    user = make_user(db_session)
    alloc = {"other": 50000, "telecom": 40000, "transport": 10000, "food": 100000, "housing": 300000}
    shortages = _submit(api, user.id, total_income=600000, allocations=alloc).json()["shortages"]
    assert [(s["item"], s["gap"]) for s in shortages] == [
        ("housing", 100000), ("food", 150000), ("transport", 50000), ("other", 50000),
    ]


def test_related_policies_order_dedupe_and_filters(api, db_session):
    user = make_user(db_session, region="서울")  # 만 21세
    fin = make_policy(db_session, category="finance")
    lc1 = make_policy(db_session, category="living_cost")
    lc2 = make_policy(db_session, category="living_cost", region="서울")
    make_policy(db_session, category="housing")  # 주거비는 부족하지 않음
    make_policy(db_session, category="living_cost", is_active=False)
    make_policy(db_session, category="living_cost", easy_text_verified=False)
    make_policy(db_session, category="living_cost", region="부산")  # excluded (지역)
    make_policy(db_session, category="finance", age_min=30)  # excluded (나이)
    alloc = {**AT_MINIMUM, "food": 200000, "other": 50000}  # 식비(living_cost)·기타(living_cost, finance) 부족

    related = _submit(api, user.id, total_income=1000000, allocations=alloc).json()["related_policies"]

    # 부족 항목 순서(식비 → 기타) → policy_categories 순서 → policy_id, 중복 제거
    assert [p["policy_id"] for p in related] == [lc1.id, lc2.id, fin.id]


def test_same_input_gives_same_result(api, db_session):
    user = make_user(db_session)
    make_policy(db_session, category="housing")
    a, b = (_submit(api, user.id).json() for _ in range(2))
    for d in (a, b):
        d.pop("simulation_id")
        d.pop("created_at")
    assert a == b


# -------------------------------------------------------------- 3.8 검증·404


@pytest.mark.parametrize(
    "body",
    [
        {"total_income": 0},
        {"total_income": -1},
        {"total_income": "900000"},
        {"total_income": 900000.5},
        {"total_income": True},
        {"total_income": None},
        {"total_income": INT_MAX + 1},
        {"allocations": {k: v for k, v in SPEC_ALLOCATIONS.items() if k != "other"}},
        {"allocations": {**SPEC_ALLOCATIONS, "savings": 0}},
        {"allocations": {**SPEC_ALLOCATIONS, "food": -1}},
        {"allocations": {**SPEC_ALLOCATIONS, "food": "300000"}},
        {"allocations": {**SPEC_ALLOCATIONS, "food": 1.5}},
        {"allocations": {**SPEC_ALLOCATIONS, "food": False}},
        {"allocations": {**SPEC_ALLOCATIONS, "food": INT_MAX + 1}},
        {"allocations": [300000, 300000, 100000, 50000, 100000]},
        {"allocations": None},
        {"criteria_version": "x"},  # 명세에 없는 필드
    ],
)
def test_validation_error(api, db_session, body):
    user = make_user(db_session)
    base = {"user_id": str(user.id), "total_income": 900000, "allocations": SPEC_ALLOCATIONS}
    _assert_error(api.post("/api/simulations", json={**base, **body}), 422, "VALIDATION_ERROR")
    assert _count(db_session) == 0


@pytest.mark.parametrize("missing", ["total_income", "allocations"])
def test_missing_field(api, db_session, missing):
    user = make_user(db_session)
    body = {"user_id": str(user.id), "total_income": 900000, "allocations": SPEC_ALLOCATIONS}
    body.pop(missing)
    _assert_error(api.post("/api/simulations", json=body), 422, "VALIDATION_ERROR")


def test_int_max_is_allowed(api, db_session):
    user = make_user(db_session)
    alloc = {**AT_MINIMUM, "other": INT_MAX - 750000}
    res = _submit(api, user.id, total_income=INT_MAX, allocations=alloc)
    assert res.status_code == 201, res.text
    assert res.json()["remaining"] == 0


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid", ""])
def test_unknown_user(api, user_id):
    _assert_error(_submit(api, user_id), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize(
    "body",
    [
        {"total_income": -1},
        {"allocations": {"housing": "x"}},
        {"total_income": 1},  # 합계 초과 (SUM_EXCEEDS_INCOME 보다도 먼저)
    ],
)
def test_unknown_user_takes_priority(api, body):
    base = {"user_id": "not-a-uuid", "total_income": 900000, "allocations": SPEC_ALLOCATIONS}
    _assert_error(api.post("/api/simulations", json={**base, **body}), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize("body", [{"total_income": 900000, "allocations": SPEC_ALLOCATIONS}, []])
def test_user_id_missing_is_422(api, body):
    _assert_error(api.post("/api/simulations", json=body), 422, "VALIDATION_ERROR")


# ---------------------------------------------------------- 3.14 결과 조회


def _get(api, sim_id, user_id):
    return api.get(f"/api/simulations/{sim_id}", params={"user_id": str(user_id)})


def test_get_returns_submitted_result(api, db_session):
    user = make_user(db_session)
    make_policy(db_session, category="housing")
    submitted = _submit(api, user.id).json()

    res = _get(api, submitted["simulation_id"], user.id)

    assert res.status_code == 200
    assert res.json() == submitted


def test_get_other_users_simulation_is_not_found(api, db_session):
    owner = make_user(db_session)
    other = make_user(db_session)
    sim_id = _submit(api, owner.id).json()["simulation_id"]
    _assert_error(_get(api, sim_id, other.id), 404, "SIMULATION_NOT_FOUND")


@pytest.mark.parametrize("sim_id", ["999999", "abc", "0", "-1", "9223372036854775808"])
def test_get_unknown_simulation(api, db_session, sim_id):
    user = make_user(db_session)
    _assert_error(_get(api, sim_id, user.id), 404, "SIMULATION_NOT_FOUND")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
@pytest.mark.parametrize("sim_id", ["1", "abc"])
def test_get_unknown_user_takes_priority(api, db_session, user_id, sim_id):
    owner = make_user(db_session)
    _submit(api, owner.id)
    _assert_error(_get(api, sim_id, user_id), 404, "USER_NOT_FOUND")


def test_get_without_user_id_is_422(api, db_session):
    user = make_user(db_session)
    sim_id = _submit(api, user.id).json()["simulation_id"]
    _assert_error(api.get(f"/api/simulations/{sim_id}"), 422, "VALIDATION_ERROR")


def test_get_refilters_related_policies(api, db_session, clock):
    user = make_user(db_session, region="서울", birth_date=date(2005, 10, 5))  # 오늘 만 20세
    keep = make_policy(db_session, category="housing")
    deactivated = make_policy(db_session, category="housing")
    unverified = make_policy(db_session, category="housing")
    aged_out = make_policy(db_session, category="housing", age_max=20)
    submitted = _submit(api, user.id).json()
    assert [p["policy_id"] for p in submitted["related_policies"]] == [
        keep.id, deactivated.id, unverified.id, aged_out.id,
    ]

    deactivated.is_active = False
    unverified.easy_text_verified = False
    db_session.commit()
    make_policy(db_session, category="housing")  # 제출 뒤에 생긴 정책은 추가하지 않음
    clock.today = date(2026, 10, 5)  # 생일이 지나 만 21세 → aged_out 은 excluded

    data = _get(api, submitted["simulation_id"], user.id).json()

    assert [p["policy_id"] for p in data["related_policies"]] == [keep.id]
    other_fields = {k: v for k, v in data.items() if k != "related_policies"}
    assert other_fields == {k: v for k, v in submitted.items() if k != "related_policies"}


def test_get_uses_stored_result_even_if_criteria_changes(api, db_session):
    user = make_user(db_session)
    submitted = _submit(api, user.id).json()

    changed = get_criteria().model_copy(deep=True)
    changed.version = "2027-01-v2"
    changed.items["housing"].minimum = 100000
    app.dependency_overrides[get_criteria] = lambda: Criteria.model_validate(changed.model_dump())
    try:
        data = _get(api, submitted["simulation_id"], user.id).json()
    finally:
        app.dependency_overrides.pop(get_criteria, None)

    assert data["criteria_version"] == "TEMP-2026-10-v1"  # 제출 당시 기준
    assert data["shortages"] == submitted["shortages"]


def test_user_region_change_refilters_related(api, db_session):
    user = make_user(db_session, region="서울")
    seoul = make_policy(db_session, category="housing", region="서울")
    nationwide = make_policy(db_session, category="housing")
    sim_id = _submit(api, user.id).json()["simulation_id"]

    api.patch(f"/api/users/{user.id}", json={"region": "부산"})
    related = _get(api, sim_id, user.id).json()["related_policies"]

    assert [p["policy_id"] for p in related] == [nationwide.id]
    assert seoul.id not in [p["policy_id"] for p in related]
