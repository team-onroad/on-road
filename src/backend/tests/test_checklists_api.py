"""POST /checklists, GET /checklists/{id}, PATCH /checklists/{id}/items/{item_id} (api.md 1.5, 3.9~3.11)."""

import threading
import uuid
from datetime import date, datetime

import pytest
from sqlalchemy import text

from app.ids import BIGINT_MAX
from app.services import checklists as checklist_service
from tests.factories import make_policy, make_user

RESPONSE_KEYS = {"checklist_id", "policy", "progress", "document_progress", "steps"}
POLICY_KEYS = {"policy_id", "name", "agency", "category", "contact", "apply_url", "checked_at", "is_active"}
STEP_KEYS = {"item_id", "step_key", "label", "is_checked", "checked_at", "detail", "documents"}
DOC_KEYS = {"item_id", "label", "is_checked", "checked_at"}
ITEM_KEYS = {"item_id", "item_type", "step_key", "label", "is_checked", "checked_at"}


def _create(api, user_id, policy_id):
    return api.post("/api/checklists", json={"user_id": str(user_id), "policy_id": policy_id})


def _get(api, checklist_id, user_id):
    return api.get(f"/api/checklists/{checklist_id}", params={"user_id": str(user_id)})


def _toggle(api, checklist_id, item_id, user_id, is_checked=True):
    return api.patch(
        f"/api/checklists/{checklist_id}/items/{item_id}",
        params={"user_id": str(user_id)},
        json={"is_checked": is_checked},
    )


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def _step(data, key):
    return next(s for s in data["steps"] if s["step_key"] == key)


def _counts(db_session):
    return (
        db_session.execute(text("SELECT count(*) FROM checklists")).scalar(),
        db_session.execute(text("SELECT count(*) FROM checklist_items")).scalar(),
    )


def _updated_at(db_session, checklist_id):
    db_session.expire_all()
    return db_session.execute(
        text("SELECT updated_at FROM checklists WHERE id = :id"), {"id": checklist_id}
    ).scalar_one()


@pytest.fixture
def setup(db_session):
    user = make_user(db_session)
    policy = make_policy(
        db_session, name="자립수당", agency="보건복지부", contact="129", required_docs=["신분증", "통장 사본"],
        target_description="지원 대상", easy_text="쉬운 말", apply_method="신청 방법",
        apply_url="https://example.com/apply",
    )
    return user, policy


# ------------------------------------------------------------- 3.9 생성


def test_create_checklist(api, db_session, setup):
    user, policy = setup
    res = _create(api, user.id, policy.id)

    assert res.status_code == 201, res.text
    data = res.json()
    assert set(data) == RESPONSE_KEYS
    assert data["policy"] == {
        "policy_id": policy.id, "name": "자립수당", "agency": "보건복지부", "category": "living_cost",
        "contact": "129", "apply_url": "https://example.com/apply", "checked_at": "2026-09-28", "is_active": True,
    }
    assert data["progress"] == {"checked_steps": 0, "total_steps": 4, "percent": 0}
    assert data["document_progress"] == {"checked": 0, "total": 2}

    steps = data["steps"]
    assert [(s["step_key"], s["label"]) for s in steps] == [
        ("target_check", "대상 확인"), ("condition_check", "조건 확인"), ("doc_prepare", "서류 준비"), ("apply", "신청"),
    ]
    assert all(set(s) == STEP_KEYS and s["is_checked"] is False and s["checked_at"] is None for s in steps)
    assert steps[0]["detail"] == {"target_description": "지원 대상", "easy_text": "쉬운 말"}
    assert steps[1]["detail"] == {
        "target_description": "지원 대상", "eligibility": {"status": "match", "age": "match", "region": "match"},
    }
    assert steps[2]["detail"] is None
    assert steps[3]["detail"] == {"apply_method": "신청 방법", "apply_url": "https://example.com/apply"}

    docs = steps[2]["documents"]
    assert [d["label"] for d in docs] == ["신분증", "통장 사본"]  # required_docs 순서
    assert all(set(d) == DOC_KEYS and d["is_checked"] is False and d["checked_at"] is None for d in docs)
    assert [s["documents"] for s in steps if s["step_key"] != "doc_prepare"] == [[], [], []]


