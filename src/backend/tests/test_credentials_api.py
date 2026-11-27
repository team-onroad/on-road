"""이수한 교육 · 취득한 자격증: GET · POST · DELETE /users/{user_id}/credentials (api.md 3.37)"""

import uuid

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from tests.factories import make_user

KEYS = {"credential_id", "kind", "code", "name", "done_on", "created_at"}


def _err(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert res.json()["error"]["code"] == code


def _url(user_id, credential_id=None):
    base = f"/api/users/{user_id}/credentials"
    return base if credential_id is None else f"{base}/{credential_id}"


def test_education_options(api):
    data = api.get("/api/users/education-options").json()
    assert [o["code"] for o in data] == ["settlement", "finance"]
    assert data[0]["name"] == "자립정착금 의무교육"


def test_add_education_by_code_201(api, db_session):
    user = make_user(db_session)
    res = api.post(_url(user.id), json={"kind": "education", "code": "settlement", "done_on": "2026-09-01"})

    assert res.status_code == 201, res.text
    data = res.json()
    assert set(data) == KEYS
    assert (data["kind"], data["code"], data["name"], data["done_on"]) == (
        "education", "settlement", "자립정착금 의무교육", "2026-09-01")


def test_add_certificate_and_list(api, db_session):
    user = make_user(db_session)
    assert api.post(_url(user.id), json={"kind": "certificate", "name": "  컴퓨터활용능력 2급 "}).status_code == 201
    assert api.post(_url(user.id), json={"kind": "education", "name": "바리스타 과정"}).status_code == 201

    all_items = api.get(_url(user.id)).json()
    assert all_items["count"] == 2
    certs = api.get(_url(user.id), params={"kind": "certificate"}).json()
    assert certs["count"] == 1
    assert certs["credentials"][0]["name"] == "컴퓨터활용능력 2급"
    assert certs["credentials"][0]["code"] is None


def test_same_name_updates_date_200(api, db_session):
    user = make_user(db_session)
    first = api.post(_url(user.id), json={"kind": "certificate", "name": "운전면허"}).json()
    res = api.post(_url(user.id), json={"kind": "certificate", "name": "운전면허", "done_on": "2026-05-02"})

    assert res.status_code == 200
    assert res.json()["credential_id"] == first["credential_id"]
    assert res.json()["done_on"] == "2026-05-02"
    # 날짜를 안 주면 그대로
    again = api.post(_url(user.id), json={"kind": "certificate", "name": "운전면허"}).json()
    assert again["done_on"] == "2026-05-02"


@pytest.mark.parametrize("body", [
    {"kind": "certificate"},  # 이름 없음
    {"kind": "certificate", "name": "a", "code": "settlement"},  # code 는 교육만
    {"kind": "license", "name": "a"},
    {"kind": "education", "code": "unknown"},
    {"kind": "certificate", "name": " "},
    {"kind": "certificate", "name": "가" * 61},
    {"kind": "certificate", "name": "a", "extra": 1},
    {"kind": "certificate", "name": "a", "done_on": "2026-10-05"},  # 오늘(2026-10-04) 이후
])
def test_add_validation_422(api, db_session, body):
    user = make_user(db_session)
    _err(api.post(_url(user.id), json=body), 422, "VALIDATION_ERROR")


def test_unknown_user_404_before_body_422(api):
    _err(api.post(_url(uuid.uuid4()), content=b"{broken"), 404, "USER_NOT_FOUND")
    _err(api.get(_url(uuid.uuid4())), 404, "USER_NOT_FOUND")


def test_delete(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    mine = api.post(_url(user.id), json={"kind": "certificate", "name": "운전면허"}).json()
    theirs = api.post(_url(other.id), json={"kind": "certificate", "name": "운전면허"}).json()

    assert api.delete(_url(user.id, theirs["credential_id"])).status_code == 204  # 남의 것은 안 지워짐
    assert api.get(_url(other.id)).json()["count"] == 1
    assert api.delete(_url(user.id, mine["credential_id"])).status_code == 204
    assert api.get(_url(user.id)).json()["count"] == 0
    assert api.delete(_url(user.id, 999999)).status_code == 204
    _err(api.delete(_url(user.id, "abc")), 404, "CREDENTIAL_NOT_FOUND")


def test_kind_check_constraint(db_session):
    user = make_user(db_session)
    with pytest.raises(IntegrityError):
        db_session.execute(text(
            "INSERT INTO user_credentials (user_id, kind, name) VALUES (:u, 'license', 'x')"), {"u": user.id})
    db_session.rollback()


def test_resume_draft_uses_credentials(api, db_session):
    user = make_user(db_session)
    api.post(_url(user.id), json={"kind": "certificate", "name": "컴퓨터활용능력 2급", "done_on": "2026-03-10"})
    api.post(_url(user.id), json={"kind": "education", "code": "settlement", "done_on": "2026-09-01"})

    data = api.post(f"/api/users/{user.id}/documents", json={"type": "resume"}).json()
    sections = {s["title"]: [(x["text"], x["source"]) for x in s["sentences"]] for s in data["sections"]}

    assert sections["자격증"] == [("컴퓨터활용능력 2급 (2026.03 취득)", "내 자격증")]
    assert sections["경험·활동"] == [("자립정착금 의무교육 이수 (2026.09)", "내 교육 이수")]
    assert data["notice"].startswith("앱에 기록된 내용만")


def test_add_same_twice_keeps_one_row(api, db_session):
    """같은 칸을 빠르게 두 번 눌러도 한 줄만 (두 번째는 200, 같은 id)"""
    user = make_user(db_session)
    body = {"kind": "education", "code": "settlement"}
    first = api.post(_url(user.id), json=body)
    second = api.post(_url(user.id), json=body)

    assert (first.status_code, second.status_code) == (201, 200)
    assert first.json()["credential_id"] == second.json()["credential_id"]
    assert db_session.execute(text("SELECT count(*) FROM user_credentials")).scalar() == 1
