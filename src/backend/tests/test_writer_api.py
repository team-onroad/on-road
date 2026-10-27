"""문서 AI (api.md 3.25~3.31): 항목별 공동 작성, 라우터, 내보내기·체크리스트 연동."""

import json
import uuid
from datetime import date

import pytest
from app.config import get_settings
from app.doc_llm import DocLlm, get_doc_llm
from app.llm_mock import TIP_INTRO
from app.main import app

from tests.factories import doc, make_policy, make_user
from tests.test_search_api import rag  # noqa: F401  (fixture)


class FakeDocLlm:
    """기본은 목 함수와 같은 규칙. route·assist 에 값을 넣으면 그 값을 돌려준다"""

    def __init__(self):
        from app import llm_mock

        self._mock = llm_mock
        self.route: object = None
        self.assist: object = None
        self.error: Exception | None = None
        self.calls: list[tuple[str, tuple]] = []

    def route_message(self, message, history, context):
        self.calls.append(("route_message", (message, history, context)))
        if self.error is not None:
            raise self.error
        return self.route if self.route is not None else self._mock.route_message(message, history, context)

    def assist_field(self, req):
        self.calls.append(("assist_field", (req,)))
        if self.error is not None:
            raise self.error
        return self.assist if self.assist is not None else self._mock.assist_field(req)

    def as_llm(self) -> DocLlm:
        return DocLlm(self.route_message, self.assist_field)


@pytest.fixture
def doc_llm(api):
    fake = FakeDocLlm()
    app.dependency_overrides[get_doc_llm] = fake.as_llm
    yield fake
    app.dependency_overrides.pop(get_doc_llm, None)


