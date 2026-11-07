import json
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response, status
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
from sqlalchemy.orm import Session

from app.api.deps import JsonBody
from app.clock import get_today
from app.schemas.home import DashboardResponse, GrowthResponse, RoleplayDetail
from app.schemas.readiness import ActivityCreate, ActivityCreated, ActivityKind, ActivityList, ReadinessResponse
from app.schemas.saved_policies import SavedPolicy, SavedPolicyCreate, SavedPolicyList
from app.schemas.users import UserCreate, UserResponse, UserUpdate
from app.services import home, readiness, saved_policies
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


def _saved_body(_user: ExistingUser, payload: JsonBody) -> SavedPolicyCreate:
    # _user 를 먼저 선언해야 404 가 본문 422 (깨진 JSON 포함) 보다 먼저
    try:
        return SavedPolicyCreate.model_validate(payload)
    except ValidationError as e:
        raise RequestValidationError(e.errors(include_input=False)) from None


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


@router.get(
    "/{user_id}/growth",
    response_model=GrowthResponse,
    summary="성장 기록",
    responses={404: ERROR_RESPONSES[404]},
)
def get_growth(user: ExistingUser, db: DB, today: Today) -> GrowthResponse:
    service.refresh_stage(db, user, today)  # 3.4 와 같이 stage 재계산
    return home.growth(db, user, today)


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


@router.post(
    "/{user_id}/activities",
    status_code=status.HTTP_201_CREATED,
    response_model=ActivityCreated,
    summary="활동 기록 (실전 연습 한 번 · 한 달 살아보기 한 번)",
    responses=ERROR_RESPONSES,
)
def post_activity(user: ExistingUser, body: ActivityCreate, db: DB) -> ActivityCreated:
    return ActivityCreated(activity_id=readiness.add_activity(db, user, body))


@router.get(
    "/{user_id}/activities",
    response_model=ActivityList,
    summary="활동 기록 목록 (실전 연습 · 한 달 살아보기)",
    responses=ERROR_RESPONSES,
)
def list_activities(user: ExistingUser, db: DB, kind: ActivityKind | None = None) -> ActivityList:
    return readiness.activities(db, user, kind)


@router.get(
    "/{user_id}/roleplays/{roleplay_id}",
    response_model=RoleplayDetail,
    summary="기록에 남긴 연습 결과",
    responses={404: {"description": "USER_NOT_FOUND / ROLEPLAY_NOT_FOUND"}},
)
def get_roleplay(user: ExistingUser, roleplay_id: str, db: DB) -> RoleplayDetail:
    return home.roleplay_detail(db, user, roleplay_id)


@router.get(
    "/{user_id}/readiness",
    response_model=ReadinessResponse,
    summary="자립 준비도 (6개 영역 점수와 다음 할 일, 지난주 점수, 최근 점수)",
    responses={404: ERROR_RESPONSES[404]},
)
def get_readiness(user: ExistingUser, db: DB, today: Today) -> ReadinessResponse:
    return readiness.readiness(db, user, today)


@router.get(
    "/{user_id}/saved-policies",
    response_model=SavedPolicyList,
    summary="저장한 정책 목록",
    responses={404: ERROR_RESPONSES[404]},
)
def list_saved_policies(user: ExistingUser, db: DB) -> SavedPolicyList:
    return saved_policies.saved_list(db, user)


@router.post(
    "/{user_id}/saved-policies",
    response_model=SavedPolicy,
    summary="정책 저장 (이미 저장했으면 그대로)",
    responses={
        201: {"description": "새로 저장", "model": SavedPolicy},
        404: {"description": "USER_NOT_FOUND / POLICY_NOT_FOUND"},
        422: ERROR_RESPONSES[422],
    },
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": SavedPolicyCreate.model_json_schema()}},
        }
    },
)
def save_policy(
    user: ExistingUser, body: Annotated[SavedPolicyCreate, Depends(_saved_body)], response: Response, db: DB
) -> SavedPolicy:
    item, created = saved_policies.save(db, user, body.policy_id)
    response.status_code = status.HTTP_201_CREATED if created else status.HTTP_200_OK
    return item


@router.delete(
    "/{user_id}/saved-policies/{policy_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="정책 저장 취소 (저장 안 한 정책이어도 성공)",
    responses={404: {"description": "USER_NOT_FOUND / POLICY_NOT_FOUND (policy_id 형식 오류)"}},
)
def unsave_policy(user: ExistingUser, policy_id: str, db: DB) -> Response:
    saved_policies.unsave(db, user, policy_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
