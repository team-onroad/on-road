"""GET /users/{user_id}/dashboard (api.md 3.4, 부록)."""

import uuid
from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import text

from tests.factories import make_policy, make_user

KST = timezone(timedelta(hours=9))
SPEC_ALLOCATIONS = {"housing": 300000, "food": 300000, "transport": 100000, "telecom": 50000, "other": 100000}


def _dashboard(api, user_id):
    return api.get(f"/api/users/{user_id}/dashboard")


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert res.json()["error"]["code"] == code


def _checklist(api, user, policy, checked_steps=()):
    data = api.post("/api/checklists", json={"user_id": str(user.id), "policy_id": policy.id}).json()
    for s in data["steps"]:
        if s["step_key"] in checked_steps:
            api.patch(f"/api/checklists/{data['checklist_id']}/items/{s['item_id']}",
                      params={"user_id": str(user.id)}, json={"is_checked": True})
    return data["checklist_id"]


ALL_STEPS = ("target_check", "condition_check", "doc_prepare", "apply")


def _set_time(db_session, table, column, row_id, ts):
    db_session.execute(text(f"UPDATE {table} SET {column} = :ts WHERE id = :id"), {"ts": ts, "id": row_id})
    db_session.commit()


def _simulate(api, user, allocations=None, total_income=900000):
    res = api.post("/api/simulations", json={
        "user_id": str(user.id), "total_income": total_income, "allocations": allocations or SPEC_ALLOCATIONS,
    })
    assert res.status_code == 201, res.text
    return res.json()["simulation_id"]


def test_empty_dashboard(api, db_session):
    user = make_user(db_session, status="leaving_soon", d_date=date(2026, 11, 12))
    res = _dashboard(api, user.id)

    assert res.status_code == 200
    assert res.json() == {
        "user": {"user_id": str(user.id), "name": "테스트", "stage": "youth", "status": "leaving_soon",
                 "d_date": "2026-11-12", "d_day": 39},
        "checklists": [],
        "latest_simulation": None,
    }


def test_user_without_d_date(api, db_session):
    user = make_user(db_session)
    data = _dashboard(api, user.id).json()["user"]
    assert (data["d_date"], data["d_day"]) == (None, None)


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
def test_unknown_user(api, user_id):
    _assert_error(_dashboard(api, user_id), 404, "USER_NOT_FOUND")


def test_stage_is_refreshed(api, db_session, clock):
    user = make_user(db_session, birth_date=date(2008, 10, 5), status="in_care")  # 오늘 17세 teen
    assert _dashboard(api, user.id).json()["user"]["stage"] == "teen"

    clock.today = date(2026, 10, 5)  # 18번째 생일
    assert _dashboard(api, user.id).json()["user"]["stage"] == "youth"
    db_session.expire_all()
    assert db_session.execute(text("SELECT stage FROM users WHERE id = :id"), {"id": user.id}).scalar() == "youth"


def test_checklist_fields(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session, name="자립수당", agency="보건복지부", contact=None, apply_url=None)
    cid = _checklist(api, user, policy, checked_steps=("target_check", "condition_check"))

    item = _dashboard(api, user.id).json()["checklists"][0]

    assert set(item) == {"checklist_id", "policy", "progress", "next_step", "updated_at"}
    assert item["checklist_id"] == cid
    assert item["policy"] == {
        "policy_id": policy.id, "name": "자립수당", "agency": "보건복지부", "category": "living_cost",
        "contact": None, "apply_url": None, "checked_at": "2026-09-28", "is_active": True,
    }
    assert item["progress"] == {"checked_steps": 2, "total_steps": 4, "percent": 50}
    assert item["next_step"] == {"step_key": "doc_prepare", "label": "서류 준비"}
    assert datetime.fromisoformat(item["updated_at"]).utcoffset().total_seconds() == 9 * 3600


@pytest.mark.parametrize(
    ("checked", "expected"),
    [
        ((), {"step_key": "target_check", "label": "대상 확인"}),
        (("condition_check",), {"step_key": "target_check", "label": "대상 확인"}),  # 체크 안 된 첫 단계
        (("target_check", "condition_check", "doc_prepare"), {"step_key": "apply", "label": "신청"}),
        (ALL_STEPS, None),  # 완료
    ],
)
def test_next_step(api, db_session, checked, expected):
    user = make_user(db_session)
    _checklist(api, user, make_policy(db_session), checked_steps=checked)
    assert _dashboard(api, user.id).json()["checklists"][0]["next_step"] == expected


