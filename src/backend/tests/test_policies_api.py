"""GET /policies/{policy_id} (api.md 3.6, 1.3, 1.4, 부록)."""

import uuid
from datetime import date

import pytest

from tests.factories import make_policy, make_user

DETAIL_KEYS = {
    "policy_id", "policy_key", "name", "agency", "contact", "category", "region", "age_min",
    "age_max", "target_description", "support_content", "support_amount", "support_period",
    "apply_method", "required_docs", "apply_url", "source_url", "original_text", "easy_text",
    "checked_at", "is_outdated", "eligibility",
}
NULLABLE = {"contact", "age_min", "age_max", "support_amount", "support_period", "apply_url", "eligibility"}


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def test_policy_detail_fields(api, db_session):
    p = make_policy(db_session, region="서울", age_min=19, age_max=34, required_docs=["신분증", "통장 사본"])

    res = api.get(f"/api/policies/{p.id}")

    assert res.status_code == 200
    data = res.json()
    assert set(data) == DETAIL_KEYS
    assert data["policy_id"] == p.id
    assert data["policy_key"] == p.policy_key
    assert (data["region"], data["age_min"], data["age_max"]) == ("서울", 19, 34)
    assert data["required_docs"] == ["신분증", "통장 사본"]
    assert data["easy_text"] == "쉬운 말"
    assert data["checked_at"] == "2026-09-28"
    assert data["is_outdated"] is False
    assert data["eligibility"] is None  # user_id 쿼리 없음


def test_policy_detail_nullable_fields(api, db_session):
    p = make_policy(
        db_session, contact=None, age_min=None, age_max=None, support_amount=None,
        support_period=None, apply_url=None, required_docs=[],
    )
    data = api.get(f"/api/policies/{p.id}").json()

    assert {k for k, v in data.items() if v is None} == NULLABLE
    assert data["required_docs"] == []  # 배열은 null 이 아니라 []


def test_non_nullable_fields_always_have_values(api, db_session):
    p = make_policy(db_session)
    data = api.get(f"/api/policies/{p.id}").json()
    assert all(data[k] is not None for k in DETAIL_KEYS - NULLABLE)


@pytest.mark.parametrize(
    ("overrides", "visible"),
    [
        ({"is_active": True, "easy_text_verified": True}, True),
        ({"is_active": False, "easy_text_verified": True}, False),  # 비활성
        ({"is_active": True, "easy_text_verified": False}, False),  # 쉬운 말 미검증
        ({"is_active": False, "easy_text_verified": False}, False),
        ({"is_active": True, "easy_text_verified": False, "easy_text": None}, False),
    ],
)
def test_policy_detail_visibility(api, db_session, overrides, visible):
    p = make_policy(db_session, **overrides)
    res = api.get(f"/api/policies/{p.id}")
    if visible:
        assert res.status_code == 200
    else:
        _assert_error(res, 404, "POLICY_NOT_FOUND")


@pytest.mark.parametrize("policy_id", ["999999", "abc", "0", "-1", "1.5", "9223372036854775808", "１"])
def test_policy_detail_not_found(api, db_session, policy_id):
    make_policy(db_session)
    _assert_error(api.get(f"/api/policies/{policy_id}"), 404, "POLICY_NOT_FOUND")


@pytest.mark.parametrize(
    ("checked_at", "outdated"),
    [
        (date(2026, 4, 7), False),  # 정확히 180일 전
        (date(2026, 4, 6), True),  # 181일 전
    ],
)
def test_policy_detail_is_outdated_boundary(api, db_session, checked_at, outdated):
    p = make_policy(db_session, checked_at=checked_at)
    assert api.get(f"/api/policies/{p.id}").json()["is_outdated"] is outdated


def test_is_outdated_uses_fixed_today(api, db_session, clock):
    p = make_policy(db_session, checked_at=date(2026, 4, 7))
    assert api.get(f"/api/policies/{p.id}").json()["is_outdated"] is False
    clock.today = date(2026, 10, 5)  # 하루 지나면 181일
    assert api.get(f"/api/policies/{p.id}").json()["is_outdated"] is True


@pytest.mark.parametrize(
    ("policy", "eligibility"),
    [
        ({"region": "전국"}, {"status": "match", "age": "match", "region": "match"}),
        ({"region": "서울"}, {"status": "match", "age": "match", "region": "match"}),
        ({"region": "부산"}, {"status": "excluded", "age": "match", "region": "excluded"}),
        ({"age_min": 22}, {"status": "excluded", "age": "excluded", "region": "match"}),
        ({"age_max": 20}, {"status": "excluded", "age": "excluded", "region": "match"}),
        ({"age_min": 21, "age_max": 21}, {"status": "match", "age": "match", "region": "match"}),
        (
            {"region": "경기", "age_min": 30},
            {"status": "excluded", "age": "excluded", "region": "excluded"},
        ),
    ],
)
def test_policy_detail_eligibility(api, db_session, policy, eligibility):
    user = make_user(db_session, region="서울")  # 만 21세
    p = make_policy(db_session, **policy)

    res = api.get(f"/api/policies/{p.id}", params={"user_id": str(user.id)})

    assert res.status_code == 200  # excluded 여도 상세는 보여준다
    assert res.json()["eligibility"] == eligibility


@pytest.mark.parametrize(
    ("today", "age_result"),
    [
        (date(2026, 3, 14), "excluded"),  # 21번째 생일 전날 → 만 20세
        (date(2026, 3, 15), "match"),  # 생일 당일 → 만 21세
    ],
)
def test_policy_detail_eligibility_birthday_boundary(api, db_session, clock, today, age_result):
    clock.today = today
    user = make_user(db_session, today=today, birth_date=date(2005, 3, 15))
    p = make_policy(db_session, age_min=21, checked_at=date(2026, 3, 1))
    data = api.get(f"/api/policies/{p.id}", params={"user_id": str(user.id)}).json()
    assert data["eligibility"]["age"] == age_result


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid", ""])
def test_policy_detail_unknown_user(api, db_session, user_id):
    p = make_policy(db_session)
    _assert_error(api.get(f"/api/policies/{p.id}", params={"user_id": user_id}), 404, "USER_NOT_FOUND")


def test_policy_not_found_takes_priority_over_user_not_found(api, db_session):
    p = make_policy(db_session, is_active=False)
    res = api.get(f"/api/policies/{p.id}", params={"user_id": "not-a-uuid"})
    _assert_error(res, 404, "POLICY_NOT_FOUND")


def test_policy_detail_never_says_apply_possible(api, db_session):
    user = make_user(db_session)
    p = make_policy(db_session)
    res = api.get(f"/api/policies/{p.id}", params={"user_id": str(user.id)})
    assert "신청 가능" not in res.text
