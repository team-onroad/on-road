"""스트리밍 (coach.llm.sentences · counsel_stream · roleplay_stream). OpenAI 대신 글 조각을 흘려보내는 가짜를 쓴다."""

import pytest

from coach.llm import (
    ROLEPLAY_END, ROLEPLAY_END_MARK, ROLEPLAY_EXTRA_TURNS, SAFE_COUNSEL, CoachFormatError,
    counsel_stream, roleplay_stream, sentences,
)
from coach.tests.test_coach import SCENARIO, turns


class FakeStream:
    """글을 몇 글자씩 잘라 흘려보낸다 (실제 스트리밍처럼 문장이 조각 중간에서 끝남)"""

    def __init__(self, text: str, size: int = 3):
        self.text, self.size = text, size
        self.calls: list[list[dict]] = []

    def __call__(self, messages):
        self.calls.append(messages)
        return (self.text[i:i + self.size] for i in range(0, len(self.text), self.size))


def test_sentences_split_on_end_marks_only():
    pieces = ["안녕하세", "요. 오늘", "은 1.5배 ", "빨라요! 정말", "요?\n다음 줄", " 끝"]
    assert list(sentences(pieces)) == ["안녕하세요.", "오늘은 1.5배 빨라요!", "정말요?", "다음 줄 끝"]


def test_counsel_stream_yields_sentences_then_final():
    fake = FakeStream("속상했겠어요. 조금 더 이야기해 줄래요?")
    out = list(counsel_stream("힘들어", [], {"stage": "youth"}, fake))
    assert out == [
        {"delta": "속상했겠어요."}, {"delta": "조금 더 이야기해 줄래요?"},
        {"final": "속상했겠어요. 조금 더 이야기해 줄래요?", "replace": False},
    ]
    system = fake.calls[0][0]["content"]
    assert "JSON 없이" in system and '{"reply"' not in system


def test_counsel_stream_polite_and_safe():
    out = list(counsel_stream("x", [], {}, FakeStream("속상했구나. 다시 해 봐.")))
    assert [e["delta"] for e in out if "delta" in e] == ["속상했군요.", "다시 해 봐요."]
    # 금액이 나오면 거기서 멈추고 고정 안내로 바꾼다
    out = list(counsel_stream("x", [], {}, FakeStream("괜찮아요. 자립수당은 50만 원이에요. 신청해요.")))
    assert out == [{"delta": "괜찮아요."}, {"final": SAFE_COUNSEL, "replace": True}]


def test_counsel_stream_empty_is_error():
    with pytest.raises(CoachFormatError):
        list(counsel_stream("x", [], {}, FakeStream("   ")))


def test_roleplay_stream_sentences_and_end_mark():
    fake = FakeStream(f"네, 다 됐어요. 안녕히 가세요. {ROLEPLAY_END_MARK}")
    out = list(roleplay_stream(SCENARIO, turns(2), "감사합니다", fake))
    assert out[:2] == [{"delta": "네, 다 됐어요."}, {"delta": "안녕히 가세요."}]
    assert out[-1] == {"final": "네, 다 됐어요. 안녕히 가세요.", "is_finished": True, "replace": False}
    system = fake.calls[0][0]["content"]
    assert ROLEPLAY_END_MARK in system and '{"reply"' not in system and "은행 창구 직원" in system


def test_roleplay_stream_end_mark_needs_user_closing():
    # 사용자가 질문 중이면 [끝] 이 와도 끝내지 않는다 (보통 답변과 같은 규칙)
    out = list(roleplay_stream(SCENARIO, turns(2), "도장 꼭 있어야 해요", FakeStream(f"네, 있어야 해요. {ROLEPLAY_END_MARK}")))
    assert out[-1]["is_finished"] is False


def test_roleplay_stream_over_limit_skips_llm():
    fake = FakeStream("x")
    over = [{"role": "assistant", "content": "x"}] * (len(SCENARIO["lines"]) + ROLEPLAY_EXTRA_TURNS)
    out = list(roleplay_stream(SCENARIO, over, "네", fake))
    assert out == [{"delta": ROLEPLAY_END}, {"final": ROLEPLAY_END, "is_finished": True, "replace": False}]
    assert fake.calls == []


def test_roleplay_stream_repeat_is_replaced_once():
    said = "신분증 있으세요? 주민등록증이나 운전면허증이요."
    history = [{"role": "assistant", "content": said}]
    retry_calls = []

    def generate(messages):
        retry_calls.append(messages)
        return {"reply": "도장이나 서명 해 주시면 돼요.", "is_finished": False}

    out = list(roleplay_stream(SCENARIO, history, "네", FakeStream(said), generate))
    assert out[-1] == {"final": "도장이나 서명 해 주시면 돼요.", "is_finished": False, "replace": True}
    assert len(retry_calls) == 1


def test_roleplay_stream_empty_is_error():
    with pytest.raises(CoachFormatError):
        list(roleplay_stream(SCENARIO, [], "안녕하세요", FakeStream(ROLEPLAY_END_MARK)))
