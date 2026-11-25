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
    "age_until", "age_max", "region", "kind", "kind_title", "pick_reason", "alternatives",
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


def test_same_kind_keeps_bigger_one_with_alternatives(api, db_session):
    """같은 종류(자립정착금)는 받는 돈이 큰 하나만 남기고, 나머지는 펼치면 보이는 alternatives 로"""
    small = make_policy(db_session, name="자립정착금", support_amount="1,000만 원")
    big = make_policy(db_session, name="구로형 자립정착금", support_amount="1,500만 원")
    other = make_policy(db_session, name="청년 월세 지원", support_amount="월 20만 원")
    user = make_user(db_session)

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()
    ids = [i["policy_id"] for g in data["stages"] for i in g["items"]]
    assert big.id in ids and other.id in ids and small.id not in ids
    item = next(i for g in data["stages"] for i in g["items"] if i["policy_id"] == big.id)
    assert item["kind"] == "settlement"
    assert item["kind_title"] == "자립정착금"
    assert item["pick_reason"] == "같은 종류 2개 중 받는 돈이 가장 커요"
    assert [a["policy_id"] for a in item["alternatives"]] == [small.id]
    assert item["alternatives"][0]["amount_text"] == "1,000만 원"
    plain = next(i for g in data["stages"] for i in g["items"] if i["policy_id"] == other.id)
    assert plain["kind"] is None and plain["alternatives"] == []


def test_same_kind_prefers_my_region_when_same_amount(api, db_session):
    nation = make_policy(db_session, name="청년도전지원사업", region="전국")
    mine = make_policy(db_session, name="청년도전지원사업", region="인천", agency="인천광역시")
    user = make_user(db_session, region="인천")

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()
    items = [i for g in data["stages"] for i in g["items"]]
    assert [i["policy_id"] for i in items] == [mine.id]
    assert items[0]["pick_reason"] == "같은 종류 2개 중 내 지역 정책이에요"
    assert items[0]["alternatives"][0]["policy_id"] == nation.id


def test_age_until_is_day_before_next_birthday(api, db_session):
    p = make_policy(db_session, name="청년 지원", age_min=18, age_max=29, target_description="청년")
    child = make_policy(db_session, name="아동 지원", age_max=17, target_description="아동")
    user = make_user(db_session, birth_date=date(2005, 3, 4))

    items = {i["policy_id"]: i for g in api.get(f"/api/users/{user.id}/policy-roadmap").json()["stages"] for i in g["items"]}
    assert items[p.id]["age_until"] == "2035-03-03"
    assert items[p.id]["age_max"] == 29
    assert child.id not in items


def test_policy_needing_age_18_shows_at_leave_for_17(api, db_session):
    """17세가 18세 이후 퇴소하면, 만 18세 이상 정책은 '퇴소하면 바로 신청'에 퇴소일 나이로 들어간다"""
    adult = make_policy(db_session, name="만기퇴소 자립정착금", age_min=18, age_max=29, support_amount="200만 원",
                        target_description="만 18세 이후 퇴소한 청년")
    user = make_user(db_session, birth_date=date(2009, 3, 1), status="in_care", d_date=date(2027, 4, 30))  # 지금 17세

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    at_leave = _stage(data, "at_leave")
    assert _ids(at_leave) == [adult.id]
    assert at_leave["items"][0]["apply_from"] == "2027-04-30"
    assert at_leave["items"][0]["eligibility"] == "match"


def test_in_care_without_leave_date_groups_leaver_policies(api, db_session):
    leaver = make_policy(db_session, target_description="보호종료 5년 이내 자립준비청년")
    anytime = make_policy(db_session, target_description="청년")
    user = make_user(db_session, status="in_care", birth_date=date(2008, 1, 1))  # 퇴소일 없음

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    assert data["base_date"] is None
    at_leave = _stage(data, "at_leave")
    assert _ids(at_leave) == [leaver.id]
    assert at_leave["items"][0]["apply_from"] is None
    assert at_leave["items"][0]["apply_note"] == "퇴소하면 신청"
    assert _ids(_stage(data, "now")) == [anytime.id]


def test_in_care_child_policy_hidden_after_leaving(api, db_session):
    child = make_policy(db_session, name="가정위탁아동 지원", target_description="가정위탁 보호조치 결정 아동",
                        support_amount="월 10만 원")
    leaver = make_policy(db_session, name="가정위탁아동 자립정착금", target_description="보호종료 아동")
    left = make_user(db_session, status="left_care")
    staying = make_user(db_session, status="in_care", birth_date=date(2010, 1, 1))

    assert _ids(_stage(api.get(f"/api/users/{left.id}/policy-roadmap").json(), "now")) == [leaver.id]
    assert child.id in _ids(_stage(api.get(f"/api/users/{staying.id}/policy-roadmap").json(), "now"))