def test_create_stores_items_per_db_schema(api, db_session, setup):
    user, policy = setup
    cid = _create(api, user.id, policy.id).json()["checklist_id"]
    rows = db_session.execute(
        text("SELECT item_type, step_key, label, sort_order FROM checklist_items "
             "WHERE checklist_id = :id ORDER BY item_type DESC, sort_order"), {"id": cid}
    ).all()
    assert [tuple(r) for r in rows] == [
        ("step", "target_check", "대상 확인", 1),
        ("step", "condition_check", "조건 확인", 2),
        ("step", "doc_prepare", "서류 준비", 3),
        ("step", "apply", "신청", 4),
        ("document", "doc_prepare", "신분증", 1),
        ("document", "doc_prepare", "통장 사본", 2),
    ]


def test_existing_checklist_returns_200(api, db_session, setup):
    user, policy = setup
    created = _create(api, user.id, policy.id).json()

    res = _create(api, user.id, policy.id)

    assert res.status_code == 200
    assert res.json() == created
    assert _counts(db_session) == (1, 6)


@pytest.mark.parametrize("change", [{"is_active": False}, {"easy_text_verified": False}])
def test_existing_checklist_of_hidden_policy_returns_200(api, db_session, setup, change):
    user, policy = setup
    _create(api, user.id, policy.id)
    for k, v in change.items():
        setattr(policy, k, v)
    db_session.commit()

    res = _create(api, user.id, policy.id)

    assert res.status_code == 200
    data = res.json()
    assert data["policy"]["is_active"] is False
    assert _step(data, "target_check")["detail"] == {"target_description": "지원 대상", "easy_text": None}


@pytest.mark.parametrize(
    "overrides",
    [{"is_active": False}, {"easy_text_verified": False}, {"easy_text_verified": False, "easy_text": None}],
)
def test_new_checklist_needs_visible_policy(api, db_session, overrides):
    user = make_user(db_session)
    policy = make_policy(db_session, **overrides)
    _assert_error(_create(api, user.id, policy.id), 404, "POLICY_NOT_FOUND")
    assert _counts(db_session) == (0, 0)


def test_new_checklist_unknown_policy(api, db_session):
    user = make_user(db_session)
    _assert_error(_create(api, user.id, 999999), 404, "POLICY_NOT_FOUND")


def test_excluded_policy_can_be_created(api, db_session):
    user = make_user(db_session, region="서울")
    policy = make_policy(db_session, region="부산")
    res = _create(api, user.id, policy.id)
    assert res.status_code == 201
    assert _step(res.json(), "condition_check")["detail"]["eligibility"] == {
        "status": "excluded", "age": "match", "region": "excluded",
    }


def test_no_required_docs(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session, required_docs=[])
    data = _create(api, user.id, policy.id).json()
    assert data["document_progress"] == {"checked": 0, "total": 0}
    assert _step(data, "doc_prepare")["documents"] == []
    assert _counts(db_session) == (1, 4)


def test_documents_are_snapshot(api, db_session, setup):
    user, policy = setup
    cid = _create(api, user.id, policy.id).json()["checklist_id"]
    policy.required_docs = ["다른 서류"]
    db_session.commit()
    docs = _step(_get(api, cid, user.id).json(), "doc_prepare")["documents"]
    assert [d["label"] for d in docs] == ["신분증", "통장 사본"]


def test_nullable_policy_fields(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session, contact=None, apply_url=None)
    data = _create(api, user.id, policy.id).json()
    assert (data["policy"]["contact"], data["policy"]["apply_url"]) == (None, None)
    assert _step(data, "apply")["detail"]["apply_url"] is None


