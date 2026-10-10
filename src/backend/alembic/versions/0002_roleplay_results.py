"""roleplay_results (db-schema.md 3.6, 부록 DDL)

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-11
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("""
CREATE TABLE roleplay_results (
    id           BIGSERIAL PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scenario_id  VARCHAR(50) NOT NULL,
    good         JSONB NOT NULL,
    missed       JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
)
""")
    op.execute("CREATE INDEX idx_roleplay_results_user ON roleplay_results (user_id, created_at DESC)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS roleplay_results")
