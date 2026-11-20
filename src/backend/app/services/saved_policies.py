"""저장한 정책"""

from sqlalchemy import delete, func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.ids import parse_id
from app.schemas.saved_policies import SavedPolicy, SavedPolicyList
from app.services.checklists import policy_object
from app.services.policies import VISIBLE, policy_not_found
from db.models import Policy, SavedPolicy as SavedPolicyRow, User


def _find(db: Session, user: User, policy_id: int) -> SavedPolicyRow | None:
    return db.scalars(
        select(SavedPolicyRow).where(SavedPolicyRow.user_id == user.id, SavedPolicyRow.policy_id == policy_id)
    ).one_or_none()


def _item(db: Session, row: SavedPolicyRow) -> SavedPolicy:
    return SavedPolicy(policy=policy_object(db.get(Policy, row.policy_id)), saved_at=row.created_at)


def save(db: Session, user: User, policy_id: int) -> tuple[SavedPolicy, bool]:
    """(저장 항목, 새로 저장했는지). 이미 저장한 것은 정책이 비활성이어도 그대로 반환 (체크리스트와 같음)"""
    existing = _find(db, user, policy_id)
    if existing is not None:
        return _item(db, existing), False
    if db.scalars(select(Policy.id).where(Policy.id == policy_id, VISIBLE)).one_or_none() is None:
        raise policy_not_found()
    # 동시 요청 대비: UNIQUE(user_id, policy_id) 충돌이면 먼저 저장한 쪽 반환
    new_id = db.scalar(
        insert(SavedPolicyRow)
        .values(user_id=user.id, policy_id=policy_id)
        .on_conflict_do_nothing(index_elements=[SavedPolicyRow.user_id, SavedPolicyRow.policy_id])
        .returning(SavedPolicyRow.id)
    )
    db.commit()
    if new_id is None:
        return _item(db, _find(db, user, policy_id)), False
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
    items = [SavedPolicy(policy=policy_object(p), saved_at=s.created_at) for s, p in rows]
    return SavedPolicyList(count=len(items), policies=items)


def saved_count(db: Session, user: User) -> int:
    return db.scalar(select(func.count()).select_from(SavedPolicyRow).where(SavedPolicyRow.user_id == user.id))
