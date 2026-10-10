"""AI 상담 채팅 스키마 (api.md 1.6, 3.15, 부록)."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.policies import SearchResponse

MESSAGE_MAX = 500
HISTORY_MAX = 20
CONTENT_MAX = 1000


def strip_within(v: str, max_len: int, field: str) -> str:
    v = v.strip()
    if not 1 <= len(v) <= max_len:
        raise ValueError(f"{field}은 공백을 제외하고 1~{max_len}자여야 합니다.")
    return v


class HistoryTurn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["user", "assistant"]
    content: str

    @field_validator("content")
    @classmethod
    def _content(cls, v: str) -> str:
        return strip_within(v, CONTENT_MAX, "content")


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    message: str
    history: Annotated[list[HistoryTurn], Field(max_length=HISTORY_MAX)] = []

    @field_validator("history", mode="before")
    @classmethod
    def _history_null(cls, v: object) -> object:
        return [] if v is None else v

    @field_validator("message")
    @classmethod
    def _message(cls, v: str) -> str:
        return strip_within(v, MESSAGE_MAX, "message")


class Contact(BaseModel):
    name: str
    phone: str


class ChatResponse(BaseModel):
    type: Literal["policy", "counsel", "crisis"]
    message: str | None  # policy 면 null
    search: SearchResponse | None  # counsel·crisis 면 null
    contacts: list[Contact]
