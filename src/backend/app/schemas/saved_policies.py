"""저장한 정책 스키마"""

from datetime import date, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt

from app.ids import BIGINT_MAX
from app.schemas.checklists import ChecklistPolicy

# saved: 관심 있어 저장 · receiving: 지금 받고 있음 (로드맵 3.32 에서 '받고 있어요')
SavedStatus = Literal["saved", "receiving"]


class SavedPolicyCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    policy_id: Annotated[StrictInt, Field(ge=1, le=BIGINT_MAX)]
    # 주면 이미 저장한 정책도 이 상태로 바꾼다. 안 주면 새로 저장할 때 saved, 이미 저장했으면 그대로
    status: SavedStatus | None = None
    # 받기 시작한 날 (receiving 일 때만 저장, saved 로 바꾸면 지운다)
    started_on: date | None = None


class SavedPolicy(BaseModel):
    policy: ChecklistPolicy
    saved_at: datetime
    status: SavedStatus
    started_on: date | None


class SavedPolicyList(BaseModel):
    count: int
    policies: list[SavedPolicy]
