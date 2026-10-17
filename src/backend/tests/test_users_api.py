"""POST /users, GET /users/{user_id}, PATCH /users/{user_id} (api.md 3.2, 3.3, 3.12)."""

import uuid
from datetime import date, datetime

import pytest
from sqlalchemy import text

ONBOARDING = {
    "name": "김지민",
    "birth_date": "2005-03-15",
    "phone": "01098765432",
    "region": "서울",
    "status": "leaving_soon",
    "d_date": "2026-11-12",
}
RESPONSE_KEYS = {
    "user_id", "name", "birth_date", "age", "phone", "region", "status", "stage",
    "d_date", "d_day", "created_at", "updated_at",
}


def _create(api, **overrides):
    body = {**ONBOARDING, **overrides}
    body = {k: v for k, v in body.items() if v is not ...}
    res = api.post("/api/users", json=body)
    assert res.status_code == 201, res.text
    return res.json()


def _ts(value):
    return datetime.fromisoformat(value)


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    body = res.json()
    assert set(body) == {"error"}
    assert body["error"]["code"] == code
    assert isinstance(body["error"]["message"], str) and body["error"]["message"]


def _db_user(db_session, user_id):
    return db_session.execute(
        text("SELECT * FROM users WHERE id = :id"), {"id": user_id}
    ).mappings().one()


# ---------------------------------------------------------------- POST /users


def test_create_user_matches_spec_example(api, db_session):
    data = _create(api)

    assert set(data) == RESPONSE_KEYS
    assert uuid.UUID(data["user_id"])
    assert data["name"] == "김지민"
    assert data["birth_date"] == "2005-03-15"
    assert data["age"] == 21
    assert data["phone"] == "01098765432"
    assert data["region"] == "서울"
    assert data["status"] == "leaving_soon"
    assert data["stage"] == "youth"
    assert data["d_date"] == "2026-11-12"
    assert data["d_day"] == 39
    assert datetime.fromisoformat(data["created_at"]).utcoffset().total_seconds() == 9 * 3600

    row = _db_user(db_session, data["user_id"])
    assert row["stage"] == "youth"  # stage 는 서버 계산 후 저장
    assert "age" not in row and "d_day" not in row  # 나이·D-day 는 저장하지 않음


def test_create_user_optional_fields(api):
    data = _create(api, phone=..., d_date=...)
    assert data["phone"] is None
    assert data["d_date"] is None
    assert data["d_day"] is None


def test_create_user_empty_phone_saved_as_null(api, db_session):
    data = _create(api, phone="")
    assert data["phone"] is None
    assert _db_user(db_session, data["user_id"])["phone"] is None


def test_create_user_strips_name(api):
    assert _create(api, name="  김지민 ")["name"] == "김지민"


@pytest.mark.parametrize(
    ("today", "age", "stage"),
    [
        (date(2026, 10, 4), 17, "teen"),  # 18번째 생일 전날
        (date(2026, 10, 5), 18, "youth"),  # 18번째 생일 당일
    ],
)
def test_create_user_birthday_boundary_18(api, clock, today, age, stage):
    clock.today = today
    data = _create(api, birth_date="2008-10-05", status="in_care")
    assert (data["age"], data["stage"]) == (age, stage)


@pytest.mark.parametrize(
    ("today", "age", "stage"),
    [
        (date(2026, 10, 4), 14, "teen"),  # 15번째 생일 전날
        (date(2026, 10, 5), 15, "youth"),  # 15번째 생일 당일 (퇴소 예정 + 15세)
    ],
)
def test_create_user_birthday_boundary_15_leaving_soon(api, clock, today, age, stage):
    clock.today = today
    data = _create(api, birth_date="2011-10-05", status="leaving_soon")
    assert (data["age"], data["stage"]) == (age, stage)


@pytest.mark.parametrize(
    ("birth_date", "status", "stage"),
    [
        ("2016-01-01", "left_care", "youth"),  # 1. 퇴소 후 (만 10세)
        ("2011-01-01", "leaving_soon", "youth"),  # 2. 퇴소 예정 + 만 15세
        ("2012-01-01", "leaving_soon", "teen"),  # 퇴소 예정 + 만 14세 → 4번
        ("2008-01-01", "in_care", "youth"),  # 3. 만 18세
        ("2015-01-01", "in_care", "teen"),  # 4. 만 11세
        ("2016-01-01", "in_care", "child"),  # 5. 만 10세
        ("2016-01-01", "leaving_soon", "child"),  # 퇴소 예정이어도 만 10세 → 5번
    ],
)
def test_create_user_stage_rules(api, birth_date, status, stage):
    assert _create(api, birth_date=birth_date, status=status)["stage"] == stage


def test_create_user_birth_date_today_is_allowed(api):
    data = _create(api, birth_date="2026-10-04", status="in_care")
    assert (data["age"], data["stage"]) == (0, "child")


