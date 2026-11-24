"""app/money.py: 정책 금액 · 기간 읽기 (규칙)."""

import pytest

from app.money import parse_amount, parse_period_years, won_text


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("월 50만 원", (500_000, "monthly", "월 50만 원")),
        ("매월 50만원", (500_000, "monthly", "월 50만 원")),
        ("월 최대 20만원", (200_000, "monthly", "월 최대 20만 원")),
        ("300천원", (300_000, None, "30만 원")),
        ("10,000,000원", (10_000_000, None, "1,000만 원")),
        ("1500만원", (15_000_000, None, "1,500만 원")),
        ("연 500만원", (5_000_000, "yearly", "연 500만 원")),
        ("1회 1,000만원", (10_000_000, "once", "1,000만 원")),
        ("1인당 1,000만원", (10_000_000, "once", "1,000만 원")),
        ("5,500원", (5_500, None, "5,500원")),
    ],
)
def test_parse_amount_units(text, expected):
    assert parse_amount(text) == expected


@pytest.mark.parametrize("text", [None, "", "원문에서 확인", "659,900천원", "2억 원"])
def test_parse_amount_skips_unknown_or_budget(text):
    # 금액이 없거나, 1억 원 이상(1인 지원금이 아니라 사업 예산일 가능성)은 읽지 않는다
    assert parse_amount(text) == (None, None, None)


def test_won_text():
    assert won_text(500_000) == "50만 원"
    assert won_text(30_000_000) == "3,000만 원"
    assert won_text(5_500) == "5,500원"


@pytest.mark.parametrize(
    ("text", "years"),
    [
        ("일반 보호종료자는 보호종료 후 5년 이내", 5),
        ("퇴소 후 3년", 3),
        ("18세가 된 때부터 5년 이내", 5),
        ("1년", None),
        (None, None),
    ],
)
def test_parse_period_years(text, years):
    assert parse_period_years(text) == years


def test_normalize_amounts_in_sentence():
    from app.money import normalize_amounts

    assert normalize_amounts("월 300천원을 받아요") == "월 30만 원을 받아요"
    assert normalize_amounts("10,000,000원 한 번") == "1,000만 원 한 번"
    assert normalize_amounts("월 50만 원") == "월 50만 원"
    assert normalize_amounts("예산 659,900천원") == "예산 659,900천원"  # 1억 이상은 그대로
    assert normalize_amounts(None) is None


def test_paid_and_daily_amounts_are_not_benefits():
    from app.money import parse_amount

    assert parse_amount("임대료 : 시중 시세의 30%, 임대보증금 100만원") == (None, None, None)
    assert parse_amount("2,400원", "교통비 지원(1일/2.4천원)") == (None, None, None)
    assert parse_amount("30만 원", "생활보조수당 300천원(인/월) 지원") == (300_000, "monthly", "월 30만 원")
    assert parse_amount("본인부담 10만 원, 지원 50만 원")[0] == 500_000
