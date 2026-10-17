from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.deps import DB, BodyUser, Today, openapi_body, validated_body
from app.rag_client import SearchPolicies, get_search_policies
from app.schemas.policies import PolicyDetail, SearchRequest, SearchResponse
from app.services import policies as service
from app.services.users import get_user

router = APIRouter(tags=["정책"])

Rag = Annotated[SearchPolicies, Depends(get_search_policies)]


@router.get(
    "/policies/{policy_id}",
    response_model=PolicyDetail,
    summary="정책 상세",
    responses={404: {"description": "POLICY_NOT_FOUND / USER_NOT_FOUND"}},
)
def get_policy(
    policy_id: str,
    db: DB,
    today: Today,
    user_id: str | None = None,
) -> PolicyDetail:
    policy = service.get_visible_policy(db, policy_id)  # 정책 404 를 먼저 확인
    user = get_user(db, user_id) if user_id is not None else None
    return service.to_detail(policy, today, user)


@router.post(
    "/search",
    response_model=SearchResponse,
    summary="정책 검색 (상담 화면)",
    responses={
        404: {"description": "USER_NOT_FOUND (user_id 형식 오류 포함)"},
        422: {"description": "VALIDATION_ERROR"},
        503: {"description": "RAG_UNAVAILABLE"},
    },
    openapi_extra=openapi_body(SearchRequest),
)
def search(
    body: Annotated[SearchRequest, Depends(validated_body(SearchRequest))],
    user: BodyUser,
    db: DB,
    today: Today,
    rag: Rag,
) -> SearchResponse:
    # 본문 검증을 통과했으면 user_id 는 문자열이라 BodyUser 에서 이미 조회됐다.
    user = user or get_user(db, body.user_id)
    return service.search(db, user, body.question, today, rag)
