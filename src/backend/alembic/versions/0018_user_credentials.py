"""user_credentials: 사용자가 이수한 교육 · 취득한 자격증

- kind: education(교육 이수) · certificate(자격증)
- code: 정책 조건과 이어지는 교육 (settlement: 자립정착금 의무교육, finance: 자립수당 자립교육). 그 밖의 교육 · 자격증은 null
- done_on: 이수한 날 · 취득한 날 (모르면 null)
- 같은 사람이 같은 종류 · 같은 이름을 두 번 넣지 않는다 (다시 넣으면 날짜만 바뀐다)

맞춤 신청 로드맵(3.32)은 의무교육이 필요한 정책에 이수 여부를 붙이고, 이력서 초안(3.20)은 자격증 · 교육을 채운다.

Revision ID: 0018
Revises: 0017
Create Date: 2026-11-27
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0018"
down_revision: str | None = "0017"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("""
CREATE TABLE user_credentials (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind        VARCHAR(12) NOT NULL CHECK (kind IN ('education', 'certificate')),
    code        VARCHAR(20) CHECK (code IN ('settlement', 'finance')),
    name        VARCHAR(60) NOT NULL,
    done_on     DATE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, kind, name)
)
""")
    op.execute("CREATE INDEX idx_user_credentials_user ON user_credentials (user_id, created_at DESC)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS user_credentials")
