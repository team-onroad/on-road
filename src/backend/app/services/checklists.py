"""체크리스트 (api.md 1.5, 3.9~3.11 / db-schema.md 3.3, 3.4)."""

from datetime import date

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.codes import STEP_KEYS, STEP_LABELS
from app.errors import AppError
from app.ids import parse_id
from app.schemas.checklists import (
    ApplyDetail,
    ChecklistPolicy,
    ChecklistResponse,
    ConditionDetail,
    Document,
    DocumentProgress,
    Progress,
    Step,
    TargetDetail,
    ToggledItem,
    ToggleResponse,
)
from app.services.policies import VISIBLE, eligibility_for, policy_not_found
from db.models import Checklist, ChecklistItem, Policy, User


def checklist_not_found() -> AppError:
    return AppError(404, "CHECKLIST_NOT_FOUND", "체크리스트를 찾을 수 없습니다.")


def item_not_found() -> AppError:
    return AppError(404, "ITEM_NOT_FOUND", "체크리스트 항목을 찾을 수 없습니다.")


def _find(db: Session, user: User, policy_id: int) -> Checklist | None:
    return db.scalars(
        select(Checklist).where(Checklist.user_id == user.id, Checklist.policy_id == policy_id)
    ).one_or_none()


def _new_items(checklist_id: int, required_docs: list[str]) -> list[dict]:
    # db-schema.md 3.4 생성 규칙: step 4개 + required_docs 스냅샷
    steps = [
        {"checklist_id": checklist_id, "item_type": "step", "step_key": k, "label": STEP_LABELS[k], "sort_order": i}
        for i, k in enumerate(STEP_KEYS, start=1)
    ]
    docs = [
        {"checklist_id": checklist_id, "item_type": "document", "step_key": "doc_prepare", "label": d, "sort_order": i}
        for i, d in enumerate(required_docs, start=1)
    ]
    return steps + docs


def get_or_create(db: Session, user: User, policy_id: int) -> tuple[Checklist, bool]:
    """(체크리스트, 새로 만들었는지). 기존 것은 정책이 비활성이어도 반환."""
    existing = _find(db, user, policy_id)
    if existing is not None:
        return existing, False

    policy = db.scalars(select(Policy).where(Policy.id == policy_id, VISIBLE)).one_or_none()
    if policy is None:
        raise policy_not_found()

    # 동시 요청 대비: UNIQUE(user_id, policy_id) 충돌이면 먼저 만든 쪽 반환
    new_id = db.scalar(
        insert(Checklist)
        .values(user_id=user.id, policy_id=policy_id)
        .on_conflict_do_nothing(index_elements=[Checklist.user_id, Checklist.policy_id])
        .returning(Checklist.id)
    )
    if new_id is None:
        db.rollback()
        return _find(db, user, policy_id), False

    db.execute(insert(ChecklistItem), _new_items(new_id, list(policy.required_docs)))
    db.commit()
    return db.get(Checklist, new_id), True


def get_owned(db: Session, user: User, checklist_id: str) -> Checklist:
    """없는 ID, 형식 오류, 다른 사용자 것 → 404 CHECKLIST_NOT_FOUND"""
    cid = parse_id(checklist_id)
    checklist = None
    if cid is not None:
        checklist = db.scalars(
            select(Checklist).where(Checklist.id == cid, Checklist.user_id == user.id)
        ).one_or_none()
    if checklist is None:
        raise checklist_not_found()
    return checklist


def get_item(db: Session, checklist: Checklist, item_id: str) -> ChecklistItem:
    """없는 ID, 형식 오류, 다른 체크리스트 소속 → 404 ITEM_NOT_FOUND"""
    iid = parse_id(item_id)
    item = None
    if iid is not None:
        item = db.scalars(
            select(ChecklistItem).where(ChecklistItem.id == iid, ChecklistItem.checklist_id == checklist.id)
        ).one_or_none()
    if item is None:
        raise item_not_found()
    return item


def _items(db: Session, checklist: Checklist) -> list[ChecklistItem]:
    return list(
        db.scalars(
            select(ChecklistItem)
            .where(ChecklistItem.checklist_id == checklist.id)
            .order_by(ChecklistItem.sort_order, ChecklistItem.id)
        )
    )


def _progress(items: list[ChecklistItem]) -> tuple[Progress, DocumentProgress]:
    steps = [i for i in items if i.item_type == "step"]
    docs = [i for i in items if i.item_type == "document"]
    checked = sum(i.is_checked for i in steps)
    return (
        Progress(checked_steps=checked, total_steps=len(STEP_KEYS), percent=checked * 100 // len(STEP_KEYS)),
        DocumentProgress(checked=sum(i.is_checked for i in docs), total=len(docs)),
    )


def _detail(step_key: str, policy: Policy, visible: bool, user: User, today: date):
    # db-schema.md 3.4 "단계별 상세 설명 출처"
    if step_key == "target_check":
        return TargetDetail(
            target_description=policy.target_description,
            easy_text=policy.easy_text if visible else None,  # 검증 안 된 쉬운 말은 숨김
        )
    if step_key == "condition_check":
        return ConditionDetail(
            target_description=policy.target_description, eligibility=eligibility_for(user, policy, today)
        )
    if step_key == "apply":
        return ApplyDetail(apply_method=policy.apply_method, apply_url=policy.apply_url)
    return None


def to_response(db: Session, checklist: Checklist, user: User, today: date) -> ChecklistResponse:
    policy = db.get(Policy, checklist.policy_id)
    visible = policy.is_active and policy.easy_text_verified
    items = _items(db, checklist)
    documents = [
        Document(item_id=i.id, label=i.label, is_checked=i.is_checked, checked_at=i.checked_at)
        for i in items
        if i.item_type == "document"
    ]
    steps = [
        Step(
            item_id=i.id,
            step_key=i.step_key,
            label=i.label,
            is_checked=i.is_checked,
            checked_at=i.checked_at,
            detail=_detail(i.step_key, policy, visible, user, today),
            documents=documents if i.step_key == "doc_prepare" else [],
        )
        for i in items
        if i.item_type == "step"
    ]
    progress, document_progress = _progress(items)
    return ChecklistResponse(
        checklist_id=checklist.id,
        policy=ChecklistPolicy(
            policy_id=policy.id,
            name=policy.name,
            agency=policy.agency,
            category=policy.category,
            contact=policy.contact,
            apply_url=policy.apply_url,
            checked_at=policy.checked_at,
            is_active=visible,
        ),
        progress=progress,
        document_progress=document_progress,
        steps=steps,
    )


def toggle(db: Session, checklist: Checklist, item: ChecklistItem, is_checked: bool) -> ToggleResponse:
    """같은 값이면 변경 없음 (updated_at 유지). 서류를 다 체크해도 단계는 자동 체크 안 함."""
    if item.is_checked != is_checked:
        item.is_checked = is_checked
        item.checked_at = func.now() if is_checked else None
        checklist.updated_at = func.now()  # 홈 "지금 할 일" 정렬 기준
        db.commit()
        db.refresh(item)
    progress, document_progress = _progress(_items(db, checklist))
    return ToggleResponse(
        item=ToggledItem(
            item_id=item.id,
            item_type=item.item_type,
            step_key=item.step_key,
            label=item.label,
            is_checked=item.is_checked,
            checked_at=item.checked_at,
        ),
        progress=progress,
        document_progress=document_progress,
    )
