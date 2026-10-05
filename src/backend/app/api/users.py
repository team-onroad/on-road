import json
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Request, status
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
from sqlalchemy.orm import Session

from app.clock import get_today
from app.schemas.home import DashboardResponse
from app.schemas.users import UserCreate, UserResponse, UserUpdate
from app.services import home
from app.services import users as service
from db.models import User
from db.session import get_db

router = APIRouter(prefix="/users", tags=["온보딩·회원정보"])

DB = Annotated[Session, Depends(get_db)]
Today = Annotated[date, Depends(get_today)]

ERROR_RESPONSES = {
    404: {"description": "USER_NOT_FOUND (user_id 형식 오류 포함)"},
    422: {"description": "VALIDATION_ERROR"},
}


def _existing_user(user_id: str, db: DB) -> User:
    return service.get_user(db, user_id)


ExistingUser = Annotated[User, Depends(_existing_user)]


async def _update_body(request: Request, _user: ExistingUser) -> UserUpdate:
    """PATCH 본문. 사용자를 먼저 확인한 뒤 파싱한다.

    FastAPI 기본 방식은 의존성보다 먼저 JSON 을 파싱해서, 깨진 JSON 이면 사용자가 없어도 422 가 나간다.
    앱은 404 일 때 온보딩으로 이동하므로 404 USER_NOT_FOUND 를 먼저 응답하기 위해 직접 파싱한다.
    """
    try:
        payload = json.loads(await request.body())
    except ValueError:
        raise RequestValidationError(
            [{"type": "json_invalid", "loc": ("body",), "msg": "JSON 형식 오류", "input": None}]
        ) from None
    try:
        return UserUpdate.model_validate(payload)
    except ValidationError as e:
        raise RequestValidationError(e.errors(include_input=False)) from None


UpdateBody = Annotated[UserUpdate, Depends(_update_body)]


@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    response_model=UserResponse,
    summary="온보딩 (사용자 생성)",
    responses={422: ERROR_RESPONSES[422]},
)
def create_user(body: UserCreate, db: DB, today: Today) -> UserResponse:
    user = service.create_user(db, body, today)
    return service.to_response(user, today)


@router.get(
    "/{user_id}",
    response_model=UserResponse,
    summary="프로필 조회 (성장 단계 재계산)",
    responses={404: ERROR_RESPONSES[404]},
)
def get_user(user: ExistingUser, db: DB, today: Today) -> UserResponse:
    service.refresh_stage(db, user, today)
    return service.to_response(user, today)


@router.get(
    "/{user_id}/dashboard",
    response_model=DashboardResponse,
    summary="홈 대시보드",
    responses={404: ERROR_RESPONSES[404]},
)
def get_dashboard(user: ExistingUser, db: DB, today: Today) -> DashboardResponse:
    service.refresh_stage(db, user, today)  # 3.3 과 같이 stage 재계산
    return home.dashboard(db, user, today)


@router.patch(
    "/{user_id}",
    response_model=UserResponse,
    summary="회원정보 수정 (퇴소 처리, D-day 목표일 설정 등)",
    responses=ERROR_RESPONSES,
    # 본문을 직접 파싱하므로 /docs 에 요청 형식을 따로 알려준다.
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": UserUpdate.model_json_schema()}},
        }
    },
)
def update_user(user: ExistingUser, body: UpdateBody, db: DB, today: Today) -> UserResponse:
    service.update_user(db, user, body, today)
    return service.to_response(user, today)
