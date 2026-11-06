"""저장한 정책 스키마"""

from datetime import datetime
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, StrictInt

from app.ids import BIGINT_MAX
from app.schemas.checklists import ChecklistPolicy


class SavedPolicyCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    policy_id: Annotated[StrictInt, Field(ge=1, le=BIGINT_MAX)]


class SavedPolicy(BaseModel):
    policy: ChecklistPolicy
    saved_at: datetime


class SavedPolicyList(BaseModel):
    count: int
    policies: list[SavedPolicy]