@pytest.mark.parametrize(
    ("d_date", "d_day"),
    [("2026-10-04", 0), ("2026-10-01", -3)],
)
def test_create_user_d_day_today_or_past(api, d_date, d_day):
    assert _create(api, d_date=d_date)["d_day"] == d_day


@pytest.mark.parametrize(
    "overrides",
    [
        {"birth_date": "2026-10-05"},  # 미래 생년월일
        {"birth_date": "2005/03/15"},
        {"birth_date": "2005-02-30"},
        {"birth_date": 1110844800},  # 숫자 타임스탬프
        {"birth_date": "2005-03-15T00:00:00"},
        {"d_date": "2026.11.12"},
        {"phone": "010-9876-5432"},
        {"phone": "0109876543210"},
        {"phone": "021234567"},
        {"phone": 1098765432},
        {"region": "전국"},
        {"region": "서울특별시"},
        {"status": "unknown"},
        {"name": "   "},
        {"name": ""},
        {"name": "가" * 51},
        {"stage": "youth"},  # 명세에 없는 필드 (서버 계산)
        {"gender": "F"},
        {"name": None},
        {"region": None},
    ],
)
def test_create_user_validation_error(api, db_session, overrides):
    res = api.post("/api/users", json={**ONBOARDING, **overrides})
    _assert_error(res, 422, "VALIDATION_ERROR")
    assert db_session.execute(text("SELECT count(*) FROM users")).scalar() == 0


@pytest.mark.parametrize("missing", ["name", "birth_date", "region", "status"])
def test_create_user_missing_required(api, missing):
    body = {k: v for k, v in ONBOARDING.items() if k != missing}
    _assert_error(api.post("/api/users", json=body), 422, "VALIDATION_ERROR")


def test_validation_error_does_not_echo_personal_info(api):
    res = api.post("/api/users", json={**ONBOARDING, "phone": "010-9876-5432"})
    assert "9876" not in res.text
    assert "phone" in res.json()["error"]["message"]


# ------------------------------------------------------- GET /users/{user_id}


def test_get_user(api):
    created = _create(api)
    res = api.get(f"/api/users/{created['user_id']}")
    assert res.status_code == 200
    assert res.json() == created


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid", "123"])
def test_get_user_not_found(api, user_id):
    _assert_error(api.get(f"/api/users/{user_id}"), 404, "USER_NOT_FOUND")


def test_get_user_refreshes_stage_after_birthday(api, clock, db_session):
    created = _create(api, birth_date="2008-10-05", status="in_care")  # 오늘 17세 teen
    assert created["stage"] == "teen"

    clock.today = date(2026, 10, 5)  # 18번째 생일
    data = api.get(f"/api/users/{created['user_id']}").json()

    assert (data["age"], data["stage"]) == (18, "youth")
    assert _ts(data["updated_at"]) > _ts(created["updated_at"])
    assert _db_user(db_session, created["user_id"])["stage"] == "youth"


def test_get_user_keeps_updated_at_when_stage_unchanged(api, clock):
    created = _create(api)
    clock.today = date(2026, 10, 10)
    data = api.get(f"/api/users/{created['user_id']}").json()
    assert data["updated_at"] == created["updated_at"]
    assert data["d_day"] == 33  # D-day 는 조회 시점 기준으로 다시 계산


# ----------------------------------------------------- PATCH /users/{user_id}


def _patch(api, user_id, body):
    return api.patch(f"/api/users/{user_id}", json=body)


def test_patch_changes_only_sent_fields(api):
    created = _create(api)
    res = _patch(api, created["user_id"], {"name": "김지민2", "region": "부산"})

    assert res.status_code == 200
    data = res.json()
    assert (data["name"], data["region"]) == ("김지민2", "부산")
    unchanged = RESPONSE_KEYS - {"name", "region", "updated_at"}
    assert {k: data[k] for k in unchanged} == {k: created[k] for k in unchanged}
    assert _ts(data["updated_at"]) > _ts(created["updated_at"])


@pytest.mark.parametrize("value", [None, ""])
def test_patch_phone_null_or_empty_clears(api, value):
    created = _create(api)
    assert _patch(api, created["user_id"], {"phone": value}).json()["phone"] is None


def test_patch_d_date_null_clears(api):
    created = _create(api)
    data = _patch(api, created["user_id"], {"d_date": None}).json()
    assert (data["d_date"], data["d_day"]) == (None, None)


def test_patch_set_d_date(api):
    created = _create(api, status="left_care", d_date=...)
    data = _patch(api, created["user_id"], {"d_date": "2027-03-01"}).json()
    assert (data["d_date"], data["d_day"]) == ("2027-03-01", 148)