def test_only_one_allowance_listed(api, db_session):
    p001 = make_policy(db_session, policy_key="P001", name="자립준비청년 자립수당 지급", support_amount="월 50만 원")
    make_policy(db_session, name="청소년복지시설 퇴소청소년 자립지원수당", support_amount="월 50만 원")
    make_policy(db_session, name="보호종료아동 자립수당", support_amount="월 50만 원")
    user = make_user(db_session)

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    assert _ids(_stage(data, "now")) == [p001.id]
    assert data["summary"]["monthly_won"] == 500_000


def test_child_household_policy_not_for_in_care(api, db_session):
    make_policy(db_session, name="소년소녀가정 지원", age_max=17, support_amount="월 20만 원",
                target_description="만 18세 미만 아동이 가정을 이끄는 세대")
    user = make_user(db_session, status="in_care", birth_date=date(2010, 1, 1))
    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()
    assert data["summary"]["policy_count"] == 0


def test_recommended_policies_order(api, db_session):
    now_cash = make_policy(db_session, name="청년 월세 지원", support_amount="월 20만 원", target_description="청년")
    pre = make_policy(db_session, policy_key="P001", name="자립준비청년 자립수당 지급", support_amount="월 50만 원",
                      target_description="보호종료 자립준비청년", apply_method="보호종료 30일 전부터 사전 신청")
    service = make_policy(db_session, name="자립지원 전담기관 운영", support_amount=None, target_description="보호종료 아동")
    check = make_policy(db_session, name="서초구 생활보조수당", agency="서울특별시 서초구", region="서울",
                        support_amount="월 30만 원", target_description="청년")
    user = make_user(db_session, region="서울", status="leaving_soon", d_date=date(2026, 12, 31))

    res = api.get(f"/api/users/{user.id}/recommended-policies")

    assert res.status_code == 200, res.text
    items = res.json()
    assert [i["policy_id"] for i in items] == [pre.id, now_cash.id, service.id, check.id]
    first = items[0]
    assert first["when_text"] == "12월 1일부터 미리 신청"
    assert (first["amount_unit"], first["amount_value"]) == ("매월", "50만 원")
    assert items[2]["amount_unit"] is None and items[2]["amount_value"] is None
    assert items[3]["eligibility"] == "needs_check"


def test_recommended_policy_has_same_kind_reason(api, db_session):
    make_policy(db_session, name="자립정착금", support_amount="1,000만 원", target_description="청년")
    big = make_policy(db_session, name="구로형 자립정착금", support_amount="1,500만 원", target_description="청년")
    user = make_user(db_session)
    recs = api.get(f"/api/users/{user.id}/recommended-policies").json()
    item = next(r for r in recs if r["policy_id"] == big.id)
    assert item["kind_title"] == "자립정착금"
    assert item["same_kind_count"] == 1
    assert item["pick_reason"] == "같은 종류 2개 중 받는 돈이 가장 커요"


def test_policy_detail_same_kind(api, db_session):
    small = make_policy(db_session, name="자립정착금", support_amount="1,000만 원", target_description="청년")
    big = make_policy(db_session, name="구로형 자립정착금", support_amount="1,500만 원", target_description="청년")
    lone = make_policy(db_session, name="청년 월세 지원", support_amount="월 20만 원", target_description="청년")
    user = make_user(db_session)

    best = api.get(f"/api/policies/{big.id}", params={"user_id": str(user.id)}).json()["same_kind"]
    assert best["is_best"] is True and best["kind_title"] == "자립정착금"
    assert [o["policy_id"] for o in best["others"]] == [small.id]

    worse = api.get(f"/api/policies/{small.id}", params={"user_id": str(user.id)}).json()["same_kind"]
    assert worse["is_best"] is False
    assert worse["best"]["policy_id"] == big.id
    assert worse["reason"] == "같은 종류인 '구로형 자립정착금'이 내 조건에서 더 유리해요"

    assert api.get(f"/api/policies/{lone.id}", params={"user_id": str(user.id)}).json()["same_kind"] is None
    assert api.get(f"/api/policies/{big.id}").json()["same_kind"] is None
