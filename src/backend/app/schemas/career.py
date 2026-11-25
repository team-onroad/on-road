"""진로 로드맵 (api.md 3.33). 진로 방향별로 할 일 · 앱 기능 · 받을 수 있는 지원 · 공식 사이트를 잇는다 (규칙, AI 없음)."""

from typing import Literal

from pydantic import BaseModel

from app.schemas.policies import Category, EligibilityValue

CareerPath = Literal["job", "college", "training"]
StepKind = Literal["roleplay", "document", "link", "app", "search"]


class CareerStep(BaseModel):
    step_id: str  # 방향 안에서 바뀌지 않는 이름 ("job-jobcenter"). 앱이 끝낸 일 · 일정에 쓴다
    kind: StepKind  # roleplay: 실전 연습 · document: 서류 쓰기 · link: 공식 사이트 · app: 앱 기능 · search: 앱 정책 검색
    title: str
    detail: str
    scenario_id: str | None = None  # roleplay 일 때 (실전 연습 시나리오)
    template_id: str | None = None  # document 일 때 (POST /documents 의 source_id)
    url: str | None = None  # link 일 때 (공공기관 공식 사이트만)
    phone: str | None = None  # link 일 때 기관 상담 전화
    target: str | None = None  # app 일 때 앱 화면: interest(흥미 탐색) · risk(서류 위험 찾기)
    query: str | None = None  # search 일 때 정책 검색어
    guide: list[str] = []  # 펼치면 보이는 '이렇게 해요' 줄 (어디서 · 무엇을 · 무엇을 챙기는지)
    reason: str | None = None  # 내 조건 때문에 들어간 할 일이면 그 까닭 ("만 23세라 청년 대상이에요")


class CareerPolicy(BaseModel):
    policy_id: int
    name: str
    agency: str
    category: Category
    eligibility: EligibilityValue
    amount_text: str | None
    benefit: str | None = None  # 받는 것 한 줄


class CareerPathPlan(BaseModel):
    path: CareerPath
    title: str  # "취업 준비" · "대학 진학" · "직업훈련 · 자격증"
    steps: list[CareerStep]
    policies: list[CareerPolicy]  # 이 방향과 관련된, 내가 받을 수 있는 정책 (최대 4개)


class CareerRoadmap(BaseModel):
    recommended: CareerPath  # 프로필(work · 성장 단계)로 고른 방향. 앱은 이 탭을 먼저 연다
    paths: list[CareerPathPlan]  # 항상 job · college · training 3개 (recommended 가 맨 앞)
