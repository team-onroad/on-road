"""롤플레잉 스키마 (api.md 3.16~3.19, 부록)."""

from typing import Annotated, Literal

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
    # 이번 연습의 전개·돌발 상황. 앱은 reply·feedback 에 그대로 보낸다 (서버는 저장하지 않음)
    variant_id: str | None = None
    situation: str | None = None  # 앱에 보여 줄 이번 상황 이름 (기본 상황이면 null)
    surprise_ids: list[str] = []
    surprise_count: int = 0  # 돌발 상황 수 (내용은 미리 알려 주지 않음)


IdList = Annotated[list[Annotated[str, Field(max_length=40)]], Field(max_length=5)]


class ReplyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    stage: Stage | None = None
    message: str
    history: History
    variant_id: Annotated[str, Field(max_length=40)] | None = None
    surprise_ids: IdList = []

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
    stage: Stage | None = None
    variant_id: Annotated[str, Field(max_length=40)] | None = None
    surprise_ids: IdList = []

    @field_validator("history")
    @classmethod
    def _has_user_turn(cls, v: list[HistoryTurn]) -> list[HistoryTurn]:
        if not any(h.role == "user" for h in v):
            raise ValueError("history 에 사용자 발화가 1개 이상 있어야 합니다.")
        return v


class BetterOut(BaseModel):
    before: str
    after: str


class FeedbackItemOut(BaseModel):
    label: str
    ok: bool
    kind: Literal["basic", "situation", "surprise"]  # 기본 · 이번 전개 · 돌발 상황
    example: str | None  # 놓친 항목이면 "이렇게 말해 보세요"


class FeedbackResponse(BaseModel):
    roleplay_id: int | None  # save: false 면 null
    good: list[str]
    missed: list[str]
    documents: list[str]
    better: list[BetterOut]
    score: int = 0  # 0~100 (돌발 상황 대처는 2배)
    grade: str = ""
    items: list[FeedbackItemOut] = []
    situation: str | None = None
    retry_lines: list[str] = []  # 다시 해 볼 때 써 볼 문장 (놓친 항목의 예시)
