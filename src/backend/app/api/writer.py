from typing import Annotated, Literal

from db.models import Document, User
from fastapi import APIRouter, Depends, Response, status
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ValidationError

from app import doc_export
from app.api.deps import DB, BodyUser, JsonBody, Today, openapi_body, validated_body
from app.doc_llm import DocLlm, get_doc_llm
from app.rag_client import SearchPolicies, get_search_policies
from app.schemas.writer import (
    AssistRequest,
    AssistResponse,
    CheckResponse,
    DocumentCreate,
    DocumentList,
    DocumentResponse,
    ExportResponse,
    FieldResponse,
    FieldUpdate,
    UserOnly,
)
from app.services import writer as service
from app.services.users import get_user

router = APIRouter(tags=["문서 AI"])

NOT_FOUND = "USER_NOT_FOUND / DOCUMENT_NOT_FOUND"


def _parse[M: BaseModel](model: type[M], payload: object) -> M:
    try:
        return model.model_validate(payload)
    except ValidationError as e:
        raise RequestValidationError(e.errors(include_input=False)) from None


# 확인 순서: 사용자 → 문서 → 본문 (체크리스트 API 와 같은 원칙)
def _query_target(document_id: str, user_id: str, db: DB) -> tuple[User, Document]:
    user = get_user(db, user_id)
    return user, service.get_owned(db, user, document_id)


def _body_target(document_id: str, user: BodyUser, payload: JsonBody, db: DB) -> tuple[User, Document]:
    if user is None:  # user_id 가 없거나 문자열이 아님
        raise RequestValidationError([{"type": "missing", "loc": ("body", "user_id"), "msg": "user_id 필요"}])
    return user, service.get_owned(db, user, document_id)


QueryTarget = Annotated[tuple[User, Document], Depends(_query_target)]
BodyTarget = Annotated[tuple[User, Document], Depends(_body_target)]


@router.post(
    "/documents",
    response_model=DocumentResponse,
    summary="서류 작성 시작 (조회 또는 생성)",
    responses={
        201: {"description": "새로 생성", "model": DocumentResponse},
        404: {"description": "USER_NOT_FOUND / FORM_NOT_FOUND / POLICY_NOT_FOUND / FORM_GUIDE_NOT_FOUND / ITEM_NOT_FOUND"},
        422: {"description": "VALIDATION_ERROR"},
    },
    openapi_extra=openapi_body(DocumentCreate),
)
def create_document(
    body: Annotated[DocumentCreate, Depends(validated_body(DocumentCreate))],
    user: BodyUser,
    response: Response,
    db: DB,
) -> DocumentResponse:
    user = user or get_user(db, body.user_id)
    doc, created = service.create(db, user, body)
    response.status_code = status.HTTP_201_CREATED if created else status.HTTP_200_OK
    return service.to_response(db, doc)


@router.get(
    "/users/{user_id}/documents",
    response_model=DocumentList,
    summary="내 서류 목록",
    responses={404: {"description": "USER_NOT_FOUND"}},
)
def list_documents(user_id: str, db: DB) -> DocumentList:
    return service.list_documents(db, get_user(db, user_id))


@router.get(
    "/documents/{document_id}",
    response_model=DocumentResponse,
    summary="작성 중인 서류",
    responses={404: {"description": NOT_FOUND}},
)
def get_document(target: QueryTarget, db: DB) -> DocumentResponse:
    return service.to_response(db, target[1])


@router.patch(
    "/documents/{document_id}/fields/{field_id}",
    response_model=FieldResponse,
    summary="칸 수정·완료·다시 열기",
    responses={
        404: {"description": f"{NOT_FOUND} / FIELD_NOT_FOUND"},
        409: {"description": "FIELD_COMPLETED"},
        422: {"description": "VALIDATION_ERROR / FIELD_NOT_WRITABLE"},
    },
    openapi_extra=openapi_body(FieldUpdate),
)
def update_field(field_id: str, target: QueryTarget, payload: JsonBody, db: DB) -> FieldResponse:
    return service.update_field(db, target[1], field_id, _parse(FieldUpdate, payload))


@router.post(
    "/documents/{document_id}/fields/{field_id}/undo",
    response_model=FieldResponse,
    summary="칸 되돌리기 (1단계)",
    responses={
        404: {"description": f"{NOT_FOUND} / FIELD_NOT_FOUND"},
        409: {"description": "FIELD_COMPLETED / NOTHING_TO_UNDO"},
        422: {"description": "FIELD_NOT_WRITABLE"},
    },
)
def undo_field(field_id: str, target: QueryTarget, db: DB) -> FieldResponse:
    return service.undo(db, target[1], field_id)


@router.post(
    "/documents/{document_id}/assist",
    response_model=AssistResponse,
    summary="문서 작성 도우미 AI (선택한 칸에 바로 채움)",
    responses={
        404: {"description": f"{NOT_FOUND} / FIELD_NOT_FOUND"},
        409: {"description": "FIELD_COMPLETED"},
        422: {"description": "VALIDATION_ERROR / FIELD_NOT_WRITABLE / VALUE_TOO_LONG"},
        503: {"description": "AI_UNAVAILABLE / RAG_UNAVAILABLE"},
    },
    openapi_extra=openapi_body(AssistRequest),
)
def assist(
    target: BodyTarget,
    payload: JsonBody,
    db: DB,
    today: Today,
    doc_llm: Annotated[DocLlm, Depends(get_doc_llm)],
    rag: Annotated[SearchPolicies, Depends(get_search_policies)],
) -> AssistResponse:
    user, doc = target
    return service.assist(db, user, doc, _parse(AssistRequest, payload), today, doc_llm, rag)


@router.get(
    "/documents/{document_id}/check",
    response_model=CheckResponse,
    summary="내보내기 전 검사",
    responses={404: {"description": NOT_FOUND}},
)
def check_document(target: QueryTarget, db: DB) -> CheckResponse:
    user, doc = target
    return service.check(db, user, doc)


@router.post(
    "/documents/{document_id}/export",
    response_model=ExportResponse,
    summary="내보내기 (체크리스트 서류 항목 체크)",
    responses={
        404: {"description": NOT_FOUND},
        422: {"description": "VALIDATION_ERROR / EXPORT_NOT_READY"},
    },
    openapi_extra=openapi_body(UserOnly),
)
def export_document(target: BodyTarget, payload: JsonBody, db: DB) -> ExportResponse:
    user, doc = target
    _parse(UserOnly, payload)
    return service.export(db, user, doc)


MEDIA = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}


@router.get(
    "/documents/{document_id}/file",
    summary="내보낸 파일 받기 (PDF·DOCX)",
    response_class=Response,
    responses={
        200: {"content": {MEDIA["pdf"]: {}, MEDIA["docx"]: {}}},
        404: {"description": NOT_FOUND},
        409: {"description": "NOT_EXPORTED"},
        422: {"description": "VALIDATION_ERROR"},
    },
)
def download(target: QueryTarget, db: DB, format: Literal["pdf", "docx"] = "pdf") -> Response:
    _, doc = target
    rows = service.file_rows(db, doc)
    data = doc_export.render_pdf(doc.title, rows) if format == "pdf" else doc_export.render_docx(doc.title, rows)
    filename = f"document-{doc.id}.{format}"
    return Response(
        content=data,
        media_type=MEDIA[format],
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
