"""users.care_type (보호받던 곳: 아동복지시설 · 가정위탁 · 청소년복지시설)

자립수당(보건복지부, 아동복지시설 · 가정위탁)과 청소년복지시설 퇴소청소년 자립지원수당(성평등가족부)처럼
보호받던 곳에 따라 받는 제도가 갈려서 받는다. null 이면 모름 (가르지 않는다).

Revision ID: 0016
Revises: 0015
Create Date: 2026-11-25
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0016"
down_revision: str | None = "0015"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("ALTER TABLE users ADD COLUMN care_type VARCHAR(20)")
    op.execute(
        "ALTER TABLE users ADD CONSTRAINT ck_users_care_type "
        "CHECK (care_type IS NULL OR care_type IN ('child_facility', 'foster', 'youth_facility'))"
    )


def downgrade() -> None:
    op.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_care_type")
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS care_type")
