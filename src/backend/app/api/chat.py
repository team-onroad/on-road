from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app import sse
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


@router.post(
    "/chat/stream",
    summary="AI 상담 채팅 (스트리밍)",
    description=(
        "POST /chat 과 같은 요청. 응답은 text/event-stream: 고민 상담이면 delta {text} 가 문장마다 오고 "
        "마지막에 done (POST /chat 응답과 같은 모양 + replace). 위기 · 서류 넘기기 · 정책 검색은 done 하나. "
        "답을 만들다 실패하면 error {error: {code, message}}."
    ),
    response_class=StreamingResponse,
    responses={
        200: {"content": {sse.MEDIA_TYPE: {}}},
        404: {"description": "USER_NOT_FOUND (user_id 형식 오류 포함)"},
        422: {"description": "VALIDATION_ERROR"},
        503: {"description": "AI_UNAVAILABLE / RAG_UNAVAILABLE (상담 답을 만들기 전)"},
    },
    openapi_extra=openapi_body(ChatRequest),
)
def chat_stream(
    body: Annotated[ChatRequest, Depends(validated_body(ChatRequest))],
    user: BodyUser,
    db: DB,
    today: Today,
    llm: Annotated[Llm, Depends(get_llm)],
    rag: Annotated[SearchPolicies, Depends(get_search_policies)],
    doc_llm: Annotated[DocLlm, Depends(get_doc_llm)],
) -> StreamingResponse:
    user = user or get_user(db, body.user_id)
    return sse.response(service.chat_stream(db, user, body, today, llm, rag, doc_llm))
