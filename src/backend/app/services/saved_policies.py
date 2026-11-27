"""저장한 정책 (관심 있어 저장한 것 · 지금 받고 있는 것)"""

from datetime import date

from sqlalchemy import delete, func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.ids import parse_id
from app.schemas.saved_policies import SavedPolicy, SavedPolicyCreate, SavedPolicyList
from app.services.checklists import policy_object
from app.services.policies import VISIBLE, policy_not_found
from db.models import Policy, SavedPolicy as SavedPolicyRow, User

RECEIVING = "receiving"


def _find(db: Session, user: User, policy_id: int) -> SavedPolicyRow | None:
    return db.scalars(
        select(SavedPolicyRow).where(SavedPolicyRow.user_id == user.id, SavedPolicyRow.policy_id == policy_id)
    ).one_or_none()


def _out(row: SavedPolicyRow, policy: Policy) -> SavedPolicy:
    return SavedPolicy(policy=policy_object(policy), saved_at=row.created_at, status=row.status,
                       started_on=row.started_on)


def _item(db: Session, row: SavedPolicyRow) -> SavedPolicy:
    return _out(row, db.get(Policy, row.policy_id))


def _started_on(status: str, started_on: date | None) -> date | None:
    """받기 시작한 날은 받고 있을 때만 남긴다"""
    return started_on if status == RECEIVING else None


def save(db: Session, user: User, body: SavedPolicyCreate) -> tuple[SavedPolicy, bool]:
    """(저장 항목, 새로 저장했는지).
    이미 저장한 것은 정책이 비활성이어도 그대로 반환하고 (체크리스트와 같음), status 를 주면 그 상태로 바꾼다"""
    existing = _find(db, user, body.policy_id)
    if existing is not None:
        if body.status is not None:
            existing.status = body.status
            # 받는 중으로 다시 저장하면서 날짜를 안 주면 원래 날짜를 둔다
            started = body.started_on if body.started_on is not None else existing.started_on
            existing.started_on = _started_on(body.status, started)
            db.commit()
            db.refresh(existing)
        return _item(db, existing), False
    if db.scalars(select(Policy.id).where(Policy.id == body.policy_id, VISIBLE)).one_or_none() is None:
        raise policy_not_found()
    status = body.status or "saved"
    # 동시 요청 대비: UNIQUE(user_id, policy_id) 충돌이면 먼저 저장한 쪽 반환
    new_id = db.scalar(
        insert(SavedPolicyRow)
        .values(user_id=user.id, policy_id=body.policy_id, status=status,
                started_on=_started_on(status, body.started_on))
        .on_conflict_do_nothing(index_elements=[SavedPolicyRow.user_id, SavedPolicyRow.policy_id])
        .returning(SavedPolicyRow.id)
    )
    db.commit()
    if new_id is None:
        return _item(db, _find(db, user, body.policy_id)), False
    return _item(db, db.get(SavedPolicyRow, new_id)), True


def unsave(db: Session, user: User, policy_id: str) -> None:
    """저장 안 한 정책이어도 성공. 형식 오류만 404"""
    pid = parse_id(policy_id)
    if pid is None:
        raise policy_not_found()
    db.execute(delete(SavedPolicyRow).where(SavedPolicyRow.user_id == user.id, SavedPolicyRow.policy_id == pid))
    db.commit()


def saved_list(db: Session, user: User) -> SavedPolicyList:
    rows = db.execute(
        select(SavedPolicyRow, Policy)
        .join(Policy, Policy.id == SavedPolicyRow.policy_id)
        .where(SavedPolicyRow.user_id == user.id)
        .order_by(SavedPolicyRow.created_at.desc(), SavedPolicyRow.id.desc())
    ).all()
    items = [_out(s, p) for s, p in rows]
    return SavedPolicyList(count=len(items), policies=items)


def saved_count(db: Session, user: User) -> int:
    return db.scalar(select(func.count()).select_from(SavedPolicyRow).where(SavedPolicyRow.user_id == user.id))


def receiving(db: Session, user: User) -> dict[int, date | None]:
    """지금 받고 있는 정책 {policy_id: 받기 시작한 날}"""
    rows = db.execute(
        select(SavedPolicyRow.policy_id, SavedPolicyRow.started_on)
        .where(SavedPolicyRow.user_id == user.id, SavedPolicyRow.status == RECEIVING)
    ).all()
    return {pid: started for pid, started in rows}
