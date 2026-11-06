"""checklists.due_date: 사용자가 정한 신청 예정일

Revision ID: 0010
Revises: 0009
Create Date: 2026-11-07
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0010"
down_revision: str | None = "0009"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("ALTER TABLE checklists ADD COLUMN due_date DATE")


def downgrade() -> None:
    op.execute("ALTER TABLE checklists DROP COLUMN IF EXISTS due_date")
