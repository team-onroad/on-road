"""GET /users/{user_id}/activities, GET /users/{user_id}/roleplays/{roleplay_id}"""

import json
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import text

from app.roleplay import get_scenarios
from tests.factories import make_user

KST = timezone(timedelta(hours=9))
MONTH = {"income": 180, "left": 12, "shortage": 0, "used_emergency": 0, "home": "STUDIO", "good": 5, "bad": 2,
         "bad_events": ["smishing"]}
ITEM_KEYS = {"activity_id", "kind", "ref", "title", "data", "created_at"}


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def _add(api, user, kind, ref=None, data=None):
    body = {"kind": kind, "data": data or {"good": 2, "missed": 1, "score": 60}}
    if ref is not None:
        body["ref"] = ref
    res = api.post(f"/api/users/{user.id}/activities", json=body)
    assert res.status_code == 201, res.text
    return res.json()["activity_id"]


def _list(api, user_id, **params):
    return api.get(f"/api/users/{user_id}/activities", params=params)


def _set_created(db_session, table, row_id, at):
    db_session.execute(text(f"UPDATE {table} SET created_at = :t WHERE id = :id"), {"t": at, "id": row_id})
    db_session.commit()


def _roleplay_row(db_session, user, scenario_id="bank", good=("신분증",), missed=("목적",)):
    return db_session.execute(
        text("INSERT INTO roleplay_results (user_id, scenario_id, good, missed) "
             "VALUES (:u, :s, CAST(:g AS jsonb), CAST(:m AS jsonb)) RETURNING id"),
        {"u": user.id, "s": scenario_id, "g": json.dumps(list(good)), "m": json.dumps(list(missed))},
    ).scalar_one()


# ------------------------------------------------------------------ activities


def test_list_empty(api, db_session):
    user = make_user(db_session)
    assert _list(api, user.id).json() == {"activities": []}


def test_list_items(api, db_session):
    user = make_user(db_session)
    sid = next(iter(get_scenarios()))
    practice = _add(api, user, "practice", sid)
    month = _add(api, user, "month_life", data=MONTH)

    items = {a["activity_id"]: a for a in _list(api, user.id).json()["activities"]}
    assert all(set(a) == ITEM_KEYS for a in items.values())
    assert items[practice]["kind"] == "practice"
    assert items[practice]["ref"] == sid
    assert items[practice]["title"] == get_scenarios()[sid].title
    assert items[practice]["data"] == {"good": 2, "missed": 1, "score": 60}
    assert items[month]["kind"] == "month_life"
    assert (items[month]["ref"], items[month]["title"]) == (None, None)
    assert items[month]["data"] == MONTH
    assert datetime.fromisoformat(items[month]["created_at"]).utcoffset() == timedelta(hours=9)


def test_unknown_scenario_title_is_ref(api, db_session):
    user = make_user(db_session)
    _add(api, user, "practice", "gone-scenario")
    assert _list(api, user.id).json()["activities"][0]["title"] == "gone-scenario"


def test_newest_first_same_time_larger_id_first(api, db_session):
    user = make_user(db_session)
    a, b, c = (_add(api, user, "practice", "bank") for _ in range(3))
    base = datetime(2026, 10, 1, 9, 0, tzinfo=KST)
    _set_created(db_session, "activity_logs", a, base + timedelta(hours=1))
    _set_created(db_session, "activity_logs", b, base)
    _set_created(db_session, "activity_logs", c, base)
    assert [x["activity_id"] for x in _list(api, user.id).json()["activities"]] == [a, c, b]


@pytest.mark.parametrize("kind", ["practice", "month_life"])
def test_filter_kind(api, db_session, kind):
    user = make_user(db_session)
    _add(api, user, "practice", "bank")
    _add(api, user, "month_life", data=MONTH)
    items = _list(api, user.id, kind=kind).json()["activities"]
    assert [x["kind"] for x in items] == [kind]


@pytest.mark.parametrize("kind", ["PRACTICE", "", "roleplay"])
def test_invalid_kind_422(api, db_session, kind):
    user = make_user(db_session)
    _assert_error(_list(api, user.id, kind=kind), 422, "VALIDATION_ERROR")


def test_only_own(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    _add(api, other, "practice", "bank")
    assert _list(api, user.id).json() == {"activities": []}


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-uuid"])
def test_list_unknown_user_404_before_422(api, user_id):
    _assert_error(_list(api, user_id, kind="bad"), 404, "USER_NOT_FOUND")


# ------------------------------------------------------------------ roleplays/{roleplay_id}


def test_roleplay_detail(api, db_session):
    user = make_user(db_session)
    sid = next(iter(get_scenarios()))
    rid = _roleplay_row(db_session, user, sid, good=("신분증 챙기기", "끝까지 대화"), missed=("목적 말하기",))
    db_session.commit()

    res = api.get(f"/api/users/{user.id}/roleplays/{rid}")
    assert res.status_code == 200, res.text
    data = res.json()
    assert set(data) == {"roleplay_id", "scenario_id", "title", "good", "missed", "created_at"}
    assert data["roleplay_id"] == rid
    assert data["scenario_id"] == sid
    assert data["title"] == get_scenarios()[sid].title
    assert data["good"] == ["신분증 챙기기", "끝까지 대화"]
    assert data["missed"] == ["목적 말하기"]
    assert datetime.fromisoformat(data["created_at"]).utcoffset() == timedelta(hours=9)


def test_roleplay_unknown_scenario_title_is_id(api, db_session):
    user = make_user(db_session)
    rid = _roleplay_row(db_session, user, "gone-scenario")
    db_session.commit()
    assert api.get(f"/api/users/{user.id}/roleplays/{rid}").json()["title"] == "gone-scenario"


def test_roleplay_saved_by_feedback_can_be_read(api, db_session):
    user = make_user(db_session)
    sid = next(iter(get_scenarios()))
    res = api.post(f"/api/coach/roleplay/{sid}/feedback", json={
        "user_id": str(user.id), "save": True, "history": [{"role": "user", "content": "안녕하세요"}],
    })
    assert res.status_code == 200, res.text
    fb = res.json()
    data = api.get(f"/api/users/{user.id}/roleplays/{fb['roleplay_id']}").json()
    assert (data["good"], data["missed"]) == (fb["good"], fb["missed"])


@pytest.mark.parametrize("rid", ["999999", "abc", "0", "-1", str(2**63)])
def test_roleplay_not_found(api, db_session, rid):
    user = make_user(db_session)
    _assert_error(api.get(f"/api/users/{user.id}/roleplays/{rid}"), 404, "ROLEPLAY_NOT_FOUND")


def test_roleplay_of_other_user_404(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    rid = _roleplay_row(db_session, other)
    db_session.commit()
    _assert_error(api.get(f"/api/users/{user.id}/roleplays/{rid}"), 404, "ROLEPLAY_NOT_FOUND")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-uuid"])
def test_roleplay_unknown_user_404_first(api, user_id):
    _assert_error(api.get(f"/api/users/{user_id}/roleplays/abc"), 404, "USER_NOT_FOUND")
