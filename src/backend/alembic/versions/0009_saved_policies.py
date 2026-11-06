"""saved_policies: 사용자가 저장한 정책

Revision ID: 0009
Revises: 0008
Create Date: 2026-11-07
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0009"
down_revision: str | None = "0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("""
CREATE TABLE saved_policies (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    policy_id   BIGINT NOT NULL REFERENCES policies(id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, policy_id)
)
""")
    op.execute("CREATE INDEX idx_saved_policies_user ON saved_policies (user_id, created_at DESC)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS saved_policies")
