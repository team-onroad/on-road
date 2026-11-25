"""users.interest_code · interest_tags (흥미 탐색 결과와 고른 관심 분야)

- interest_code: 흥미 탐색 결과 상위 유형 ("RA" 처럼 R · I · A · S · E · C 1~3글자)
- interest_tags: 사용자가 고른 관심 분야 (최대 3개, "제과제빵")

Revision ID: 0015
Revises: 0014
Create Date: 2026-11-25
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0015"
down_revision: str | None = "0014"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("ALTER TABLE users ADD COLUMN interest_code VARCHAR(3)")
    op.execute("ALTER TABLE users ADD COLUMN interest_tags JSONB NOT NULL DEFAULT '[]'")


def downgrade() -> None:
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS interest_tags")
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS interest_code")
