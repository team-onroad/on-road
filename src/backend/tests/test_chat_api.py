"""POST /chat (api.md 1.6, 3.15, 4.2, 부록)."""

import logging
import uuid
from datetime import date

import pytest
from sqlalchemy import text

from app.crisis import CRISIS_MESSAGE
from app.llm_client import Llm, get_llm
from app.llm_mock import COUNSEL_MESSAGE
from app.main import app
from tests.conftest import TABLES
from tests.factories import make_policy, make_user
from tests.test_search_api import rag  # noqa: F401  (fixture)

CONTACTS = [
    {"name": "자살예방상담전화", "phone": "109"},
    {"name": "청소년상담전화", "phone": "1388"},
    {"name": "보건복지상담센터", "phone": "129"},
    {"name": "경찰", "phone": "112"},
]


class FakeLlm:
    def __init__(self):
        self.intent: object = "counsel"
        self.reply: object = "상담 답변"
        self.error: Exception | None = None
        self.calls: list[tuple[str, tuple]] = []

    def _call(self, name, args, value):
        self.calls.append((name, args))
        if self.error is not None:
            raise self.error
        return value

    def classify_intent(self, message, history):
        return self._call("classify_intent", (message, history), self.intent)

    def counsel_reply(self, message, history, profile):
        return self._call("counsel_reply", (message, history, profile), self.reply)

    def as_llm(self) -> Llm:
        return Llm(self.classify_intent, self.counsel_reply, lambda *a: {}, lambda *a: [])


@pytest.fixture
def llm(api):
    fake = FakeLlm()
    app.dependency_overrides[get_llm] = fake.as_llm
    yield fake
    app.dependency_overrides.pop(get_llm, None)


def _chat(api, user_id, message="취업 준비는 뭐부터 해야 할까?", history=None):
    body = {"user_id": str(user_id), "message": message}
    if history is not None:
        body["history"] = history
    return api.post("/api/chat", json=body)


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


# ------------------------------------------------------------------ 응답 종류


def test_counsel(api, db_session, llm, rag):
    user = make_user(db_session)
    res = _chat(api, user.id)

    assert res.status_code == 200
    assert res.json() == {"type": "counsel", "message": "상담 답변", "search": None, "contacts": [], "handoff": None, "profile_changes": [], "plan": None}
    assert rag.calls == []


