"""대화 속 프로필 변경 감지 (app/profile_signal.py). DB 없이 규칙만 본다."""

from datetime import date
from types import SimpleNamespace

import pytest

from app.profile_signal import detect

TODAY = date(2026, 10, 27)


def user(**kw):
    base = {"name": "김민지", "region": "서울", "phone": "01012345678", "status": "in_care", "d_date": None}
    base.update(kw)
    return SimpleNamespace(**base)


def changes(message, **kw):
    return {c.field: c.proposed for c in detect(message, user(**kw), TODAY)}


@pytest.mark.parametrize("message,expected", [
    ("저 이번에 부산으로 이사했어요", {"region": "부산"}),
    ("서울에서 경기도 수원으로 이사 왔어", {"region": "경기"}),
    ("대구광역시에 살게 됐어요", {"region": "대구"}),
    ("나 개명했는데 정서현으로 해줘", {"name": "정서현"}),
    ("이름 바꿨어 정서현이야", {"name": "정서현"}),
    ("저 개명했어요", {}),  # 새 이름이 없음
    ("정서현", {}),  # 바꿨다는 말이 없음
    ("부산 정책 알려줘", {}),  # 바뀌었다는 말이 없으면 후보 아님
    ("서울로 이사했어요", {}),  # 지금 값과 같음
    ("번호 010-9876-5432로 바꿨어요", {"phone": "01098765432"}),
    ("010 9876 5432", {}),  # 번호를 바꿨다는 말이 없음
    ("저 이번 달에 퇴소했어요", {"status": "left_care"}),
    ("곧 퇴소 예정이에요 2027년 2월 28일이요", {"status": "leaving_soon", "d_date": "2027-02-28"}),
])
def test_detect(message, expected):
    assert changes(message) == expected


def test_labels_for_card():
    c = detect("번호 01098765432로 바꿨어", user(), TODAY)[0]
    assert (c.label, c.current, c.proposed_label) == ("휴대폰 번호", "010-1234-5678", "010-9876-5432")
    s = detect("퇴소했어요", user(status="leaving_soon"), TODAY)[0]
    assert (s.current, s.proposed_label) == ("퇴소 예정", "퇴소함")
    assert detect("퇴소했어요", user(status="left_care"), TODAY) == []  # 이미 퇴소함
