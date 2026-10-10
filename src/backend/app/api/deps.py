"""라우터 공통 의존성.

본문에 user_id 가 있는 API(POST /search, POST /simulations)는 사용자 확인(404)을 본문 검증(422)보다
먼저 한다 (PATCH /users 와 같은 원칙). FastAPI 기본 방식은 본문을 먼저 검증하므로 본문을 직접 읽는다.
"""

import json
from datetime import date
from typing import Annotated

from fastapi import Depends, Request
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ValidationError
from sqlalchemy.orm import Session

from app.clock import get_today
from app.services.users import get_user
from db.models import User
from db.session import get_db

DB = Annotated[Session, Depends(get_db)]
Today = Annotated[date, Depends(get_today)]


async def _json_body(request: Request) -> object:
    try:
        return json.loads(await request.body())
    except ValueError:
        raise RequestValidationError(
            [{"type": "json_invalid", "loc": ("body",), "msg": "JSON 형식 오류", "input": None}]
        ) from None


JsonBody = Annotated[object, Depends(_json_body)]


def _body_user(payload: JsonBody, db: DB) -> User | None:
    if isinstance(payload, dict) and isinstance(payload.get("user_id"), str):
        return get_user(db, payload["user_id"])
    return None  # user_id 가 없거나 문자열이 아니면 본문 검증에서 422


BodyUser = Annotated[User | None, Depends(_body_user)]


def validated_body[M: BaseModel](model: type[M]):
    """사용자 확인 뒤에 본문을 model 로 검증하는 의존성을 만든다."""

    def _dep(payload: JsonBody, _user: BodyUser) -> M:
        try:
            return model.model_validate(payload)
        except ValidationError as e:
            raise RequestValidationError(e.errors(include_input=False)) from None

    return _dep


def openapi_body[M: BaseModel](model: type[M]) -> dict:
    """본문을 직접 파싱하는 API 의 /docs 요청 형식."""
    return {
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": model.model_json_schema()}},
        }
    }
