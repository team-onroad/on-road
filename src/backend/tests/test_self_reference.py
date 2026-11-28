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


@pytest.mark.parametrize("spec, key", [
    ({"label": "사는 곳 주소", "section": "신청인"}, "주소"),
    ({"label": "주민등록상주소", "section": "신청인"}, "주소"),
    ({"label": "새 주소", "section": None}, "새주소"),  # 이름이 같을 때만 (예전처럼)
    ({"label": "주소", "section": "부양의무자 2"}, None),
    ({"label": "주소", "section": "현 복지시설"}, None),
    ({"label": "휴대전화", "section": "신청인"}, "연락처"),
    ({"label": "보호자(담당자) 연락처", "section": None}, None),
    ({"label": "E-mail", "section": "신청인"}, "이메일"),
    ({"label": "은행명", "section": "입금정보"}, "은행"),
    ({"label": "계좌번호", "section": "복지급여 계좌 > 변경", "input_type": "account"}, None),
    ({"label": "보호기관명", "section": None}, "보호기관명"),
])
def test_reuse_key_by_meaning(spec, key):
    from app import doc_spec

    assert doc_spec.reuse_key({"input_type": "text", **spec}) == key


def test_opening_document_fills_from_other_documents(api, db_session, doc_llm):
    """장학금 신청서에 쓴 주소가 이미 만든 이력서 양식의 빈 주소 칸에도 들어간다 (이미 쓴 칸은 그대로)"""
    user = make_user(db_session)
    resume = _create(api, user, source_id="resume-form").json()["document_id"]
    scholar = _create(api, user).json()["document_id"]
    api.patch(f"/api/documents/{scholar}/fields/address?user_id={user.id}",
              json={"value": "서울특별시 강남구 테헤란로 123", "is_completed": True})
    opened = api.get(f"/api/documents/{resume}?user_id={user.id}").json()
    address = _field(opened, "address")
    assert (address["value"], address["reused_from"]) == ("서울특별시 강남구 테헤란로 123", "장학금 신청서")
