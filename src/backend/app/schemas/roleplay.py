"""롤플레잉 스키마 (api.md 3.16~3.19, 부록)."""

from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator

from app.roleplay import Level, Stage
from app.schemas.chat import HISTORY_MAX, MESSAGE_MAX, HistoryTurn, strip_within

History = Annotated[list[HistoryTurn], Field(min_length=1, max_length=HISTORY_MAX)]


class ScenarioOut(BaseModel):
    scenario_id: str
    title: str
    category: str
    partner: str
    setting: str
    goal: str
    level: Level
    stages: list[Stage]


class ScenarioList(BaseModel):
    scenarios: list[ScenarioOut]


class Opening(BaseModel):
    scenario_id: str
    level: Level
    message: str


class ReplyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    stage: Stage | None = None
    message: str
    history: History

    @field_validator("message")
    @classmethod
    def _message(cls, v: str) -> str:
        return strip_within(v, MESSAGE_MAX, "message")


class ReplyResponse(BaseModel):
    reply: str
    is_finished: bool


class FeedbackRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    history: History
    save: StrictBool = False

    @field_validator("history")
    @classmethod
    def _has_user_turn(cls, v: list[HistoryTurn]) -> list[HistoryTurn]:
        if not any(h.role == "user" for h in v):
            raise ValueError("history 에 사용자 발화가 1개 이상 있어야 합니다.")
        return v


class BetterOut(BaseModel):
    before: str
    after: str


class FeedbackResponse(BaseModel):
    roleplay_id: int | None  # save: false 면 null
    good: list[str]
    missed: list[str]
    documents: list[str]
    better: list[BetterOut]
