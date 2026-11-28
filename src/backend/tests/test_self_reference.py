"""'내 이름으로 적어줘' 같은 부탁은 그 말을 적지 않고 앱에 있는 내 정보를 넣는다. 칸 형식 검사 · 고칠 안내"""

from datetime import date

import pytest
from app import answer_extract
from app.services import writer

from tests.factories import make_user
from tests.test_interview_api import _say, _start
from tests.test_writer_api import _assist, _create, _field, doc_llm, forms_dir  # noqa: F401  (fixture)


@pytest.mark.parametrize("message, key", [
    ("내 이름으로 적어줘", "name"), ("제 이름으로 해 주세요", "name"), ("내 이름 넣어줘", "name"),
    ("내 번호로 써줘", "phone"), ("제 연락처 그대로요", "phone"), ("내 생일로", "birth_date"),
    ("내 이름은 정서현이야", None), ("엄마 이름으로 적어줘", None), ("정서현", None),
])
def test_self_reference(message, key):
    assert answer_extract.self_reference(message) == key


def test_assist_fills_my_name_without_llm(api, db_session, doc_llm):
    user = make_user(db_session, name="김민지", phone="01012345678")
    doc_id = _create(api, user).json()["document_id"]
    res = _assist(api, user, doc_id, "name", "내 이름으로 적어줘").json()
    assert (res["action"], res["field"]["value"]) == ("draft", "김민지")
    res = _assist(api, user, doc_id, "phone", "제 번호로 해 주세요").json()
    assert res["field"]["value"] == "010-1234-5678"
    assert not [c for c in doc_llm.calls if c[0] == "assist_field"]


def test_assist_without_phone_explains(api, db_session, doc_llm):
    user = make_user(db_session, phone=None)
    doc_id = _create(api, user).json()["document_id"]
    res = _assist(api, user, doc_id, "phone", "내 번호로 써줘").json()
    assert res["action"] == "tip" and "010-1234-5678" in res["field"]["tip"]


def test_assist_does_not_write_request_echo(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]
    doc_llm.assist = {"action": "draft", "text": "내 주소로 써 줘"}
    res = _assist(api, user, doc_id, "address", "내 주소로 써 줘").json()
    assert res["action"] == "tip" and res["field"]["value"] == ""
    assert "직접 알려 주세요" in res["field"]["tip"]


def test_interview_fills_my_name(api, db_session, doc_llm):
    user = make_user(db_session, name="김민지", birth_date=date(2005, 3, 15))
    doc_id = _start(api, user)
    assert _say(api, user, doc_id)["question"]["field_id"] == "name"
    r = _say(api, user, doc_id, "내 이름으로 적어줘")
    assert (r["field"]["value"], r["field"]["is_completed"]) == ("김민지", True)
    assert r["message"] == "'김민지'로 적었어요."


@pytest.mark.parametrize("value, ok", [
    ("110-123-456789", True), ("1101234567890", True), ("110 123 456789", False), ("일일공-123", False), ("12345", False),
])
def test_account_format(value, ok):
    spec = {"value_source": "user", "input_type": "account", "label": "계좌번호", "options": None}
    assert (writer._field_warnings(spec, value) == []) is ok


def test_check_problem_has_where_and_example(api, db_session, doc_llm):
    user = make_user(db_session, phone=None)
    doc_id = _create(api, user).json()["document_id"]
    api.patch(f"/api/documents/{doc_id}/fields/phone?user_id={user.id}", json={"value": "1234", "is_completed": True})
    problems = api.get(f"/api/documents/{doc_id}/check?user_id={user.id}").json()["problems"]
    phone = next(p for p in problems if p["field_id"] == "phone")
    assert phone["reason"] == "invalid_format" and phone["example"] == "010-1234-5678"
    assert "0으로 시작하는 숫자" in phone["message"]


def test_policy_detail_lists_forms(api, db_session):
    from tests.factories import make_policy

    p = make_policy(db_session, policy_key="G009")
    body = api.get(f"/api/policies/{p.id}").json()
    ids = [f["form_id"] for f in body["forms"]]
    assert "mohw-settle-plan" in ids and len(ids) == len(set(ids))
    settle = next(f for f in body["forms"] if f["form_id"] == "mohw-settle-plan")
    assert settle["doc_name"] == "자립정착금 사용계획서" and settle["title"]


def test_assist_today_for_date_field(api, db_session, doc_llm, clock):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]
    res = _assist(api, user, doc_id, "birth", "오늘 날짜로 적어줘").json()
    assert res["action"] == "draft" and res["field"]["value"].count(".") == 2


@pytest.mark.parametrize("value, ok", [("5000000", True), ("1,000,000", True), ("50만원", True), ("3명", True), ("오십만", False), ("본인", False)])
def test_number_format(value, ok):
    spec = {"value_source": "user", "input_type": "number", "label": "금액", "options": None, "example": "500000"}
    assert (writer._field_warnings(spec, value) == []) is ok


def test_amount_is_not_mash():
    spec = {"value_source": "user", "input_type": "number", "label": "금액", "options": None}
    assert writer._field_warnings(spec, "5000000") == []
    assert answer_extract.looks_mash("가가가가") is True