def test_policy_uses_search(api, db_session, llm, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001", name="자립수당")
    llm.intent = "policy"
    rag.answer_with(("P001", "근거 원문"))

    res = _chat(api, user.id, message="  퇴소하면 받는 돈 있어?  ")
    data = res.json()

    assert res.status_code == 200
    assert (data["type"], data["message"], data["contacts"]) == ("policy", None, [])
    assert data["search"]["answer_status"] == "answered"
    assert data["search"]["results"][0]["name"] == "자립수당"
    assert data["search"]["results"][0]["evidences"] == [{"content": "근거 원문"}]
    assert rag.calls[0]["question"] == "퇴소하면 받는 돈 있어?"
    assert [name for name, _ in llm.calls] == ["classify_intent"]  # 상담 답변 안 부름


def test_policy_search_matches_search_api(api, db_session, llm, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    make_policy(db_session, policy_key="P002", region="부산")  # 지역 제외
    llm.intent = "policy"
    rag.answer_with(("P001", "근거"))

    chat = _chat(api, user.id, message="자립수당").json()["search"]
    search = api.post("/api/search", json={"user_id": str(user.id), "question": "자립수당"}).json()

    # 상담은 답변 끝에 '내 조건으로 보면'을 붙인다 (정책 카드 · 판정은 검색과 같다)
    assert chat["results"] == search["results"]
    assert chat["answer"].startswith(search["answer"])
    assert "[내 조건으로 보면]" in chat["answer"]
    assert rag.calls[0]["candidate_policy_keys"] == rag.calls[1]["candidate_policy_keys"] == ["P001"]


def test_policy_no_evidence(api, db_session, llm, rag):
    user = make_user(db_session)
    llm.intent = "policy"
    res = _chat(api, user.id)
    assert res.json()["search"] == {"answer_status": "no_evidence", "answer": None, "results": []}


@pytest.mark.parametrize(
    "message",
    ["요즘 죽고 싶어", "자 살 생각이 들어", "손목을 긋고 싶어", "집에서 학대를 당하고 있어", "살기 싫어"],
)
def test_crisis_skips_llm(api, db_session, llm, rag, message):
    user = make_user(db_session)
    llm.error = RuntimeError("불리면 안 됨")

    res = _chat(api, user.id, message=message)

    assert res.status_code == 200
    assert res.json() == {"type": "crisis", "message": CRISIS_MESSAGE, "search": None, "contacts": CONTACTS, "handoff": None, "profile_changes": [], "plan": None}
    assert llm.calls == [] and rag.calls == []


def test_crisis_only_checks_current_message(api, db_session, llm, rag):
    user = make_user(db_session)
    history = [{"role": "user", "content": "죽고 싶어"}, {"role": "assistant", "content": "안내"}]
    assert _chat(api, user.id, message="이제 좀 괜찮아", history=history).json()["type"] == "counsel"


def test_mock_llm_without_override(api, db_session, rag):
    user = make_user(db_session)
    assert _chat(api, user.id, message="요즘 앞으로가 막막해").json()["message"] == COUNSEL_MESSAGE
    assert _chat(api, user.id, message="월세 지원 받을 수 있어?").json()["type"] == "policy"


# ------------------------------------------------------------------ LLM 전달 값


def test_llm_receives_history_and_profile_only(api, db_session, llm, rag):
    user = make_user(db_session, name="홍길동", phone="01012345678", status="leaving_soon",
                     d_date=date(2026, 11, 12))
    history = [{"role": "assistant", "content": " 안녕하세요 "}, {"role": "user", "content": "고민 있어"}]

    _chat(api, user.id, message="진로가 고민이야", history=history)

    expected_history = [{"role": "assistant", "content": "안녕하세요"}, {"role": "user", "content": "고민 있어"}]
    assert llm.calls[0] == ("classify_intent", ("진로가 고민이야", expected_history))
    assert llm.calls[1] == (
        "counsel_reply",
        ("진로가 고민이야", expected_history, {"stage": "youth", "status": "leaving_soon", "d_day": 39}),
    )
    sent = str(llm.calls)
    assert all(v not in sent for v in ("홍길동", "01012345678", "2005-03-15", str(user.id)))


def test_profile_stage_follows_birthday(api, db_session, llm, rag, clock):
    user = make_user(db_session, birth_date=date(2008, 10, 5), status="in_care")
    _chat(api, user.id)
    clock.today = date(2026, 10, 5)
    _chat(api, user.id)
    assert [c[1][2]["stage"] for c in llm.calls if c[0] == "counsel_reply"] == ["teen", "youth"]


def test_history_defaults_to_empty(api, db_session, llm, rag):
    user = make_user(db_session)
    _chat(api, user.id)
    assert llm.calls[0][1][1] == []


def test_history_null_is_empty(api, db_session, llm, rag):
    user = make_user(db_session)
    res = api.post("/api/chat", json={"user_id": str(user.id), "message": "질문", "history": None})
    assert res.status_code == 200
    assert llm.calls[0][1][1] == []


# ------------------------------------------------------------------ LLM 오류


def test_llm_exception_returns_503(api, db_session, llm, rag):
    user = make_user(db_session)
    llm.error = RuntimeError("down")
    _assert_error(_chat(api, user.id), 503, "AI_UNAVAILABLE")


@pytest.mark.parametrize("intent", [None, "", "crisis", "POLICY", 1, ["policy"]])
def test_invalid_intent_returns_503(api, db_session, llm, rag, intent):
    user = make_user(db_session)
    llm.intent = intent
    _assert_error(_chat(api, user.id), 503, "AI_UNAVAILABLE")


@pytest.mark.parametrize("reply", [None, "", "   ", 1, {"reply": "x"}])
def test_invalid_counsel_reply_returns_503(api, db_session, llm, rag, reply):
    user = make_user(db_session)
    llm.reply = reply
    _assert_error(_chat(api, user.id), 503, "AI_UNAVAILABLE")


def test_rag_error_in_policy_returns_rag_unavailable(api, db_session, llm, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    llm.intent = "policy"
    rag.error = RuntimeError("down")
    _assert_error(_chat(api, user.id), 503, "RAG_UNAVAILABLE")


# ------------------------------------------------------------------ 404 / 422


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
def test_unknown_user_returns_404(api, db_session, llm, rag, user_id):
    _assert_error(_chat(api, user_id), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize(
    "extra",
    [{"message": ""}, {"history": [{"role": "system", "content": "x"}]}, {"unknown": 1}],
)
def test_unknown_user_takes_priority_over_invalid_body(api, llm, rag, extra):
    body = {"user_id": str(uuid.uuid4()), "message": "질문", **extra}
    _assert_error(api.post("/api/chat", json=body), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize(
    "body",
    [
        {"message": "질문"},
        {"user_id": 123, "message": "질문"},
        {"user_id": None, "message": "질문"},
    ],
)
def test_user_id_missing_or_not_string_is_422(api, db_session, llm, rag, body):
    _assert_error(api.post("/api/chat", json=body), 422, "VALIDATION_ERROR")


@pytest.mark.parametrize(
    "extra",
    [
        {},
        {"message": ""},
        {"message": "   "},
        {"message": "가" * 501},
        {"message": 1},
        {"message": None},
        {"message": "질문", "history": "x"},
        {"message": "질문", "history": [{"role": "user", "content": "x"}] * 21},
        {"message": "질문", "history": [{"role": "system", "content": "x"}]},
        {"message": "질문", "history": [{"role": "user", "content": ""}]},
        {"message": "질문", "history": [{"role": "user", "content": " "}]},
        {"message": "질문", "history": [{"role": "user", "content": "가" * 1001}]},
        {"message": "질문", "history": [{"role": "user"}]},
        {"message": "질문", "history": [{"role": "user", "content": "x", "extra": 1}]},
        {"message": "질문", "unknown": 1},
    ],
)
def test_invalid_body_is_422(api, db_session, llm, rag, extra):
    user = make_user(db_session)
    res = api.post("/api/chat", json={"user_id": str(user.id), **extra})
    _assert_error(res, 422, "VALIDATION_ERROR")
    assert llm.calls == []


def test_limits_are_allowed(api, db_session, llm, rag):
    user = make_user(db_session)
    history = [{"role": "user", "content": "가" * 1000}] * 20
    assert _chat(api, user.id, message="나" * 500, history=history).status_code == 200


def test_malformed_json_is_422(api, llm, rag):
    res = api.post("/api/chat", content="{bad", headers={"content-type": "application/json"})
    _assert_error(res, 422, "VALIDATION_ERROR")


def test_422_message_does_not_echo_input(api, db_session, llm, rag):
    user = make_user(db_session)
    secret = "비밀내용-" + "가" * 1000
    res = api.post("/api/chat", json={"user_id": str(user.id), "message": "질문",
                                      "history": [{"role": "user", "content": secret}]})
    assert res.status_code == 422 and "비밀내용" not in res.text


# ------------------------------------------------------------------ 저장·로그 안 함


@pytest.mark.parametrize("intent", ["policy", "counsel"])
def test_conversation_is_not_stored_or_logged(api, db_session, llm, rag, caplog, intent):
    secret = "비밀대화-월세-12345"
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    rag.answer_with(("P001", "근거"))
    llm.intent = intent
    history = [{"role": "user", "content": f"{secret}-이전"}]
    caplog.set_level(logging.DEBUG)

    _chat(api, user.id, message=secret, history=history)
    llm.error = RuntimeError(f"failed for {secret}")  # 예외 메시지에 대화가 들어 있어도
    _chat(api, user.id, message=secret, history=history)
    _chat(api, user.id, message=f"{secret} 죽고 싶어", history=history)

    assert secret not in caplog.text
    for table in TABLES:
        rows = db_session.execute(text(f"SELECT * FROM {table}")).all()
        assert all(secret not in str(row) for row in rows)


def test_never_says_apply_possible(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    rag.answer_with(("P001", "근거"))
    for message in ("요즘 막막해", "자립수당 신청", "죽고 싶어"):
        assert "신청 가능" not in _chat(api, user.id, message=message).text


def test_profile_change_card(api, db_session, llm, rag):
    user = make_user(db_session, region="서울")
    res = _chat(api, user.id, "저 이번에 부산으로 이사했는데 월세 지원 받을 수 있어?")
    assert res.status_code == 200, res.text
    assert res.json()["profile_changes"] == [{
        "field": "region", "label": "사는 지역", "current": "서울", "proposed": "부산", "proposed_label": "부산",
    }]
    db_session.refresh(user)
    assert user.region == "서울"  # 승인 전에는 바꾸지 않는다

    # 앱이 카드에서 '바꾸기' → 회원정보 수정 API
    assert api.patch(f"/api/users/{user.id}", json={"region": "부산"}).json()["region"] == "부산"
    assert _chat(api, user.id, "부산으로 이사했어요").json()["profile_changes"] == []

    crisis = _chat(api, user.id, "서울로 이사했는데 죽고 싶어").json()
    assert crisis["type"] == "crisis" and "profile_changes" not in crisis or crisis["profile_changes"] == []


def test_policy_embedding_is_prefetched_while_classifying(api, db_session, llm, rag):
    """AI 가 정책 질문인지 판단하는 동안 검색 임베딩을 미리 만든다 (실제 RAG 함수에만 prefetch 가 있다)"""
    user = make_user(db_session)
    prefetched = []
    rag.prefetch = prefetched.append
    llm.intent = "counsel"
    _chat(api, user.id, message="퇴소하면 받는 돈 있어?")  # 규칙으로 못 정해 AI 에게 묻는다
    _chat(api, user.id, message="요즘 너무 힘들어")  # 규칙으로 상담 → 미리 만들지 않음
    assert prefetched == ["퇴소하면 받는 돈 있어?"]


def test_prefetch_masks_personal_info_like_search(api, db_session, llm, rag):
    """미리 만들기도 검색처럼 개인정보를 가린 문장을 보낸다 (app/pii.py, OpenAI 로 나가는 글자는 모두 가림)"""
    user = make_user(db_session)
    make_policy(db_session)  # 후보 정책이 없으면 백엔드는 RAG 를 부르지 않는다 (api.md 4장)
    prefetched = []
    rag.prefetch = prefetched.append
    llm.intent = "policy"
    _chat(api, user.id, message="010-1234-5678 로 연락받는 퇴소 청년이 받는 돈 있어?")
    assert prefetched and "010-1234-5678" not in prefetched[0]
    assert rag.calls[0]["question"] == prefetched[0]  # 검색에 쓴 문장과 같아 임베딩을 다시 쓴다


# ------------------------------------------------------------------ 넓은 질문: 내 조건 요약 (plan)


def test_broad_policy_question_has_plan(api, db_session, llm, rag):
    user = make_user(db_session, status="leaving_soon", d_date=date(2026, 12, 31))
    make_policy(
        db_session, policy_key="P001", name="자립수당", target_description="보호종료 자립준비청년",
        apply_method="보호종료 30일 전부터 사전 신청", support_amount="월 50만 원",
    )
    make_policy(db_session, policy_key="P002", name="정착금", target_description="보호종료아동",
                support_amount="1,000만 원")
    llm.intent = "policy"
    rag.answer_with(("P001", "근거"), answer="월 500천원을 받아요")

    data = _chat(api, user.id, message="퇴소하면 받을 수 있는 게 뭐가 있어?").json()

    assert data["search"]["answer"].startswith("월 50만 원을 받아요")
    plan = data["plan"]
    assert plan["base_date"] == "2026-12-31"
    assert plan["monthly_won"] == 500_000
    assert plan["once_won"] == 10_000_000
    assert [i["name"] for i in plan["items"]] == ["자립수당", "정착금"]
    assert plan["items"][0]["apply_from"] == "2026-12-01"
    assert plan["items"][0]["apply_note"] == "퇴소 30일 전부터 미리 신청"
    assert set(plan["items"][0]) == {"policy_id", "name", "amount_text", "benefit", "apply_from", "apply_note", "has_form"}
    assert plan["notice"]


def test_specific_policy_question_has_no_plan(api, db_session, llm, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001", name="자립수당")
    llm.intent = "policy"
    rag.answer_with(("P001", "근거"))

    data = _chat(api, user.id, message="자립수당 신청 방법 알려줘").json()

    assert data["plan"] is None


def test_counsel_has_no_plan(api, db_session, llm, rag):
    user = make_user(db_session)
    data = _chat(api, user.id, message="요즘 너무 힘들어").json()
    assert data["type"] == "counsel" and data["plan"] is None


# ------------------------------------------------------------------ 내 조건으로 보면 (답변 끝 규칙 문장)


def _note(data):
    answer = data["search"]["answer"]
    assert "[내 조건으로 보면]" in answer
    return answer.split("[내 조건으로 보면]", 1)[1].strip().splitlines()


def test_my_check_receiving_policy(api, db_session, llm, rag):
    user = make_user(db_session)
    got = make_policy(db_session, policy_key="P001", name="자립준비청년 자립수당 지급")
    api.post(f"/api/users/{user.id}/saved-policies", json={"policy_id": got.id, "status": "receiving"})
    llm.intent = "policy"
    rag.answer_with(("P001", "근거"))

    assert _note(_chat(api, user.id, message="자립수당").json()) == ["- 자립수당 지급: 지금 받고 있는 지원이에요."]


def test_my_check_education_and_better_same_kind(api, db_session, llm, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="T101", name="가 자립정착금 지원", support_amount="500만 원", support_period=None,
                required_docs=["사용계획서", "의무교육확인서"])
    make_policy(db_session, policy_key="T102", name="나 자립정착금 지원", support_amount="1,500만 원", support_period=None)
    llm.intent = "policy"
    rag.answer_with(("T101", "근거"))

    lines = _note(_chat(api, user.id, message="정착금").json())

    assert len(lines) == 1
    assert lines[0].startswith("- 가 자립정착금 지원: 나이 · 지역 조건은 맞아요.")
    assert "자립정착금 의무교육을 먼저 들어야 해요" in lines[0]
    assert "같은 종류 중에는 나 자립정착금 지원(1,500만 원)이 더 유리해요" in lines[0]


def test_my_check_short_name():
    from app.services.my_check import short_name

    assert short_name("서리풀 희망사다리 프로젝트 자립준비청년(보호종료아동) 생활보조수당 지원") == "서리풀 생활보조수당 지원"
    assert short_name("자립준비청년 자립수당 지급") == "자립수당 지급"


@pytest.mark.parametrize(("text", "want"), [
    ("서초구 1년 이상 거주 자립준비청년(보호종료아동)", "서초구 1년 이상 거주"),
    ("기준중위소득 50% 이하 청년", "기준중위소득 50% 이하"),
    ("아동복지시설 퇴소 후 5년 이내 미혼 무주택자", "미혼 무주택자"),
    ("보호종료 5년 이내 자립준비청년", ""),  # 앱이 이미 판정한 조건만 있으면 묻지 않는다
])
def test_my_check_clean_condition(text, want):
    from app.services.my_check import clean_condition

    assert clean_condition(text) == want


def test_my_check_failure_keeps_answer(api, db_session, llm, rag, monkeypatch):
    """덧붙이기가 실패해도 답변은 그대로 나간다 (시연 중 상담이 멈추지 않게)"""
    from app.services import my_check

    def boom(*_a, **_k):
        raise RuntimeError("테스트")

    monkeypatch.setattr(my_check, "lines", boom)
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    llm.intent = "policy"
    rag.answer_with(("P001", "근거"), answer="근거 기반 답변")

    res = _chat(api, user.id, message="자립수당")

    assert res.status_code == 200
    assert res.json()["search"]["answer"] == "근거 기반 답변"
