"""체크리스트 스키마 (api.md 1.5, 3.9~3.11, 부록)."""

from datetime import date, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, field_validator, model_validator

from app.codes import ITEM_TYPES, POLICY_CATEGORIES, STEP_KEYS
from app.ids import BIGINT_MAX
from app.schemas.policies import Eligibility, PolicySummary
from app.schemas.users import _date_string_only

StepKey = Literal[STEP_KEYS]


class ChecklistCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    policy_id: Annotated[StrictInt, Field(ge=1, le=BIGINT_MAX)]


class DueDateUpdate(BaseModel):
    """null 이면 지움. 필드 자체가 없으면 422"""

    model_config = ConfigDict(extra="forbid")

    due_date: date | None

    _date = field_validator("due_date", mode="before")(_date_string_only)


class ItemToggle(BaseModel):
    """is_checked, is_not_applicable 중 하나만 (api.md 3.11)"""

    model_config = ConfigDict(extra="forbid")

    # 기본값 None 은 빠졌다는 뜻. 직접 보낸 null 은 StrictBool 검사로 422
    is_checked: StrictBool = None
    is_not_applicable: StrictBool = None

    @model_validator(mode="after")
    def _one_field(self) -> "ItemToggle":
        if len(self.model_fields_set) != 1:
            raise ValueError("is_checked, is_not_applicable 중 하나만 보내야 합니다.")
        return self


class ChecklistPolicy(BaseModel):
    policy_id: int
    name: str
    agency: str
    category: Literal[POLICY_CATEGORIES]
    contact: str | None
    apply_url: str | None
    checked_at: date
    is_active: bool  # 노출 여부 (is_active AND easy_text_verified)


class Progress(BaseModel):
    checked_steps: int
    total_steps: int
    percent: int


class DocumentProgress(BaseModel):
    checked: int
    total: int


class NextStep(BaseModel):
    step_key: StepKey
    label: str


# detail 은 단계마다 키가 다름 (부록 3.9·3.10)
class TargetDetail(BaseModel):
    target_description: str
    easy_text: str | None  # 노출 대상 아니면 null
    summary: PolicySummary | None  # 짧은 요약 (대상·제외). 요약이 없거나 노출 대상 아니면 null


class ConditionDetail(BaseModel):
    target_description: str
    eligibility: Eligibility
    summary: PolicySummary | None  # 짧은 요약 (대상·제외). 요약이 없거나 노출 대상 아니면 null


class ApplyDetail(BaseModel):
    apply_method: str
    apply_url: str | None
    summary: PolicySummary | None  # 짧은 요약 (신청 방법). 요약이 없거나 노출 대상 아니면 null


class FormLink(BaseModel):
    """그 서류를 앱에서 바로 쓸 수 있는 서버 양식 (data/forms/index.json). POST /documents 에 form_id 로 시작"""

    form_id: str
    title: str
    checked: bool  # 사람이 원본 양식과 대조했는지. false 면 "원본과 대조하지 않은 안내" 표시
    notice: str | None = None  # 공식 양식 대신 쓰는 공통 양식이면 안내 (자립정착금 신청서 → 사회보장급여 신청서)


class Document(BaseModel):
    item_id: int
    label: str
    short_label: str | None  # 조건까지 담은 짧은 이름. 요약이 없거나 노출 대상 아니면 null → label·condition 표시
    condition: str | None  # null: 모든 신청자에게 필요한 서류
    is_checked: bool
    is_not_applicable: bool
    checked_at: datetime | None
    # 문서 AI 로 쓰는 서류면 그 문서 (POST /documents 의 checklist_item_id 로 연결).
    # document_status 가 exported 면 체크가 문서를 따르고, 이 항목은 직접 바꿀 수 없다 (409 DOCUMENT_WRITTEN)
    document_id: int | None = None
    document_status: Literal["writing", "exported"] | None = None
    forms: list[FormLink] = []  # 이 서류를 바로 쓸 수 있는 양식 (서류 이름이 연결표와 글자 그대로 같을 때)


class Step(BaseModel):
    item_id: int
    step_key: StepKey
    label: str
    is_checked: bool
    checked_at: datetime | None
    detail: TargetDetail | ConditionDetail | ApplyDetail | None  # doc_prepare 는 null
    documents: list[Document]


class ChecklistResponse(BaseModel):
    checklist_id: int
    policy: ChecklistPolicy
    due_date: date | None
    progress: Progress
    document_progress: DocumentProgress
    steps: list[Step]
    # 서류 목록에는 없지만 이 정책에 쓰는 양식 (원문에 서류가 따로 안 적혔거나 참여 뒤 내는 서류)
    extra_forms: list[FormLink] = []


class ToggledItem(BaseModel):
    item_id: int
    item_type: Literal[ITEM_TYPES]
    step_key: StepKey
    label: str
    condition: str | None
    is_checked: bool
    is_not_applicable: bool
    checked_at: datetime | None


class ToggleResponse(BaseModel):
    item: ToggledItem
    progress: Progress
    document_progress: DocumentProgress
