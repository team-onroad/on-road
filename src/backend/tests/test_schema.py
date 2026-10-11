"""마이그레이션 결과가 db-schema.md 부록 DDL 의 테이블·제약·인덱스와 맞는지 확인한다."""

import pytest
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError

from tests.conftest import TABLES

USER_SQL = """
INSERT INTO users (name, phone, birth_date, region, status, stage)
VALUES (:name, :phone, '2005-03-15', :region, :status, :stage)
RETURNING id
"""

POLICY_SQL = """
INSERT INTO policies (policy_key, name, agency, category, region, age_min, age_max,
                      target_description, support_content, apply_method, source_url,
                      original_text, easy_text, easy_text_verified, source_type, checked_at)
VALUES (:key, '정책', '기관', :category, :region, :age_min, :age_max,
        '대상', '내용', '방법', 'https://example.com', '원문', :easy_text, :verified,
        'manual', '2026-09-28')
RETURNING id
"""


def _user(session, **overrides):
    params = {"name": "테스트", "phone": None, "region": "서울", "status": "in_care", "stage": "youth"}
    params.update(overrides)
    return session.execute(text(USER_SQL), params).scalar_one()


def _policy(session, **overrides):
    params = {
        "key": "T001", "category": "housing", "region": "전국", "age_min": None, "age_max": None,
        "easy_text": "쉬운 말", "verified": True,
    }
    params.update(overrides)
    return session.execute(text(POLICY_SQL), params).scalar_one()


def _assert_rejected(session, fn, **kwargs):
    with pytest.raises(IntegrityError):
        with session.begin_nested():
            fn(session, **kwargs)


def test_tables_and_indexes_exist(db_engine):
    insp = inspect(db_engine)
    assert set(TABLES) <= set(insp.get_table_names())

    with db_engine.connect() as conn:
        indexes = dict(
            conn.execute(
                text("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'")
            ).all()
        )
    assert "WHERE ((is_active = true) AND (easy_text_verified = true))" in indexes["idx_policies_visible"]
    assert "UNIQUE INDEX" in indexes["uq_checklist_step"]
    assert "WHERE ((item_type)::text = 'step'::text)" in indexes["uq_checklist_step"]
    assert "idx_checklist_items_checklist" in indexes
    assert "(user_id, created_at DESC)" in indexes["idx_simulations_user"]


def test_user_defaults(db_session):
    user_id = _user(db_session, phone="01012345678")
    row = db_session.execute(
        text("SELECT id, created_at, updated_at FROM users WHERE id = :id"), {"id": user_id}
    ).one()
    assert row.created_at is not None and row.updated_at is not None
    assert row.created_at.utcoffset().total_seconds() == 9 * 3600  # 세션 시간대 Asia/Seoul


@pytest.mark.parametrize(
    "overrides",
    [
        {"phone": "010123456"},  # 9자리 (10~11자리여야 함)
        {"phone": "010-123-45"},  # 하이픈 포함
        {"phone": "02123456789"},  # 01로 시작하지 않음
        {"region": "전국"},  # 사용자에는 전국 불가
        {"status": "unknown"},
        {"status": "left_care", "stage": "teen"},  # 퇴소 후는 항상 youth
    ],
)
def test_user_checks(db_session, overrides):
    _assert_rejected(db_session, _user, **overrides)


@pytest.mark.parametrize(
    "overrides",
    [
        {"category": "food"},
        {"region": "서울시"},
        {"age_min": 30, "age_max": 20},
        {"easy_text": None, "verified": True},  # 검증 통과인데 쉬운 말 없음
    ],
)
def test_policy_checks(db_session, overrides):
    _assert_rejected(db_session, _policy, **overrides)


def test_policy_key_unique(db_session):
    _policy(db_session, key="T001")
    _assert_rejected(db_session, _policy, key="T001")


def test_checklist_constraints(db_session):
    user_id = _user(db_session)
    policy_id = _policy(db_session)
    checklist_sql = text(
        "INSERT INTO checklists (user_id, policy_id) VALUES (:u, :p) RETURNING id"
    )
    checklist_id = db_session.execute(checklist_sql, {"u": user_id, "p": policy_id}).scalar_one()

    # (user_id, policy_id) UNIQUE
    with pytest.raises(IntegrityError), db_session.begin_nested():
        db_session.execute(checklist_sql, {"u": user_id, "p": policy_id})

    item_sql = text(
        "INSERT INTO checklist_items (checklist_id, item_type, step_key, label, sort_order) "
        "VALUES (:c, :t, :s, '항목', 1)"
    )
    db_session.execute(item_sql, {"c": checklist_id, "t": "step", "s": "target_check"})
    db_session.execute(item_sql, {"c": checklist_id, "t": "document", "s": "doc_prepare"})
    db_session.execute(item_sql, {"c": checklist_id, "t": "document", "s": "doc_prepare"})

    # 같은 단계의 step 항목은 1개만 (부분 UNIQUE 인덱스)
    with pytest.raises(IntegrityError), db_session.begin_nested():
        db_session.execute(item_sql, {"c": checklist_id, "t": "step", "s": "target_check"})
    # document 항목은 doc_prepare 단계 소속만
    with pytest.raises(IntegrityError), db_session.begin_nested():
        db_session.execute(item_sql, {"c": checklist_id, "t": "document", "s": "apply"})

    # 정책은 체크리스트가 있으면 삭제 불가 (ON DELETE RESTRICT)
    with pytest.raises(IntegrityError), db_session.begin_nested():
        db_session.execute(text("DELETE FROM policies WHERE id = :p"), {"p": policy_id})

    # 사용자를 지우면 체크리스트·항목이 함께 삭제 (ON DELETE CASCADE)
    db_session.execute(text("DELETE FROM users WHERE id = :u"), {"u": user_id})
    assert db_session.execute(text("SELECT count(*) FROM checklists")).scalar() == 0
    assert db_session.execute(text("SELECT count(*) FROM checklist_items")).scalar() == 0


def test_simulation_sum_must_not_exceed_income(db_session):
    user_id = _user(db_session)
    sql = text(
        "INSERT INTO simulations (user_id, total_income, housing, food, transport, telecom, other, "
        "result, criteria_version) VALUES (:u, :income, 300000, 300000, 100000, 50000, 100000, "
        "'{}'::jsonb, '2026-10-v1')"
    )
    db_session.execute(sql, {"u": user_id, "income": 850000})  # 합계 = 총소득 허용

    with pytest.raises(IntegrityError), db_session.begin_nested():
        db_session.execute(sql, {"u": user_id, "income": 849999})
