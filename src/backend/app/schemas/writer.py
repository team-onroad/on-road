"""문서 AI 스키마 (api.md 3.25~3.31)."""

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, StrictStr, field_validator, model_validator

from app.schemas.chat import Contact
from app.schemas.policies import SearchResponse

PROMPT_MAX = 500
CONTEXT_MAX = 500
VALUE_MAX = 3000
SEGMENTS_MAX = 50
INT_MAX = 2**63 - 1

SourceType = Literal["template", "policy_form"]
SegmentSource = Literal["user", "ai", "profile", "reused"]
ValueSource = Literal["profile", "user", "handwrite", "office"]
Action = Literal["tip", "outline", "draft", "revise"]
Route = Literal["field", "policy", "counsel", "crisis"]


# ---------------- 요청 ----------------


class DocumentCreate(BaseModel):
    """source_type=template 이면 source_id(양식 ID) 필수, policy_form 이면 policy_id 필수"""

    model_config = ConfigDict(extra="forbid")

    user_id: str
    source_type: SourceType
    source_id: Annotated[StrictStr, Field(min_length=1, max_length=100)] | None = None
    policy_id: Annotated[StrictInt, Field(ge=1, le=INT_MAX)] | None = None
    checklist_item_id: Annotated[StrictInt, Field(ge=1, le=INT_MAX)] | None = None
    context: str | None = None

    @field_validator("context")
    @classmethod
    def _context(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.strip()
        if len(v) > CONTEXT_MAX:
            raise ValueError(f"context 는 {CONTEXT_MAX}자 이하여야 합니다.")
        return v or None

    @model_validator(mode="after")
    def _source(self) -> "DocumentCreate":
        if self.source_type == "template" and self.source_id is None:
            raise ValueError("template 은 source_id 가 필요합니다.")
        if self.source_type == "policy_form" and self.policy_id is None:
            raise ValueError("policy_form 은 policy_id 가 필요합니다.")
        return self


class Segment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: StrictStr
    source: SegmentSource


class FieldUpdate(BaseModel):
    """value(전부 사용자 글) 또는 segments(앱이 AI 글 구간을 유지) 중 하나, 그리고/또는 is_completed"""

    model_config = ConfigDict(extra="forbid")

    value: Annotated[StrictStr, Field(max_length=VALUE_MAX)] = None
    segments: Annotated[list[Segment], Field(max_length=SEGMENTS_MAX)] = None
    is_completed: StrictBool = None

    @model_validator(mode="after")
    def _one(self) -> "FieldUpdate":
        sent = self.model_fields_set
        if not sent:
            raise ValueError("value, segments, is_completed 중 하나 이상 보내야 합니다.")
        if {"value", "segments"} <= sent:
            raise ValueError("value 와 segments 는 함께 보낼 수 없습니다.")
        if "segments" in sent and sum(len(s.text) for s in self.segments) > VALUE_MAX:
            raise ValueError(f"내용은 {VALUE_MAX}자 이하여야 합니다.")
        return self


class AssistRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    field_id: Annotated[StrictStr, Field(min_length=1, max_length=100)]
    prompt: str
    mode: Literal["append", "replace"] = "append"

    @field_validator("prompt")
    @classmethod
    def _prompt(cls, v: str) -> str:
        v = v.strip()
        if not 1 <= len(v) <= PROMPT_MAX:
            raise ValueError(f"prompt 는 공백을 제외하고 1~{PROMPT_MAX}자여야 합니다.")
        return v


class InterviewRequest(BaseModel):
    """message 가 없으면 지금 물어볼 칸을 돌려준다 (시작·이어 하기)"""

    model_config = ConfigDict(extra="forbid")

    user_id: str
    message: str | None = None

    @field_validator("message")
    @classmethod
    def _message(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.strip()
        if not 1 <= len(v) <= PROMPT_MAX:
            raise ValueError(f"message 는 공백을 제외하고 1~{PROMPT_MAX}자여야 합니다.")
        return v


class UserOnly(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str


# ---------------- 응답 ----------------


class DocField(BaseModel):
    field_id: str
    label: str
    easy_label: str | None
    section: str | None
    guide: str
    caution: str | None
    example: str | None
    outline: list[str] | None
    multiline: bool
    essay: bool
    required: bool | None
    input_type: str
    options: list[str] | None  # 체크박스 칸 선택지. 앱은 고르게 하고 고른 것을 value 로 보낸다 (여러 개는 ", ")
    value_source: ValueSource
    writable: bool  # false: handwrite·office (앱에서 입력·AI 도우미 없음, 진행률에서 제외)
    value: str
    segments: list[Segment]
    tip: str | None
    reused_from: str | None
    is_completed: bool
    completed_at: datetime | None
    can_undo: bool
    warnings: list[str]  # 남은 [대괄호 빈칸], 형식 오류 등. 완료·내보내기 전 표시


class DocProgress(BaseModel):
    completed: int
    total: int


class DocumentResponse(BaseModel):
    document_id: int
    title: str
    source_type: SourceType
    source_id: str
    policy_id: int | None
    checklist_item_id: int | None
    status: Literal["writing", "exported"]
    context: str | None
    progress: DocProgress
    next_field_id: str | None  # 아직 완료하지 않은 첫 쓰기 칸
    fields: list[DocField]
    notices: list[str]
    exported_at: datetime | None
    created_at: datetime
    updated_at: datetime


class DocumentSummary(BaseModel):
    document_id: int
    title: str
    source_type: SourceType
    source_id: str
    policy_id: int | None
    checklist_item_id: int | None  # 연결된 체크리스트 서류 항목
    status: Literal["writing", "exported"]
    progress: DocProgress
    updated_at: datetime


class DocumentList(BaseModel):
    documents: list[DocumentSummary]


class FieldResponse(BaseModel):
    field: DocField
    progress: DocProgress
    next_field_id: str | None
    status: Literal["writing", "exported"]


class AssistResponse(BaseModel):
    route: Route
    action: Action | None  # route=field 일 때만
    field: DocField | None  # route=field 일 때 바뀐 칸
    progress: DocProgress | None
    message: str | None  # counsel·crisis 안내
    search: SearchResponse | None  # route=policy 일 때 상담 AI 정책 검색 결과
    contacts: list[Contact]


class Question(BaseModel):
    field_id: str
    label: str
    text: str  # 앱이 말풍선으로 보여 줄 질문
    input_type: str
    options: list[str] | None  # 체크박스면 선택지 버튼
    current_value: str | None  # 미리 채운 값·AI 초안 (확인 질문일 때)
    awaiting_confirm: bool  # "네"면 이 값으로 완료
    can_skip: bool  # 필수가 아니면 "해당 없음"으로 넘길 수 있다


class Problem(BaseModel):
    field_id: str
    label: str
    reason: Literal["not_completed", "required_empty", "placeholder_left", "invalid_format"]
    message: str


class CheckResponse(BaseModel):
    ready: bool
    problems: list[Problem]  # 있으면 내보낼 수 없음
    warnings: list[str]  # 내보낼 수는 있음 (다른 서류와 다른 값 등)


class ChecklistUpdate(BaseModel):
    checklist_id: int
    item_id: int
    label: str
    is_checked: bool


class ExportResponse(BaseModel):
    document: DocumentResponse
    checklist: ChecklistUpdate | None  # 연결된 체크리스트 서류 항목을 체크했으면
    warnings: list[str]
    files: dict[str, str]  # {"pdf": 다운로드 경로, "docx": 다운로드 경로}
    pdf_layout: Literal["original", "table"]  # original: 원본 양식 위에 채움 (넘치는 칸은 별지), table: 표 문서


class InterviewResponse(BaseModel):
    route: Literal["field", "policy", "counsel", "crisis"]
    message: str | None  # 방금 답에 대한 짧은 말 (예: "적었어요."), 정책·고민·위기 안내
    field: DocField | None  # 방금 채우거나 완료한 칸
    question: Question | None  # 다음에 물어볼 칸. null 이면 다 채움
    progress: DocProgress
    done: bool
    export: ExportResponse | None  # 다 채우고 검사를 통과하면 바로 내보낸 결과 (PDF 경로·체크리스트)
    check: CheckResponse | None  # 다 채웠는데 검사에 걸리면 문제 목록
    search: SearchResponse | None
    contacts: list[Contact]
