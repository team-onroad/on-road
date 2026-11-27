"""이수한 교육 · 취득한 자격증 스키마 (api.md 3.37)"""

from datetime import date, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

CredentialKind = Literal["education", "certificate"]
# 정책 조건과 이어지는 교육. settlement: 자립정착금 의무교육 (자립지원전담기관) · finance: 자립수당 자립교육 (온라인 이수증)
EducationCode = Literal["settlement", "finance"]


class CredentialCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    kind: CredentialKind
    # education 일 때만. 주면 name 을 안 줘도 정해진 이름으로 저장한다
    code: EducationCode | None = None
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=60)] | None = None
    done_on: date | None = None  # 이수한 날 · 취득한 날 (오늘 이후는 안 됨)

    @model_validator(mode="after")
    def _check(self) -> "CredentialCreate":
        if self.code is not None and self.kind != "education":
            raise ValueError("code 는 education 일 때만 씁니다")
        if self.name is None and self.code is None:
            raise ValueError("name 이 필요합니다")
        return self


class Credential(BaseModel):
    credential_id: int
    kind: CredentialKind
    code: EducationCode | None
    name: str
    done_on: date | None
    created_at: datetime


class CredentialList(BaseModel):
    count: int
    credentials: list[Credential]


class EducationOption(BaseModel):
    """앱의 '교육 추가'에서 고르는 정해진 교육"""
    code: EducationCode
    name: str
    description: str


class RoadmapEducation(BaseModel):
    """로드맵 항목에 붙는 의무교육 (교육을 들어야 받을 수 있는 정책만)"""
    code: EducationCode
    name: str  # "자립정착금 의무교육"
    done: bool  # 사용자가 이 교육을 이수했다고 등록했는지
    done_on: date | None
    note: str  # "자립지원전담기관 의무교육을 먼저 들어야 받을 수 있어요" · "의무교육 이수했어요"
