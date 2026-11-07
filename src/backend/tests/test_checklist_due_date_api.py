"""신청 예정일: PATCH /checklists/{checklist_id}"""

import uuid
from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import text

from tests.factories import make_policy, make_user
from tests.test_checklists_api import RESPONSE_KEYS

KST = timezone(timedelta(hours=9))


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def _checklist(api, user, policy):
    res = api.post("/api/checklists", json={"user_id": str(user.id), "policy_id": policy.id})
    assert res.status_code == 201, res.text
    return res.json()["checklist_id"]


def _patch(api, checklist_id, user_id, body):
    return api.patch(f"/api/checklists/{checklist_id}", params={"user_id": str(user_id)}, json=body)


def _row(db_session, checklist_id):
    return db_session.execute(
        text("SELECT due_date, updated_at FROM checklists WHERE id = :id"), {"id": checklist_id}
    ).mappings().one()


@pytest.fixture
def setup(api, db_session):
    user = make_user(db_session)
    return user, _checklist(api, user, make_policy(db_session))


def test_new_checklist_has_no_due_date(api, setup):
    user, cid = setup
    assert api.get(f"/api/checklists/{cid}", params={"user_id": str(user.id)}).json()["due_date"] is None


def test_set_due_date(api, db_session, setup):
    user, cid = setup
    res = _patch(api, cid, user.id, {"due_date": "2026-10-20"})

    assert res.status_code == 200, res.text
    data = res.json()
    assert set(data) == RESPONSE_KEYS
    assert data["checklist_id"] == cid
    assert data["due_date"] == "2026-10-20"
    assert _row(db_session, cid)["due_date"] == date(2026, 10, 20)


def test_today_allowed(api, setup):
    user, cid = setup
    assert _patch(api, cid, user.id, {"due_date": "2026-10-04"}).json()["due_date"] == "2026-10-04"


def test_past_date_422(api, db_session, setup):
    user, cid = setup
    _assert_error(_patch(api, cid, user.id, {"due_date": "2026-10-03"}), 422, "VALIDATION_ERROR")
    assert _row(db_session, cid)["due_date"] is None


def test_today_follows_clock(api, clock, setup):
    user, cid = setup
    clock.today = date(2026, 10, 21)
    _assert_error(_patch(api, cid, user.id, {"due_date": "2026-10-20"}), 422, "VALIDATION_ERROR")


def test_null_clears(api, setup):
    user, cid = setup
    _patch(api, cid, user.id, {"due_date": "2026-10-20"})
    assert _patch(api, cid, user.id, {"due_date": None}).json()["due_date"] is None


def test_null_allowed_even_if_saved_date_passed(api, clock, setup):
    user, cid = setup
    _patch(api, cid, user.id, {"due_date": "2026-10-20"})
    clock.today = date(2026, 11, 1)
    assert _patch(api, cid, user.id, {"due_date": None}).status_code == 200


def test_updated_at_changes_only_when_value_changes(api, db_session, setup):
    user, cid = setup
    old = datetime(2026, 10, 1, 9, 0, tzinfo=KST)
    db_session.execute(text("UPDATE checklists SET updated_at = :t WHERE id = :id"), {"t": old, "id": cid})
    db_session.commit()

    _patch(api, cid, user.id, {"due_date": None})
    assert _row(db_session, cid)["updated_at"] == old

    _patch(api, cid, user.id, {"due_date": "2026-10-20"})
    assert _row(db_session, cid)["updated_at"] > old


def test_shown_in_dashboard_and_growth(api, setup):
    user, cid = setup
    _patch(api, cid, user.id, {"due_date": "2026-10-20"})
    assert api.get(f"/api/users/{user.id}/dashboard").json()["checklists"][0]["due_date"] == "2026-10-20"
    assert api.get(f"/api/users/{user.id}/growth").json()["checklists"][0]["due_date"] == "2026-10-20"


@pytest.mark.parametrize(
    "body",
    [{}, {"due_date": "2026/10/20"}, {"due_date": "2026-10-20T00:00:00"}, {"due_date": "2026-13-01"},
     {"due_date": 20261020}, {"due_date": ""}, {"due_date": "2026-10-20", "x": 1}, [], "2026-10-20"],
)
def test_invalid_body_422(api, setup, body):
    user, cid = setup
    _assert_error(_patch(api, cid, user.id, body), 422, "VALIDATION_ERROR")


def test_malformed_json_422(api, setup):
    user, cid = setup
    res = api.patch(f"/api/checklists/{cid}", params={"user_id": str(user.id)}, content="{bad",
                    headers={"content-type": "application/json"})
    _assert_error(res, 422, "VALIDATION_ERROR")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-uuid"])
def test_unknown_user_404_before_422(api, setup, user_id):
    _, cid = setup
    res = api.patch(f"/api/checklists/{cid}", params={"user_id": user_id}, content="{bad",
                    headers={"content-type": "application/json"})
    _assert_error(res, 404, "USER_NOT_FOUND")


@pytest.mark.parametrize("checklist_id", ["999999", "abc", "0"])
def test_unknown_checklist_404_before_422(api, setup, checklist_id):
    user, _ = setup
    _assert_error(_patch(api, checklist_id, user.id, {"x": 1}), 404, "CHECKLIST_NOT_FOUND")


def test_other_users_checklist_404(api, db_session, setup):
    _, cid = setup
    other = make_user(db_session)
    _assert_error(_patch(api, cid, other.id, {"due_date": "2026-10-20"}), 404, "CHECKLIST_NOT_FOUND")


def test_missing_user_id_422(api, setup):
    _, cid = setup
    _assert_error(api.patch(f"/api/checklists/{cid}", json={"due_date": None}), 422, "VALIDATION_ERROR")
