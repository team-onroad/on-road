"""오늘 날짜(한국 시간). 테스트에서는 get_today 의존성을 덮어써서 날짜를 고정한다."""

from datetime import date, datetime
from zoneinfo import ZoneInfo

from app.config import get_settings


def today_kst() -> date:
    return datetime.now(ZoneInfo(get_settings().timezone)).date()


def get_today() -> date:
    """FastAPI 의존성. 테스트: app.dependency_overrides[get_today] = lambda: date(...)"""
    return today_kst()
