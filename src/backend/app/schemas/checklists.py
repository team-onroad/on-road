"""체크리스트 스키마 (api.md 1.5, 3.9~3.11, 부록)."""

from datetime import date, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt

from app.codes import ITEM_TYPES, POLICY_CATEGORIES, STEP_KEYS
from app.ids import BIGINT_MAX
from app.schemas.policies import Eligibility

StepKey = Literal[STEP_KEYS]


class ChecklistCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    policy_id: Annotated[StrictInt, Field(ge=1, le=BIGINT_MAX)]


class ItemToggle(BaseModel):
    model_config = ConfigDict(extra="forbid")

    is_checked: StrictBool


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


# detail 은 단계마다 키가 다름 (부록 3.9·3.10)
class TargetDetail(BaseModel):
    target_description: str
    easy_text: str | None  # 노출 대상 아니면 null


class ConditionDetail(BaseModel):
    target_description: str
    eligibility: Eligibility


class ApplyDetail(BaseModel):
    apply_method: str
    apply_url: str | None


class Document(BaseModel):
    item_id: int
    label: str
    is_checked: bool
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
    is_checked: bool
    checked_at: datetime | None


class ToggleResponse(BaseModel):
    item: ToggledItem
    progress: Progress
    document_progress: DocumentProgress
