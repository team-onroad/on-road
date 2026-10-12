"""GET /users/{user_id}/growth (api.md 3.13, 부록)."""

import uuid
from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import text

from tests.factories import make_policy, make_user
from tests.test_dashboard_api import ALL_STEPS, SPEC_ALLOCATIONS, _checklist, _set_time, _simulate

KST = timezone(timedelta(hours=9))


def _growth(api, user_id):
    return api.get(f"/api/users/{user_id}/growth")


def test_empty_growth(api, db_session):
    user = make_user(db_session, status="leaving_soon", d_date=date(2026, 11, 12))
    res = _growth(api, user.id)

    assert res.status_code == 200
    data = res.json()
    created_at = data["user"].pop("created_at")
    assert datetime.fromisoformat(created_at).utcoffset().total_seconds() == 9 * 3600
    assert data == {
        "user": {"user_id": str(user.id), "name": "테스트", "stage": "youth", "status": "leaving_soon",
                 "d_date": "2026-11-12", "d_day": 39},
        "summary": {"checklist_count": 0, "completed_checklist_count": 0, "simulation_count": 0},
        "checklists": [],
        "simulations": [],
    }


def test_user_created_at_is_onboarding_time(api, db_session):
    user = make_user(db_session)
    _set_time(db_session, "users", "created_at", user.id, datetime(2026, 9, 20, 9, 30, tzinfo=KST))
    assert _growth(api, user.id).json()["user"]["created_at"] == "2026-09-20T09:30:00+09:00"


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
def test_unknown_user(api, user_id):
    res = _growth(api, user_id)
    assert res.status_code == 404
    assert res.json()["error"]["code"] == "USER_NOT_FOUND"


def test_stage_is_refreshed(api, db_session, clock):
    user = make_user(db_session, birth_date=date(2008, 10, 5), status="in_care")
    assert _growth(api, user.id).json()["user"]["stage"] == "teen"
    clock.today = date(2026, 10, 5)
    assert _growth(api, user.id).json()["user"]["stage"] == "youth"
    db_session.expire_all()
    assert db_session.execute(text("SELECT stage FROM users WHERE id = :id"), {"id": user.id}).scalar() == "youth"


def test_summary_and_checklists(api, db_session):
    user = make_user(db_session)
    in_progress = _checklist(api, user, make_policy(db_session, required_docs=["신분증", "통장 사본"]),
                             checked_steps=("target_check",))
    done = _checklist(api, user, make_policy(db_session, required_docs=[]), checked_steps=ALL_STEPS)
    hidden = make_policy(db_session)
    done_hidden = _checklist(api, user, hidden, checked_steps=ALL_STEPS)
    hidden.is_active = False
    db_session.commit()
    _simulate(api, user)

    data = _growth(api, user.id).json()

    assert data["summary"] == {"checklist_count": 3, "completed_checklist_count": 2, "simulation_count": 1}
    first = data["checklists"][0]
    assert set(first) == {
        "checklist_id", "policy", "progress", "document_progress", "next_step", "created_at", "updated_at",
    }
    assert first["checklist_id"] == in_progress
    assert first["progress"] == {"checked_steps": 1, "total_steps": 4, "percent": 25}
    assert first["document_progress"] == {"checked": 0, "total": 2}
    assert first["next_step"] == {"step_key": "condition_check", "label": "조건 확인"}
    by_id = {c["checklist_id"]: c for c in data["checklists"]}
    assert by_id[done]["next_step"] is None
    assert by_id[done]["document_progress"] == {"checked": 0, "total": 0}
    assert by_id[done_hidden]["policy"]["is_active"] is False  # 비활성 정책도 빠지지 않음


def test_checklists_same_order_as_dashboard(api, db_session):
    user = make_user(db_session)
    base = datetime(2026, 10, 1, 9, 0, tzinfo=KST)
    a = _checklist(api, user, make_policy(db_session))
    b = _checklist(api, user, make_policy(db_session))
    c = _checklist(api, user, make_policy(db_session), checked_steps=ALL_STEPS)
    d = _checklist(api, user, make_policy(db_session), checked_steps=ALL_STEPS)
    e = _checklist(api, user, make_policy(db_session))
    for cid, hours in [(a, 1), (b, 3), (c, 4), (d, 2), (e, 3)]:
        _set_time(db_session, "checklists", "updated_at", cid, base + timedelta(hours=hours))

    growth = [x["checklist_id"] for x in _growth(api, user.id).json()["checklists"]]
    dashboard = [x["checklist_id"] for x in api.get(f"/api/users/{user.id}/dashboard").json()["checklists"]]

    assert growth == dashboard == [e, b, a, c, d]


