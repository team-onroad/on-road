"""사용자 처리 규칙 (docs/db-schema.md 3.1, 5장 / docs/api.md 3.2, 3.3, 3.12)."""

import uuid
from datetime import date

from sqlalchemy.orm import Session

from app.errors import AppError
from app.rules import calc_age, calc_d_day, calc_stage
from app.schemas.users import UserCreate, UserResponse, UserUpdate
from db.models import User


def user_not_found() -> AppError:
    return AppError(404, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.")


def get_user(db: Session, user_id: str) -> User:
    """user_id 가 UUID 형식이 아니거나 사용자가 없으면 둘 다 404 USER_NOT_FOUND."""
    try:
        uid = uuid.UUID(user_id)
    except ValueError:
        raise user_not_found() from None
    user = db.get(User, uid)
    if user is None:
        raise user_not_found()
    return user


def _check_birth_date(birth_date: date, today: date) -> None:
    if birth_date > today:
        raise AppError(422, "VALIDATION_ERROR", "요청 값이 올바르지 않습니다. (birth_date)")


def _stage_for(user: User, today: date) -> str:
    return calc_stage(calc_age(user.birth_date, today), user.status)


def to_response(user: User, today: date) -> UserResponse:
    return UserResponse(
        user_id=user.id,
        name=user.name,
        birth_date=user.birth_date,
        age=calc_age(user.birth_date, today),
        phone=user.phone,
        region=user.region,
        status=user.status,
        stage=user.stage,
        d_date=user.d_date,
        d_day=calc_d_day(user.d_date, today),
        created_at=user.created_at,
        updated_at=user.updated_at,
    )


def create_user(db: Session, data: UserCreate, today: date) -> User:
    _check_birth_date(data.birth_date, today)
    user = User(**data.model_dump())
    user.stage = _stage_for(user, today)
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def refresh_stage(db: Session, user: User, today: date) -> User:
    """stage 를 다시 계산해서 저장된 값과 다르면 갱신한다 (생일이 지나 단계가 바뀌는 경우)."""
    stage = _stage_for(user, today)
    if stage != user.stage:
        user.stage = stage  # updated_at 은 ORM onupdate 로 함께 갱신
        db.commit()
        db.refresh(user)
    return user


def update_user(db: Session, user: User, data: UserUpdate, today: date) -> User:
    changes = data.model_dump(exclude_unset=True)
    if "birth_date" in changes:
        _check_birth_date(changes["birth_date"], today)

    # 다른 상태 → 퇴소 후로 실제로 바뀔 때만 d_date 를 비운다. 같은 요청의 d_date 가 있으면 그 값을 저장
    becomes_left_care = changes.get("status") == "left_care" and user.status != "left_care"
    if becomes_left_care and "d_date" not in changes:
        changes["d_date"] = None

    for field, value in changes.items():
        setattr(user, field, value)
    # status·birth_date 가 바뀐 경우는 물론, 조회 API 처럼 생일이 지나 단계가 바뀐 경우도 반영
    user.stage = _stage_for(user, today)

    # 값이 실제로 바뀐 경우에만 UPDATE 가 실행되고 updated_at 이 갱신된다.
    db.commit()
    db.refresh(user)
    return user
