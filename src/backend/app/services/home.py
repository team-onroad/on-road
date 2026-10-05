"""홈 대시보드 (api.md 3.4)."""

from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.rules import calc_d_day
from app.schemas.checklists import DocumentProgress, NextStep, Progress
from app.schemas.home import DashboardChecklist, DashboardResponse, DashboardUser, LatestSimulation
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


def shortage_count(sim: Simulation) -> int:
    return len(sim.result["shortages"])


def dashboard(db: Session, user: User, today: date) -> DashboardResponse:
    latest = db.scalars(
        select(Simulation)
        .where(Simulation.user_id == user.id)
        .order_by(Simulation.created_at.desc(), Simulation.id.desc())
        .limit(1)
    ).first()
    return DashboardResponse(
        user=DashboardUser(
            user_id=user.id,
            name=user.name,
            stage=user.stage,
            status=user.status,
            d_date=user.d_date,
            d_day=calc_d_day(user.d_date, today),
        ),
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