@pytest.mark.parametrize("field", ["name", "birth_date", "region", "status"])
def test_patch_null_not_allowed(api, field):
    created = _create(api)
    _assert_error(_patch(api, created["user_id"], {field: None}), 422, "VALIDATION_ERROR")


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"stage": "child"},
        {"name": "새이름", "stage": "child"},
        {"name": "   "},
        {"birth_date": "2026-10-05"},
        {"phone": "0101234"},
        {"region": "전국"},
        {"status": "done"},
        {"d_date": "2027-3-1"},
    ],
)
def test_patch_validation_error_leaves_user_unchanged(api, db_session, body):
    created = _create(api)
    _assert_error(_patch(api, created["user_id"], body), 422, "VALIDATION_ERROR")
    assert api.get(f"/api/users/{created['user_id']}").json() == created


def test_patch_to_left_care_clears_d_date(api):
    created = _create(api, status="leaving_soon", d_date="2026-11-12")
    data = _patch(api, created["user_id"], {"status": "left_care"}).json()
    assert (data["status"], data["stage"], data["d_date"], data["d_day"]) == (
        "left_care", "youth", None, None,
    )


def test_patch_to_left_care_with_d_date_saves_it(api):
    created = _create(api, status="leaving_soon", d_date="2026-11-12")
    data = _patch(api, created["user_id"], {"status": "left_care", "d_date": "2027-03-01"}).json()
    assert (data["status"], data["d_date"]) == ("left_care", "2027-03-01")


def test_patch_left_care_again_keeps_d_date(api):
    created = _create(api, status="left_care", d_date="2027-03-01")
    data = _patch(api, created["user_id"], {"status": "left_care"}).json()
    assert data["d_date"] == "2027-03-01"


@pytest.mark.parametrize(
    ("birth_date", "before", "status", "after"),
    [
        ("2010-01-01", "teen", "leaving_soon", "youth"),  # 만 16세: 퇴소 예정 → youth
        ("2012-01-01", "teen", "left_care", "youth"),  # 만 14세: 퇴소 후 → youth
        ("2012-01-01", "teen", "leaving_soon", "teen"),  # 만 14세: 퇴소 예정이어도 teen
    ],
)
def test_patch_status_recalculates_stage(api, db_session, birth_date, before, status, after):
    created = _create(api, birth_date=birth_date, status="in_care")
    assert created["stage"] == before

    data = _patch(api, created["user_id"], {"status": status}).json()

    assert data["stage"] == after
    assert _db_user(db_session, created["user_id"])["stage"] == after


def test_patch_left_care_to_in_care_recalculates_stage(api):
    created = _create(api, birth_date="2012-01-01", status="left_care")
    assert created["stage"] == "youth"
    assert _patch(api, created["user_id"], {"status": "in_care"}).json()["stage"] == "teen"


@pytest.mark.parametrize(
    ("birth_date", "age", "stage"),
    [
        ("2016-01-01", 10, "child"),
        ("2015-01-01", 11, "teen"),
        ("2008-10-04", 18, "youth"),  # 오늘이 18번째 생일
        ("2008-10-05", 17, "teen"),  # 내일이 18번째 생일
    ],
)
def test_patch_birth_date_recalculates_stage(api, birth_date, age, stage):
    created = _create(api, status="in_care")
    data = _patch(api, created["user_id"], {"birth_date": birth_date}).json()
    assert (data["birth_date"], data["age"], data["stage"]) == (birth_date, age, stage)
    assert _ts(data["updated_at"]) > _ts(created["updated_at"])


def test_patch_same_values_keeps_updated_at(api):
    created = _create(api)
    data = _patch(api, created["user_id"], {"name": created["name"], "status": created["status"]}).json()
    assert data["updated_at"] == created["updated_at"]


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
@pytest.mark.parametrize("body", [{"name": "새이름"}, {}, {"stage": "child"}, {"name": None}])
def test_patch_not_found_takes_priority_over_validation(api, user_id, body):
    _assert_error(_patch(api, user_id, body), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
def test_patch_not_found_takes_priority_over_malformed_json(api, user_id):
    res = api.patch(
        f"/api/users/{user_id}", content="{bad json", headers={"content-type": "application/json"}
    )
    _assert_error(res, 404, "USER_NOT_FOUND")


@pytest.mark.parametrize("content", ["{bad json", "", "[]", '"name"'])
def test_patch_malformed_body_is_validation_error(api, content):
    created = _create(api)
    res = api.patch(
        f"/api/users/{created['user_id']}",
        content=content,
        headers={"content-type": "application/json"},
    )
    _assert_error(res, 422, "VALIDATION_ERROR")


def test_validation_error_message_format(api):
    created = _create(api)
    empty = _patch(api, created["user_id"], {}).json()["error"]["message"]
    stage = _patch(api, created["user_id"], {"stage": "child"}).json()["error"]["message"]
    assert empty == "요청 값이 올바르지 않습니다."
    assert stage == "요청 값이 올바르지 않습니다. (stage)"
