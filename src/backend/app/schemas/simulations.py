"""시뮬레이션 스키마 (docs/api.md 3.7, 3.8, 3.14, 부록). 이 응답들에는 null 이 되는 필드가 없다."""

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt

from app.codes import ALLOCATION_ITEMS, POLICY_CATEGORIES

INT_MAX = 2**31 - 1  # simulations 컬럼 타입 INT

AllocationItem = Literal[ALLOCATION_ITEMS]
Amount = Annotated[StrictInt, Field(ge=0, le=INT_MAX)]


class CriteriaItemOut(BaseModel):
    key: AllocationItem
    label: str
    minimum: int


class CriteriaResponse(BaseModel):
    criteria_version: str
    items: list[CriteriaItemOut]


class Allocations(BaseModel):
    model_config = ConfigDict(extra="forbid")

    housing: Amount
    food: Amount
    transport: Amount
    telecom: Amount
    other: Amount

    def total(self) -> int:
        return sum(self.model_dump().values())


class SimulationCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    total_income: Annotated[StrictInt, Field(ge=1, le=INT_MAX)]
    allocations: Allocations


class Shortage(BaseModel):
    item: AllocationItem
    label: str
    input: int
    minimum: int
    gap: int


class Alternative(BaseModel):
    label: str
    allocations: Allocations
    remaining: int
    remaining_shortages: list[Shortage]


class RelatedPolicy(BaseModel):
    policy_id: int
    name: str
    agency: str
    category: Literal[POLICY_CATEGORIES]


class SimulationResponse(BaseModel):
    simulation_id: int
    criteria_version: str
    total_income: int
    allocations: Allocations
    remaining: int
    shortages: list[Shortage]
    alternatives: list[Alternative]
    related_policies: list[RelatedPolicy]
    created_at: datetime
