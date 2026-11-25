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
    status: EligibilityValue  # 나이 · 지역 판정
    age: EligibilityValue
    region: EligibilityValue
    # 보호 상태: 보호가 끝나야 받는 정책(대상에 보호종료 · 퇴소 · 자립준비)인데 아직 보호 중이면 after_leave,
    # 이미 퇴소했으면 now. 보호 상태와 상관없는 정책이면 null
    leave: Literal["now", "after_leave"] | None = None
    # 보호받던 곳(users.care_type): match · excluded. 사용자가 모르거나 정책이 가르지 않으면 null.
    # excluded 면 추천 · 검색 · 로드맵에서 빠진다
    care: Literal["match", "excluded"] | None = None


class RequiredDoc(BaseModel):
    name: str
    condition: str | None  # null: 모든 신청자에게 필요한 서류


class RelatedPolicy(BaseModel):
    """정책 상세의 '같이 보면 좋은 정책' 한 개 (같은 분야 · 내가 받을 수 있는 정책, 규칙으로 고름)"""
    policy_id: int
    policy_key: str
    name: str
    agency: str
    category: Category
    amount_text: str | None  # "월 50만 원" (금액을 읽지 못하면 null)
    benefit: str | None = None  # 받는 것 한 줄 ("주거(오피스텔) 지원"). 돈이 아닌 정책도 있음


class ApplyWay(BaseModel):
    way: str  # 방문 · 온라인 · 우편·팩스 · 전화 · 카카오톡 · 따로 신청하지 않아요 · 기관이 대신 신청해요 · 문의 · 신청 …
    lines: list[str] = []


class SameKindPolicy(BaseModel):
    policy_id: int
    name: str
    region: str
    amount_text: str | None


class SameKind(BaseModel):
    """같은 종류라 함께 받기 어려운 정책 묶음 안에서 이 정책의 자리 (맞춤 신청 로드맵 3.32 와 같은 기준)"""

    kind_title: str  # "자립정착금"
    is_best: bool  # 내 조건에서 이 정책이 같은 종류 중 가장 유리함
    reason: str  # 고른 까닭 ("같은 종류 3개 중 받는 돈이 가장 커요") 또는 다른 정책이 나은 까닭
    best: SameKindPolicy  # 같은 종류 중 가장 유리한 정책 (is_best 면 이 정책)
    others: list[SameKindPolicy]  # 같은 종류의 나머지 (이 정책 제외, 받는 돈이 큰 순서)


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
    # 금액 · 기간을 숫자로 (app/money.py). 읽지 못하면 null → 앱은 support_amount 문장을 보여 준다
    amount_won: int | None = None
    amount_cycle: Literal["monthly", "yearly", "once"] | None = None
    amount_text: str | None = None
    period_years: int | None = None
    benefit: str | None = None  # 받는 것 한 줄 (카드 요약의 지원 내용). 돈이 아닌 지원(주거 · 의료 · 상담)도 보여 준다
    # 나이 · 지역 말고 따로 확인할 대상 조건 (카드 요약의 '지원 대상' 구절). 앱 '내 조건'에 그대로 보여 준다
    conditions: list[str] = []
    exclusions: list[str] = []  # 받을 수 없는 경우 (카드 요약의 '받을 수 없는 경우')
    apply_ways: list[ApplyWay] = []  # 신청 방법 정리본 (방문 · 온라인 … 갈래별 쉬운 문장). 없으면 앱은 apply_steps
    apply_deadline: str | None = None  # 신청 기한 ("상시 신청" 등). 정리본에 있을 때만
    apply_steps: list[str] = []  # 신청 방법을 짧게 나눈 구절 (핵심 사실의 apply). 없으면 앱은 apply_method 원문
    doc_facts: list[str] = []  # 필요 서류를 짧게 정리한 구절 (핵심 사실의 docs). 괄호 안은 언제 내는지
    related: list[RelatedPolicy] = []
    same_kind: SameKind | None = None  # 사용자를 주고, 같은 종류 정책이 2개 이상 받을 수 있을 때만


class SearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    question: str
    # false: 답변 문장 없이 정책 목록만 (분야별로 보기처럼 카드만 쓸 때. 훨씬 빠름)
    answer: bool = True

    @field_validator("question")
    @classmethod
    def _strip_question(cls, v: str) -> str:
        v = v.strip()
        if not 1 <= len(v) <= QUESTION_MAX:
            raise ValueError(f"질문은 공백을 제외하고 1~{QUESTION_MAX}자여야 합니다.")
        return v


class Evidence(BaseModel):
    content: str


class SummarySection(BaseModel):
    label: str  # 지원 대상, 받을 수 없는 경우, 지원 내용, 지원 기간, 신청 방법
    items: list[str]  # 짧은 키워드 구절


class PolicySummary(BaseModel):
    sections: list[SummarySection]
    notice: str | None  # 자격 단정 금지 안내 (카드 아래 작게)
    verified: bool  # 사람이 원문과 대조했는지. false 면 앱이 "검토 중" 안내


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
    summary: PolicySummary | None  # 카드용 짧은 요약. null 이면 target_description 등을 그대로 보여 준다
    # 답변 근거가 아니라 질문과 뜻이 가까워 덧붙인 정책 (RAG related). 앱은 '비슷한 정책'으로 따로 보여 준다
    related: bool = False
    # 본인 나이로는 빠지지만 가족(family_members) 나이가 맞는 아동 대상 정책. 앱은 따로 묶는다
    for_family: bool = False


class SearchResponse(BaseModel):
    answer_status: Literal["answered", "no_evidence"]
    answer: str | None
    results: list[SearchResult]


# --- RAG 함수 출력 (api.md 4장). 형식이 다르면 503 RAG_UNAVAILABLE ---


class RagEvidence(BaseModel):
    policy_key: str
    content: str
    score: float | None = None  # 백엔드는 사용하지 않음
    related: bool = False  # 답변 근거가 아닌 비슷한 정책 (SearchResult.related 로 전달)


class RagOutput(BaseModel):
    status: Literal["answered", "no_evidence"]
    answer: str | None
    evidences: list[RagEvidence]
    # 답변 AI 가 늦거나 실패해 근거 정책 안내 문장으로 대신한 결과 (캐시하지 않는다)
    fallback: bool = False


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
