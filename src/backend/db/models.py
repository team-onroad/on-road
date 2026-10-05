"""SQLAlchemy 모델 (docs/db-schema.md 3장).

실제 테이블·제약·인덱스는 Alembic 마이그레이션(부록 DDL)이 만든다.
이 모델은 애플리케이션 조회·저장용이며, 컬럼 정의는 DDL과 같게 유지한다.
"""

import uuid
from datetime import date, datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


def _created_at() -> Mapped[datetime]:
    return mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


def _updated_at() -> Mapped[datetime]:
    # updated_at 은 애플리케이션(ORM)에서 갱신 (db-schema.md 부록)
    return mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()")
    )
    name: Mapped[str] = mapped_column(String(50), nullable=False)
    phone: Mapped[str | None] = mapped_column(String(11))
    birth_date: Mapped[date] = mapped_column(Date, nullable=False)
    region: Mapped[str] = mapped_column(String(10), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    stage: Mapped[str] = mapped_column(String(10), nullable=False)
    d_date: Mapped[date | None] = mapped_column(Date)
    created_at: Mapped[datetime] = _created_at()
    updated_at: Mapped[datetime] = _updated_at()


class Policy(Base):
    __tablename__ = "policies"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    policy_key: Mapped[str] = mapped_column(String(20), nullable=False, unique=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    agency: Mapped[str] = mapped_column(String(100), nullable=False)
    contact: Mapped[str | None] = mapped_column(String(200))
    category: Mapped[str] = mapped_column(String(20), nullable=False)
    region: Mapped[str] = mapped_column(String(10), nullable=False, server_default="전국")
    age_min: Mapped[int | None] = mapped_column(SmallInteger)
    age_max: Mapped[int | None] = mapped_column(SmallInteger)
    target_description: Mapped[str] = mapped_column(Text, nullable=False)
    support_content: Mapped[str] = mapped_column(Text, nullable=False)
    support_amount: Mapped[str | None] = mapped_column(Text)
    support_period: Mapped[str | None] = mapped_column(Text)
    apply_method: Mapped[str] = mapped_column(Text, nullable=False)
    required_docs: Mapped[list[str]] = mapped_column(
        ARRAY(Text), nullable=False, server_default=text("'{}'")
    )
    apply_url: Mapped[str | None] = mapped_column(Text)
    source_url: Mapped[str] = mapped_column(Text, nullable=False)
    original_text: Mapped[str] = mapped_column(Text, nullable=False)
    easy_text: Mapped[str | None] = mapped_column(Text)
    easy_text_verified: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("false")
    )
    source_type: Mapped[str] = mapped_column(String(10), nullable=False)
    checked_at: Mapped[date] = mapped_column(Date, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("true"))
    created_at: Mapped[datetime] = _created_at()
    updated_at: Mapped[datetime] = _updated_at()


class Checklist(Base):
    __tablename__ = "checklists"
    __table_args__ = (UniqueConstraint("user_id", "policy_id"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    policy_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("policies.id", ondelete="RESTRICT"), nullable=False
    )
    created_at: Mapped[datetime] = _created_at()
    updated_at: Mapped[datetime] = _updated_at()


class ChecklistItem(Base):
    __tablename__ = "checklist_items"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    checklist_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("checklists.id", ondelete="CASCADE"), nullable=False
    )
    item_type: Mapped[str] = mapped_column(String(10), nullable=False)
    step_key: Mapped[str] = mapped_column(String(20), nullable=False)
    label: Mapped[str] = mapped_column(String(200), nullable=False)
    sort_order: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    is_checked: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Simulation(Base):
    __tablename__ = "simulations"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    total_income: Mapped[int] = mapped_column(Integer, nullable=False)
    housing: Mapped[int] = mapped_column(Integer, nullable=False)
    food: Mapped[int] = mapped_column(Integer, nullable=False)
    transport: Mapped[int] = mapped_column(Integer, nullable=False)
    telecom: Mapped[int] = mapped_column(Integer, nullable=False)
    other: Mapped[int] = mapped_column(Integer, nullable=False)
    result: Mapped[dict] = mapped_column(JSONB, nullable=False)
    criteria_version: Mapped[str] = mapped_column(String(20), nullable=False)
    created_at: Mapped[datetime] = _created_at()
