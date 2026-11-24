"""GET /users/{user_id}/policy-roadmap (api.md 3.32)."""

import uuid
from datetime import date

from app.services.roadmap import pre_apply_days
from tests.factories import make_policy, make_user

TODAY = date(2026, 10, 4)
KEYS = {"base_date", "d_day", "summary", "stages"}
ITEM_KEYS = {
    "policy_id", "policy_key", "name", "agency", "category", "eligibility", "amount_won", "amount_cycle",
    "amount_text", "benefit", "period_years", "max_total_won", "apply_from", "until", "apply_note",
}


def _stage(data, name):
    return next((g for g in data["stages"] if g["stage"] == name), None)


def _ids(group):
    return [i["policy_id"] for i in group["items"]] if group else []


def test_roadmap_before_leave_user(api, db_session):
    pre = make_policy(
        db_session, name="자립수당", target_description="보호종료 5년 이내 자립준비청년",
        apply_method="보호종료 30일 전부터 사전 신청", support_amount="월 50만 원", support_period="보호종료 후 5년",
    )
    leaver = make_policy(
        db_session, target_description="보호종료아동", apply_method="방문", support_amount="1,000만 원 1회",
        support_period=None,
    )
    anytime = make_policy(db_session, target_description="청년", support_amount="월 20만 원", support_period="1년")
    user = make_user(db_session, status="leaving_soon", d_date=date(2026, 12, 31))

    res = api.get(f"/api/users/{user.id}/policy-roadmap")

    assert res.status_code == 200, res.text
    data = res.json()
    assert set(data) == KEYS
    assert data["base_date"] == "2026-12-31"
    assert data["d_day"] == 88
    assert [g["stage"] for g in data["stages"]] == ["before_leave", "at_leave", "now"]

    before = _stage(data, "before_leave")
    assert _ids(before) == [pre.id]
    item = before["items"][0]
    assert set(item) == ITEM_KEYS
    assert item["apply_from"] == "2026-12-01"
    assert item["until"] == "2031-12-31"
    assert item["amount_text"] == "월 50만 원"
    assert item["max_total_won"] == 500_000 * 12 * 5
    assert item["apply_note"] == "퇴소 30일 전부터 미리 신청"

    assert _ids(_stage(data, "at_leave")) == [leaver.id]
    assert _stage(data, "at_leave")["items"][0]["apply_from"] == "2026-12-31"
    assert anytime.id in _ids(_stage(data, "now"))

    s = data["summary"]
    assert s["monthly_won"] == 700_000
    assert s["once_won"] == 10_000_000
    assert s["policy_count"] == 3


def test_roadmap_left_care_all_now(api, db_session):
    make_policy(db_session, target_description="보호종료 자립준비청년", apply_method="보호종료 30일 전부터 사전 신청")
    user = make_user(db_session, status="left_care", d_date=date(2027, 3, 1))  # 퇴소 후 d_date 는 목표일

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    assert data["base_date"] is None
    assert data["d_day"] is None
    assert [g["stage"] for g in data["stages"]] == ["now"]
    assert data["stages"][0]["items"][0]["apply_from"] is None
    assert data["stages"][0]["items"][0]["until"] is None


def test_roadmap_past_leave_date_treated_as_left(api, db_session):
    make_policy(db_session, target_description="보호종료아동")
    user = make_user(db_session, status="leaving_soon", d_date=date(2026, 9, 1))

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    assert data["base_date"] is None
    assert [g["stage"] for g in data["stages"]] == ["now"]


def test_roadmap_excludes_ineligible_and_sorts_by_amount(api, db_session):
    small = make_policy(db_session, support_amount="월 10만 원")
    big = make_policy(db_session, support_amount="월 40만 원")
    make_policy(db_session, age_min=30, age_max=34)  # 21세 사용자는 제외
    make_policy(db_session, region="부산")
    user = make_user(db_session)

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    assert _ids(_stage(data, "now")) == [big.id, small.id]
    assert data["summary"]["policy_count"] == 2


def test_roadmap_unknown_user(api):
    res = api.get(f"/api/users/{uuid.uuid4()}/policy-roadmap")
    assert res.status_code == 404
    assert res.json()["error"]["code"] == "USER_NOT_FOUND"


def test_pre_apply_days_patterns():
    class P:
        def __init__(self, a, t=""):
            self.apply_method, self.target_description = a, t

    assert pre_apply_days(P("보호종료 30일 전부터 시·군에 사전 신청")) == 30
    assert pre_apply_days(P("", "보호 종료 전 30일 이내 신청")) == 30
    assert pre_apply_days(P("시설종사자가 종료 전 30일 이내 사전신청")) == 30
    assert pre_apply_days(P("방문 신청")) is None


def test_dummy_policies_hidden_on_real_server(api, db_session, monkeypatch):
    from app.config import get_settings

    real = make_policy(db_session, support_amount="월 20만 원")
    make_policy(db_session, policy_key="D901", name="(더미) 지원금", support_amount="월 50만 원")
    user = make_user(db_session)

    monkeypatch.setattr(get_settings(), "rag_mode", "real")
    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()
    assert _ids(_stage(data, "now")) == [real.id]
    assert data["summary"]["monthly_won"] == 200_000


def test_same_kind_allowance_counted_once(api, db_session):
    make_policy(db_session, name="자립준비청년 자립수당 지급", support_amount="월 50만 원")
    make_policy(db_session, name="퇴소청소년 자립지원수당", support_amount="월 50만 원")
    make_policy(db_session, name="청년 월세 지원", support_amount="월 20만 원")
    make_policy(db_session, name="자립정착금", support_amount="1,000만 원")
    make_policy(db_session, name="구로형 자립정착금", support_amount="1,500만 원")
    user = make_user(db_session)

    s = api.get(f"/api/users/{user.id}/policy-roadmap").json()["summary"]
    assert s["monthly_won"] == 700_000
    assert s["once_won"] == 15_000_000
