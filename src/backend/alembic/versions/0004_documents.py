"""documents, document_fields (문서 AI: 항목별 공동 작성)

Revision ID: 0004
Revises: 0003
Create Date: 2026-10-27
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("""
CREATE TABLE documents (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_type        VARCHAR(20) NOT NULL CHECK (source_type IN ('template', 'policy_form')),
    source_id          VARCHAR(100) NOT NULL,
    title              VARCHAR(200) NOT NULL,
    policy_id          BIGINT REFERENCES policies(id) ON DELETE SET NULL,
    checklist_item_id  BIGINT REFERENCES checklist_items(id) ON DELETE SET NULL,
    context            TEXT,
    spec               JSONB NOT NULL,
    status             VARCHAR(10) NOT NULL DEFAULT 'writing' CHECK (status IN ('writing', 'exported')),
    exported_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, source_type, source_id)
)
""")
    op.execute("CREATE INDEX idx_documents_user ON documents (user_id, updated_at DESC)")
    op.execute("""
CREATE TABLE document_fields (
    id                 BIGSERIAL PRIMARY KEY,
    document_id        BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    field_id           VARCHAR(100) NOT NULL,
    sort_order         SMALLINT NOT NULL,
    segments           JSONB NOT NULL DEFAULT '[]',
    previous_segments  JSONB,
    tip                TEXT,
    reused_from        VARCHAR(200),
    is_completed       BOOLEAN NOT NULL DEFAULT false,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, field_id)
)
""")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS document_fields")
    op.execute("DROP TABLE IF EXISTS documents")
