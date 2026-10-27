"""문서 AI 대화형 작성 (docs/document-ai.md 3.32): AI 가 빈 칸을 하나씩 묻고, 다 채우면 내보낸다."""

import json
from datetime import date

from tests.factories import doc, make_policy, make_user
from tests.test_search_api import rag  # noqa: F401  (fixture)
from tests.test_writer_api import _err, _write_form, doc_llm, forms_dir  # noqa: F401  (fixture)


def _start(api, user, **body):
    payload = {"user_id": str(user.id), "source_type": "template", "source_id": "scholarship", **body}
    return api.post("/api/documents", json=payload).json()["document_id"]


def _say(api, user, doc_id, message=None):
    body = {"user_id": str(user.id)}
    if message is not None:
        body["message"] = message
    res = api.post(f"/api/documents/{doc_id}/interview", json=body)
    assert res.status_code == 200, res.text
    return res.json()


def test_full_interview_fills_and_exports(api, db_session, doc_llm, clock):
    user = make_user(db_session, name="김민지", birth_date=date(2005, 3, 15), phone="01012345678")
    policy = make_policy(db_session, required_docs=[doc("장학금 신청서")])
    checklist = api.post("/api/checklists", json={"user_id": str(user.id), "policy_id": policy.id}).json()
    item_id = checklist["steps"][2]["documents"][0]["item_id"]
    doc_id = _start(api, user, checklist_item_id=item_id)

    first = _say(api, user, doc_id)
    assert first["question"]["field_id"] == "name"
    assert first["question"]["awaiting_confirm"] is True  # 프로필로 미리 채운 값 확인
    assert "'김민지'" in first["question"]["text"]
    assert first["progress"] == {"completed": 0, "total": 7}

    assert _say(api, user, doc_id, "네")["question"]["field_id"] == "birth"
    assert _say(api, user, doc_id, "맞아요")["question"]["field_id"] == "phone"
    address_q = _say(api, user, doc_id, "응")["question"]
    assert (address_q["field_id"], address_q["awaiting_confirm"]) == ("address", False)
    assert address_q["text"].startswith("'사는 곳 주소'를 알려 주세요.")

    r = _say(api, user, doc_id, "서울시 OO구 OO로 12, 101동 202호")
    assert (r["message"], r["field"]["value"], r["field"]["is_completed"]) == ("적었어요.", "서울시 OO구 OO로 12, 101동 202호", True)
    assert r["field"]["segments"][0]["source"] == "user"
    reason_q = _say(api, user, doc_id, "OO대학교 2학년")["question"]
    assert reason_q["field_id"] == "reason" and "문장으로 정리" in reason_q["text"]

    # 서술형: AI 가 문장으로 쓰고 확인을 기다린다
    drafted = _say(api, user, doc_id, "혼자 살면서 등록금이 부담돼요")
    assert drafted["field"]["segments"][0]["source"] == "ai"
    assert drafted["field"]["is_completed"] is False
    assert drafted["question"]["field_id"] == "reason" and drafted["question"]["awaiting_confirm"] is True
    assert "이렇게 써 봤어요" in drafted["question"]["text"]
    revised = _say(api, user, doc_id, "조금 더 짧게")  # 확인 대신 고칠 점 → 다시 고친 초안
    assert revised["question"]["field_id"] == "reason"
    confirmed = _say(api, user, doc_id, "좋아")
    assert confirmed["field"]["is_completed"] is True
    assert confirmed["question"]["field_id"] == "plan"

    _say(api, user, doc_id, "이번 학기 전공 평점 3.5 이상")
    done = _say(api, user, doc_id, "좋아요")
    assert done["done"] is True and done["question"] is None
    assert done["progress"] == {"completed": 7, "total": 7}
    assert done["export"]["document"]["status"] == "exported"
    assert done["export"]["checklist"]["is_checked"] is True  # 다 채우면 체크리스트 서류도 체크
    assert done["message"].endswith("PDF로 받아 볼 수 있어요.")
    pdf = api.get(f"/api/documents/{doc_id}/file?user_id={user.id}&format=pdf")
    assert pdf.status_code == 200 and pdf.content.startswith(b"%PDF")


def test_profile_values_never_sent_to_llm(api, db_session, doc_llm):
    user = make_user(db_session, name="김민지", phone="01012345678")
    doc_id = _start(api, user)
    for message in (None, "네", "네", "네", "서울시 OO구", "OO대학교", "등록금이 부담돼요"):
        _say(api, user, doc_id, message)
    sent = json.dumps([args for _, args in doc_llm.calls], ensure_ascii=False)
    assert "김민지" not in sent and "01012345678" not in sent and "010-1234-5678" not in sent
    assert "서울시 OO구" not in sent  # 짧은 칸 답은 규칙으로만 정리
    assert [name for name, _ in doc_llm.calls] == ["assist_field"]  # 서술형 칸에서만 AI


