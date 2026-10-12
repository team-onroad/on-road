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
    assert res.json() == {"type": "counsel", "message": "상담 답변", "search": None, "contacts": []}
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

    assert chat == search
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
    assert res.json() == {"type": "crisis", "message": CRISIS_MESSAGE, "search": None, "contacts": CONTACTS}
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
