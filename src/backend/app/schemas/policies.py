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
    required_docs: list[str]
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
