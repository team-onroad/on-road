"""칸 내용 검사: 이름 · 주소 · 이메일 형식, 아무렇게나 친 글자, 너무 짧은 글 칸 (answer_extract.problem)"""

import pytest

from app.answer_extract import looks_mash, problem
from tests.factories import make_user
from tests.test_writer_api import (  # noqa: F401  (fixture)
    _complete_all,
    _create,
    doc_llm,
)


def _spec(label, essay=False, input_type="text"):
    return {"label": label, "input_type": input_type, "essay": essay, "profile_key": None}


@pytest.mark.parametrize(("label", "value"), [
    ("성명", "ㅋㅋ"), ("성명", "나는 민수"), ("성명", "asdf"),
    ("주소", "우리집"), ("이메일", "abc"), ("소속(학교·학년)", "qwerty"), ("관계", "가가가가"),
])
def test_bad_values_are_flagged(label, value):
    assert problem(_spec(label), value) is not None


@pytest.mark.parametrize(("label", "value"), [
    ("성명", "김민수"), ("성명", "남궁민수"), ("성명", "Kim Minsu"), ("주소", "인천광역시 남동구 구월로 12"),
    ("이메일", "a@example.com"), ("관계", "이모"), ("소속(학교·학년)", "인하대학교 2학년"),
])
def test_good_values_pass(label, value):
    assert problem(_spec(label), value) is None


def test_short_essay_is_flagged():
    assert problem(_spec("신청 사유", essay=True), "돈") is not None
    assert problem(_spec("신청 사유", essay=True), "보호종료 후 월세가 부담돼서 신청합니다") is None


def test_mash_detection():
    assert looks_mash("asdf") and looks_mash("ㅁㄴㅇㄹ") and looks_mash("1111") and not looks_mash("서울시")


def test_export_blocked_until_content_makes_sense(api, db_session, doc_llm):
    user = make_user(db_session)
    data = _create(api, user).json()
    school = next(f["field_id"] for f in data["fields"] if f["label"].startswith("소속"))
    _complete_all(api, user, data, {school: "asdf"})
    res = api.post(f"/api/documents/{data['document_id']}/export", json={"user_id": str(user.id)})
    assert res.status_code == 422
    check = api.get(f"/api/documents/{data['document_id']}/check?user_id={user.id}").json()
    assert any(p["field_id"] == school and p["reason"] == "invalid_format" for p in check["problems"])
