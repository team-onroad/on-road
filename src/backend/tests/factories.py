"""테스트용 데이터를 DB 에 직접 넣는 도우미."""

from datetime import date
from itertools import count

from sqlalchemy.orm import Session

from app.rules import calc_stage, calc_age
from db.models import Policy, User

_seq = count(1)


def make_policy(session: Session, **overrides) -> Policy:
    n = next(_seq)
    values = {
        "policy_key": f"T{n:03d}",
        "name": f"테스트 정책 {n}",
        "agency": "테스트 기관",
        "contact": "000-0000",
        "category": "living_cost",
        "region": "전국",
        "age_min": None,
        "age_max": None,
        "target_description": "대상",
        "support_content": "내용",
        "support_amount": "월 10만 원",
        "support_period": "1년",
        "apply_method": "방법",
        "required_docs": ["신분증"],
        "apply_url": "https://example.com/apply",
        "source_url": "https://example.com",
        "original_text": "원문",
        "easy_text": "쉬운 말",
        "easy_text_verified": True,
        "source_type": "manual",
        "checked_at": date(2026, 9, 28),
        "is_active": True,
    }
    values.update(overrides)
    policy = Policy(**values)
    session.add(policy)
    session.commit()
    return policy


def make_user(session: Session, today: date = date(2026, 10, 4), **overrides) -> User:
    values = {
        "name": "테스트",
        "birth_date": date(2005, 3, 15),  # 2026-10-04 기준 만 21세
        "region": "서울",
        "status": "left_care",
    }
    values.update(overrides)
    user = User(**values)
    user.stage = calc_stage(calc_age(user.birth_date, today), user.status)
    session.add(user)
    session.commit()
    return user
