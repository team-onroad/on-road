"""홈 대시보드 스키마 (api.md 3.4, 부록)."""

import uuid
from datetime import date, datetime

from pydantic import BaseModel

from app.schemas.checklists import ChecklistPolicy, NextStep, Progress
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
