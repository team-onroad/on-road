"""서류 양식·초안 스키마 (api.md 3.20~3.22, 부록)."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator

from app.codes import DOC_TYPES
from app.forms import FormField, Stage

INT_MAX = 2**31 - 1
INTEREST_MAX = 500


class TemplateSummary(BaseModel):
    template_id: str
    name: str
    description: str
    stages: list[Stage]


class TemplateList(BaseModel):
    templates: list[TemplateSummary]


class TemplateDetail(TemplateSummary):
    fields: list[FormField]


class AppRecords(BaseModel):
    model_config = ConfigDict(extra="forbid")

    saved_policy_count: Annotated[StrictInt, Field(ge=0, le=INT_MAX)] = 0
    interest_summary: str | None = None

    @field_validator("interest_summary")
    @classmethod
    def _interest(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.strip()
        if len(v) > INTEREST_MAX:
            raise ValueError(f"interest_summary 는 {INTEREST_MAX}자 이하여야 합니다.")
        return v or None  # 빈 문자열은 없음으로


class DocumentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal[DOC_TYPES]
    app_records: AppRecords | None = None  # null 이면 없는 것과 같음


class Sentence(BaseModel):
    text: str
    source: str | None  # null: 사용자가 채울 빈칸


class Section(BaseModel):
    title: str
    sentences: list[Sentence]


class DocumentResponse(BaseModel):
    type: Literal[DOC_TYPES]
    sections: list[Section]
    notice: str
