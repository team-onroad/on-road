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


class ProfileChange(BaseModel):
    """대화에서 찾은 프로필 변경 후보 (app/profile_signal.py). 앱은 확인 카드를 띄우고,
    사용자가 승인하면 PATCH /users/{id} 에 {field: proposed} 를 보낸다. 자동으로 바꾸지 않는다"""

    field: Literal["region", "phone", "status", "d_date"]
    label: str  # 화면에 보일 항목 이름 (예: "사는 지역")
    current: str | None  # 지금 값 (보여 주기용)
    proposed: str  # PATCH 에 그대로 보낼 값 (region 코드, 01012345678, left_care, 2027-02-28)
    proposed_label: str  # 보여 주기용 새 값


class HandoffCandidate(BaseModel):
    """POST /documents 에 그대로 보낼 값 (source_type, source_id 또는 policy_id)"""

    source_type: Literal["template", "policy_form"]
    source_id: str | None  # template 일 때 양식 ID
    policy_id: int | None  # policy_form 일 때 정책 ID
    title: str


class Handoff(BaseModel):
    """상담 AI → 문서 AI. candidates 가 1개면 앱이 바로 문서 화면으로, 여러 개면 고르게 한다"""

    context: str  # POST /documents 의 context 로 그대로 보낸다 (문서 AI 가 처음부터 묻지 않게)
    candidates: list[HandoffCandidate]


class ChatResponse(BaseModel):
    type: Literal["policy", "counsel", "crisis", "document"]
    message: str | None  # policy 면 null
    search: SearchResponse | None  # counsel·crisis·document 면 null
    contacts: list[Contact]
    handoff: Handoff | None = None  # document 일 때만
    profile_changes: list[ProfileChange] = []  # 대화에서 찾은 프로필 변경 후보 (확인 카드, crisis 면 비움)
