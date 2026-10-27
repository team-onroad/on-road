"""documents.interview (문서 AI 대화형 작성 진행 상태)

Revision ID: 0005
Revises: 0004
Create Date: 2026-10-27
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # {"current": 지금 묻는 field_id, "skipped": [나중에 묻기로 한 field_id], "pending": 확인 기다리는 field_id}
    op.execute("ALTER TABLE documents ADD COLUMN interview JSONB")


def downgrade() -> None:
    op.execute("ALTER TABLE documents DROP COLUMN IF EXISTS interview")