def test_skip_required_field_comes_back_last(api, db_session, doc_llm):
    user = make_user(db_session, phone="01012345678")
    doc_id = _start(api, user)
    for _ in range(3):
        _say(api, user, doc_id, "네")
    later = _say(api, user, doc_id, "나중에 할게")
    assert later["message"] == "나중에 다시 물어볼게요."
    assert later["question"]["field_id"] == "school"
    _say(api, user, doc_id, "OO대학교")
    _say(api, user, doc_id, "사유")
    _say(api, user, doc_id, "좋아")
    _say(api, user, doc_id, "계획")
    back = _say(api, user, doc_id, "좋아")
    assert back["question"]["field_id"] == "address"  # 건너뛴 필수 칸은 맨 끝에 다시
    assert back["done"] is False and back["export"] is None


def test_form_checkbox_date_phone_and_not_applicable(api, db_session, doc_llm, forms_dir, clock):
    user = make_user(db_session, name="김민지")
    policy = make_policy(db_session, policy_key="P001", name="자립수당")
    spec = {
        "form_id": "P001-apply", "policy_key": "P001", "title": "자립수당 지급 신청서", "source": "t.pdf",
        "fields": [
            {"id": "notice", "label": "통지방법", "input_type": "checkbox", "guide": "고르기",
             "box": {"page": 0, "rect": [0, 0, 10, 10], "how": "checkbox", "options": ["서면", "문자 메시지", "기타"]}},
            {"id": "end", "label": "보호종료일", "input_type": "date", "guide": "날짜"},
            {"id": "g_phone", "label": "대리인 전화 :", "input_type": "phone", "guide": "전화"},
            {"id": "g_name", "label": "대리인 성명", "guide": "대리 신청할 때만"},
        ],
    }
    (forms_dir / "P001-apply.json").write_text(json.dumps(spec, ensure_ascii=False), encoding="utf-8")
    doc_id = api.post("/api/documents", json={
        "user_id": str(user.id), "source_type": "policy_form", "policy_id": policy.id,
    }).json()["document_id"]

    q = _say(api, user, doc_id)["question"]
    assert q["options"] == ["서면", "문자 메시지", "기타"] and "골라 주세요" in q["text"]
    assert _say(api, user, doc_id, "전화로 해 주세요")["message"].startswith("선택지 중에서 골라 주세요")
    assert _say(api, user, doc_id, "문자메시지로요")["field"]["value"] == "문자 메시지"
    assert _say(api, user, doc_id, "2026년 2월 28일")["field"]["value"] == "2026.02.28"
    assert _say(api, user, doc_id, "공일공 1234")["message"].startswith("전화번호를 다시")
    assert _say(api, user, doc_id, "01098765432")["field"]["value"] == "010-9876-5432"
    skipped = _say(api, user, doc_id, "해당 없음")  # 필수가 아닌 대리인 칸은 비워 두고 완료
    assert skipped["message"].startswith("비워 둘게요.")
    assert (skipped["field"]["value"], skipped["field"]["is_completed"]) == ("", True)
    assert skipped["done"] is True and skipped["export"]["pdf_layout"] in ("original", "table")


def test_question_goes_to_router(api, db_session, doc_llm, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001", name="자립수당")
    rag.answer_with(("P001", "소득 기준 근거"))
    doc_id = _start(api, user)
    before = _say(api, user, doc_id)["question"]

    r = _say(api, user, doc_id, "소득 기준이 뭐야?")
    assert r["route"] == "policy" and r["search"]["results"][0]["name"] == "자립수당"
    assert r["question"] == before  # 칸은 그대로, 같은 질문 다시

    tip = _say(api, user, doc_id, "여기엔 뭘 써야 돼?")
    assert tip["route"] == "field" and tip["message"] and tip["question"] == before

    crisis = _say(api, user, doc_id, "죽고 싶어")
    assert crisis["route"] == "crisis" and crisis["contacts"][0]["phone"] == "109"


def test_done_with_problems_returns_check(api, db_session, doc_llm):
    user = make_user(db_session, phone="01012345678")
    doc_id = _start(api, user)
    for message in ("네", "네", "네", "서울시", "OO대학교", "[학교 이름]에서 공부해요", "좋아", "계획", "좋아"):
        r = _say(api, user, doc_id, message)
    assert r["done"] is True and r["export"] is None
    assert r["check"]["problems"][0]["reason"] == "placeholder_left"
    assert r["message"].startswith("고칠 칸이") or "고칠 칸이" in r["message"]


def test_interview_errors(api, db_session, doc_llm):
    user = make_user(db_session)
    other = make_user(db_session)
    doc_id = _start(api, user)
    _err(api.post(f"/api/documents/{doc_id}/interview", json={"user_id": str(other.id)}), 404, "DOCUMENT_NOT_FOUND")
    _err(api.post(f"/api/documents/{doc_id}/interview", json={"user_id": str(user.id), "message": "  "}),
         422, "VALIDATION_ERROR")
