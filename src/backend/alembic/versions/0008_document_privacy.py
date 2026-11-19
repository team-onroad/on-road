"""documents.personal_cleared_at (오래된 서류의 개인정보 칸 비우기)

서류 칸 내용 암호화(app/sealing.py)는 컬럼 형식이 그대로라 마이그레이션이 필요 없다.
이미 있는 평문 값은 그대로 읽히고, 다음에 고칠 때부터 암호화된다.
한 번에 모두 암호화하려면: python -m db.document_privacy seal

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
    op.execute("ALTER TABLE documents ADD COLUMN personal_cleared_at TIMESTAMPTZ")


def downgrade() -> None:
    op.execute("ALTER TABLE documents DROP COLUMN IF EXISTS personal_cleared_at")
