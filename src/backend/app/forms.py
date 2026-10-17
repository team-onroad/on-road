"""서류 양식 데이터 (api.md 3.20, 3.21). 앱 FakeCoachApi 양식 그대로"""

from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictStr, TypeAdapter

from app.codes import USER_STAGES
from app.errors import AppError

FORMS_PATH = Path(__file__).resolve().parent / "forms.json"

Stage = Literal[USER_STAGES]
Text = Annotated[StrictStr, Field(min_length=1)]


class FormField(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field_id: Text
    label: Text
    easy_label: Text
    guide: Text
    caution: Text | None
    example: Text | None
    multiline: StrictBool
    essay: StrictBool
    outline: Annotated[list[Text], Field(min_length=1)] | None


class FormTemplate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    template_id: Text
    name: Text
    description: Text
    stages: Annotated[list[Stage], Field(min_length=1)]
    fields: Annotated[list[FormField], Field(min_length=1)]


@lru_cache
def get_templates() -> dict[str, FormTemplate]:
    """파일 순서 유지. 형식 오류면 서버 시작 실패"""
    items = TypeAdapter(list[FormTemplate]).validate_json(FORMS_PATH.read_bytes())
    templates = {t.template_id: t for t in items}
    if len(templates) != len(items):
        raise ValueError("forms.json template_id 중복")
    return templates


def get_template(template_id: str) -> FormTemplate:
    template = get_templates().get(template_id)
    if template is None:
        raise AppError(404, "FORM_NOT_FOUND", "서류 양식을 찾을 수 없습니다.")
    return template
