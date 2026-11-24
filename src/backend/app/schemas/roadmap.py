"""맞춤 신청 로드맵 (api.md 3.32). 받을 수 있는 정책을 '언제 신청하는지'로 묶는다 (규칙, AI 없음)."""

from datetime import date
from typing import Literal

from pydantic import BaseModel

from app.schemas.policies import Category, EligibilityValue

RoadmapStage = Literal["before_leave", "at_leave", "now"]
AmountCycle = Literal["monthly", "yearly", "once"]


class RoadmapItem(BaseModel):
    policy_id: int
    policy_key: str
    name: str
    agency: str
    category: Category
    eligibility: EligibilityValue  # match · needs_check (excluded 는 빠진다)
    amount_won: int | None
    amount_cycle: AmountCycle | None
    amount_text: str | None  # "월 50만 원"
    period_years: int | None
    max_total_won: int | None  # 월 금액 × 12 × 기간(년), 연 금액 × 기간, 1회 · 주기 없는 금액은 그대로. 읽지 못하면 null
    apply_from: date | None  # 이날부터 신청할 수 있음. 퇴소 예정일을 모르면 null
    until: date | None  # 지원이 끝나는 날 (퇴소 예정일 + 기간). 모르면 null
    apply_note: str  # "퇴소 30일 전부터 미리 신청" 같은 한 줄


class RoadmapStageGroup(BaseModel):
    stage: RoadmapStage
    title: str  # "퇴소 전에 미리 신청" · "퇴소하면 바로 신청" · "지금 신청할 수 있어요"
    items: list[RoadmapItem]


class RoadmapSummary(BaseModel):
    monthly_won: int  # 조건이 맞는(match) 월 지급 정책 금액 합
    once_won: int  # 조건이 맞는(match) 1회 지급 정책 금액 합 (주기 없는 금액 포함)
    policy_count: int  # 로드맵에 든 정책 수 (확인 필요 포함)
    match_count: int


class PolicyRoadmap(BaseModel):
    base_date: date | None  # 퇴소 예정일 (퇴소 전 · 퇴소 예정 사용자만). 퇴소 후면 null
    d_day: int | None
    summary: RoadmapSummary
    stages: list[RoadmapStageGroup]  # 빈 단계는 빠진다. 순서: before_leave → at_leave → now
