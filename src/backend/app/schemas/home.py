"""홈 대시보드·성장 기록 스키마 (api.md 3.4, 3.13, 부록)."""

import uuid
from datetime import date, datetime

from pydantic import BaseModel

from app.schemas.checklists import ChecklistPolicy, DocumentProgress, NextStep, Progress
from app.schemas.users import Stage, Status


class DashboardUser(BaseModel):
    user_id: uuid.UUID
    name: str
    stage: Stage
    status: Status
    d_date: date | None
    d_day: int | None


class DashboardChecklist(BaseModel):
    checklist_id: int
    policy: ChecklistPolicy
    progress: Progress
    next_step: NextStep | None  # 4단계 모두 체크면 null
    updated_at: datetime


class LatestSimulation(BaseModel):
    simulation_id: int
    shortage_count: int
    created_at: datetime


class DashboardResponse(BaseModel):
    user: DashboardUser
    checklists: list[DashboardChecklist]
    latest_simulation: LatestSimulation | None  # 기록 없으면 null


# --- 성장 기록 (api.md 3.13) ---


class GrowthUser(DashboardUser):
    created_at: datetime


class GrowthSummary(BaseModel):
    checklist_count: int
    completed_checklist_count: int  # next_step 이 null 인 것
    simulation_count: int


class GrowthChecklist(BaseModel):
    checklist_id: int
    policy: ChecklistPolicy
    progress: Progress
    document_progress: DocumentProgress
    next_step: NextStep | None
    created_at: datetime
    updated_at: datetime


class SimulationSummary(BaseModel):
    simulation_id: int
    total_income: int
    remaining: int
    shortage_count: int
    criteria_version: str
    created_at: datetime


class GrowthResponse(BaseModel):
    user: GrowthUser
    summary: GrowthSummary
    checklists: list[GrowthChecklist]
    simulations: list[SimulationSummary]
