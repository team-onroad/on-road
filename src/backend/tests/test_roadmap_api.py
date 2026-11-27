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
    "receiving", "started_on", "education",
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


def _receive(api, user, policy, started_on=None):
    body = {"policy_id": policy.id, "status": "receiving"}
    if started_on:
        body["started_on"] = started_on
    assert api.post(f"/api/users/{user.id}/saved-policies", json=body).status_code in (200, 201)


def test_receiving_policy_marked_and_split_in_summary(api, db_session):
    """받는 중으로 저장한 정책: '지금 받고 있어요', 지원 끝나는 날은 받기 시작한 날부터. 요약은 받는 돈과 더 받을 돈을 나눈다"""
    got = make_policy(db_session, name="자립준비청년 자립수당 지급", support_amount="월 50만 원",
                      support_period="보호종료 후 5년")
    extra = make_policy(db_session, name="서초구 생활보조수당", support_amount="월 30만 원")
    once = make_policy(db_session, name="자립정착금", support_amount="1,000만 원", support_period=None)
    user = make_user(db_session)
    _receive(api, user, got, "2025-07-01")

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    now = _stage(data, "now")
    assert _ids(now)[-1] == got.id  # 받는 중은 새로 신청할 것들 뒤로
    item = now["items"][-1]
    assert item["receiving"] is True
    assert item["started_on"] == "2025-07-01"
    assert item["apply_note"] == "지금 받고 있어요"
    assert item["until"] == "2030-07-01"
    assert all(i["receiving"] is False for i in now["items"] if i["policy_id"] in (extra.id, once.id))
    s = data["summary"]
    assert s["monthly_won"] == 800_000
    assert s["receiving_count"] == 1
    assert s["receiving_monthly_won"] == 500_000
    assert s["new_monthly_won"] == 300_000
    assert s["new_once_won"] == 10_000_000


def test_receiving_kept_in_same_kind(api, db_session):
    """같은 종류에서는 받고 있는 것을 남긴다 (돈이 더 큰 것이 있어도)"""
    small = make_policy(db_session, name="자립정착금", support_amount="1,000만 원")
    big = make_policy(db_session, name="구로형 자립정착금", support_amount="1,500만 원")
    user = make_user(db_session)
    _receive(api, user, small)

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    assert _ids(_stage(data, "now")) == [small.id]
    item = _stage(data, "now")["items"][0]
    assert item["pick_reason"] == "지금 받고 있어서 같은 종류는 따로 신청하지 않아도 돼요"
    assert [a["policy_id"] for a in item["alternatives"]] == [big.id]
    assert data["summary"]["new_once_won"] == 0


def test_receiving_moves_to_now_for_leaving_user(api, db_session):
    pre = make_policy(db_session, name="자립수당", target_description="보호종료 자립준비청년",
                      apply_method="보호종료 30일 전부터 사전 신청", support_amount="월 50만 원")
    user = make_user(db_session, status="leaving_soon", d_date=date(2026, 12, 31))
    _receive(api, user, pre)

    data = api.get(f"/api/users/{user.id}/policy-roadmap").json()

    assert [g["stage"] for g in data["stages"]] == ["now"]
    assert data["stages"][0]["items"][0]["apply_from"] is None


def test_saved_only_is_not_receiving(api, db_session):
    p = make_policy(db_session)
    user = make_user(db_session)
    api.post(f"/api/users/{user.id}/saved-policies", json={"policy_id": p.id})
    item = api.get(f"/api/users/{user.id}/policy-roadmap").json()["stages"][0]["items"][0]
    assert item["receiving"] is False and item["started_on"] is None


def test_recommended_skips_receiving(api, db_session):
    got = make_policy(db_session, name="자립준비청년 자립수당 지급", support_amount="월 50만 원")
    other = make_policy(db_session, name="청년 월세 지원", support_amount="월 20만 원")
    user = make_user(db_session)
    _receive(api, user, got)

    ids = [r["policy_id"] for r in api.get(f"/api/users/{user.id}/recommended-policies").json()]
    assert ids == [other.id]


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
    # 퇴소가 60일 넘게 남았으면 '퇴소하면 신청'(service)은 홈에 넣지 않는다 (로드맵에서만)
    assert [i["policy_id"] for i in items] == [pre.id, now_cash.id, check.id]
    first = items[0]
    assert first["when_text"] == "12월 1일부터 미리 신청"
    assert (first["amount_unit"], first["amount_value"]) == ("매월", "50만 원")
    assert items[2]["eligibility"] == "needs_check"


