from typing import Annotated

from fastapi import APIRouter, Depends, Response, status
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError

from app.api.deps import DB, BodyUser, JsonBody, Today, openapi_body, validated_body
from app.schemas.checklists import ChecklistCreate, ChecklistResponse, ItemToggle, ToggleResponse
from app.services import checklists as service
from app.services.users import get_user
from db.models import Checklist, ChecklistItem

router = APIRouter(prefix="/checklists", tags=["체크리스트"])


@router.post(
    "",
    response_model=ChecklistResponse,
    summary="체크리스트 조회 또는 생성",
    responses={
        201: {"description": "새로 생성", "model": ChecklistResponse},
        404: {"description": "USER_NOT_FOUND / POLICY_NOT_FOUND"},
        422: {"description": "VALIDATION_ERROR"},
    },
    openapi_extra=openapi_body(ChecklistCreate),
)
def get_or_create(
    body: Annotated[ChecklistCreate, Depends(validated_body(ChecklistCreate))],
    user: BodyUser,
    response: Response,
    db: DB,
    today: Today,
) -> ChecklistResponse:
    user = user or get_user(db, body.user_id)
    checklist, created = service.get_or_create(db, user, body.policy_id)
    response.status_code = status.HTTP_201_CREATED if created else status.HTTP_200_OK
    return service.to_response(db, checklist, user, today)


@router.get(
    "/{checklist_id}",
    response_model=ChecklistResponse,
    summary="체크리스트 상세",
    responses={404: {"description": "USER_NOT_FOUND / CHECKLIST_NOT_FOUND"}},
)
def get_checklist(checklist_id: str, user_id: str, db: DB, today: Today) -> ChecklistResponse:
    user = get_user(db, user_id)
    return service.to_response(db, service.get_owned(db, user, checklist_id), user, today)


def _toggle_target(checklist_id: str, item_id: str, user_id: str, db: DB) -> tuple[Checklist, ChecklistItem]:
    # 확인 순서: 사용자 → 체크리스트 → 항목
    user = get_user(db, user_id)
    checklist = service.get_owned(db, user, checklist_id)
    return checklist, service.get_item(db, checklist, item_id)


ToggleTarget = Annotated[tuple[Checklist, ChecklistItem], Depends(_toggle_target)]


def _toggle_body(target: ToggleTarget, payload: JsonBody) -> ItemToggle:
    # target 을 먼저 선언해야 404 가 본문 422 (깨진 JSON 포함) 보다 먼저
    try:
        return ItemToggle.model_validate(payload)
    except ValidationError as e:
        raise RequestValidationError(e.errors(include_input=False)) from None


@router.patch(
    "/{checklist_id}/items/{item_id}",
    response_model=ToggleResponse,
    summary="항목 체크 토글",
    responses={
        404: {"description": "USER_NOT_FOUND / CHECKLIST_NOT_FOUND / ITEM_NOT_FOUND"},
        422: {"description": "VALIDATION_ERROR"},
    },
    openapi_extra=openapi_body(ItemToggle),
)
def toggle_item(target: ToggleTarget, body: Annotated[ItemToggle, Depends(_toggle_body)], db: DB) -> ToggleResponse:
    checklist, item = target
    return service.toggle(db, checklist, item, body.is_checked)
