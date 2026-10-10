"""LLM 목 함수·위기 신호 규칙 (api.md 3.15, 4.2). DB 불필요"""

import pytest

from app import llm_client, llm_mock
from app.crisis import CRISIS_CONTACTS, is_crisis
from app.errors import AppError


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ("퇴소하면 매달 받을 수 있는 돈이 있어?", "policy"),
        ("자립수당 얼마야?", "policy"),
        ("월세 지원 있어?", "policy"),
        ("장 학 금 알려줘", "policy"),
        ("취업 준비는 뭐부터 해야 할까?", "counsel"),
        ("요즘 앞으로가 너무 막막해", "counsel"),
    ],
)
def test_classify_intent(message, expected):
    assert llm_mock.classify_intent(message, []) == expected


def test_counsel_reply_is_fixed():
    reply = llm_mock.counsel_reply("고민", [], {"stage": "youth", "status": "left_care", "d_day": None})
    assert reply == llm_mock.COUNSEL_MESSAGE
    assert "신청 가능" not in reply


def test_roleplay_reply_in_order():
    scenario = {"lines": ["대사1", "대사2"]}
    opening = [{"role": "assistant", "content": "첫 대사"}, {"role": "user", "content": "답"}]
    second = opening + [{"role": "assistant", "content": "대사1"}, {"role": "user", "content": "답"}]
    third = second + [{"role": "assistant", "content": "대사2"}, {"role": "user", "content": "답"}]

    assert llm_mock.roleplay_reply(scenario, opening, "답") == {"reply": "대사1", "is_finished": False}
    assert llm_mock.roleplay_reply(scenario, second, "답") == {"reply": "대사2", "is_finished": False}
    assert llm_mock.roleplay_reply(scenario, third, "답") == {"reply": llm_mock.ROLEPLAY_END, "is_finished": True}


def test_polish_sentences_returns_input():
    sentences = ["문장 1", "문장 2"]
    result = llm_mock.polish_sentences("resume", sentences)
    assert result == sentences and result is not sentences


def test_client_uses_mock():
    llm = llm_client.get_llm()
    assert llm.classify_intent is llm_mock.classify_intent
    assert llm.counsel_reply is llm_mock.counsel_reply
    assert llm.roleplay_reply is llm_mock.roleplay_reply
    assert llm.polish_sentences is llm_mock.polish_sentences


def test_call_llm_errors():
    def boom():
        raise ValueError("x")

    for fn in (boom, lambda: None):
        with pytest.raises(AppError) as e:
            llm_client.call_llm("f", fn, lambda v: v is not None)
        assert (e.value.status_code, e.value.code) == (503, "AI_UNAVAILABLE")


@pytest.mark.parametrize(
    "message",
    ["죽고 싶어", "죽고싶다", "자살", "자해했어", "사라지고 싶어", "목숨을 끊고 싶어", "뛰어내리고 싶어",
     "성추행 당했어", "아빠한테 폭행 당해", "협박 받고 있어"],
)
def test_crisis_detected(message):
    assert is_crisis(message)


@pytest.mark.parametrize(
    "message",
    ["배고파 죽겠다", "죽 먹고 싶어", "취업이 걱정돼", "월세가 너무 비싸", "요즘 막막해", "맞았어 정답이야"],
)
def test_crisis_not_detected(message):
    assert not is_crisis(message)


def test_crisis_contacts():
    assert [c["phone"] for c in CRISIS_CONTACTS] == ["109", "1388", "129", "112"]


def test_roleplay_reply_without_assistant_turn_finishes():
    history = [{"role": "user", "content": "답"}]
    assert llm_mock.roleplay_reply({"lines": ["대사1"]}, history, "답")["is_finished"] is True
