"""0003: required_docs TEXT[] → JSONB 변환 (db-schema.md 3.2)."""

from alembic import command
from sqlalchemy import text

from tests.conftest import TABLES, _alembic_config

POLICY_SQL = """
INSERT INTO policies (policy_key, name, agency, category, region, target_description, support_content,
                      apply_method, required_docs, source_url, original_text, source_type, checked_at)
VALUES (:key, '정책', '기관', 'housing', '전국', '대상', '내용', '방법', {docs}, 'https://example.com', '원문',
        'manual', '2026-09-28')
"""


def _clear(engine):
    with engine.begin() as conn:
        conn.execute(text(f"TRUNCATE {', '.join(TABLES)} RESTART IDENTITY CASCADE"))


def _docs(engine):
    with engine.connect() as conn:
        return dict(conn.execute(text("SELECT policy_key, required_docs FROM policies")).all())


def test_upgrade_and_downgrade_convert_required_docs(db_engine):
    cfg = _alembic_config(db_engine.url.render_as_string(hide_password=False))
    _clear(db_engine)
    try:
        command.downgrade(cfg, "0002")
        with db_engine.begin() as conn:
            conn.execute(text(POLICY_SQL.format(docs="ARRAY['신분증', '통장 사본']")), {"key": "A"})
            conn.execute(text(POLICY_SQL.format(docs="DEFAULT")), {"key": "B"})

        command.upgrade(cfg, "0003")
        assert _docs(db_engine) == {
            "A": [{"name": "신분증", "condition": None}, {"name": "통장 사본", "condition": None}],
            "B": [],
        }
        with db_engine.begin() as conn:
            conn.execute(
                text(POLICY_SQL.format(docs="CAST(:docs AS JSONB)")),
                {"key": "C", "docs": '[{"name": "신분증", "condition": null}, '
                                     '{"name": "위임장", "condition": "대리 신청 시"}]'},
            )
            conn.execute(text(POLICY_SQL.format(docs="DEFAULT")), {"key": "D"})
        assert _docs(db_engine)["D"] == []  # 기본값 '[]'

        # 되돌리면 name 만 남음
        command.downgrade(cfg, "0002")
        assert _docs(db_engine) == {
            "A": ["신분증", "통장 사본"], "B": [], "C": ["신분증", "위임장"], "D": [],
        }
    finally:
        command.upgrade(cfg, "head")
        _clear(db_engine)
