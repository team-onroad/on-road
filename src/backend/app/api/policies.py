import json
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
from sqlalchemy.orm import Session

from app.clock import get_today
from app.rag_client import SearchPolicies, get_search_policies
from app.schemas.policies import PolicyDetail, SearchRequest, SearchResponse
from app.services import policies as service
from app.services.users import get_user
from db.models import User
from db.session import get_db

router = APIRouter(tags=["정책"])

DB = Annotated[Session, Depends(get_db)]
Today = Annotated[date, Depends(get_today)]
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


async def _json_body(request: Request) -> object:
    try:
        return json.loads(await request.body())
    except ValueError:
        raise RequestValidationError(
            [{"type": "json_invalid", "loc": ("body",), "msg": "JSON 형식 오류", "input": None}]
        ) from None


JsonBody = Annotated[object, Depends(_json_body)]


def _search_user(payload: JsonBody, db: DB) -> User | None:
    # 사용자 확인(404)을 질문 검증(422)보다 먼저 한다 (PATCH /users 와 같은 원칙).
    if isinstance(payload, dict) and isinstance(payload.get("user_id"), str):
        return get_user(db, payload["user_id"])
    return None  # user_id 가 없거나 문자열이 아니면 아래 본문 검증에서 422


def _search_body(payload: JsonBody, _user: Annotated[User | None, Depends(_search_user)]) -> SearchRequest:
    try:
        return SearchRequest.model_validate(payload)
    except ValidationError as e:
        raise RequestValidationError(e.errors(include_input=False)) from None


@router.post(
    "/search",
    response_model=SearchResponse,
    summary="정책 검색 (상담 화면)",
    responses={
        404: {"description": "USER_NOT_FOUND (user_id 형식 오류 포함)"},
        422: {"description": "VALIDATION_ERROR"},
        503: {"description": "RAG_UNAVAILABLE"},
    },
    # 본문을 직접 파싱하므로 /docs 에 요청 형식을 따로 알려준다.
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": SearchRequest.model_json_schema()}},
        }
    },
)
def search(
    body: Annotated[SearchRequest, Depends(_search_body)],
    user: Annotated[User | None, Depends(_search_user)],
    db: DB,
    today: Today,
    rag: Rag,
) -> SearchResponse:
    # 본문 검증을 통과했으면 user_id 는 문자열이라 _search_user 에서 이미 조회됐다.
    user = user or get_user(db, body.user_id)
    return service.search(db, user, body.question, today, rag)
