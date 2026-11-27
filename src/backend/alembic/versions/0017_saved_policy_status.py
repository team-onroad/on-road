"""saved_policies.status · started_on (저장한 정책 중 지금 받고 있는 것)

- status: saved(관심 있어 저장) · receiving(지금 받고 있음). 기존 저장은 saved
- started_on: 받기 시작한 날 (receiving 일 때만, 모르면 null). 로드맵에서 지원이 끝나는 날을 셈한다

맞춤 신청 로드맵(3.32)은 받고 있는 정책을 '받고 있어요'로 표시하고, 같은 종류(자립수당 등)에서는 받고 있는 것을 남긴다.

Revision ID: 0017
Revises: 0016
Create Date: 2026-11-27
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0017"
down_revision: str | None = "0016"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("ALTER TABLE saved_policies ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'saved'")
    op.execute(
        "ALTER TABLE saved_policies ADD CONSTRAINT ck_saved_policies_status "
        "CHECK (status IN ('saved', 'receiving'))"
    )
    op.execute("ALTER TABLE saved_policies ADD COLUMN started_on DATE")


def downgrade() -> None:
    op.execute("ALTER TABLE saved_policies DROP COLUMN IF EXISTS started_on")
    op.execute("ALTER TABLE saved_policies DROP CONSTRAINT IF EXISTS ck_saved_policies_status")
    op.execute("ALTER TABLE saved_policies DROP COLUMN IF EXISTS status")
