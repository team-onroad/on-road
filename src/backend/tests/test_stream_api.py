"""스트리밍: POST /chat/stream, POST /coach/roleplay/{id}/reply/stream (text/event-stream)."""

import json
import uuid

import pytest

from app import llm_mock
from app.crisis import CRISIS_MESSAGE
from app.llm_client import Llm, get_llm
from app.main import app
from app.services.chat import _quick_intent, _quick_route
from tests.factories import make_policy, make_user
from tests.test_search_api import rag  # noqa: F401  (fixture)

OPENING = {"role": "assistant", "content": "다음 분 오세요. 무슨 일로 오셨어요?"}


def _events(res) -> list[tuple[str, dict]]:
    """SSE 본문 → [(이벤트 이름, data JSON)]"""
    out = []
    for block in res.text.split("\n\n"):
        if not block.strip():
            continue
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        out.append((lines["event"], json.loads(lines["data"])))
    return out


class FakeLlm:
    def __init__(self):
        self.intent = "counsel"
        self.counsel_events: list[dict] | None = [{"delta": "첫 문장이에요."}, {"delta": "둘째 문장이에요."},
                                                  {"final": "첫 문장이에요. 둘째 문장이에요.", "replace": False}]
        self.roleplay_events: list[dict] | None = [{"delta": "아, 네."}, {"delta": "신분증 있으세요?"},
                                                   {"final": "아, 네. 신분증 있으세요?", "is_finished": False, "replace": False}]
        self.fail_after: int | None = None
        self.calls: list[str] = []

    def classify_intent(self, message, history):
        self.calls.append("classify_intent")
        return self.intent

    def counsel_reply(self, message, history, profile):
        self.calls.append("counsel_reply")
        return "한 번에 온 답"

    def roleplay_reply(self, scenario, history, message):
        self.calls.append("roleplay_reply")
        return {"reply": "한 번에 온 대사", "is_finished": False}

    def _stream(self, events):
        for i, ev in enumerate(events):
            if self.fail_after is not None and i >= self.fail_after:
                raise RuntimeError("끊김")
            yield ev

    def counsel_stream(self, message, history, profile):
        self.calls.append("counsel_stream")
        return self._stream(self.counsel_events)

    def roleplay_stream(self, scenario, history, message):
        self.calls.append("roleplay_stream")
        return self._stream(self.roleplay_events)

    def as_llm(self) -> Llm:
        return Llm(
            self.classify_intent, self.counsel_reply, self.roleplay_reply, lambda *a: [], llm_mock.judge_checks,
            self.counsel_stream if self.counsel_events is not None else None,
            self.roleplay_stream if self.roleplay_events is not None else None,
        )


@pytest.fixture
def llm(api):
    fake = FakeLlm()
    app.dependency_overrides[get_llm] = fake.as_llm
    yield fake
    app.dependency_overrides.pop(get_llm, None)


def _chat(api, user_id, message="요즘 너무 막막해요", history=None):
    return api.post("/api/chat/stream", json={"user_id": str(user_id), "message": message, "history": history or []})


def _reply(api, user_id, message="통장 만들러 왔어요", scenario="bank"):
    body = {"user_id": str(user_id), "history": [OPENING], "message": message}
    return api.post(f"/api/coach/roleplay/{scenario}/reply/stream", json=body)


# ------------------------------------------------------------------ 상담


def test_counsel_streams_sentences_then_done(api, db_session, llm, rag):
    user = make_user(db_session)
    res = _chat(api, user.id)
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("text/event-stream")
    assert "gzip" not in res.headers.get("content-encoding", "")
    ev = _events(res)
    assert ev[:2] == [("delta", {"text": "첫 문장이에요."}), ("delta", {"text": "둘째 문장이에요."})]
    name, done = ev[2]
    assert name == "done" and len(ev) == 3
    assert done == {
        "type": "counsel", "message": "첫 문장이에요. 둘째 문장이에요.", "search": None, "contacts": [],
        "handoff": None, "profile_changes": [], "plan": None, "replace": False,
    }
    # 마음 이야기는 규칙으로 정해서 질문 종류를 AI 에게 묻지 않는다
    assert "classify_intent" not in llm.calls


def test_counsel_replace_when_unsafe(api, db_session, llm, rag):
    user = make_user(db_session)
    llm.counsel_events = [{"delta": "괜찮아요."}, {"final": "고정 안내", "replace": True}]
    ev = _events(_chat(api, user.id))
    assert ev[-1][0] == "done"
    assert ev[-1][1]["message"] == "고정 안내" and ev[-1][1]["replace"] is True


def test_counsel_falls_back_without_stream(api, db_session, llm, rag):
    user = make_user(db_session)
    llm.counsel_events = None
    ev = _events(_chat(api, user.id))
    assert ev == [
        ("delta", {"text": "한 번에 온 답"}),
        ("done", {"type": "counsel", "message": "한 번에 온 답", "search": None, "contacts": [], "handoff": None,
                  "profile_changes": [], "plan": None, "replace": False}),
    ]