def test_recommend_after_leave_policies_only_when_leaving_soon(api, db_session):
    leaver = make_policy(db_session, name="자립지원 전담기관 운영", support_amount=None, target_description="보호종료 아동")
    far = make_user(db_session, status="leaving_soon", d_date=date(2027, 3, 1))  # 오늘(10. 4)부터 148일
    near = make_user(db_session, status="leaving_soon", d_date=date(2026, 11, 20))  # 47일
    in_care = make_user(db_session, status="in_care", birth_date=date(2008, 1, 1))  # 퇴소일 없음

    def ids(u):
        return [i["policy_id"] for i in api.get(f"/api/users/{u.id}/recommended-policies").json()]

    assert leaver.id not in ids(far)
    assert leaver.id in ids(near)
    assert leaver.id not in ids(in_care)


def test_eligibility_leave_part(api, db_session):
    """보호가 끝나야 받는 정책: 아직 보호 중이면 after_leave, 퇴소했으면 now, 상관없는 정책은 null"""
    leaver = make_policy(db_session, name="자립정착금", target_description="보호종료 5년 이내 자립준비청년")
    plain = make_policy(db_session, name="청년 월세 지원", target_description="청년")
    child = make_policy(db_session, name="가정위탁아동 지원", target_description="가정위탁 보호조치 결정 아동")
    staying = make_user(db_session, status="leaving_soon", d_date=date(2027, 2, 28))
    left = make_user(db_session, status="left_care")

    def leave(p, u):
        return api.get(f"/api/policies/{p.id}", params={"user_id": str(u.id)}).json()["eligibility"]["leave"]

    assert leave(leaver, staying) == "after_leave"
    assert leave(leaver, left) == "now"
    assert leave(plain, staying) is None
    assert leave(child, staying) is None


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


def test_same_kind_group_lists_other_regions(api, db_session):
    """같은 종류 전체: 내 지역 것을 고르고, 다른 지역 것도 금액 큰 순으로 (금액 모르면 뒤)"""
    mine = make_policy(db_session, name="인천 자립정착금 지원", region="인천", agency="인천광역시", support_amount="1,000만 원")
    big = make_policy(db_session, name="대전 자립정착금 지원", region="대전", agency="대전광역시", support_amount="1,500만 원")
    unknown = make_policy(db_session, name="대구 자립정착금", region="대구", agency="대구광역시", support_amount=None)
    other = make_policy(db_session, name="청년 월세 지원", support_amount="월 20만 원")
    user = make_user(db_session, region="인천")

    res = api.get(f"/api/users/{user.id}/same-kind/{mine.id}")
    assert res.status_code == 200, res.text
    g = res.json()
    assert (g["kind"], g["kind_title"], g["total"]) == ("settlement", "자립정착금", 3)
    assert g["rule"] == "지역마다 하나씩 있어서 내 지역 것을 받아요"
    assert g["picked"]["policy_id"] == mine.id
    assert g["picked"]["reason"] == "내 조건에 맞는 건 이것 하나예요"
    assert [o["policy_id"] for o in g["others"]] == [big.id, unknown.id]
    assert g["others"][0]["amount_text"] == "1,500만 원" and g["others"][0]["mine"] is False
    assert g["others"][1]["amount_text"] is None

    # 다른 지역 정책으로 열어도 같은 묶음
    assert api.get(f"/api/users/{user.id}/same-kind/{big.id}").json()["picked"]["policy_id"] == mine.id
    # 묶이지 않는 정책
    assert api.get(f"/api/users/{user.id}/same-kind/{other.id}").status_code == 404



def test_care_type_splits_allowances(api, db_session):
    """보호받던 곳에 따라 갈리는 제도: 아동복지시설 출신은 청소년쉼터 퇴소자 수당이 빠지고, 쉼터 출신은 자립수당이 빠진다.
    모르면(null) 둘 다 보인다"""
    child = make_policy(db_session, name="자립준비청년 자립수당", target_description="아동복지시설 · 가정위탁 보호종료 5년 이내")
    youth = make_policy(db_session, name="청소년복지시설 퇴소청소년 자립지원수당", target_description="아동복지시설 보호종료")
    both = make_policy(db_session, name="보호종료아동 및 쉼터퇴소 청소년 임대주택", target_description="보호종료아동, 청소년쉼터 퇴소자")
    plain = make_policy(db_session, name="청년 월세 지원", target_description="청년")
    user = make_user(db_session, status="left_care")

    def ids():
        return {r["policy_id"] for r in api.get(f"/api/policies?user_id={user.id}").json()["results"]}

    assert {child.id, youth.id, both.id, plain.id} <= ids()
    api.patch(f"/api/users/{user.id}", json={"care_type": "child_facility"})
    assert child.id in ids() and youth.id not in ids() and both.id in ids() and plain.id in ids()
    api.patch(f"/api/users/{user.id}", json={"care_type": "youth_facility"})
    assert child.id not in ids() and youth.id in ids() and both.id in ids()
    detail = api.get(f"/api/policies/{child.id}", params={"user_id": str(user.id)}).json()
    assert detail["eligibility"]["care"] == "excluded"
    api.patch(f"/api/users/{user.id}", json={"care_type": None})
    assert api.get(f"/api/users/{user.id}").json()["care_type"] is None
    assert {child.id, youth.id} <= ids()


