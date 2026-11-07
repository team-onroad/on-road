"""readiness_snapshots: 날짜별 자립 준비도 점수

Revision ID: 0011
Revises: 0010
Create Date: 2026-11-07
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0011"
down_revision: str | None = "0010"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("""
CREATE TABLE readiness_snapshots (
    id             BIGSERIAL PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    score          SMALLINT NOT NULL CONSTRAINT ck_readiness_snapshots_score CHECK (score BETWEEN 0 AND 100),
    areas          JSONB NOT NULL,
    snapshot_date  DATE NOT NULL,
    UNIQUE (user_id, snapshot_date)
)
""")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS readiness_snapshots")