def test_counsel_error_mid_stream(api, db_session, llm, rag):
    user = make_user(db_session)
    llm.fail_after = 1
    ev = _events(_chat(api, user.id))
    assert ev[0] == ("delta", {"text": "첫 문장이에요."})
    assert ev[-1] == ("error", {"error": {"code": "AI_UNAVAILABLE", "message": ev[-1][1]["error"]["message"]}})
    assert len(ev) == 2


def test_counsel_missing_final_is_error(api, db_session, llm, rag):
    user = make_user(db_session)
    llm.counsel_events = [{"delta": "끝이 없어요."}]
    ev = _events(_chat(api, user.id))
    assert ev[-1][0] == "error"


def test_crisis_is_single_done(api, db_session, llm, rag):
    user = make_user(db_session)
    ev = _events(_chat(api, user.id, message="죽고 싶어"))
    assert len(ev) == 1 and ev[0][0] == "done"
    assert ev[0][1]["type"] == "crisis" and ev[0][1]["message"] == CRISIS_MESSAGE
    assert llm.calls == []


def test_policy_question_is_single_done_without_ai_routing(api, db_session, llm, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001", name="자립수당")
    rag.answer_with(("P001", "근거"))
    ev = _events(_chat(api, user.id, message="자립수당 신청 자격이 궁금해요"))
    assert len(ev) == 1 and ev[0][0] == "done"
    assert ev[0][1]["type"] == "policy" and ev[0][1]["search"] is not None
    assert "classify_intent" not in llm.calls and "counsel_stream" not in llm.calls


def test_unknown_user_is_http_404(api, db_session, llm, rag):
    res = _chat(api, uuid.uuid4())
    assert res.status_code == 404
    assert res.json()["error"]["code"] == "USER_NOT_FOUND"


def test_validation_is_http_422(api, db_session, llm, rag):
    user = make_user(db_session)
    res = _chat(api, user.id, message="   ")
    assert res.status_code == 422


def test_mock_llm_streams(api, db_session, rag):
    """LLM_MODE=mock: 목 함수도 문장마다 나눠 보낸다"""
    user = make_user(db_session)
    ev = _events(_chat(api, user.id))
    assert [n for n, _ in ev].count("delta") == len(llm_mock._split(llm_mock.COUNSEL_MESSAGE))
    assert ev[-1][1]["message"] == llm_mock.COUNSEL_MESSAGE


# ------------------------------------------------------------------ 실전 연습


def test_roleplay_streams_then_done(api, db_session, llm):
    res = _reply(api, make_user(db_session).id)
    assert res.status_code == 200
    ev = _events(res)
    assert ev == [
        ("delta", {"text": "아, 네."}),
        ("delta", {"text": "신분증 있으세요?"}),
        ("done", {"reply": "아, 네. 신분증 있으세요?", "is_finished": False, "replace": False}),
    ]


def test_roleplay_fallback(api, db_session, llm):
    llm.roleplay_events = None
    ev = _events(_reply(api, make_user(db_session).id))
    assert ev == [
        ("delta", {"text": "한 번에 온 대사"}),
        ("done", {"reply": "한 번에 온 대사", "is_finished": False, "replace": False}),
    ]


def test_roleplay_error_mid_stream(api, db_session, llm):
    llm.fail_after = 1
    ev = _events(_reply(api, make_user(db_session).id))
    assert ev[0] == ("delta", {"text": "아, 네."})
    assert ev[-1][0] == "error" and ev[-1][1]["error"]["code"] == "AI_UNAVAILABLE"


def test_roleplay_crisis_and_404(api, db_session, llm):
    user = make_user(db_session)
    ev = _events(_reply(api, user.id, message="죽고 싶어요"))
    assert ev == [("done", {"reply": CRISIS_MESSAGE, "is_finished": True, "replace": False})]
    assert llm.calls == []
    assert _reply(api, user.id, scenario="nope").json()["error"]["code"] == "SCENARIO_NOT_FOUND"
    assert _reply(api, uuid.uuid4()).json()["error"]["code"] == "USER_NOT_FOUND"


# ------------------------------------------------------------------ 규칙 판단


@pytest.mark.parametrize("msg,expected", [
    ("요즘 너무 힘들어요", "counsel"),
    ("자립수당 신청 방법 알려줘", "policy"),
    ("월세 지원 받을 수 있어?", "policy"),
    ("안녕", "counsel"),
    ("자격증 공부를 시작했는데 걱정돼요", "counsel"),
    ("월세 지원 때문에 너무 막막해", None),  # 둘 다 → AI
    ("취업 준비는 뭐부터 해야 할까?", None),  # 애매 → AI
])
def test_quick_intent(msg, expected):
    assert _quick_intent(msg, []) == expected


def test_quick_intent_follow_up_goes_to_ai():
    history = [{"role": "user", "content": "힘들어"}, {"role": "assistant", "content": "그랬군요."}]
    assert _quick_intent("더 쉽게 말해 줘", history) is None


@pytest.mark.parametrize("msg,expected", [
    ("자립수당 신청서 써 줘", None),
    ("이력서 작성 도와줘", None),
    ("자립수당 받으려면 무슨 서류 필요해?", "other"),
    ("요즘 너무 힘들어", "other"),
])
def test_quick_route(msg, expected):
    assert _quick_route(msg) == expected