def test_extended_care_policies_are_now_for_18_plus_in_care(api, db_session):
    """보호연장 중(18세 이상, 아직 보호 중): '퇴소일을 연장한 청년도 포함' 정책은 지금 신청, 그 밖의 퇴소 정책은 퇴소하면"""
    ext = make_policy(db_session, name="청년도전지원사업", target_description="자립준비청년: 퇴소 5년 이내, 퇴소일을 연장한 만 18~34세 청년도 포함")
    leaver = make_policy(db_session, name="자립정착금", target_description="만 18세 이후 보호종료 아동")
    adult = make_user(db_session, status="in_care", birth_date=date(2006, 1, 1))  # 20세, 보호연장 중
    teen = make_user(db_session, status="in_care", birth_date=date(2010, 1, 1))  # 16세

    def stage_of(u, p):
        return next(g["stage"] for g in api.get(f"/api/users/{u.id}/policy-roadmap").json()["stages"]
                    for i in g["items"] if i["policy_id"] == p.id)

    def leave(u, p):
        return api.get(f"/api/policies/{p.id}", params={"user_id": str(u.id)}).json()["eligibility"]["leave"]

    assert stage_of(adult, ext) == "now" and leave(adult, ext) == "now"
    assert stage_of(adult, leaver) == "at_leave" and leave(adult, leaver) == "after_leave"
    assert leave(teen, ext) == "after_leave"


# ------------------------------------------------------------------ 의무교육 (3.37 교육 이수)


def _settlement(db_session, **kw):
    return make_policy(db_session, name="자립정착금 지원", support_amount="1,000만 원", support_period=None,
                       target_description="보호종료아동", required_docs=["사용계획서", "1차 의무교육확인서"], **kw)


def test_education_needed_when_not_done(api, db_session):
    settle = _settlement(db_session)
    plain = make_policy(db_session, name="청년 월세 지원")
    user = make_user(db_session)

    items = {i["policy_id"]: i for g in api.get(f"/api/users/{user.id}/policy-roadmap").json()["stages"]
             for i in g["items"]}

    edu = items[settle.id]["education"]
    assert edu == {"code": "settlement", "name": "자립정착금 의무교육", "done": False, "done_on": None,
                   "note": "자립지원전담기관 의무교육을 먼저 들어야 받을 수 있어요"}
    assert items[plain.id]["education"] is None


def test_education_done_and_stale(api, db_session):
    settle = _settlement(db_session)
    allowance = make_policy(db_session, name="보호종료아동 자립수당", support_amount="월 50만 원",
                            required_docs=["신청서", "사이버 강의 이수증 1부"])
    user = make_user(db_session)
    base = f"/api/users/{user.id}/credentials"
    assert api.post(base, json={"kind": "education", "code": "settlement", "done_on": "2026-09-01"}).status_code == 201
    assert api.post(base, json={"kind": "education", "code": "finance", "done_on": "2025-08-01"}).status_code == 201

    items = {i["policy_id"]: i for g in api.get(f"/api/users/{user.id}/policy-roadmap").json()["stages"]
             for i in g["items"]}

    assert items[settle.id]["education"]["done"] is True
    assert items[settle.id]["education"]["done_on"] == "2026-09-01"
    assert items[settle.id]["education"]["note"] == "자립정착금 의무교육을 이수했어요"
    stale = items[allowance.id]["education"]
    assert stale["code"] == "finance" and stale["done"] is True
    assert stale["note"].startswith("이수한 지 1년이 넘었어요")


def test_policy_detail_required_education(api, db_session):
    settle = _settlement(db_session)
    plain = make_policy(db_session)
    assert api.get(f"/api/policies/{settle.id}").json()["required_education"] == "settlement"
    assert api.get(f"/api/policies/{plain.id}").json()["required_education"] is None
