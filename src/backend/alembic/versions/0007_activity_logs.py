"""activity_logs: 앱에서 한 실전 연습 · 한 달 살아보기 결과 (자립 준비도 계산용)

Revision ID: 0007
Revises: 0006
Create Date: 2026-10-30
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("""
CREATE TABLE activity_logs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind        VARCHAR(20) NOT NULL CHECK (kind IN ('practice', 'month_life')),
    ref         VARCHAR(50),
    data        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
)
""")
    op.execute("CREATE INDEX idx_activity_logs_user ON activity_logs (user_id, kind, created_at DESC)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS activity_logs")
