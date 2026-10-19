"""AI 상담·롤플레잉·문장 다듬기 (coach.llm). OpenAI 대신 가짜 LLM 을 쓴다.

실행 (src 폴더에서): backend\\.venv\\Scripts\\python -m pytest coach/tests
"""

import pytest

from coach.llm import (
    HISTORY_TURNS, ROLEPLAY_END, ROLEPLAY_EXTRA_TURNS, SAFE_COUNSEL, CoachFormatError, classify_intent, counsel_reply,
    polish_sentences, polite, roleplay_reply, safe_counsel,
)
from coach.prompts import LEVEL_GUIDE, STAGE_STYLE


class FakeLLM:
    def __init__(self, output):
        self.output = output
        self.calls: list[list[dict]] = []

    def __call__(self, messages):
        self.calls.append(list(messages))
        return self.output


def turns(n):
    return [{"role": "user" if i % 2 else "assistant", "content": f"말 {i}"} for i in range(n)]


# --- 의도 분류 ---


@pytest.mark.parametrize("out,expected", [
    ({"intent": "policy"}, "policy"), ({"intent": "counsel"}, "counsel"), ({"intent": "모름"}, "counsel"), ([], "counsel"),
])
def test_classify_intent(out, expected):
    llm = FakeLLM(out)
    assert classify_intent("월세 지원 받을 수 있어?", turns(9), llm) == expected
    messages = llm.calls[0]
    assert messages[-1] == {"role": "user", "content": "월세 지원 받을 수 있어?"}
    assert len(messages) == 1 + 4 + 1  # 시스템 + 앞 대화 4개 + 질문


# --- 상담 ---


@pytest.mark.parametrize("stage", ["child", "teen", "youth"])
def test_counsel_uses_stage_style(stage):
    llm = FakeLLM({"reply": "많이 속상했겠어요. 친구에게 먼저 인사해 볼까요?"})
    reply = counsel_reply("친구랑 다퉜어", [], {"stage": stage, "status": "in_care", "d_day": None}, llm)
    assert reply.startswith("많이 속상했겠어요")
    assert STAGE_STYLE[stage] in llm.calls[0][0]["content"]


def test_counsel_context_and_history_limit():
    llm = FakeLLM({"reply": "준비를 하나씩 해 봐요."})
    counsel_reply("퇴소가 걱정돼", turns(HISTORY_TURNS + 5), {"stage": "youth", "status": "leaving_soon", "d_day": 30}, llm)
    system, *rest = llm.calls[0]
    assert "퇴소 예정" in system["content"] and "30일" in system["content"]
    assert len(rest) == HISTORY_TURNS + 1  # 오래된 대화는 버린다
    # 이름·전화번호 같은 개인정보는 프로필로 받지 않는다
    assert "name" not in system["content"]


def test_counsel_unknown_stage_falls_back_to_youth():
    llm = FakeLLM({"reply": "괜찮아요."})
    counsel_reply("안녕", [], {"stage": None}, llm)
    assert STAGE_STYLE["youth"] in llm.calls[0][0]["content"]


@pytest.mark.parametrize("bad", ["자립수당은 매달 50만 원이에요.", "회원님은 신청 가능합니다.", "자격이 됩니다."])
def test_counsel_blocks_money_and_eligibility(bad):
    assert counsel_reply("돈 받을 수 있어?", [], {"stage": "youth"}, FakeLLM({"reply": bad})) == SAFE_COUNSEL


def test_safe_counsel_keeps_normal_reply():
    assert safe_counsel("오늘은 푹 쉬어 봐요.") == "오늘은 푹 쉬어 봐요."


def test_polite_fixes_casual_endings_only_at_sentence_end():
    assert polite("화해하고 싶구나. 먼저 말해 보는 건 어때? 할 수 있어") == "화해하고 싶군요. 먼저 말해 보는 건 어때요? 할 수 있어요"
    assert polite("구나라는 말과 있어요") == "구나라는 말과 있어요"


@pytest.mark.parametrize("out", [{}, {"reply": "  "}, None, {"reply": 3}])
def test_counsel_empty_output_is_error(out):
    with pytest.raises(CoachFormatError):
        counsel_reply("안녕", [], {"stage": "teen"}, FakeLLM(out))


# --- 롤플레잉 ---

SCENARIO = {
    "scenario_id": "bank", "title": "은행 계좌 개설", "partner": "은행 창구 직원", "setting": "통장을 만들러 왔어요.",
    "goal": "계좌 만들기", "level": "HARD", "lines": ["신분증 있으세요?", "도장이나 서명 해 주세요."],
}


def test_roleplay_prompt_has_role_and_lines():
    llm = FakeLLM({"reply": "신분증 있으세요?", "is_finished": False})
    out = roleplay_reply(SCENARIO, [{"role": "assistant", "content": "어떤 업무 도와드릴까요?"}], "통장 만들래요", llm)
    assert out == {"reply": "신분증 있으세요?", "is_finished": False}
    system = llm.calls[0][0]["content"]
    assert "은행 창구 직원" in system and "1. 신분증 있으세요?" in system and LEVEL_GUIDE["HARD"] in system


def test_roleplay_finishes_when_llm_says_or_turns_run_out():
    llm = FakeLLM({"reply": "다 됐어요. 안녕히 가세요.", "is_finished": True})
    assert roleplay_reply(SCENARIO, turns(2), "감사합니다", llm)["is_finished"] is True
    # 마지막 허용 차례면 LLM 이 안 끝내도 끝낸다
    last = [{"role": "assistant", "content": "x"}] * (len(SCENARIO["lines"]) + ROLEPLAY_EXTRA_TURNS - 1)
    assert roleplay_reply(SCENARIO, last, "네", FakeLLM({"reply": "네.", "is_finished": False}))["is_finished"] is True
    # 이미 다 말했으면 LLM 을 부르지 않고 마무리
    done = FakeLLM({"reply": "x", "is_finished": False})
    over = [{"role": "assistant", "content": "x"}] * (len(SCENARIO["lines"]) + ROLEPLAY_EXTRA_TURNS)
    assert roleplay_reply(SCENARIO, over, "네", done) == {"reply": ROLEPLAY_END, "is_finished": True}
    assert done.calls == []


def test_roleplay_empty_reply_is_error():
    with pytest.raises(CoachFormatError):
        roleplay_reply(SCENARIO, [], "안녕하세요", FakeLLM({"is_finished": False}))


# --- 문장 다듬기 ---


def test_polish_sentences():
    llm = FakeLLM({"sentences": ["저는 성실합니다.", "열심히 하겠습니다."]})
    assert polish_sentences("resume", ["난 성실함", "열심히 할게"], llm) == ["저는 성실합니다.", "열심히 하겠습니다."]


@pytest.mark.parametrize("out", [{"sentences": ["하나만"]}, {"sentences": ["", "둘"]}, {}, None])
def test_polish_keeps_original_when_output_is_wrong(out):
    assert polish_sentences("resume", ["가", "나"], FakeLLM(out)) == ["가", "나"]


def test_polish_empty_input_skips_llm():
    llm = FakeLLM({"sentences": []})
    assert polish_sentences("resume", [], llm) == [] and llm.calls == []
