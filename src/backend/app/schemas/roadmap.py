"""맞춤 신청 로드맵 (api.md 3.32). 받을 수 있는 정책을 '언제 신청하는지'로 묶는다 (규칙, AI 없음)."""

from datetime import date
from typing import Literal

from pydantic import BaseModel

from app.schemas.policies import Category, EligibilityValue

RoadmapStage = Literal["before_leave", "at_leave", "now"]
AmountCycle = Literal["monthly", "yearly", "once"]


class RoadmapAlternative(BaseModel):
    """같은 종류라 함께 받기 어려운 다른 정책 (고른 정책 카드를 펼치면 보인다)"""

    policy_id: int
    name: str
    region: str
    amount_text: str | None
    eligibility: EligibilityValue


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
    benefit: str | None  # 받는 것 한 줄 ("주거(오피스텔) 지원"). 돈이 아닌 정책은 이걸 보여 준다
    period_years: int | None
    max_total_won: int | None  # 월 금액 × 12 × 기간(년), 연 금액 × 기간, 1회 · 주기 없는 금액은 그대로. 읽지 못하면 null
    apply_from: date | None  # 이날부터 신청할 수 있음. 퇴소 예정일을 모르면 null
    until: date | None  # 지원이 끝나는 날 (퇴소 예정일 + 기간). 모르면 null
    age_until: date | None = None  # 나이 상한으로 신청할 수 있는 마지막 날 (만 N+1세 생일 전날). 상한이 없거나 아동 정책이면 null
    age_max: int | None = None  # 나이 상한 (18세 이상만). "만 29세까지" 표시용
    region: str = "전국"  # 정책 지역
    kind: str | None = None  # 같은 종류 묶음 (allowance · settlement · challenge · college · scholarship · lease). 묶인 게 없으면 null
    kind_title: str | None = None  # "자립정착금"
    pick_reason: str | None = None  # 같은 종류 중 이걸 고른 까닭 ("같은 종류 3개 중 받는 돈이 가장 커요")
    alternatives: list[RoadmapAlternative] = []  # 같은 종류의 다른 정책 (받는 돈이 큰 순서)
    apply_note: str  # "퇴소 30일 전부터 미리 신청" 같은 한 줄


class RoadmapStageGroup(BaseModel):
    stage: RoadmapStage
    title: str  # "퇴소 전에 미리 신청" · "퇴소하면 바로 신청" · "지금 신청할 수 있어요"
    items: list[RoadmapItem]


class RoadmapSummary(BaseModel):
    monthly_won: int  # 조건이 맞는(match) 월 지급 정책을 모두 받을 때의 최대 금액. 같은 종류(자립수당 · 자립정착금)는 가장 큰 것 하나만
    once_won: int  # 조건이 맞는(match) 1회 지급 최대 금액 (주기 없는 금액 포함, 같은 종류는 하나만)
    policy_count: int  # 로드맵에 든 정책 수 (확인 필요 포함)
    match_count: int


class PolicyRoadmap(BaseModel):
    base_date: date | None  # 퇴소 예정일 (퇴소 전 · 퇴소 예정 사용자만). 퇴소 후면 null
    d_day: int | None
    summary: RoadmapSummary
    stages: list[RoadmapStageGroup]  # 빈 단계는 빠진다. 순서: before_leave → at_leave → now


class RecommendedPolicy(BaseModel):
    """홈 '나에게 맞는 지원 정책' 카드 하나 (추천 순서대로)"""

    policy_id: int
    policy_key: str
    name: str
    category: Category
    eligibility: EligibilityValue
    stage: RoadmapStage
    when_text: str  # "1월 29일부터 미리 신청" · "퇴소하면 신청" · "지금 신청"
    amount_unit: str | None  # "매월" · "매년" · "한 번" (현금이 아니면 null)
    amount_value: str | None  # "50만 원" (현금이 아니면 null)
    amount_text: str | None  # "월 50만 원"
    benefit: str | None  # 받는 것 한 줄 (현금이 아니면 앱이 이걸 보여 준다)
    kind_title: str | None = None  # 같은 종류 묶음 이름 ("자립정착금"). 없으면 null
    pick_reason: str | None = None  # 같은 종류 중 이걸 고른 까닭
    same_kind_count: int = 0  # 이 정책을 뺀 같은 종류 정책 수
    score: int  # 추천 점수 (클수록 먼저). 조건 맞음 > 신청 시기 가까움 > 현금 금액 순으로 더한다
