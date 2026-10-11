"""사용자 요청·응답 스키마 (docs/api.md 3.2, 3.3, 3.12)."""

import re
import uuid
from datetime import date, datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, field_validator, model_validator

from app.codes import USER_REGIONS, USER_STAGES, USER_STATUSES

Region = Literal[USER_REGIONS]
Status = Literal[USER_STATUSES]
Stage = Literal[USER_STAGES]

_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_PHONE_RE = re.compile(r"^01[0-9]{8,9}$")
NAME_MAX = 50


def _date_string_only(v: Any) -> Any:
    # 날짜는 YYYY-MM-DD 문자열만 받는다 (숫자 타임스탬프·시각 포함 문자열 거부)
    if v is None:
        return v
    if not isinstance(v, str) or not _DATE_RE.match(v):
        raise ValueError("날짜는 YYYY-MM-DD 형식이어야 합니다.")
    return v


def _normalize_name(v: str | None) -> str | None:
    if v is None:
        return v
    v = v.strip()
    if not 1 <= len(v) <= NAME_MAX:
        raise ValueError(f"이름은 공백을 제외하고 1~{NAME_MAX}자여야 합니다.")
    return v


def _empty_phone_to_none(v: Any) -> Any:
    return None if v == "" else v


def _check_phone(v: str | None) -> str | None:
    if v is not None and not _PHONE_RE.match(v):
        raise ValueError("휴대폰 번호는 하이픈 없이 01로 시작하는 숫자 10~11자리여야 합니다.")
    return v


class UserCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    birth_date: date
    phone: str | None = None
    region: Region
    status: Status
    d_date: date | None = None

    _dates = field_validator("birth_date", "d_date", mode="before")(_date_string_only)
    _phone_empty = field_validator("phone", mode="before")(_empty_phone_to_none)
    _phone = field_validator("phone")(_check_phone)
    _name = field_validator("name")(_normalize_name)


class UserUpdate(BaseModel):
    """보낸 필드만 바꾼다. phone, d_date 는 null 이면 지우고, 나머지는 null 불가."""

    model_config = ConfigDict(extra="forbid")

    name: str | None = None
    birth_date: date | None = None
    phone: str | None = None
    region: Region | None = None
    status: Status | None = None
    d_date: date | None = None

    _dates = field_validator("birth_date", "d_date", mode="before")(_date_string_only)
    _phone_empty = field_validator("phone", mode="before")(_empty_phone_to_none)
    _phone = field_validator("phone")(_check_phone)
    _name = field_validator("name")(_normalize_name)

    @field_validator("name", "birth_date", "region", "status")
    @classmethod
    def _not_null(cls, v: Any) -> Any:
        # 기본값(None)은 검증하지 않으므로 이 검사는 요청에 null 을 직접 보낸 경우에만 걸린다.
        if v is None:
            raise ValueError("null 로 보낼 수 없는 필드입니다.")
        return v

    @model_validator(mode="after")
    def _has_any_field(self) -> "UserUpdate":
        if not self.model_fields_set:
            raise ValueError("바꿀 필드가 없습니다.")
        return self


class UserResponse(BaseModel):
    user_id: uuid.UUID
    name: str
    birth_date: date
    age: int
    phone: str | None
    region: Region
    status: Status
    stage: Stage
    d_date: date | None
    d_day: int | None
    created_at: datetime
    updated_at: datetime
