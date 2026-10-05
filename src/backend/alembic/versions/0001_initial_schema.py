"""초기 스키마: users, policies, checklists, checklist_items, simulations

docs/db-schema.md 부록 DDL 을 그대로 옮긴다. 부분 인덱스·CHECK 제약을 정확히 유지하기 위해
autogenerate 대신 SQL 을 직접 실행한다.

Revision ID: 0001
Revises:
Create Date: 2026-10-04
"""
from collections.abc import Sequence

from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # 사용자
    op.execute("""
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50) NOT NULL,
    phone       VARCHAR(11)
                CHECK (phone ~ '^01[0-9]{8,9}$'),
    birth_date  DATE NOT NULL,
    region      VARCHAR(10) NOT NULL
                CHECK (region IN ('서울','부산','대구','인천','광주','대전','울산','세종',
                                  '경기','강원','충북','충남','전북','전남','경북','경남','제주')),
    status      VARCHAR(20) NOT NULL
                CHECK (status IN ('in_care', 'leaving_soon', 'left_care')),
    stage       VARCHAR(10) NOT NULL
                CHECK (stage IN ('child', 'teen', 'youth')),
    d_date      DATE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status <> 'left_care' OR stage = 'youth')
)
""")

    # 정책
    op.execute("""
CREATE TABLE policies (
    id                  BIGSERIAL PRIMARY KEY,
    policy_key          VARCHAR(20)  NOT NULL UNIQUE,
    name                VARCHAR(200) NOT NULL,
    agency              VARCHAR(100) NOT NULL,
    contact             VARCHAR(200),
    category            VARCHAR(20)  NOT NULL
                        CHECK (category IN ('independence','housing','education','employment',
                                            'living_cost','medical','finance')),
    region              VARCHAR(10)  NOT NULL DEFAULT '전국'
                        CHECK (region IN ('전국','서울','부산','대구','인천','광주','대전','울산','세종',
                                          '경기','강원','충북','충남','전북','전남','경북','경남','제주')),
    age_min             SMALLINT,
    age_max             SMALLINT,
    target_description  TEXT NOT NULL,
    support_content     TEXT NOT NULL,
    support_amount      TEXT,
    support_period      TEXT,
    apply_method        TEXT NOT NULL,
    required_docs       TEXT[] NOT NULL DEFAULT '{}',
    apply_url           TEXT,
    source_url          TEXT NOT NULL,
    original_text       TEXT NOT NULL,
    easy_text           TEXT,
    easy_text_verified  BOOLEAN NOT NULL DEFAULT false,
    source_type         VARCHAR(10) NOT NULL
                        CHECK (source_type IN ('api', 'crawler', 'manual')),
    checked_at          DATE NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (age_min IS NULL OR age_max IS NULL OR age_min <= age_max),
    CHECK (NOT easy_text_verified OR easy_text IS NOT NULL)
)
""")
    op.execute("""
CREATE INDEX idx_policies_visible ON policies (category, region)
    WHERE is_active = true AND easy_text_verified = true
""")

    # 체크리스트
    op.execute("""
CREATE TABLE checklists (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    policy_id   BIGINT NOT NULL REFERENCES policies(id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, policy_id)
)
""")
    op.execute("""
CREATE TABLE checklist_items (
    id            BIGSERIAL PRIMARY KEY,
    checklist_id  BIGINT NOT NULL REFERENCES checklists(id) ON DELETE CASCADE,
    item_type     VARCHAR(10) NOT NULL
                  CHECK (item_type IN ('step', 'document')),
    step_key      VARCHAR(20) NOT NULL
                  CHECK (step_key IN ('target_check', 'condition_check', 'doc_prepare', 'apply')),
    label         VARCHAR(200) NOT NULL,
    sort_order    SMALLINT NOT NULL,
    is_checked    BOOLEAN NOT NULL DEFAULT false,
    checked_at    TIMESTAMPTZ,
    CHECK (item_type = 'step' OR step_key = 'doc_prepare')
)
""")
    op.execute("CREATE INDEX idx_checklist_items_checklist ON checklist_items (checklist_id)")
    op.execute("""
CREATE UNIQUE INDEX uq_checklist_step ON checklist_items (checklist_id, step_key)
    WHERE item_type = 'step'
""")

    # 시뮬레이션
    op.execute("""
CREATE TABLE simulations (
    id                BIGSERIAL PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    total_income      INT NOT NULL CHECK (total_income > 0),
    housing           INT NOT NULL CHECK (housing   >= 0),
    food              INT NOT NULL CHECK (food      >= 0),
    transport         INT NOT NULL CHECK (transport >= 0),
    telecom           INT NOT NULL CHECK (telecom   >= 0),
    other             INT NOT NULL CHECK (other     >= 0),
    result            JSONB NOT NULL,
    criteria_version  VARCHAR(20) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (housing + food + transport + telecom + other <= total_income)
)
""")
    op.execute("CREATE INDEX idx_simulations_user ON simulations (user_id, created_at DESC)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS simulations")
    op.execute("DROP TABLE IF EXISTS checklist_items")
    op.execute("DROP TABLE IF EXISTS checklists")
    op.execute("DROP TABLE IF EXISTS policies")
    op.execute("DROP TABLE IF EXISTS users")
