"""외부 AI 로 보내기 전 개인정보 가리기 (app/pii.py)"""

import pytest

from app.pii import ACCOUNT, ADDRESS, CARD, EMAIL, PHONE, RRN, mask, masked


@pytest.mark.parametrize(
    ("text", "label"),
    [
        ("제 번호는 010-1234-5678이에요", PHONE),
        ("01012345678로 연락 주세요", PHONE),
        ("010 1234 5678", PHONE),
        ("집 전화 02-123-4567", PHONE),
        ("주민번호 010203-3123456 맞아요", RRN),
        ("메일은 minsu.kim@example.com 입니다", EMAIL),
        ("카드 1234-5678-9012-3456", CARD),
        ("계좌 110-123-456789 로 받아요", ACCOUNT),
        ("계좌번호 3333012345678", ACCOUNT),
        ("인천 남동구 인주대로 123에 살아요", ADDRESS),
        ("세종대로 110 근처", ADDRESS),
        ("구월로 12-3", ADDRESS),
        ("만수동 123-45번지", ADDRESS),
        ("101동 1203호예요", ADDRESS),
    ],
)
def test_masks(text: str, label: str) -> None:
    out = mask(text)
    assert label in out
    assert not any(ch.isdigit() for ch in out.replace(label, "")) or label == ADDRESS


@pytest.mark.parametrize(
    "text",
    [
        "자립수당은 월 50만 원이에요",
        "보호종료 2개월 차예요",
        "정도로 3번 갔어요",
        "그걸로 2개 샀어요",
        "월세로 50만 원 내요",
        "2026년 3월 20일에 신청했어요",
        "19살이고 인천으로 이사했어요",
        "1388 청소년상담전화",
    ],
)
def test_keeps_ordinary_text(text: str) -> None:
    assert mask(text) == text


def test_masked_wrapper_masks_strings_and_history_only() -> None:
    seen = {}

    def fn(scenario: dict, history: list[dict], message: str) -> str:
        seen.update(scenario=scenario, history=history, message=message)
        return "ok"

    scenario = {"title": "은행", "phone": "010-1111-2222"}
    history = [{"role": "user", "content": "번호 010-9876-5432"}, {"role": "assistant", "content": "네"}]
    assert masked(fn)(scenario, history, "주소는 구월로 12-3") == "ok"
    assert seen["scenario"] == scenario  # 상황 · 프로필 dict 는 그대로
    assert seen["history"][0]["content"] == f"번호 {PHONE}"
    assert seen["history"][1]["content"] == "네"
    assert seen["message"] == f"주소는 {ADDRESS}"
    assert history[0]["content"] == "번호 010-9876-5432"  # 원본은 바뀌지 않는다


def test_masked_wrapper_keeps_generator() -> None:
    def gen(message: str, history: list[dict]):
        yield {"delta": message}

    assert list(masked(gen)("010-1234-5678", [])) == [{"delta": PHONE}]


def test_rag_question_is_masked() -> None:
    from app.services.policies import _call_rag_now

    got = {}

    def rag(question: str, keys: list[str], answer: bool = True) -> dict:
        got["q"] = question
        return {"status": "no_evidence", "answer": None, "evidences": []}

    _call_rag_now(rag, "제 번호 010-1234-5678인데 자립수당 받을 수 있어요?", ["P001"])
    assert got["q"] == f"제 번호 {PHONE}인데 자립수당 받을 수 있어요?"
