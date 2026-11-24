"""진로 로드맵 (api.md 3.33). 진로 방향별로 할 일 · 앱 기능 · 받을 수 있는 지원 · 공식 사이트를 잇는다 (규칙, AI 없음)."""

from typing import Literal

from pydantic import BaseModel

from app.schemas.policies import Category, EligibilityValue

CareerPath = Literal["job", "college", "training"]
StepKind = Literal["roleplay", "document", "link"]


class CareerStep(BaseModel):
    kind: StepKind  # roleplay: 실전 연습 · document: 서류 쓰기 · link: 공식 사이트
    title: str
    detail: str
    scenario_id: str | None = None  # roleplay 일 때 (실전 연습 시나리오)
    template_id: str | None = None  # document 일 때 (POST /documents 의 source_id)
    url: str | None = None  # link 일 때 (공공기관 공식 사이트만)
    phone: str | None = None  # link 일 때 기관 상담 전화


class CareerPolicy(BaseModel):
    policy_id: int
    name: str
    agency: str
    category: Category
    eligibility: EligibilityValue
    amount_text: str | None


class CareerPathPlan(BaseModel):
    path: CareerPath
    title: str  # "취업 준비" · "대학 진학" · "직업훈련 · 자격증"
    steps: list[CareerStep]
    policies: list[CareerPolicy]  # 이 방향과 관련된, 내가 받을 수 있는 정책 (최대 4개)


class CareerRoadmap(BaseModel):
    recommended: CareerPath  # 프로필(work · 성장 단계)로 고른 방향. 앱은 이 탭을 먼저 연다
    paths: list[CareerPathPlan]  # 항상 job · college · training 3개 (recommended 가 맨 앞)
