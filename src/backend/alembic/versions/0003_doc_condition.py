"""조건부 서류 (db-schema.md 3.2, 3.4, 부록 DDL)

Revision ID: 0003
Revises: 0002
Create Date: 2026-10-12
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # ALTER ... USING 에는 서브쿼리를 못 써서 임시 함수로 변환
    op.execute("""
CREATE FUNCTION pg_temp.docs_to_jsonb(docs TEXT[]) RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(jsonb_agg(jsonb_build_object('name', d, 'condition', NULL) ORDER BY o), '[]'::jsonb)
    FROM unnest(docs) WITH ORDINALITY AS t(d, o)
$$
""")
    op.execute("ALTER TABLE policies ALTER COLUMN required_docs DROP DEFAULT")
    op.execute(
        "ALTER TABLE policies ALTER COLUMN required_docs TYPE JSONB USING pg_temp.docs_to_jsonb(required_docs)"
    )
    op.execute("ALTER TABLE policies ALTER COLUMN required_docs SET DEFAULT '[]'")

    op.execute("""
ALTER TABLE checklist_items
    ADD COLUMN condition VARCHAR(200),
    ADD COLUMN is_not_applicable BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_checklist_items_condition_document
        CHECK (item_type = 'document' OR condition IS NULL),
    ADD CONSTRAINT ck_checklist_items_not_applicable_condition
        CHECK (NOT is_not_applicable OR (item_type = 'document' AND condition IS NOT NULL)),
    ADD CONSTRAINT ck_checklist_items_checked_not_applicable
        CHECK (NOT (is_checked AND is_not_applicable))
""")


def downgrade() -> None:
    op.execute("""
ALTER TABLE checklist_items
    DROP CONSTRAINT ck_checklist_items_checked_not_applicable,
    DROP CONSTRAINT ck_checklist_items_not_applicable_condition,
    DROP CONSTRAINT ck_checklist_items_condition_document,
    DROP COLUMN is_not_applicable,
    DROP COLUMN condition
""")
    # name 만 남기고 condition 은 버림
    op.execute("""
CREATE FUNCTION pg_temp.docs_to_array(docs JSONB) RETURNS TEXT[] LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(array_agg(e->>'name' ORDER BY o), '{}')
    FROM jsonb_array_elements(docs) WITH ORDINALITY AS t(e, o)
$$
""")
    op.execute("ALTER TABLE policies ALTER COLUMN required_docs DROP DEFAULT")
    op.execute(
        "ALTER TABLE policies ALTER COLUMN required_docs TYPE TEXT[] USING pg_temp.docs_to_array(required_docs)"
    )
    op.execute("ALTER TABLE policies ALTER COLUMN required_docs SET DEFAULT '{}'")
