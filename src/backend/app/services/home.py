"""홈 대시보드·성장 기록 (api.md 3.4, 3.13)."""

from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.codes import ALLOCATION_ITEMS
from app.rules import calc_d_day
from app.schemas.checklists import DocumentProgress, NextStep, Progress
from app.schemas.home import (
    DashboardChecklist,
    DashboardResponse,
    DashboardUser,
    GrowthChecklist,
    GrowthResponse,
    GrowthSummary,
    GrowthUser,
    LatestSimulation,
    SimulationSummary,
)
from app.services.checklists import next_step, policy_object, progress
from db.models import Checklist, ChecklistItem, Policy, Simulation, User


@dataclass
class ChecklistSummary:
    checklist: Checklist
    policy: Policy
    progress: Progress
    document_progress: DocumentProgress
    next_step: NextStep | None


def user_checklists(db: Session, user: User) -> list[ChecklistSummary]:
    """전체 체크리스트. 진행 중 먼저, 완료 뒤. 각각 updated_at 내림차순, 같으면 ID 큰 것 먼저"""
    rows = db.execute(
        select(Checklist, Policy)
        .join(Policy, Policy.id == Checklist.policy_id)
        .where(Checklist.user_id == user.id)
        .order_by(Checklist.updated_at.desc(), Checklist.id.desc())
    ).all()
    items: dict[int, list[ChecklistItem]] = {c.id: [] for c, _ in rows}
    if items:
        for item in db.scalars(select(ChecklistItem).where(ChecklistItem.checklist_id.in_(items))):
            items[item.checklist_id].append(item)

    summaries = []
    for checklist, policy in rows:
        step_progress, document_progress = progress(items[checklist.id])
        summaries.append(
            ChecklistSummary(checklist, policy, step_progress, document_progress, next_step(items[checklist.id]))
        )
    # 정렬 유지(stable): 진행 중 → 완료
    return sorted(summaries, key=lambda s: s.next_step is None)


def _simulations(user: User):
    """최신순, created_at 같으면 ID 큰 것 먼저"""
    return (
        select(Simulation)
        .where(Simulation.user_id == user.id)
        .order_by(Simulation.created_at.desc(), Simulation.id.desc())
    )


def shortage_count(sim: Simulation) -> int:
    return len(sim.result["shortages"])


def _user_fields(user: User, today: date) -> dict:
    return {
        "user_id": user.id,
        "name": user.name,
        "stage": user.stage,
        "status": user.status,
        "d_date": user.d_date,
        "d_day": calc_d_day(user.d_date, today),
    }


def dashboard(db: Session, user: User, today: date) -> DashboardResponse:
    latest = db.scalars(_simulations(user).limit(1)).first()
    return DashboardResponse(
        user=DashboardUser(**_user_fields(user, today)),
        checklists=[
            DashboardChecklist(
                checklist_id=s.checklist.id,
                policy=policy_object(s.policy),
                progress=s.progress,
                next_step=s.next_step,
                updated_at=s.checklist.updated_at,
            )
            for s in user_checklists(db, user)
        ],
        latest_simulation=(
            LatestSimulation(simulation_id=latest.id, shortage_count=shortage_count(latest), created_at=latest.created_at)
            if latest
            else None
        ),
    )


def growth(db: Session, user: User, today: date) -> GrowthResponse:
    checklists = user_checklists(db, user)
    simulations = list(db.scalars(_simulations(user)))
    return GrowthResponse(
        user=GrowthUser(**_user_fields(user, today), created_at=user.created_at),
        summary=GrowthSummary(
            checklist_count=len(checklists),
            completed_checklist_count=sum(s.next_step is None for s in checklists),
            simulation_count=len(simulations),
        ),
        checklists=[
            GrowthChecklist(
                checklist_id=s.checklist.id,
                policy=policy_object(s.policy),
                progress=s.progress,
                document_progress=s.document_progress,
                next_step=s.next_step,
                created_at=s.checklist.created_at,
                updated_at=s.checklist.updated_at,
            )
            for s in checklists
        ],
        simulations=[
            SimulationSummary(
                simulation_id=sim.id,
                total_income=sim.total_income,
                remaining=sim.total_income - sum(getattr(sim, k) for k in ALLOCATION_ITEMS),
                shortage_count=shortage_count(sim),
                criteria_version=sim.criteria_version,
                created_at=sim.created_at,
            )
            for sim in simulations
        ],
    )
