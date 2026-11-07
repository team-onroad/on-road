"""대화 속 프로필 변경 감지 (app/profile_signal.py). DB 없이 규칙만 본다."""

from datetime import date
from types import SimpleNamespace

import pytest

from app.profile_signal import detect

TODAY = date(2026, 10, 27)


def user(**kw):
    base = {"name": "김민지", "region": "서울", "phone": "01012345678", "status": "in_care", "d_date": None,
            "work": None}
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


@pytest.mark.parametrize("message,expected", [
    ("저 이번에 취업했어요", "working"),
    ("편의점 알바 시작했어", "working"),
    ("다음 달부터 출근하게 됐어요", "working"),
    ("대학교 입학했어요", "student"),
    ("알바 그만두고 학교 다니고 있어요", "student"),
    ("회사 그만뒀어요", "job_seeking"),
    ("요즘 일자리 찾고 있어요", "job_seeking"),
    ("당분간 쉬고 있어요", "resting"),
    ("취업 지원 정책 알려줘", None),
    ("학교 근처 집 알아봐", None),
])
def test_detect_work(message, expected):
    assert changes(message).get("work") == expected


def test_work_change_without_saved_work():
    c = next(c for c in detect("취업했어요", user(), TODAY) if c.field == "work")
    assert c.label == "지금 하는 일" and c.current is None and c.proposed_label == "일해요"


def test_work_change_shows_saved_work():
    c = next(c for c in detect("취업했어요", user(work="job_seeking"), TODAY) if c.field == "work")
    assert (c.current, c.proposed) == ("일 찾는 중", "working")


def test_work_change_same_value_still_shown():
    # 카드 표시 조건은 기존 그대로 (같은 값이어도 띄움)
    c = next(c for c in detect("취업했어요", user(work="working"), TODAY) if c.field == "work")
    assert (c.current, c.proposed) == ("일해요", "working")
