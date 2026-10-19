"""정책 상세·검색 스키마 (docs/api.md 3.5, 3.6, 부록).

부록의 null 칸이 O 인 필드만 `| None` 으로 둔다. 나머지는 항상 값이 있다.
"""

from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict, field_validator

from app.codes import ELIGIBILITY_VALUES, POLICY_CATEGORIES, POLICY_REGIONS

EligibilityValue = Literal[ELIGIBILITY_VALUES]
Category = Literal[POLICY_CATEGORIES]
PolicyRegion = Literal[POLICY_REGIONS]

QUESTION_MAX = 500


class Eligibility(BaseModel):
    status: EligibilityValue
    age: EligibilityValue
    region: EligibilityValue


class RequiredDoc(BaseModel):
    name: str
    condition: str | None  # null: 모든 신청자에게 필요한 서류


class PolicyDetail(BaseModel):
    policy_id: int
    policy_key: str
    name: str
    agency: str
    contact: str | None
    category: Category
    region: PolicyRegion
    age_min: int | None
    age_max: int | None
    target_description: str
    support_content: str
    support_amount: str | None
    support_period: str | None
    apply_method: str
    required_docs: list[RequiredDoc]
    apply_url: str | None
    source_url: str
    original_text: str
    easy_text: str
    checked_at: date
    is_outdated: bool
    eligibility: Eligibility | None


class SearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    question: str

    @field_validator("question")
    @classmethod
    def _strip_question(cls, v: str) -> str:
        v = v.strip()
        if not 1 <= len(v) <= QUESTION_MAX:
            raise ValueError(f"질문은 공백을 제외하고 1~{QUESTION_MAX}자여야 합니다.")
        return v


class Evidence(BaseModel):
    content: str


class SearchResult(BaseModel):
    policy_id: int
    policy_key: str
    name: str
    agency: str
    category: Category
    checked_at: date
    is_outdated: bool
    eligibility: Eligibility
    target_description: str
    support_content: str
    support_amount: str | None
    support_period: str | None
    original_text: str
    easy_text: str
    source_url: str
    evidences: list[Evidence]


class SearchResponse(BaseModel):
    answer_status: Literal["answered", "no_evidence"]
    answer: str | None
    results: list[SearchResult]


# --- RAG 함수 출력 (api.md 4장). 형식이 다르면 503 RAG_UNAVAILABLE ---


class RagEvidence(BaseModel):
    policy_key: str
    content: str
    score: float | None = None  # 백엔드는 사용하지 않음


class RagOutput(BaseModel):
    status: Literal["answered", "no_evidence"]
    answer: str | None
    evidences: list[RagEvidence]


# ---------------- 쉬운 말 단계 (3.23) ----------------

EasyLevelCode = Literal["L1", "L2", "L3", "L4", "L5", "L6", "L7"]


class EasyLevelOption(BaseModel):
    level: EasyLevelCode
    level_name: str


class EasyTerm(BaseModel):
    term: str
    gloss: str


class EasyText(BaseModel):
    policy_id: int
    policy_key: str
    level: EasyLevelCode  # 실제로 보여 주는 단계
    level_name: str
    recommended_level: EasyLevelCode  # 나이 기준 추천 단계 (user_id 없으면 L6)
    levels: list[EasyLevelOption]  # 고를 수 있는 단계 (자동 검사 통과한 것만, L1→L7)
    core_text: str  # 핵심 요약
    more_text: str | None  # 더 알아보기 (없으면 null)
    terms: list[EasyTerm]  # 용어 풀이. 앱이 본문에서 강조하고 누르면 풀이 표시
    verified: bool  # 사람이 원문과 대조했는지. false 면 앱이 "검토 중" 안내


# ---------------- 신청서 작성 도우미 (3.24) ----------------

ValueSource = Literal["profile", "user", "handwrite", "office"]


class FormGuideItem(BaseModel):
    field_id: str
    label: str
    section: str | None
    how: str
    value_source: ValueSource
    required: bool | None
    guide: str
    example: str | None
    prefill: str | None
    evidence: str | None


class FormGuide(BaseModel):
    policy_id: int
    form_id: str
    policy_key: str
    title: str
    checked: bool
    notes: list[str]
    items: list[FormGuideItem]
    notices: list[str]
