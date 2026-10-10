import copy
import json

import pytest
from sqlalchemy import select, text

from db.models import Policy
from db.seed import DUMMY_POLICIES_PATH, SeedError, load_policy_file, seed_policies

DUMMY_KEYS = ["D001", "D002", "D003", "D004"]

REAL_POLICY = {
    "policy_key": "P001",
    "name": "자립수당",
    "agency": "보건복지부",
    "contact": "129",
    "category": "living_cost",
    "region": "전국",
    "age_min": None,
    "age_max": None,
    "target_description": "대상",
    "support_content": "내용",
    "support_amount": "월 OO만 원",
    "support_period": "보호종료 후 O년",
    "apply_method": "방법",
    "required_docs": ["신분증", "통장 사본"],
    "apply_url": "https://example.com/apply",
    "source_url": "https://example.com",
    "original_text": "원문",
    "easy_text": "쉬운 말",
    "easy_text_verified": True,
    "source_type": "manual",
    "checked_at": "2026-09-28",
}


def _write(tmp_path, data, name="policies.json"):
    path = tmp_path / name
    path.write_text(json.dumps(data, ensure_ascii=False) if not isinstance(data, str) else data,
                    encoding="utf-8")
    return path


def _policies(session):
    return {p.policy_key: p for p in session.scalars(select(Policy)).all()}


def _visible_keys(session):
    # 서비스 노출 조건 (db-schema.md 3.2)
    return sorted(
        session.scalars(
            select(Policy.policy_key).where(Policy.is_active, Policy.easy_text_verified)
        ).all()
    )


# --- DB 없이 동작하는 파일 읽기 / 검증 ---


@pytest.mark.parametrize("content", ["", "   \n", "[]"])
def test_empty_file_is_treated_as_no_data(tmp_path, content):
    assert load_policy_file(_write(tmp_path, content)) is None


def test_missing_file_is_treated_as_no_data(tmp_path):
    assert load_policy_file(tmp_path / "nope.json") is None


def test_dummy_file_is_valid():
    items = load_policy_file(DUMMY_POLICIES_PATH)
    assert [i["policy_key"] for i in items] == DUMMY_KEYS
    assert all(i["easy_text_verified"] and i["easy_text"] for i in items)


# --- DB 시드 ---


@pytest.mark.parametrize("content", ["", "[]"])
def test_seed_uses_dummy_when_no_real_data(db_session, tmp_path, content):
    result = seed_policies(db_session, path=_write(tmp_path, content))

    assert result.source == "dummy"
    assert sorted(result.inserted) == DUMMY_KEYS
    assert _visible_keys(db_session) == DUMMY_KEYS
    d001 = _policies(db_session)["D001"]
    assert d001.required_docs == ["신분증", "통장 사본", "보호종료 확인서"]
    assert str(d001.checked_at) == "2026-09-28"


def test_seed_is_idempotent(db_session, tmp_path):
    path = _write(tmp_path, "")
    seed_policies(db_session, path=path)
    ids_before = {k: p.id for k, p in _policies(db_session).items()}

    result = seed_policies(db_session, path=path)

    assert result.inserted == []
    assert sorted(result.updated) == DUMMY_KEYS
    assert db_session.execute(text("SELECT count(*) FROM policies")).scalar() == 4
    assert {k: p.id for k, p in _policies(db_session).items()} == ids_before  # id 유지


def test_seed_updates_changed_fields_and_clears_removed_optional(db_session, tmp_path):
    seed_policies(db_session, path=_write(tmp_path, [REAL_POLICY]))
    changed = copy.deepcopy(REAL_POLICY)
    changed["name"] = "자립수당(변경)"
    del changed["contact"]  # 빠진 선택 키는 비운다

    result = seed_policies(db_session, path=_write(tmp_path, [changed]))
    db_session.expire_all()

    assert result.updated == ["P001"]
    p = _policies(db_session)["P001"]
    assert p.name == "자립수당(변경)"
    assert p.contact is None