def _err(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert res.json()["error"]["code"] == code


def _create(api, user, **body):
    payload = {"user_id": str(user.id), "source_type": "template", "source_id": "scholarship", **body}
    return api.post("/api/documents", json=payload)


def _field(data, field_id):
    return next(f for f in data["fields"] if f["field_id"] == field_id)


def _assist(api, user, doc_id, field_id, prompt, **extra):
    return api.post(
        f"/api/documents/{doc_id}/assist",
        json={"user_id": str(user.id), "field_id": field_id, "prompt": prompt, **extra},
    )


def _patch(api, user, doc_id, field_id, **body):
    return api.patch(f"/api/documents/{doc_id}/fields/{field_id}?user_id={user.id}", json=body)


def _complete_all(api, user, data, values=None):
    values = values or {}
    for f in data["fields"]:
        if not f["writable"]:
            continue
        body = {"is_completed": True}
        if f["field_id"] in values:
            body["value"] = values[f["field_id"]]
        elif not f["value"]:
            body["value"] = "작성한 내용"
        res = _patch(api, user, data["document_id"], f["field_id"], **body)
        assert res.status_code == 200, res.text


# ------------------------------------------------------------------ 만들기


def test_create_template_prefills_profile(api, db_session, doc_llm):
    user = make_user(db_session, name="김민지", birth_date=date(2005, 3, 15), phone="01012345678")

    res = _create(api, user)
    data = res.json()

    assert res.status_code == 201
    assert data["title"] == "장학금 신청서"
    assert (data["status"], data["source_type"], data["source_id"]) == ("writing", "template", "scholarship")
    name = _field(data, "name")
    assert name["value"] == "김민지"
    assert name["segments"] == [{"text": "김민지", "source": "profile"}]
    assert _field(data, "birth")["value"] == "2005.03.15"
    assert _field(data, "phone")["value"] == "010-1234-5678"
    assert _field(data, "reason")["value"] == ""
    assert _field(data, "reason")["outline"] == ["지금 나의 상황", "장학금이 필요한 이유", "받게 되면 어디에 쓸지", "앞으로의 계획"]
    assert data["progress"] == {"completed": 0, "total": 7}
    assert data["next_field_id"] == "name"


def test_create_is_get_or_create(api, db_session, doc_llm):
    user = make_user(db_session)
    first = _create(api, user).json()
    res = _create(api, user, context="장학금 신청서 써야 해")

    assert res.status_code == 200
    assert res.json()["document_id"] == first["document_id"]
    assert res.json()["context"] == "장학금 신청서 써야 해"


def test_account_field_is_user_but_never_sent_to_ai(api, db_session, doc_llm):
    user = make_user(db_session)
    data = _create(api, user, source_id="allowance").json()
    account = _field(data, "account")

    assert (account["value_source"], account["input_type"], account["writable"]) == ("user", "account", True)
    assert data["progress"]["total"] == 6  # 계좌번호도 앱에서 받아 양식에 채운다
    assert _patch(api, user, data["document_id"], "account", value="123-456-789012").status_code == 200
    res = _assist(api, user, data["document_id"], "account", "이거 맞아? 123-456-789012")
    assert res.status_code == 200 and res.json()["action"] == "tip"
    assert "AI 에 보내지 않아요" in res.json()["field"]["tip"]
    assert doc_llm.calls == []  # 라우터·작성 도우미 모두 부르지 않음


def test_create_errors(api, db_session, doc_llm):
    user = make_user(db_session)
    _err(_create(api, user, source_id="nope"), 404, "FORM_NOT_FOUND")
    _err(api.post("/api/documents", json={"user_id": str(uuid.uuid4()), "source_type": "template",
                                          "source_id": "scholarship"}), 404, "USER_NOT_FOUND")
    _err(api.post("/api/documents", json={"user_id": str(user.id), "source_type": "template"}), 422, "VALIDATION_ERROR")
    _err(api.post("/api/documents", json={"user_id": str(user.id), "source_type": "policy_form"}),
         422, "VALIDATION_ERROR")
    _err(_create(api, user, checklist_item_id=999), 404, "ITEM_NOT_FOUND")


def test_other_users_document_is_not_found(api, db_session, doc_llm):
    owner = make_user(db_session)
    other = make_user(db_session)
    doc_id = _create(api, owner).json()["document_id"]

    _err(api.get(f"/api/documents/{doc_id}?user_id={other.id}"), 404, "DOCUMENT_NOT_FOUND")
    _err(api.get(f"/api/documents/abc?user_id={owner.id}"), 404, "DOCUMENT_NOT_FOUND")
    _err(_assist(api, other, doc_id, "reason", "예시 써 줘"), 404, "DOCUMENT_NOT_FOUND")


def test_reuses_completed_fields_from_other_document(api, db_session, doc_llm):
    user = make_user(db_session)
    first = _create(api, user).json()
    _patch(api, user, first["document_id"], "address", value="서울시 OO로 1", is_completed=True)

    data = _create(api, user, source_id="resume-form").json()
    address = _field(data, "address")

    assert address["value"] == "서울시 OO로 1"
    assert address["segments"][0]["source"] == "reused"
    assert address["reused_from"] == "장학금 신청서"
    assert address["is_completed"] is False  # 가져와도 사용자가 확인하고 완료해야 함


# ------------------------------------------------------------------ AI 도우미


def test_tip_does_not_fill_field(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]

    res = _assist(api, user, doc_id, "reason", "이 항목엔 어떤 내용을 넣으면 좋을까?")
    data = res.json()

    assert res.status_code == 200
    assert (data["route"], data["action"]) == ("field", "tip")
    assert data["field"]["value"] == ""
    assert data["field"]["tip"].startswith(TIP_INTRO)
    assert "장학금이 필요한 이유" in data["field"]["tip"]


def test_outline_then_draft_append_and_replace(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]

    outline = _assist(api, user, doc_id, "reason", "문장 구조 추천해 줘").json()
    assert outline["action"] == "outline"
    assert outline["field"]["segments"][0]["source"] == "ai"
    assert outline["field"]["value"].startswith("1. 지금 나의 상황")
    assert outline["field"]["warnings"]  # 대괄호 빈칸 남음

    _patch(api, user, doc_id, "reason", value="저는 지금 혼자 살고 있습니다.")
    appended = _assist(api, user, doc_id, "reason", "예시 문장 써 줘").json()["field"]
    assert appended["value"].startswith("저는 지금 혼자 살고 있습니다.\n")
    assert [s["source"] for s in appended["segments"]] == ["user", "ai"]

    replaced = _assist(api, user, doc_id, "reason", "예시 문장 써 줘", mode="replace").json()["field"]
    assert [s["source"] for s in replaced["segments"]] == ["ai"]
    assert replaced["can_undo"] is True


def test_revise_and_undo(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]
    _patch(api, user, doc_id, "plan", value="열심히 하겠습니다.")
    doc_llm.assist = {"action": "revise", "text": "이번 학기에는 전공 과목 성적을 올리겠습니다."}

    revised = _assist(api, user, doc_id, "plan", "좀 더 구체적으로").json()["field"]
    assert revised["segments"] == [{"text": "이번 학기에는 전공 과목 성적을 올리겠습니다.", "source": "ai"}]

    undone = api.post(f"/api/documents/{doc_id}/fields/plan/undo?user_id={user.id}").json()["field"]
    assert undone["value"] == "열심히 하겠습니다."
    assert undone["can_undo"] is False
    _err(api.post(f"/api/documents/{doc_id}/fields/plan/undo?user_id={user.id}"), 409, "NOTHING_TO_UNDO")


def test_assist_sends_no_profile_values_to_llm(api, db_session, doc_llm):
    user = make_user(db_session, name="김민지", phone="01012345678")
    doc_id = _create(api, user).json()["document_id"]
    _patch(api, user, doc_id, "plan", value="공부 계획입니다.")

    _assist(api, user, doc_id, "name", "다듬어 줘")
    _assist(api, user, doc_id, "reason", "예시 써 줘")

    sent = json.dumps([args for name, args in doc_llm.calls if name == "assist_field"], ensure_ascii=False)
    assert "김민지" not in sent and "01012345678" not in sent and "010-1234-5678" not in sent
    reason_req = [args[0] for name, args in doc_llm.calls if name == "assist_field"][-1]
    assert reason_req["other_fields"] == [{"label": "학업 계획", "text": "공부 계획입니다."}]
    assert reason_req["stage"] == "youth"


def test_completed_field_is_locked(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]

    done = _patch(api, user, doc_id, "reason", value="사유", is_completed=True).json()
    assert done["field"]["is_completed"] is True
    assert done["next_field_id"] == "name"
    assert done["progress"]["completed"] == 1

    _err(_assist(api, user, doc_id, "reason", "더 짧게"), 409, "FIELD_COMPLETED")
    _err(_patch(api, user, doc_id, "reason", value="바꿈"), 409, "FIELD_COMPLETED")

    reopened = _patch(api, user, doc_id, "reason", is_completed=False, value="바꾼 사유").json()["field"]
    assert (reopened["is_completed"], reopened["value"]) == (False, "바꾼 사유")


def test_completing_moves_to_next_field(api, db_session, doc_llm):
    user = make_user(db_session, phone="01012345678")
    doc_id = _create(api, user).json()["document_id"]
    for fid in ("name", "birth", "phone"):
        res = _patch(api, user, doc_id, fid, is_completed=True).json()
    assert res["next_field_id"] == "address"


def test_segments_keep_ai_spans(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]
    segments = [{"text": "제 계획은 ", "source": "user"}, {"text": "꾸준히 공부하는 것입니다.", "source": "ai"}]

    field = _patch(api, user, doc_id, "plan", segments=segments).json()["field"]
    assert field["segments"] == segments
    assert field["value"] == "제 계획은 꾸준히 공부하는 것입니다."
    _err(_patch(api, user, doc_id, "plan", value="a", segments=segments), 422, "VALIDATION_ERROR")
    _err(_patch(api, user, doc_id, "plan"), 422, "VALIDATION_ERROR")
    _err(_patch(api, user, doc_id, "nope", value="a"), 404, "FIELD_NOT_FOUND")


# ------------------------------------------------------------------ 라우터 (문서 화면)


def test_policy_question_goes_to_consult_ai(api, db_session, doc_llm, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001", name="자립수당")
    rag.answer_with(("P001", "소득 기준 근거"))
    doc_id = _create(api, user).json()["document_id"]

    data = _assist(api, user, doc_id, "reason", "소득 기준이 뭐야?").json()

    assert (data["route"], data["action"], data["field"]) == ("policy", None, None)
    assert data["search"]["results"][0]["name"] == "자립수당"
    assert [n for n, _ in doc_llm.calls] == ["route_message"]
    assert doc_llm.calls[0][1][2] == {"screen": "document", "document_title": "장학금 신청서", "field_label": "신청 사유"}


def test_field_request_with_policy_word_stays_field(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user, source_id="resume-form").json()["document_id"]
    data = _assist(api, user, doc_id, "intro", "지원 동기 예시 써 줘").json()
    assert (data["route"], data["action"]) == ("field", "draft")


def test_counsel_and_crisis_in_document(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]

    doc_llm.route = {"route": "counsel", "doc_hint": None}
    counsel = _assist(api, user, doc_id, "reason", "요즘 좀 우울해").json()
    assert counsel["route"] == "counsel" and counsel["field"] is None

    crisis = _assist(api, user, doc_id, "reason", "죽고 싶어").json()
    assert crisis["route"] == "crisis"
    assert crisis["contacts"][0]["phone"] == "109"


def test_llm_errors_are_503(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_id = _create(api, user).json()["document_id"]
    doc_llm.error = RuntimeError("down")
    _err(_assist(api, user, doc_id, "reason", "예시 써 줘"), 503, "AI_UNAVAILABLE")
    doc_llm.error = None
    doc_llm.assist = {"action": "unknown", "text": "x"}
    _err(_assist(api, user, doc_id, "reason", "예시 써 줘"), 503, "AI_UNAVAILABLE")


# ------------------------------------------------------------------ 검사·내보내기·체크리스트


def test_check_lists_problems(api, db_session, doc_llm):
    user = make_user(db_session, phone="01012345678")
    data = _create(api, user).json()
    doc_id = data["document_id"]
    _patch(api, user, doc_id, "reason", value="[학교 이름]에서 공부하고 있습니다.", is_completed=True)
    _patch(api, user, doc_id, "birth", value="2005년 3월", is_completed=True)

    res = api.get(f"/api/documents/{doc_id}/check?user_id={user.id}").json()
    reasons = {(p["field_id"], p["reason"]) for p in res["problems"]}

    assert res["ready"] is False
    assert ("reason", "placeholder_left") in reasons
    assert ("birth", "invalid_format") in reasons
    assert ("name", "not_completed") in reasons
    _err(api.post(f"/api/documents/{doc_id}/export", json={"user_id": str(user.id)}), 422, "EXPORT_NOT_READY")
    _err(api.get(f"/api/documents/{doc_id}/file?user_id={user.id}"), 409, "NOT_EXPORTED")


def test_export_checks_linked_checklist_item(api, db_session, doc_llm):
    user = make_user(db_session, phone="01012345678")
    policy = make_policy(db_session, required_docs=[doc("장학금 신청서"), doc("통장 사본")])
    checklist = api.post("/api/checklists", json={"user_id": str(user.id), "policy_id": policy.id}).json()
    item_id = checklist["steps"][2]["documents"][0]["item_id"]

    data = _create(api, user, checklist_item_id=item_id).json()
    assert data["checklist_item_id"] == item_id
    _complete_all(api, user, data)

    res = api.post(f"/api/documents/{data['document_id']}/export", json={"user_id": str(user.id)})
    body = res.json()

    assert res.status_code == 200, res.text
    assert body["document"]["status"] == "exported"
    assert body["checklist"] == {
        "checklist_id": checklist["checklist_id"], "item_id": item_id, "label": "장학금 신청서", "is_checked": True,
    }
    detail = api.get(f"/api/checklists/{checklist['checklist_id']}?user_id={user.id}").json()
    assert detail["steps"][2]["documents"][0]["is_checked"] is True
    assert detail["steps"][2]["documents"][1]["is_checked"] is False

    pdf = api.get(f"/api/documents/{data['document_id']}/file?user_id={user.id}&format=pdf")
    assert pdf.status_code == 200
    assert pdf.headers["content-type"] == "application/pdf"
    assert pdf.content.startswith(b"%PDF")
    docx = api.get(f"/api/documents/{data['document_id']}/file?user_id={user.id}&format=docx")
    assert docx.status_code == 200
    assert docx.content.startswith(b"PK")

    # 내보낸 서류: 체크리스트에서 문서 상태를 보여 주고, 직접 바꾸지 못한다
    doc_item = detail["steps"][2]["documents"][0]
    assert (doc_item["document_id"], doc_item["document_status"]) == (data["document_id"], "exported")
    assert detail["steps"][2]["documents"][1]["document_id"] is None
    item_url = f"/api/checklists/{checklist['checklist_id']}/items/{item_id}?user_id={user.id}"
    _err(api.patch(item_url, json={"is_checked": False}), 409, "DOCUMENT_WRITTEN")
    listed = api.get(f"/api/users/{user.id}/documents").json()["documents"][0]
    assert listed["checklist_item_id"] == item_id

    reopened = _patch(api, user, data["document_id"], "reason", is_completed=False).json()
    assert reopened["status"] == "writing"
    # 다시 고치면 체크도 풀리고, 직접 바꿀 수 있다
    after = api.get(f"/api/checklists/{checklist['checklist_id']}?user_id={user.id}").json()["steps"][2]["documents"][0]
    assert (after["is_checked"], after["document_status"]) == (False, "writing")
    assert api.patch(item_url, json={"is_checked": True}).status_code == 200


def test_export_warns_about_different_values(api, db_session, doc_llm):
    user = make_user(db_session, phone="01012345678")
    first = _create(api, user).json()
    _complete_all(api, user, first, {"address": "서울시 A로 1"})
    second = _create(api, user, source_id="resume-form").json()
    _patch(api, user, second["document_id"], "address", value="서울시 B로 2")
    _complete_all(api, user, api.get(f"/api/documents/{second['document_id']}?user_id={user.id}").json())

    res = api.post(f"/api/documents/{second['document_id']}/export", json={"user_id": str(user.id)}).json()
    assert any("장학금 신청서" in w and "주소" in w for w in res["warnings"])


def test_list_documents(api, db_session, doc_llm):
    user = make_user(db_session)
    _create(api, user)
    _create(api, user, source_id="resume-form")
    docs = api.get(f"/api/users/{user.id}/documents").json()["documents"]
    assert [d["title"] for d in docs] == ["입사지원서 (기본형)", "장학금 신청서"]


# ------------------------------------------------------------------ 정책 신청서 (formguide 결과)


@pytest.fixture
def forms_dir(tmp_path):
    settings = get_settings()
    old = settings.forms_dir
    settings.forms_dir = tmp_path
    yield tmp_path
    settings.forms_dir = old


def _write_form(path, policy_key="P001", checked=False):
    spec = {
        "form_id": f"{policy_key}-apply", "policy_key": policy_key, "title": "자립수당 지급 신청서",
        "source": "test.pdf", "checked": checked, "notes": [], "warnings": [],
        "fields": [
            {"id": "name", "label": "성명", "value_source": "profile", "profile_key": "name", "guide": "이름"},
            {"id": "rrn", "label": "주민등록번호", "input_type": "id_number", "value_source": "handwrite",
             "guide": "직접", "sensitive": True},
            {"id": "reason", "label": "신청 사유", "guide": "사유를 써요", "example": "예시"},
            {"id": "receipt", "label": "접수번호", "value_source": "office", "guide": "공무원"},
        ],
    }
    (path / f"{policy_key}-apply.json").write_text(json.dumps(spec, ensure_ascii=False), encoding="utf-8")


def test_policy_form_document(api, db_session, doc_llm, forms_dir):
    user = make_user(db_session, name="김민지")
    policy = make_policy(db_session, policy_key="P001", name="자립수당")
    _write_form(forms_dir)

    data = api.post("/api/documents", json={
        "user_id": str(user.id), "source_type": "policy_form", "policy_id": policy.id,
    }).json()

    assert (data["title"], data["source_id"], data["policy_id"]) == ("자립수당 지급 신청서", "P001-apply", policy.id)
    assert _field(data, "name")["value"] == "김민지"
    assert _field(data, "rrn")["writable"] is False
    assert _field(data, "receipt")["value_source"] == "office"
    reason = _field(data, "reason")
    assert (reason["essay"], reason["multiline"]) == (True, True)
    assert data["progress"]["total"] == 2
    assert data["notices"][0].startswith("아직 사람이 원본 양식과 대조하지 않은")


def test_policy_without_form(api, db_session, doc_llm, forms_dir):
    user = make_user(db_session)
    policy = make_policy(db_session)
    res = api.post("/api/documents", json={"user_id": str(user.id), "source_type": "policy_form", "policy_id": policy.id})
    _err(res, 404, "FORM_GUIDE_NOT_FOUND")


# ------------------------------------------------------------------ 라우터 (상담 화면 → 문서 AI 핸드오프)


def test_chat_hands_off_to_document_ai(api, db_session, doc_llm, forms_dir):
    user = make_user(db_session)
    policy = make_policy(db_session, policy_key="P001", name="자립수당")
    _write_form(forms_dir)

    res = api.post("/api/chat", json={"user_id": str(user.id), "message": "자립수당 신청서 써야 해"})
    data = res.json()

    assert res.status_code == 200, res.text
    assert data["type"] == "document"
    assert data["handoff"]["context"] == "자립수당 신청서 써야 해"
    assert data["handoff"]["candidates"][0] == {
        "source_type": "policy_form", "source_id": None, "policy_id": policy.id, "title": "자립수당",
    }
    assert {"source_type": "template", "source_id": "allowance", "policy_id": None, "title": "자립수당 신청서"} \
        in data["handoff"]["candidates"]


def test_chat_handoff_matches_policy_keyword(api, db_session, doc_llm, forms_dir):
    user = make_user(db_session)
    policy = make_policy(db_session, policy_key="P001", name="자립준비청년 자립수당 지급")
    make_policy(db_session, policy_key="P002", name="자립준비청년 주거 지원")
    _write_form(forms_dir)
    _write_form(forms_dir, policy_key="P002")

    data = api.post("/api/chat", json={"user_id": str(user.id), "message": "자립수당 신청서 써야 해"}).json()
    policy_ids = [c["policy_id"] for c in data["handoff"]["candidates"] if c["source_type"] == "policy_form"]
    assert policy_ids == [policy.id]  # "자립준비청년"·"지원" 같은 흔한 낱말로는 맞추지 않는다


def test_chat_handoff_without_match_offers_stage_templates(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_llm.route = {"route": "document", "doc_hint": None}
    data = api.post("/api/chat", json={"user_id": str(user.id), "message": "서류 좀 같이 써 줘"}).json()
    ids = [c["source_id"] for c in data["handoff"]["candidates"]]
    assert data["type"] == "document"
    assert ids == ["scholarship", "allowance", "move-in", "resume-form"]


def test_chat_question_about_documents_is_not_handoff(api, db_session, doc_llm, rag):
    user = make_user(db_session)
    data = api.post("/api/chat", json={"user_id": str(user.id), "message": "자립수당 받으려면 무슨 서류 필요해?"}).json()
    assert data["type"] == "policy"
    assert data["handoff"] is None


# ------------------------------------------------------------------ 원본 양식 위에 채워 내보내기


def test_policy_form_exports_on_original_layout(api, db_session, doc_llm, forms_dir):
    import pymupdf
    from formguide.layout import attach_boxes
    from formguide.schema import FormField, FormSpec
    from formguide.tests.sample_form import make_form

    user = make_user(db_session, name="김민지", phone="01012345678")
    policy = make_policy(db_session, policy_key="P001", name="자립수당")
    pdf = make_form(forms_dir / "P001-apply.pdf")
    fields = [
        FormField(id="name", label="성명", section="1. 신청인", value_source="profile", profile_key="name", guide="이름"),
        FormField(id="phone", label="전화:", section="1. 신청인", value_source="profile", profile_key="phone",
                  guide="전화"),
        FormField(id="seizure", label="압류방지통장 여부", input_type="checkbox", guide="고르기"),
        FormField(id="reason", label="신청 사유", guide="사유"),
        FormField(id="date", label="년 월 일", input_type="date", guide="신청일"),
        FormField(id="sign", label="신청인", value_source="handwrite", input_type="signature", sensitive=True,
                  guide="서명"),
        FormField(id="memo", label="추가 메모", guide="메모"),  # 양식에 없는 칸 → 별지
    ]
    spec = attach_boxes(FormSpec(form_id="P001-apply", policy_key="P001", title="자립수당 지급 신청서",
                                 source="test.pdf", fields=fields), pdf)
    (forms_dir / "P001-apply.json").write_text(spec.model_dump_json(), encoding="utf-8")
    blank_drawings = len(pymupdf.open(pdf)[0].get_drawings())

    data = api.post("/api/documents", json={
        "user_id": str(user.id), "source_type": "policy_form", "policy_id": policy.id,
    }).json()
    assert _field(data, "seizure")["options"] == ["네", "아니오"]
    _patch(api, user, data["document_id"], "seizure", value="모름")
    assert _field(api.get(f"/api/documents/{data['document_id']}?user_id={user.id}").json(), "seizure")["warnings"]

    _complete_all(api, user, data, {
        "seizure": "아니오", "reason": "혼자 생활하며 월세가 부담됩니다.", "date": "2026.10.27", "memo": "메모",
    })
    exported = api.post(f"/api/documents/{data['document_id']}/export", json={"user_id": str(user.id)}).json()
    assert exported["pdf_layout"] == "original"

    res = api.get(f"/api/documents/{data['document_id']}/file?user_id={user.id}&format=pdf")
    out = pymupdf.open(stream=res.content)
    first = out[0].get_text()

    assert out.page_count == 2  # 원본 1쪽 + 별지 1쪽
    assert "자립수당 지급 신청서 (가상 양식)" in first  # 원본 양식 그대로
    assert "김민지" in first and "010-1234-5678" in first and "월세가 부담됩니다" in first
    assert "2026" in first and "10" in first and "27" in first  # 년·월·일 자리에 숫자
    assert "아니오" in first and len(out[0].get_drawings()) == blank_drawings + 1  # 체크 표시 1개
    assert "메모" not in first
    assert "추가 메모" in out[1].get_text() and "별지" in out[1].get_text()


def test_template_export_is_table_layout(api, db_session, doc_llm):
    user = make_user(db_session, phone="01012345678")
    data = _create(api, user).json()
    _complete_all(api, user, data)
    exported = api.post(f"/api/documents/{data['document_id']}/export", json={"user_id": str(user.id)}).json()
    assert exported["pdf_layout"] == "table"