def test_checklist_order(api, db_session):
    user = make_user(db_session)
    base = datetime(2026, 10, 1, 9, 0, tzinfo=KST)
    a = _checklist(api, user, make_policy(db_session))
    b = _checklist(api, user, make_policy(db_session))
    c = _checklist(api, user, make_policy(db_session), checked_steps=ALL_STEPS)
    d = _checklist(api, user, make_policy(db_session), checked_steps=ALL_STEPS)
    e = _checklist(api, user, make_policy(db_session))  # b 와 updated_at 같음, ID 는 e 가 큼
    for cid, hours in [(a, 1), (b, 3), (c, 4), (d, 2), (e, 3)]:
        _set_time(db_session, "checklists", "updated_at", cid, base + timedelta(hours=hours))

    order = [x["checklist_id"] for x in _dashboard(api, user.id).json()["checklists"]]

    # 진행 중(updated_at 내림차순, 같으면 ID 큰 것) → 완료(updated_at 내림차순)
    assert order == [e, b, a, c, d]


def test_toggle_moves_checklist_to_front(api, db_session):
    user = make_user(db_session)
    first = _checklist(api, user, make_policy(db_session))
    second = _checklist(api, user, make_policy(db_session))
    _set_time(db_session, "checklists", "updated_at", first, datetime(2026, 10, 1, tzinfo=KST))
    assert [x["checklist_id"] for x in _dashboard(api, user.id).json()["checklists"]] == [second, first]

    item_id = api.get(f"/api/checklists/{first}", params={"user_id": str(user.id)}).json()["steps"][0]["item_id"]
    api.patch(f"/api/checklists/{first}/items/{item_id}", params={"user_id": str(user.id)}, json={"is_checked": True})

    assert [x["checklist_id"] for x in _dashboard(api, user.id).json()["checklists"]] == [first, second]


def test_all_checklists_without_limit(api, db_session):
    user = make_user(db_session)
    ids = {_checklist(api, user, make_policy(db_session)) for _ in range(12)}
    assert {x["checklist_id"] for x in _dashboard(api, user.id).json()["checklists"]} == ids


def test_hidden_policy_checklist_stays_with_is_active_false(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    _checklist(api, user, policy)
    policy.easy_text_verified = False
    db_session.commit()
    items = _dashboard(api, user.id).json()["checklists"]
    assert len(items) == 1 and items[0]["policy"]["is_active"] is False


def test_other_users_data_excluded(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    _checklist(api, other, make_policy(db_session))
    _simulate(api, other)
    data = _dashboard(api, user.id).json()
    assert (data["checklists"], data["latest_simulation"]) == ([], None)


def test_latest_simulation(api, db_session):
    user = make_user(db_session)
    newest = _simulate(api, user)  # 주거비 부족 1개
    older = _simulate(api, user, allocations={**SPEC_ALLOCATIONS, "food": 200000, "telecom": 10000})  # 3개
    _set_time(db_session, "simulations", "created_at", older, datetime(2026, 10, 1, tzinfo=KST))
    _set_time(db_session, "simulations", "created_at", newest, datetime(2026, 10, 2, tzinfo=KST))

    latest = _dashboard(api, user.id).json()["latest_simulation"]

    assert latest == {"simulation_id": newest, "shortage_count": 1, "created_at": "2026-10-02T00:00:00+09:00"}


def test_latest_simulation_tie_uses_larger_id(api, db_session):
    user = make_user(db_session)
    first = _simulate(api, user)
    second = _simulate(api, user, allocations={**SPEC_ALLOCATIONS, "food": 200000})  # 부족 2개
    same = datetime(2026, 10, 1, tzinfo=KST)
    _set_time(db_session, "simulations", "created_at", first, same)
    _set_time(db_session, "simulations", "created_at", second, same)

    latest = _dashboard(api, user.id).json()["latest_simulation"]

    assert (latest["simulation_id"], latest["shortage_count"]) == (second, 2)


def test_latest_simulation_without_shortage(api, db_session):
    user = make_user(db_session)
    alloc = {"housing": 400000, "food": 250000, "transport": 60000, "telecom": 40000, "other": 100000}
    _simulate(api, user, allocations=alloc, total_income=850000)
    assert _dashboard(api, user.id).json()["latest_simulation"]["shortage_count"] == 0


def test_non_nullable_fields_have_values(api, db_session):
    user = make_user(db_session, d_date=date(2026, 12, 1))
    _checklist(api, user, make_policy(db_session))
    _simulate(api, user)
    data = _dashboard(api, user.id).json()

    assert all(v is not None for v in data["user"].values())
    assert all(v is not None for v in data["checklists"][0].values())
    assert all(v is not None for k, v in data["checklists"][0]["policy"].items())
    assert all(v is not None for v in data["latest_simulation"].values())
