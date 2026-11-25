from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.deps import DB, BodyUser, Today, openapi_body, validated_body
from app.rag_client import SearchPolicies, get_search_policies
from app.schemas.policies import Category, EasyLevelCode, EasyText, FormGuide, PolicyDetail, SearchRequest, SearchResponse
from app.services import policies as service
from app.services.users import get_user

router = APIRouter(tags=["정책"])

Rag = Annotated[SearchPolicies, Depends(get_search_policies)]


@router.get(
    "/policies",
    response_model=SearchResponse,
    summary="분야별로 보기 (정책 목록)",
    description="내 연령 · 지역에 맞는 노출 정책을 분야(category)로 나눠 준다. 검색 · AI 를 쓰지 않는다. category 를 빼면 전체. "
    "형식은 /search 와 같고 evidences 는 비어 있다.",
    responses={404: {"description": "USER_NOT_FOUND"}, 422: {"description": "VALIDATION_ERROR (category 값 오류)"}},
)
def list_policies(user_id: str, db: DB, today: Today, category: Category | None = None) -> SearchResponse:
    return service.browse(db, get_user(db, user_id), today, category)


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
    detail = service.to_detail(policy, today, user, related=service.related_policies(db, policy, user, today))
    if user is not None:
        from app.services.roadmap import same_kind_for  # roadmap 이 policies 를 쓰므로 여기서 부른다
        detail.same_kind = same_kind_for(db, user, policy.id, today)
    return detail


@router.get(
    "/policies/{policy_id}/easy-text",
    response_model=EasyText,
    summary="쉬운 말 단계별 보기",
    responses={
        404: {"description": "POLICY_NOT_FOUND / USER_NOT_FOUND / EASY_TEXT_NOT_FOUND"},
        422: {"description": "VALIDATION_ERROR (level 값 오류)"},
    },
)
def get_easy_text(
    policy_id: str,
    db: DB,
    today: Today,
    user_id: str | None = None,
    level: EasyLevelCode | None = None,
) -> EasyText:
    policy = service.get_visible_policy(db, policy_id)
    user = get_user(db, user_id) if user_id is not None else None
    return service.easy_text(policy, today, user, level)


@router.get(
    "/policies/{policy_id}/form-guide",
    response_model=FormGuide,
    summary="신청서 작성 도우미",
    responses={404: {"description": "POLICY_NOT_FOUND / USER_NOT_FOUND / FORM_GUIDE_NOT_FOUND"}},
)
def get_form_guide(policy_id: str, db: DB, user_id: str | None = None) -> FormGuide:
    policy = service.get_visible_policy(db, policy_id)
    user = get_user(db, user_id) if user_id is not None else None
    return service.form_guide(policy, user)


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
    return service.search(db, user, body.question, today, rag, body.answer)
