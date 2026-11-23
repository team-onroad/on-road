"""users.has_children · has_siblings: 자녀 · 보호 중인 동생(형제자매)이 있는지 → 아동 대상 정책을 '가족을 위한 정책'으로 보여 줌

Revision ID: 0014
Revises: 0013
Create Date: 2026-11-23
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0014"
down_revision: str | None = "0013"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("ALTER TABLE users ADD COLUMN has_children BOOLEAN NOT NULL DEFAULT false")
    op.execute("ALTER TABLE users ADD COLUMN has_siblings BOOLEAN NOT NULL DEFAULT false")


def downgrade() -> None:
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS has_siblings")
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS has_children")
