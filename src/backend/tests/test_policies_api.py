"""GET /policies/{policy_id} (api.md 3.6, 1.3, 1.4, 부록)."""

import uuid
from datetime import date

import pytest

from tests.factories import doc, make_policy, make_user

DETAIL_KEYS = {
    "policy_id", "policy_key", "name", "agency", "contact", "category", "region", "age_min",
    "age_max", "target_description", "support_content", "support_amount", "support_period",
    "apply_method", "required_docs", "apply_url", "source_url", "original_text", "easy_text",
    "checked_at", "is_outdated", "eligibility",
    "amount_won", "amount_cycle", "amount_text", "period_years", "benefit", "conditions", "exclusions", "apply_ways", "apply_deadline", "apply_steps", "doc_facts", "related",
}
NULLABLE = {
    "contact", "age_min", "age_max", "support_amount", "support_period", "apply_url", "eligibility",
    "amount_won", "amount_cycle", "amount_text", "period_years", "benefit", "apply_deadline",
}


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def test_policy_detail_fields(api, db_session):
    p = make_policy(
        db_session, region="서울", age_min=19, age_max=34, required_docs=["신분증", doc("위임장", "대리 신청 시")]
    )

    res = api.get(f"/api/policies/{p.id}")

    assert res.status_code == 200
    data = res.json()
    assert set(data) == DETAIL_KEYS
    assert data["policy_id"] == p.id
    assert data["policy_key"] == p.policy_key
    assert (data["region"], data["age_min"], data["age_max"]) == ("서울", 19, 34)
    assert data["required_docs"] == [
        {"name": "신분증", "condition": None},
        {"name": "위임장", "condition": "대리 신청 시"},
    ]
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


def test_policy_detail_amount_fields(api, db_session):
    p = make_policy(db_session, support_amount="300천원", support_period="보호종료 후 5년 이내")
    data = api.get(f"/api/policies/{p.id}").json()
    assert (data["amount_won"], data["amount_cycle"], data["amount_text"]) == (300_000, None, "30만 원")
    assert data["period_years"] == 5
    assert data["support_amount"] == "300천원"  # 원문은 그대로


def test_policy_detail_related_same_category_eligible_first_with_amount(api, db_session):
    me = make_policy(db_session, category="housing", region="전국")
    no_amount = make_policy(db_session, category="housing", region="전국", support_amount=None)
    with_amount = make_policy(db_session, category="housing", region="전국", support_amount="월 20만 원")
    make_policy(db_session, category="education", region="전국")  # 다른 분야는 빠진다
    make_policy(db_session, category="housing", region="부산")  # 내 지역이 아니면 빠진다
    user = make_user(db_session, region="서울")

    data = api.get(f"/api/policies/{me.id}", params={"user_id": str(user.id)}).json()

    ids = [r["policy_id"] for r in data["related"]]
    assert me.id not in ids
    assert ids[:2] == [with_amount.id, no_amount.id]
    assert all(r["category"] == "housing" for r in data["related"])
    assert data["related"][0]["amount_text"] == "월 20만 원"


def test_policy_detail_related_limit_and_without_user(api, db_session):
    me = make_policy(db_session, category="medical")
    for _ in range(5):
        make_policy(db_session, category="medical")
    data = api.get(f"/api/policies/{me.id}").json()
    assert len(data["related"]) == 3


def test_benefit_from_card_summary(api, db_session, tmp_path, monkeypatch):
    import json as _json

    from app.config import get_settings

    monkeypatch.setattr(get_settings(), "policy_facts_dir", tmp_path)
    p = make_policy(db_session, support_amount=None)
    (tmp_path / f"{p.policy_key}.json").write_text(_json.dumps({
        "passed": True,
        "card": [
            {"item": "target", "label": "지원 대상", "items": ["보호종료 5년 이내"]},
            {"item": "exclusion", "label": "받을 수 없는 경우", "items": ["원가정 복귀"]},
            {"item": "benefit", "label": "지원 내용", "items": ["주거(오피스텔) 지원 1,500천원", "자립체험"]},
        ],
        "facts": [
            {"item": "apply", "text": "주소지 행정복지센터 방문 신청"},
            {"item": "apply", "text": "복지로 온라인 신청"},
            {"item": "apply", "text": "복지로 온라인 신청"},
            {"item": "docs", "text": "신청서 1부 (방문 신청 시)"},
            {"item": "target", "text": "보호종료 5년 이내"},
        ],
    }), encoding="utf-8")

    data = api.get(f"/api/policies/{p.id}").json()

    assert data["apply_steps"] == ["주소지 행정복지센터 방문 신청", "복지로 온라인 신청"]
    assert data["doc_facts"] == ["신청서 1부 (방문 신청 시)"]

    assert data["amount_text"] is None
    assert data["benefit"] == "주거(오피스텔) 지원 150만 원"
    assert data["conditions"] == ["보호종료 5년 이내"]
    assert data["exclusions"] == ["원가정 복귀"]


def test_apply_guide(api, db_session, tmp_path, monkeypatch):
    import json as _json

    from app.config import get_settings

    path = tmp_path / "apply_guide.json"
    monkeypatch.setattr(get_settings(), "apply_guide_path", path)
    p = make_policy(db_session)
    path.write_text(_json.dumps({p.policy_key: {
        "ways": [{"way": "방문", "lines": ["주소지 행정복지센터에서 신청해요"]}, {"way": "온라인", "lines": []}, {"way": ""}],
        "deadline": "상시 신청",
    }}), encoding="utf-8")

    data = api.get(f"/api/policies/{p.id}").json()

    assert data["apply_ways"] == [
        {"way": "방문", "lines": ["주소지 행정복지센터에서 신청해요"]},
        {"way": "온라인", "lines": []},
    ]
    assert data["apply_deadline"] == "상시 신청"


def test_apply_guide_missing(api, db_session, tmp_path, monkeypatch):
    from app.config import get_settings

    monkeypatch.setattr(get_settings(), "apply_guide_path", tmp_path / "none.json")
    data = api.get(f"/api/policies/{make_policy(db_session).id}").json()
    assert data["apply_ways"] == []
    assert data["apply_deadline"] is None


def test_apply_guide_covers_real_policies():
    """정리본은 실제 정책마다 있고, 모든 갈래에 이름이 있다"""
    import json as _json

    from app.config import get_settings

    guide = _json.loads(get_settings().apply_guide_path.read_text(encoding="utf-8"))
    keys = {p["policy_key"] for p in _json.loads(get_settings().policies_json_path.read_text(encoding="utf-8"))}
    assert keys <= set(guide)
    for entry in guide.values():
        assert entry["ways"] and all(w["way"] for w in entry["ways"])


def test_benefit_null_without_facts(api, db_session, tmp_path, monkeypatch):
    from app.config import get_settings

    monkeypatch.setattr(get_settings(), "policy_facts_dir", tmp_path)
    p = make_policy(db_session)
    assert api.get(f"/api/policies/{p.id}").json()["benefit"] is None
