"""삭제 API: 탈퇴, 서류 삭제, 체크리스트 삭제"""

import pytest
from sqlalchemy import text

from tests.factories import doc, make_policy, make_user

USER_TABLES = (
    "checklists", "checklist_items", "simulations", "roleplay_results", "documents", "document_fields",
    "activity_logs", "saved_policies", "readiness_snapshots",
)
MISSING_UUID = "00000000-0000-0000-0000-000000000000"
ALLOCATIONS = {"housing": 300000, "food": 300000, "transport": 100000, "telecom": 50000, "other": 100000}


def _err(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert res.json()["error"]["code"] == code


def _count(db_session, table, where="", **params):
    db_session.expire_all()
    return db_session.execute(text(f"SELECT count(*) FROM {table} {where}"), params).scalar()


def _checklist(api, user, policy):
    res = api.post("/api/checklists", json={"user_id": str(user.id), "policy_id": policy.id})
    assert res.status_code in (200, 201), res.text
    return res.json()


def _document(api, user, **body):
    payload = {"user_id": str(user.id), "source_type": "template", "source_id": "scholarship", **body}
    res = api.post("/api/documents", json=payload)
    assert res.status_code in (200, 201), res.text
    return res.json()


def _fill_records(api, user, policy):
    """사용자 기록이 있는 테이블마다 1줄 이상"""
    uid = str(user.id)
    _checklist(api, user, policy)
    _document(api, user)
    assert api.post("/api/simulations", json={"user_id": uid, "total_income": 900000,
                                              "allocations": ALLOCATIONS}).status_code == 201
    history = [{"role": "user", "content": "이사 왔어요"}]
    assert api.post("/api/coach/roleplay/community-center/feedback",
                    json={"user_id": uid, "history": history, "save": True}).status_code == 200
    assert api.post(f"/api/users/{uid}/saved-policies", json={"policy_id": policy.id}).status_code == 201
    assert api.get(f"/api/users/{uid}/readiness").status_code == 200


# ------------------------------------------------------------------ 탈퇴


def test_delete_user_removes_all_records(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    policy = make_policy(db_session)
    _fill_records(api, user, policy)
    _fill_records(api, other, policy)
    uid, other_id = user.id, other.id
    before = {t: _count(db_session, t) for t in USER_TABLES}

    res = api.delete(f"/api/users/{uid}")

    assert res.status_code == 204 and res.content == b""
    assert _count(db_session, "users", "WHERE id = :u", u=uid) == 0
    for t in USER_TABLES:
        assert 0 < _count(db_session, t) < before[t], t  # 다른 사용자 기록은 남음
    assert _count(db_session, "policies") == 1
    _err(api.get(f"/api/users/{uid}"), 404, "USER_NOT_FOUND")
    assert api.get(f"/api/users/{other_id}").status_code == 200


def test_delete_user_twice_404(api, db_session):
    uid = make_user(db_session).id
    assert api.delete(f"/api/users/{uid}").status_code == 204
    _err(api.delete(f"/api/users/{uid}"), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize("user_id", [MISSING_UUID, "nope", "1"])
def test_delete_unknown_user_404(api, db_session, user_id):
    make_user(db_session)
    _err(api.delete(f"/api/users/{user_id}"), 404, "USER_NOT_FOUND")
    assert _count(db_session, "users") == 1


# ------------------------------------------------------------------ 서류 삭제


def test_delete_document(api, db_session):
    user = make_user(db_session)
    did = _document(api, user)["document_id"]

    res = api.delete(f"/api/documents/{did}", params={"user_id": str(user.id)})

    assert res.status_code == 204 and res.content == b""
    assert _count(db_session, "documents") == 0
    assert _count(db_session, "document_fields") == 0
    _err(api.get(f"/api/documents/{did}", params={"user_id": str(user.id)}), 404, "DOCUMENT_NOT_FOUND")
    assert api.get(f"/api/users/{user.id}/documents").json()["documents"] == []


def test_delete_document_keeps_checklist(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session, required_docs=[doc("장학금 신청서")])
    checklist = _checklist(api, user, policy)
    item_id = checklist["steps"][2]["documents"][0]["item_id"]
    did = _document(api, user, checklist_item_id=item_id)["document_id"]

    assert api.delete(f"/api/documents/{did}", params={"user_id": str(user.id)}).status_code == 204
    assert _count(db_session, "checklist_items", "WHERE id = :i", i=item_id) == 1


def test_delete_document_twice_404(api, db_session):
    user = make_user(db_session)
    did = _document(api, user)["document_id"]
    assert api.delete(f"/api/documents/{did}", params={"user_id": str(user.id)}).status_code == 204
    _err(api.delete(f"/api/documents/{did}", params={"user_id": str(user.id)}), 404, "DOCUMENT_NOT_FOUND")


def test_delete_other_users_document_404(api, db_session):
    owner = make_user(db_session)
    other = make_user(db_session)
    did = _document(api, owner)["document_id"]
    _err(api.delete(f"/api/documents/{did}", params={"user_id": str(other.id)}), 404, "DOCUMENT_NOT_FOUND")
    assert _count(db_session, "documents") == 1


@pytest.mark.parametrize("document_id", ["999999", "abc", "0", "99999999999999999999"])
def test_delete_unknown_document_404(api, db_session, document_id):
    user = make_user(db_session)
    _err(api.delete(f"/api/documents/{document_id}", params={"user_id": str(user.id)}), 404, "DOCUMENT_NOT_FOUND")


def test_delete_document_user_checked_first(api, db_session):
    user = make_user(db_session)
    did = _document(api, user)["document_id"]
    _err(api.delete(f"/api/documents/{did}", params={"user_id": MISSING_UUID}), 404, "USER_NOT_FOUND")
    _err(api.delete("/api/documents/abc", params={"user_id": MISSING_UUID}), 404, "USER_NOT_FOUND")
    _err(api.delete(f"/api/documents/{did}"), 422, "VALIDATION_ERROR")  # user_id 없음
    assert _count(db_session, "documents") == 1


# ------------------------------------------------------------------ 체크리스트 삭제


def test_delete_checklist(api, db_session):
    user = make_user(db_session)
    cid = _checklist(api, user, make_policy(db_session))["checklist_id"]

    res = api.delete(f"/api/checklists/{cid}", params={"user_id": str(user.id)})

    assert res.status_code == 204 and res.content == b""
    assert _count(db_session, "checklists") == 0
    assert _count(db_session, "checklist_items") == 0
    _err(api.get(f"/api/checklists/{cid}", params={"user_id": str(user.id)}), 404, "CHECKLIST_NOT_FOUND")
    assert api.get(f"/api/users/{user.id}/growth").json()["checklists"] == []


def test_delete_checklist_keeps_linked_document(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session, required_docs=[doc("장학금 신청서")])
    checklist = _checklist(api, user, policy)
    item_id = checklist["steps"][2]["documents"][0]["item_id"]
    did = _document(api, user, checklist_item_id=item_id)["document_id"]

    res = api.delete(f"/api/checklists/{checklist['checklist_id']}", params={"user_id": str(user.id)})
    assert res.status_code == 204

    res = api.get(f"/api/documents/{did}", params={"user_id": str(user.id)})
    assert res.status_code == 200 and res.json()["checklist_item_id"] is None


def test_recreate_after_delete_starts_fresh(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session)
    first = _checklist(api, user, policy)
    uid = {"user_id": str(user.id)}
    res = api.patch(f"/api/checklists/{first['checklist_id']}/items/{first['steps'][0]['item_id']}",
                    params=uid, json={"is_checked": True})
    assert res.status_code == 200, res.text
    api.delete(f"/api/checklists/{first['checklist_id']}", params=uid)

    again = api.post("/api/checklists", json={**uid, "policy_id": policy.id})
    assert again.status_code == 201
    assert again.json()["checklist_id"] != first["checklist_id"]
    assert again.json()["progress"]["checked_steps"] == 0


def test_delete_checklist_twice_404(api, db_session):
    user = make_user(db_session)
    cid = _checklist(api, user, make_policy(db_session))["checklist_id"]
    assert api.delete(f"/api/checklists/{cid}", params={"user_id": str(user.id)}).status_code == 204
    _err(api.delete(f"/api/checklists/{cid}", params={"user_id": str(user.id)}), 404, "CHECKLIST_NOT_FOUND")


def test_delete_other_users_checklist_404(api, db_session):
    owner = make_user(db_session)
    other = make_user(db_session)
    cid = _checklist(api, owner, make_policy(db_session))["checklist_id"]
    _err(api.delete(f"/api/checklists/{cid}", params={"user_id": str(other.id)}), 404, "CHECKLIST_NOT_FOUND")
    assert _count(db_session, "checklists") == 1


@pytest.mark.parametrize("checklist_id", ["999999", "abc", "-1"])
def test_delete_unknown_checklist_404(api, db_session, checklist_id):
    user = make_user(db_session)
    _err(api.delete(f"/api/checklists/{checklist_id}", params={"user_id": str(user.id)}), 404, "CHECKLIST_NOT_FOUND")


def test_delete_checklist_user_checked_first(api, db_session):
    user = make_user(db_session)
    cid = _checklist(api, user, make_policy(db_session))["checklist_id"]
    _err(api.delete(f"/api/checklists/{cid}", params={"user_id": MISSING_UUID}), 404, "USER_NOT_FOUND")
    _err(api.delete(f"/api/checklists/{cid}"), 422, "VALIDATION_ERROR")  # user_id 없음
    assert _count(db_session, "checklists") == 1
