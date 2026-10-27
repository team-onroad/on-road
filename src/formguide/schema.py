"""신청서 작성 항목 형식. data/forms/{form_id}.json 에 저장한다."""

from typing import Literal

from pydantic import BaseModel, Field

InputType = Literal[
    "text", "date", "phone", "address", "number", "checkbox", "account", "id_number", "signature", "other"
]
# profile: 앱 프로필로 미리 채움 / user: 사용자가 앱에서 확인하며 직접 입력
# handwrite: 앱에서 받지 않고 양식에 손으로 작성 (주민등록번호·계좌·서명 등) / office: 담당 공무원 기재란
ValueSource = Literal["profile", "user", "handwrite", "office"]
# 프로필에서 가져올 수 있는 값. 이 밖의 개인정보는 앱이 받지 않는다
ProfileKey = Literal["name", "birth_date", "region", "phone"]


class FormField(BaseModel):
    id: str
    label: str  # 양식에 적힌 칸 이름 그대로
    section: str | None = None
    input_type: InputType = "text"
    value_source: ValueSource = "user"
    profile_key: ProfileKey | None = None
    required: bool | None = None  # 양식에 필수 여부가 없으면 None
    guide: str  # 이 칸에 무엇을 쓰는지 쉬운 설명
    example: str | None = None  # 가상의 예시 답변 (민감 항목은 None)
    evidence: str | None = None  # 근거가 된 양식 원문 (칸 이름·작성요령)
    sensitive: bool = False
    # 원본 양식에서 값을 쓸 자리 {"page", "rect": [x0, y0, x1, y1], "how", "template_text"} (formguide/layout.py)
    # 못 찾으면 None → 문서 AI 내보내기 때 별지에 적는다
    box: dict | None = None


class FormSpec(BaseModel):
    form_id: str
    policy_key: str
    title: str
    source: str  # 원본 파일 경로 또는 URL
    fields: list[FormField]
    notes: list[str] = Field(default_factory=list)  # 양식 전체 유의사항 (원문 근거 있는 것만)
    warnings: list[str] = Field(default_factory=list)  # 자동 검사에서 걸러진 내용. 사람이 확인
    checked: bool = False  # 사람이 원본과 대조해 확정했는지. false 면 앱에서 "검증 전" 표시
