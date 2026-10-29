"""documents.source_type 에 upload 추가 (사진·PDF 로 올린 양식, POST /documents/upload)

Revision ID: 0006
Revises: 0005
Create Date: 2026-10-28
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_source_type_check")
    op.execute(
        "ALTER TABLE documents ADD CONSTRAINT documents_source_type_check "
        "CHECK (source_type IN ('template', 'policy_form', 'upload'))"
    )


def downgrade() -> None:
    op.execute("DELETE FROM documents WHERE source_type = 'upload'")
    op.execute("ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_source_type_check")
    op.execute(
        "ALTER TABLE documents ADD CONSTRAINT documents_source_type_check "
        "CHECK (source_type IN ('template', 'policy_form'))"
    )
