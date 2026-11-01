"""활동 기록 · 자립 준비도 (docs/readiness.md)"""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, ValidationError, model_validator

from app.schemas.roleplay import NextStepOut

Count = Annotated[StrictInt, Field(ge=0, le=100)]
Money = Annotated[StrictInt, Field(ge=-10000, le=10000)]  # 만원
EventId = Annotated[StrictStr, Field(min_length=1, max_length=30)]


class PracticeData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    good: Count
    missed: Count
    score: Annotated[StrictInt, Field(ge=0, le=100)] | None = None


class MonthLifeData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    income: Annotated[StrictInt, Field(ge=0, le=10000)]
    left: Money
    shortage: Annotated[StrictInt, Field(ge=0, le=10000)]
    used_emergency: Annotated[StrictInt, Field(ge=0, le=10000)]
    home: Literal["STUDIO", "PUBLIC", "SHARE"]
    good: Count
    bad: Count
    bad_events: Annotated[list[EventId], Field(max_length=40)] = []


class ActivityCreate(BaseModel):
    """practice: ref = 연습 상황 id (scenario_id), data = PracticeData
    month_life: ref 없음, data = MonthLifeData"""

    model_config = ConfigDict(extra="forbid")

    kind: Literal["practice", "month_life"]
    ref: Annotated[StrictStr, Field(min_length=1, max_length=50)] | None = None
    data: dict

    @model_validator(mode="after")
    def _check(self) -> "ActivityCreate":
        if self.kind == "practice" and not self.ref:
            raise ValueError("practice 는 ref(연습 상황 id)가 필요해요")
        if self.kind == "month_life" and self.ref is not None:
            raise ValueError("month_life 는 ref 를 쓰지 않아요")
        model = PracticeData if self.kind == "practice" else MonthLifeData
        try:
            self.data = model.model_validate(self.data).model_dump()
        except ValidationError as e:
            fields = ", ".join(".".join(str(x) for x in err["loc"]) for err in e.errors())
            raise ValueError(f"data 형식 오류: {fields}") from None
        return self


class ActivityCreated(BaseModel):
    activity_id: int


class ReadinessItem(BaseModel):
    key: str
    label: str
    done: bool


class ReadinessArea(BaseModel):
    key: Literal["housing", "money", "job", "documents", "safety", "life"]
    label: str
    score: int  # 0~100
    done_count: int
    total: int
    items: list[ReadinessItem]
    next_step: NextStepOut | None  # 이 영역에서 다음에 할 것 (다 했으면 null)


class ReadinessResponse(BaseModel):
    score: int  # 0~100 (영역 점수 평균)
    level: str  # 첫걸음 · 준비 중 · 거의 다 왔어요 · 자립 준비 완료
    areas: list[ReadinessArea]
    weakest: str | None  # 가장 낮은 영역 key (다 했으면 null)
    next_steps: list[NextStepOut]  # 약한 영역 순서대로 최대 3개