def test_checklist_created_at_and_updated_at(api, db_session):
    user = make_user(db_session)
    cid = _checklist(api, user, make_policy(db_session))
    _set_time(db_session, "checklists", "created_at", cid, datetime(2026, 9, 25, 14, 0, tzinfo=KST))
    _set_time(db_session, "checklists", "updated_at", cid, datetime(2026, 10, 2, 10, 15, tzinfo=KST))
    item = _growth(api, user.id).json()["checklists"][0]
    assert (item["created_at"], item["updated_at"]) == ("2026-09-25T14:00:00+09:00", "2026-10-02T10:15:00+09:00")


def test_simulations(api, db_session):
    user = make_user(db_session)
    spec = _simulate(api, user)  # 주거비 부족 1개, 남은 금액 50,000
    three = _simulate(api, user, allocations={**SPEC_ALLOCATIONS, "food": 200000, "telecom": 10000})  # 부족 3개
    none_ = _simulate(api, user, allocations={"housing": 400000, "food": 250000, "transport": 60000,
                                               "telecom": 40000, "other": 100000}, total_income=850000)
    _set_time(db_session, "simulations", "created_at", spec, datetime(2026, 10, 3, tzinfo=KST))
    _set_time(db_session, "simulations", "created_at", three, datetime(2026, 10, 1, tzinfo=KST))
    _set_time(db_session, "simulations", "created_at", none_, datetime(2026, 10, 2, tzinfo=KST))

    sims = _growth(api, user.id).json()["simulations"]

    assert sims == [  # 최신순
        {"simulation_id": spec, "total_income": 900000, "remaining": 50000, "shortage_count": 1,
         "criteria_version": "TEMP-2026-10-v1", "created_at": "2026-10-03T00:00:00+09:00"},
        {"simulation_id": none_, "total_income": 850000, "remaining": 0, "shortage_count": 0,
         "criteria_version": "TEMP-2026-10-v1", "created_at": "2026-10-02T00:00:00+09:00"},
        {"simulation_id": three, "total_income": 900000, "remaining": 190000, "shortage_count": 3,
         "criteria_version": "TEMP-2026-10-v1", "created_at": "2026-10-01T00:00:00+09:00"},
    ]


def test_simulations_tie_uses_larger_id(api, db_session):
    user = make_user(db_session)
    ids = [_simulate(api, user) for _ in range(3)]
    for sid in ids:
        _set_time(db_session, "simulations", "created_at", sid, datetime(2026, 10, 1, tzinfo=KST))
    assert [s["simulation_id"] for s in _growth(api, user.id).json()["simulations"]] == sorted(ids, reverse=True)


def test_all_records_without_limit(api, db_session):
    user = make_user(db_session)
    for _ in range(12):
        _checklist(api, user, make_policy(db_session))
        _simulate(api, user)
    data = _growth(api, user.id).json()
    assert (len(data["checklists"]), len(data["simulations"])) == (12, 12)
    assert data["summary"]["checklist_count"] == data["summary"]["simulation_count"] == 12


def test_other_users_data_excluded(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    _checklist(api, other, make_policy(db_session))
    _simulate(api, other)
    data = _growth(api, user.id).json()
    assert data["summary"] == {"checklist_count": 0, "completed_checklist_count": 0, "simulation_count": 0}
    assert (data["checklists"], data["simulations"]) == ([], [])


def test_nullable_fields(api, db_session):
    """null 가능: user.d_date·d_day, policy.contact·apply_url, next_step 만"""
    user = make_user(db_session)
    _checklist(api, user, make_policy(db_session, contact=None, apply_url=None), checked_steps=ALL_STEPS)
    _simulate(api, user)
    data = _growth(api, user.id).json()

    assert {k for k, v in data["user"].items() if v is None} == {"d_date", "d_day"}
    c = data["checklists"][0]
    assert {k for k, v in c.items() if v is None} == {"next_step"}
    assert {k for k, v in c["policy"].items() if v is None} == {"contact", "apply_url"}
    assert all(v is not None for v in data["simulations"][0].values())
    assert all(v is not None for v in data["summary"].values())