def test_separate_checklists_per_user(api, db_session, setup):
    user, policy = setup
    other = make_user(db_session)
    a = _create(api, user.id, policy.id)
    b = _create(api, other.id, policy.id)
    assert (a.status_code, b.status_code) == (201, 201)
    assert a.json()["checklist_id"] != b.json()["checklist_id"]


@pytest.mark.parametrize("policy_id", ["1", 0, -1, 1.5, True, None, BIGINT_MAX + 1])
def test_create_invalid_policy_id_is_422(api, db_session, setup, policy_id):
    user, _ = setup
    _assert_error(_create(api, user.id, policy_id), 422, "VALIDATION_ERROR")


@pytest.mark.parametrize(
    "body",
    [{}, {"policy_id": 1, "extra": 1}],
)
def test_create_invalid_body_is_422(api, db_session, setup, body):
    user, _ = setup
    res = api.post("/api/checklists", json={"user_id": str(user.id), **body})
    _assert_error(res, 422, "VALIDATION_ERROR")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
@pytest.mark.parametrize("policy_id", [1, 999999, "x", None])
def test_create_unknown_user_takes_priority(api, db_session, setup, user_id, policy_id):
    _assert_error(_create(api, user_id, policy_id), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize("body", [{"policy_id": 1}, []])
def test_create_without_user_id_is_422(api, body):
    _assert_error(api.post("/api/checklists", json=body), 422, "VALIDATION_ERROR")


def test_unique_conflict_returns_existing(api, db_session, setup, monkeypatch):
    """다른 요청이 먼저 만든 경우 (조회 직후 INSERT 가 UNIQUE 에 걸림) → 기존 것 200"""
    user, policy = setup
    created = _create(api, user.id, policy.id).json()
    real_find = checklist_service._find
    calls = []

    def find_misses_first(db, u, pid):
        calls.append(pid)
        return None if len(calls) == 1 else real_find(db, u, pid)

    monkeypatch.setattr(checklist_service, "_find", find_misses_first)
    res = _create(api, user.id, policy.id)

    assert res.status_code == 200
    assert res.json() == created
    assert len(calls) == 2
    assert _counts(db_session) == (1, 6)


def test_concurrent_requests_create_one_checklist(http, db_session, setup):
    user, policy = setup
    body = {"user_id": str(user.id), "policy_id": policy.id}  # 스레드에서 ORM 객체 접근 금지 (세션 공유)
    barrier = threading.Barrier(8)
    results = []

    def worker():
        barrier.wait()
        results.append(http.post("/api/checklists", json=body))

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert sorted(r.status_code for r in results) == [200] * 7 + [201]
    assert len({r.json()["checklist_id"] for r in results}) == 1
    assert _counts(db_session) == (1, 6)


# ------------------------------------------------------------- 3.10 상세


def test_get_checklist(api, db_session, setup):
    user, policy = setup
    created = _create(api, user.id, policy.id).json()
    res = _get(api, created["checklist_id"], user.id)
    assert res.status_code == 200
    assert res.json() == created


def test_get_detail_uses_current_policy_fields(api, db_session, setup):
    user, policy = setup
    cid = _create(api, user.id, policy.id).json()["checklist_id"]
    policy.target_description = "바뀐 대상"
    policy.apply_method = "바뀐 방법"
    db_session.commit()
    data = _get(api, cid, user.id).json()
    assert _step(data, "target_check")["detail"]["target_description"] == "바뀐 대상"
    assert _step(data, "apply")["detail"]["apply_method"] == "바뀐 방법"


@pytest.mark.parametrize(("today", "age"), [(date(2026, 3, 14), "excluded"), (date(2026, 3, 15), "match")])
def test_get_eligibility_uses_today(api, db_session, clock, today, age):
    clock.today = today
    user = make_user(db_session, today=today, birth_date=date(2005, 3, 15))
    policy = make_policy(db_session, age_min=21)
    cid = _create(api, user.id, policy.id).json()["checklist_id"]
    assert _step(_get(api, cid, user.id).json(), "condition_check")["detail"]["eligibility"]["age"] == age


def test_get_other_users_checklist(api, db_session, setup):
    user, policy = setup
    other = make_user(db_session)
    cid = _create(api, user.id, policy.id).json()["checklist_id"]
    _assert_error(_get(api, cid, other.id), 404, "CHECKLIST_NOT_FOUND")


@pytest.mark.parametrize("cid", ["999999", "abc", "0", "-1", str(BIGINT_MAX + 1)])
def test_get_unknown_checklist(api, db_session, setup, cid):
    user, _ = setup
    _assert_error(_get(api, cid, user.id), 404, "CHECKLIST_NOT_FOUND")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
@pytest.mark.parametrize("cid", ["1", "abc"])
def test_get_unknown_user_takes_priority(api, db_session, setup, user_id, cid):
    user, policy = setup
    _create(api, user.id, policy.id)
    _assert_error(_get(api, cid, user_id), 404, "USER_NOT_FOUND")


def test_get_without_user_id_is_422(api, db_session, setup):
    user, policy = setup
    cid = _create(api, user.id, policy.id).json()["checklist_id"]
    _assert_error(api.get(f"/api/checklists/{cid}"), 422, "VALIDATION_ERROR")


# ------------------------------------------------------------- 3.11 체크 토글


@pytest.fixture
def checklist(api, setup):
    user, policy = setup
    data = _create(api, user.id, policy.id).json()
    return user, data


def test_toggle_step(api, db_session, checklist):
    user, data = checklist
    cid = data["checklist_id"]
    step = _step(data, "target_check")
    before = _updated_at(db_session, cid)

    res = _toggle(api, cid, step["item_id"], user.id, True)

    assert res.status_code == 200, res.text
    body = res.json()
    assert set(body) == {"item", "progress", "document_progress"}
    assert set(body["item"]) == ITEM_KEYS
    assert body["item"]["item_type"] == "step"
    assert body["item"]["step_key"] == "target_check"
    assert body["item"]["label"] == "대상 확인"
    assert body["item"]["is_checked"] is True
    assert datetime.fromisoformat(body["item"]["checked_at"]).utcoffset().total_seconds() == 9 * 3600
    assert body["progress"] == {"checked_steps": 1, "total_steps": 4, "percent": 25}
    assert body["document_progress"] == {"checked": 0, "total": 2}
    assert _updated_at(db_session, cid) > before  # 홈 정렬 기준 갱신

    detail = _get(api, cid, user.id).json()
    assert _step(detail, "target_check")["is_checked"] is True
    assert _step(detail, "target_check")["checked_at"] == body["item"]["checked_at"]


def test_uncheck_clears_checked_at(api, db_session, checklist):
    user, data = checklist
    cid, iid = data["checklist_id"], _step(data, "apply")["item_id"]
    _toggle(api, cid, iid, user.id, True)
    before = _updated_at(db_session, cid)

    body = _toggle(api, cid, iid, user.id, False).json()

    assert (body["item"]["is_checked"], body["item"]["checked_at"]) == (False, None)
    assert body["progress"]["checked_steps"] == 0
    assert _updated_at(db_session, cid) > before


@pytest.mark.parametrize("value", [True, False])
def test_same_value_returns_current_state(api, db_session, checklist, value):
    user, data = checklist
    cid, iid = data["checklist_id"], _step(data, "target_check")["item_id"]
    if value:
        _toggle(api, cid, iid, user.id, True)
    first = _get(api, cid, user.id).json()
    before = _updated_at(db_session, cid)

    res = _toggle(api, cid, iid, user.id, value)

    assert res.status_code == 200
    assert res.json()["item"]["is_checked"] is value
    assert res.json()["item"]["checked_at"] == _step(first, "target_check")["checked_at"]  # 시각 유지
    assert _updated_at(db_session, cid) == before  # updated_at 안 바뀜


def test_progress_percent(api, checklist):
    user, data = checklist
    cid = data["checklist_id"]
    percents = [
        _toggle(api, cid, s["item_id"], user.id, True).json()["progress"]["percent"] for s in data["steps"]
    ]
    assert percents == [25, 50, 75, 100]


def test_all_documents_checked_does_not_check_doc_step(api, checklist):
    user, data = checklist
    cid = data["checklist_id"]
    docs = _step(data, "doc_prepare")["documents"]
    results = [_toggle(api, cid, d["item_id"], user.id, True).json() for d in docs]

    assert results[-1]["item"]["item_type"] == "document"
    assert results[-1]["item"]["step_key"] == "doc_prepare"
    assert results[-1]["document_progress"] == {"checked": 2, "total": 2}
    assert results[-1]["progress"]["checked_steps"] == 0
    detail = _get(api, cid, user.id).json()
    assert _step(detail, "doc_prepare")["is_checked"] is False
    assert all(d["is_checked"] for d in _step(detail, "doc_prepare")["documents"])


def test_toggle_on_hidden_policy_checklist(api, db_session, setup, checklist):
    user, data = checklist
    _, policy = setup
    policy.is_active = False
    db_session.commit()
    res = _toggle(api, data["checklist_id"], _step(data, "apply")["item_id"], user.id, True)
    assert res.status_code == 200


def test_toggle_item_of_other_checklist(api, db_session, checklist):
    user, data = checklist
    other_policy = make_policy(db_session)
    other = _create(api, user.id, other_policy.id).json()
    res = _toggle(api, data["checklist_id"], other["steps"][0]["item_id"], user.id)
    _assert_error(res, 404, "ITEM_NOT_FOUND")


@pytest.mark.parametrize("iid", ["999999", "abc", "0", str(BIGINT_MAX + 1)])
def test_toggle_unknown_item(api, checklist, iid):
    user, data = checklist
    _assert_error(_toggle(api, data["checklist_id"], iid, user.id), 404, "ITEM_NOT_FOUND")


def test_toggle_other_users_checklist(api, db_session, checklist):
    user, data = checklist
    other = make_user(db_session)
    _assert_error(_toggle(api, data["checklist_id"], data["steps"][0]["item_id"], other.id), 404, "CHECKLIST_NOT_FOUND")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
def test_toggle_unknown_user(api, checklist, user_id):
    _, data = checklist
    _assert_error(_toggle(api, data["checklist_id"], data["steps"][0]["item_id"], user_id), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize("body", [{"is_checked": "true"}, {"is_checked": 1}, {"is_checked": None}, {},
                                  {"is_checked": True, "extra": 1}, [True]])
def test_toggle_invalid_body(api, checklist, body):
    user, data = checklist
    res = api.patch(
        f"/api/checklists/{data['checklist_id']}/items/{data['steps'][0]['item_id']}",
        params={"user_id": str(user.id)}, json=body,
    )
    _assert_error(res, 422, "VALIDATION_ERROR")


def _raw_patch(api, cid, iid, user_id, content):
    return api.patch(
        f"/api/checklists/{cid}/items/{iid}", params={"user_id": str(user_id)},
        content=content, headers={"content-type": "application/json"},
    )


@pytest.mark.parametrize("content", ['{"is_checked": "x"}', "{bad json"])
def test_toggle_not_found_order_before_body(api, db_session, checklist, content):
    user, data = checklist
    cid, iid = data["checklist_id"], data["steps"][0]["item_id"]
    other = make_user(db_session)
    _assert_error(_raw_patch(api, cid, iid, "not-a-uuid", content), 404, "USER_NOT_FOUND")
    _assert_error(_raw_patch(api, cid, iid, other.id, content), 404, "CHECKLIST_NOT_FOUND")
    _assert_error(_raw_patch(api, cid, "999999", user.id, content), 404, "ITEM_NOT_FOUND")
    _assert_error(_raw_patch(api, cid, iid, user.id, content), 422, "VALIDATION_ERROR")


def test_toggle_without_user_id_is_422(api, checklist):
    _, data = checklist
    res = api.patch(f"/api/checklists/{data['checklist_id']}/items/{data['steps'][0]['item_id']}",
                    json={"is_checked": True})
    _assert_error(res, 422, "VALIDATION_ERROR")
