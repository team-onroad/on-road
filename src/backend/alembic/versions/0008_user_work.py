"""users.work: 지금 하는 일

Revision ID: 0008
Revises: 0007
Create Date: 2026-11-07
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0008"
down_revision: str | None = "0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("""
ALTER TABLE users
    ADD COLUMN work VARCHAR(20)
        CONSTRAINT ck_users_work CHECK (work IN ('student', 'working', 'job_seeking', 'resting'))
""")


def downgrade() -> None:
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS work")