def test_real_seed_deactivates_dummies_by_default(db_session, tmp_path):
    seed_policies(db_session, path=_write(tmp_path, ""))

    result = seed_policies(db_session, path=_write(tmp_path, [REAL_POLICY]))
    db_session.expire_all()

    assert result.source == "policies"
    assert result.inserted == ["P001"]
    assert result.dummy_deactivated == DUMMY_KEYS
    assert _visible_keys(db_session) == ["P001"]  # 더미가 섞이지 않음


def test_real_seed_delete_option_removes_dummies(db_session, tmp_path):
    seed_policies(db_session, path=_write(tmp_path, ""))

    result = seed_policies(db_session, path=_write(tmp_path, [REAL_POLICY]), dummy_action="delete")

    assert result.dummy_deleted == DUMMY_KEYS
    assert result.dummy_deactivated == []
    assert sorted(_policies(db_session)) == ["P001"]


def test_delete_option_deactivates_dummy_with_checklist(db_session, tmp_path):
    seed_policies(db_session, path=_write(tmp_path, ""))
    user_id = db_session.execute(text(
        "INSERT INTO users (name, birth_date, region, status, stage) "
        "VALUES ('테스트', '2005-03-15', '서울', 'left_care', 'youth') RETURNING id"
    )).scalar_one()
    db_session.execute(
        text("INSERT INTO checklists (user_id, policy_id) "
             "SELECT :u, id FROM policies WHERE policy_key = 'D001'"),
        {"u": user_id},
    )

    result = seed_policies(db_session, path=_write(tmp_path, [REAL_POLICY]), dummy_action="delete")
    db_session.expire_all()

    assert result.dummy_deleted == ["D002", "D003", "D004"]
    assert result.dummy_deactivated == ["D001"]  # 체크리스트가 있어 삭제 대신 비활성화
    assert _visible_keys(db_session) == ["P001"]


def test_real_seed_keep_option_leaves_dummies(db_session, tmp_path):
    seed_policies(db_session, path=_write(tmp_path, ""))

    result = seed_policies(db_session, path=_write(tmp_path, [REAL_POLICY]), dummy_action="keep")

    assert result.dummy_deactivated == [] and result.dummy_deleted == []
    assert _visible_keys(db_session) == sorted(DUMMY_KEYS + ["P001"])


def test_dummy_seed_reactivates_dummies(db_session, tmp_path):
    seed_policies(db_session, path=_write(tmp_path, ""))
    seed_policies(db_session, path=_write(tmp_path, [REAL_POLICY]))

    seed_policies(db_session, path=_write(tmp_path, ""))
    db_session.expire_all()

    assert all(_policies(db_session)[k].is_active for k in DUMMY_KEYS)


def test_real_seed_keeps_is_active_when_not_in_json(db_session, tmp_path):
    path = _write(tmp_path, [REAL_POLICY])
    seed_policies(db_session, path=path)
    db_session.execute(text("UPDATE policies SET is_active = false WHERE policy_key = 'P001'"))

    seed_policies(db_session, path=path)
    db_session.expire_all()

    assert _policies(db_session)["P001"].is_active is False


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda p: p.pop("source_url"), "필수 키 누락"),
        (lambda p: p.update(policy_key="D999"), "더미 정책 전용"),
        (lambda p: p.update(category="food"), "category 코드값 오류"),
        (lambda p: p.update(region="서울특별시"), "region 코드값 오류"),
        (lambda p: p.update(checked_at="2026/09/28"), "YYYY-MM-DD"),
        (lambda p: p.update(easy_text=None), "easy_text 가 있어야"),
        (lambda p: p.update(age_min=30, age_max=20), "age_min 이 age_max 보다"),
        (lambda p: p.update(easy_txt="오타"), "알 수 없는 키"),
    ],
)
def test_invalid_real_data_is_rejected(db_session, tmp_path, mutate, message):
    bad = copy.deepcopy(REAL_POLICY)
    mutate(bad)

    with pytest.raises(SeedError, match=message):
        seed_policies(db_session, path=_write(tmp_path, [bad]))


def test_duplicate_policy_key_is_rejected(db_session, tmp_path):
    with pytest.raises(SeedError, match="중복"):
        seed_policies(db_session, path=_write(tmp_path, [REAL_POLICY, REAL_POLICY]))
