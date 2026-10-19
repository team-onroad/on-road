"""체크리스트 스키마 (api.md 1.5, 3.9~3.11, 부록)."""

from datetime import date, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, model_validator

from app.codes import ITEM_TYPES, POLICY_CATEGORIES, STEP_KEYS
from app.ids import BIGINT_MAX
from app.schemas.policies import Eligibility, PolicySummary

StepKey = Literal[STEP_KEYS]


class ChecklistCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    policy_id: Annotated[StrictInt, Field(ge=1, le=BIGINT_MAX)]


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


class Document(BaseModel):
    item_id: int
    label: str
    short_label: str | None  # 조건까지 담은 짧은 이름. 요약이 없거나 노출 대상 아니면 null → label·condition 표시
    condition: str | None  # null: 모든 신청자에게 필요한 서류
    is_checked: bool
    is_not_applicable: bool
    checked_at: datetime | None


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
    progress: Progress
    document_progress: DocumentProgress
    steps: list[Step]


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
