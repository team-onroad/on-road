"""정책 금액 · 기간을 숫자로 읽는다 (규칙). 원문(support_amount)은 그대로 두고, 응답에서만 같은 단위로 맞춘다.

DB 의 support_amount 는 수집처마다 "300천원", "10,000,000원", "월 50만 원", "1500만원"처럼 단위 · 쉼표 · 띄어쓰기가 다르다.
앱이 화면마다 따로 고치지 않도록 서버가 한 번 읽어
- amount_won: 원 단위 숫자 (한 사람이 한 번 · 한 달 · 한 해에 받는 금액)
- amount_cycle: monthly · yearly · once
- amount_text: "월 50만 원", "1,000만 원" 같은 표시용 문장
- period_years: 지원 기간(년). "보호종료 후 5년" 같은 표현만 읽는다
을 함께 준다. 읽지 못하면 모두 None (앱은 원문 문장을 보여 준다).
1인 금액으로 보기 어려운 값(1억 원 이상, 사업 예산 등)은 읽지 않는다.
"""

import re

_NUM = r"(\d[\d,]*(?:\.\d+)?)"
_AMOUNT = re.compile(_NUM + r"\s*(억|천만|백만|만|천)?\s*원")
_MONTHLY = re.compile(r"(월|매월|매달|월간)\s*(최대|평균)?\s*$")
_YEARLY = re.compile(r"(연|연간|매년|1년에)\s*(최대)?\s*$")
_ONCE = re.compile(r"(1회|일시금|한\s*번|최초|1인당|1인)\s*(최대)?\s*$")
_PERIOD = re.compile(r"(보호\s*종료|퇴소|18세가\s*된\s*때)\s*(후|일로부터|부터|된\s*때부터)?\s*(\d{1,2})\s*년")
_UNIT = {"억": 100_000_000, "천만": 10_000_000, "백만": 1_000_000, "만": 10_000, "천": 1_000, None: 1, "": 1}
MAX_PER_PERSON = 100_000_000  # 1억 원 이상은 1인 지원금이 아니라 사업 예산일 가능성이 커서 읽지 않는다


def won_text(won: int) -> str:
    """앱 전체 표기: 1만 원 단위면 "50만 원", 아니면 "5,000원" """
    if won % 10_000:
        return f"{won:,}원"
    return f"{won // 10_000:,}만 원"


def parse_amount(text: str | None) -> tuple[int | None, str | None, str | None]:
    """(amount_won, amount_cycle, amount_text). 첫 금액만 읽는다"""
    if not text:
        return None, None, None
    m = _AMOUNT.search(text)
    if not m:
        return None, None, None
    try:
        won = int(float(m.group(1).replace(",", "")) * _UNIT[m.group(2)])
    except ValueError:
        return None, None, None
    if won <= 0 or won >= MAX_PER_PERSON:
        return None, None, None
    before = text[: m.start()]
    upto = "최대 " if re.search(r"최대\s*$", before) else ""
    if _MONTHLY.search(before):
        cycle, shown = "monthly", f"월 {upto}{won_text(won)}"
    elif _YEARLY.search(before):
        cycle, shown = "yearly", f"연 {upto}{won_text(won)}"
    elif _ONCE.search(before):
        cycle, shown = "once", f"{upto}{won_text(won)}"
    else:
        cycle, shown = None, f"{upto}{won_text(won)}"
    return won, cycle, shown


def parse_period_years(text: str | None) -> int | None:
    """"보호종료 후 5년 이내" → 5. 다른 표현은 읽지 않는다"""
    if not text:
        return None
    m = _PERIOD.search(text)
    return int(m.group(3)) if m else None
