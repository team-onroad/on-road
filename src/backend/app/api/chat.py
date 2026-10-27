from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.deps import DB, BodyUser, Today, openapi_body, validated_body
from app.doc_llm import DocLlm, get_doc_llm
from app.llm_client import Llm, get_llm
from app.rag_client import SearchPolicies, get_search_policies
from app.schemas.chat import ChatRequest, ChatResponse
from app.services import chat as service
from app.services.users import get_user

router = APIRouter(tags=["AI 상담"])


@router.post(
    "/chat",
    response_model=ChatResponse,
    summary="AI 상담 채팅",
    responses={
        404: {"description": "USER_NOT_FOUND (user_id 형식 오류 포함)"},
        422: {"description": "VALIDATION_ERROR"},
        503: {"description": "AI_UNAVAILABLE / RAG_UNAVAILABLE"},
    },
    openapi_extra=openapi_body(ChatRequest),
)
def chat(
    body: Annotated[ChatRequest, Depends(validated_body(ChatRequest))],
    user: BodyUser,
    db: DB,
    today: Today,
    llm: Annotated[Llm, Depends(get_llm)],
    rag: Annotated[SearchPolicies, Depends(get_search_policies)],
    doc_llm: Annotated[DocLlm, Depends(get_doc_llm)],
) -> ChatResponse:
    user = user or get_user(db, body.user_id)
    return service.chat(db, user, body, today, llm, rag, doc_llm)
