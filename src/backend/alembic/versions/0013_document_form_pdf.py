"""documents.form_pdf: 올린 양식의 원본 규격 바탕 PDF (암호화 저장)

Revision ID: 0013
Revises: 0012
Create Date: 2026-11-22
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0013"
down_revision: str | None = "0012"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("ALTER TABLE documents ADD COLUMN form_pdf TEXT")


def downgrade() -> None:
    op.execute("ALTER TABLE documents DROP COLUMN IF EXISTS form_pdf")
