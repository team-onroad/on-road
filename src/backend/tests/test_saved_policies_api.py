"""저장한 정책: POST · DELETE · GET /users/{user_id}/saved-policies"""

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from tests.factories import make_policy, make_user

KST = timezone(timedelta(hours=9))
POLICY_KEYS = {"policy_id", "name", "agency", "category", "contact", "apply_url", "checked_at", "is_active"}


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def _url(user_id, policy_id=None):
    base = f"/api/users/{user_id}/saved-policies"
    return base if policy_id is None else f"{base}/{policy_id}"


def _save(api, user_id, policy_id):
    return api.post(_url(user_id), json={"policy_id": policy_id})


def _count(db_session):
    return db_session.execute(text("SELECT count(*) FROM saved_policies")).scalar()


# ------------------------------------------------------------------ POST


def test_save_creates_201(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session, name="청년 월세 지원")
    res = _save(api, user.id, policy.id)

    assert res.status_code == 201, res.text
    data = res.json()
    assert set(data) == {"policy", "saved_at"}
    assert set(data["policy"]) == POLICY_KEYS
    assert data["policy"]["policy_id"] == policy.id
    assert data["policy"]["name"] == "청년 월세 지원"
    assert data["policy"]["is_active"] is True
    assert datetime.fromisoformat(data["saved_at"]).utcoffset() == timedelta(hours=9)
    assert _count(db_session) == 1


def test_save_again_returns_200_same_item(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    first = _save(api, user.id, policy.id).json()
    res = _save(api, user.id, policy.id)

    assert res.status_code == 200
    assert res.json() == first
    assert _count(db_session) == 1


def test_save_again_after_policy_hidden_returns_200(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    _save(api, user.id, policy.id)
    policy.is_active = False
    db_session.commit()

    res = _save(api, user.id, policy.id)
    assert res.status_code == 200
    assert res.json()["policy"]["is_active"] is False


@pytest.mark.parametrize("overrides", [{"is_active": False}, {"easy_text_verified": False}])
def test_save_hidden_policy_404(api, db_session, overrides):
    user = make_user(db_session)
    policy = make_policy(db_session, **overrides)
    _assert_error(_save(api, user.id, policy.id), 404, "POLICY_NOT_FOUND")
    assert _count(db_session) == 0


def test_save_unknown_policy_404(api, db_session):
    user = make_user(db_session)
    _assert_error(_save(api, user.id, 999999), 404, "POLICY_NOT_FOUND")


@pytest.mark.parametrize(
    "body",
    [{}, {"policy_id": "1"}, {"policy_id": 1.0}, {"policy_id": True}, {"policy_id": None}, {"policy_id": 0},
     {"policy_id": 2**63}, {"policy_id": 1, "x": 1}, [], "1"],
)
def test_save_invalid_body_422(api, db_session, body):
    user = make_user(db_session)
    _assert_error(api.post(_url(user.id), json=body), 422, "VALIDATION_ERROR")


def test_save_malformed_json_422(api, db_session):
    user = make_user(db_session)
    res = api.post(_url(user.id), content="{bad", headers={"content-type": "application/json"})
    _assert_error(res, 422, "VALIDATION_ERROR")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-uuid"])
def test_save_unknown_user_404_before_422(api, user_id):
    _assert_error(api.post(_url(user_id), content="{bad", headers={"content-type": "application/json"}),
                  404, "USER_NOT_FOUND")
    _assert_error(_save(api, user_id, 1), 404, "USER_NOT_FOUND")


def test_save_invalid_body_422_before_policy_404(api, db_session):
    user = make_user(db_session)
    _assert_error(api.post(_url(user.id), json={"policy_id": 999999, "x": 1}), 422, "VALIDATION_ERROR")


# ------------------------------------------------------------------ DELETE


def test_unsave_204(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    _save(api, user.id, policy.id)

    res = api.delete(_url(user.id, policy.id))
    assert res.status_code == 204
    assert res.content == b""
    assert _count(db_session) == 0


def test_unsave_not_saved_204(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    assert api.delete(_url(user.id, policy.id)).status_code == 204
    assert api.delete(_url(user.id, 999999)).status_code == 204


def test_unsave_only_own(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    policy = make_policy(db_session)
    _save(api, other.id, policy.id)

    assert api.delete(_url(user.id, policy.id)).status_code == 204
    assert _count(db_session) == 1


@pytest.mark.parametrize("policy_id", ["abc", "0", "-1", "1.0", str(2**63), "１"])
def test_unsave_invalid_policy_id_404(api, db_session, policy_id):
    user = make_user(db_session)
    _assert_error(api.delete(_url(user.id, policy_id)), 404, "POLICY_NOT_FOUND")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-uuid"])
def test_unsave_unknown_user_404(api, user_id):
    _assert_error(api.delete(_url(user_id, "abc")), 404, "USER_NOT_FOUND")


# ------------------------------------------------------------------ GET


def test_list_empty(api, db_session):
    user = make_user(db_session)
    assert api.get(_url(user.id)).json() == {"count": 0, "policies": []}


def test_list_newest_first_same_time_larger_id_first(api, db_session):
    user = make_user(db_session)
    a, b, c = (make_policy(db_session, name=n) for n in ("가", "나", "다"))
    for p in (a, b, c):
        _save(api, user.id, p.id)
    base = datetime(2026, 10, 1, 9, 0, tzinfo=KST)
    for pid, at in ((a.id, base + timedelta(hours=1)), (b.id, base), (c.id, base)):
        db_session.execute(
            text("UPDATE saved_policies SET created_at = :t WHERE policy_id = :p"), {"t": at, "p": pid}
        )
    db_session.commit()

    data = api.get(_url(user.id)).json()
    assert data["count"] == 3
    assert [x["policy"]["name"] for x in data["policies"]] == ["가", "다", "나"]


def test_list_keeps_hidden_policy(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    _save(api, user.id, policy.id)
    policy.easy_text_verified = False
    db_session.commit()

    data = api.get(_url(user.id)).json()
    assert data["count"] == 1
    assert data["policies"][0]["policy"]["is_active"] is False


def test_list_only_own(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    _save(api, other.id, make_policy(db_session).id)
    assert api.get(_url(user.id)).json()["count"] == 0


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-uuid"])
def test_list_unknown_user_404(api, user_id):
    _assert_error(api.get(_url(user_id)), 404, "USER_NOT_FOUND")


# ------------------------------------------------------------------ DB


def test_deleting_user_deletes_saved(api, db_session):
    user = make_user(db_session)
    _save(api, user.id, make_policy(db_session).id)
    db_session.execute(text("DELETE FROM users WHERE id = :u"), {"u": user.id})
    db_session.commit()
    assert _count(db_session) == 0


def test_deleting_saved_policy_row_restricted(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    _save(api, user.id, policy.id)
    with pytest.raises(IntegrityError):
        db_session.execute(text("DELETE FROM policies WHERE id = :p"), {"p": policy.id})
    db_session.rollback()


def test_unique_user_policy(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    _save(api, user.id, policy.id)
    with pytest.raises(IntegrityError):
        db_session.execute(
            text("INSERT INTO saved_policies (user_id, policy_id) VALUES (:u, :p)"), {"u": user.id, "p": policy.id}
        )
    db_session.rollback()
