"""users.district (시·군·구) · family_members (자녀 · 동생의 관계 · 성별 · 생년월일)

- district: 시 · 군 · 구 정책(담당기관이 "서울특별시 서초구")을 같은 시 · 군 · 구 사용자에게만 보이게
- family_members: 아동 대상 정책을 가족 나이로 판정

Revision ID: 0014
Revises: 0013
Create Date: 2026-11-23
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0014"
down_revision: str | None = "0013"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("ALTER TABLE users ADD COLUMN district VARCHAR(20)")
    op.execute(
        """
        CREATE TABLE family_members (
            id BIGSERIAL PRIMARY KEY,
            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            relation VARCHAR(10) NOT NULL CHECK (relation IN ('child', 'sibling')),
            gender VARCHAR(10) CHECK (gender IN ('male', 'female')),
            birth_date DATE NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute("CREATE INDEX ix_family_members_user_id ON family_members (user_id)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS family_members")
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS district")
