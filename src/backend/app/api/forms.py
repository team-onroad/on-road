from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError

from app.api.deps import DB, JsonBody, openapi_body
from app.forms import Stage
from app.schemas.forms import DocumentRequest, DocumentResponse, TemplateDetail, TemplateList
from app.services import documents as service
from app.services.users import get_user
from db.models import User

router = APIRouter(tags=["서류 도우미"])


def _path_user(user_id: str, db: DB) -> User:
    return get_user(db, user_id)


PathUser = Annotated[User, Depends(_path_user)]


def _document_body(_user: PathUser, payload: JsonBody) -> DocumentRequest:
    # 사용자 확인(404) 뒤에 본문 파싱
    try:
        return DocumentRequest.model_validate(payload)
    except ValidationError as e:
        raise RequestValidationError(e.errors(include_input=False)) from None


@router.get(
    "/forms/templates",
    response_model=TemplateList,
    summary="서류 양식 목록",
    responses={422: {"description": "VALIDATION_ERROR"}},
)
def list_templates(stage: Stage | None = None) -> TemplateList:
    return service.template_list(stage)


@router.get(
    "/forms/templates/{template_id}",
    response_model=TemplateDetail,
    summary="양식 항목",
    responses={404: {"description": "FORM_NOT_FOUND"}},
)
def get_template(template_id: str) -> TemplateDetail:
    return service.template_detail(template_id)


@router.post(
    "/users/{user_id}/documents",
    response_model=DocumentResponse,
    summary="이력서·자기소개서·활동 기록 초안",
    responses={
        404: {"description": "USER_NOT_FOUND (user_id 형식 오류 포함)"},
        422: {"description": "VALIDATION_ERROR"},
    },
    openapi_extra=openapi_body(DocumentRequest),
)
def create_document(
    user: PathUser,
    body: Annotated[DocumentRequest, Depends(_document_body)],
    db: DB,
) -> DocumentResponse:
    return service.generate(db, user, body)
