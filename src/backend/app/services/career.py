"""진로 로드맵 (api.md 3.33). 규칙만 쓴다 (AI 없음).

- 방향 3개: 취업 준비(job) · 대학 진학(college) · 직업훈련 · 자격증(training)
- 단계: 앱의 실전 연습(roleplay_scenarios.json) · 서류 양식(forms.json) · 공공기관 공식 사이트를 순서대로 잇는다
- 정책: 받을 수 있는 정책(candidate_policies) 중 방향별 낱말이 이름 · 지원 내용에 들어간 것
- 추천 방향: work(학생 · 재직 · 구직) → 성장 단계 순으로 고른다
"""

import re
from datetime import date

from sqlalchemy.orm import Session

from app.money import parse_amount
from app.rules import calc_age, calc_stage
from app.schemas.career import CareerPathPlan, CareerPolicy, CareerRoadmap, CareerStep
from app.services.policies import candidate_policies, eligibility_for
from db.models import Policy, User

POLICY_LIMIT = 4
PATH_ORDER = ("job", "college", "training")
TITLES = {"job": "취업 준비", "college": "대학 진학", "training": "직업훈련 · 자격증"}
_KEYWORDS = {
    "job": re.compile(r"취업|구직|도전지원|일자리|채용|면접|청년챙김"),
    "college": re.compile(r"대학|등록금|장학|진학|입학"),
    "training": re.compile(r"훈련|교육비|자격|능력개발|학습|역량"),
}

# 공식 사이트 (공공기관 누리집 첫 화면 · 공식 안내 화면만. 11/24 확인)
CAREERNET = ("커리어넷", "https://www.career.go.kr", None)  # 한국직업능력연구원
WORK24 = ("고용24", "https://www.work24.go.kr", "1350")  # 고용노동부
CARD_GUIDE = ("고용24 국민내일배움카드 발급 안내", "https://www.work24.go.kr/hr/h/a/1100/selectIssuGudn.do", "1350")
KOSAF = ("한국장학재단", "https://www.kosaf.go.kr", "1599-2000")
QNET = ("큐넷", "https://www.q-net.or.kr", "1644-8000")  # 한국산업인력공단


def _link(site: tuple[str, str, str | None], title: str, detail: str) -> CareerStep:
    return CareerStep(kind="link", title=title, detail=f"{site[0]} · {detail}", url=site[1], phone=site[2])


def _roleplay(scenario_id: str, title: str, detail: str) -> CareerStep:
    return CareerStep(kind="roleplay", title=title, detail=detail, scenario_id=scenario_id)


def _document(template_id: str, title: str, detail: str) -> CareerStep:
    return CareerStep(kind="document", title=title, detail=detail, template_id=template_id)


STEPS: dict[str, list[CareerStep]] = {
    "job": [
        _link(CAREERNET, "하고 싶은 일 찾기", "직업정보 · 진로검사"),
        _roleplay("jobcenter", "고용센터 상담 연습", "처음 방문해서 할 말을 미리 연습"),
        _link(WORK24, "구직 등록", "채용 정보 검색 · 청년 지원 신청"),
        _document("resume-form", "입사지원서 쓰기", "기본형 이력서 양식"),
        _roleplay("parttime", "아르바이트 면접 연습", "자주 나오는 질문으로 연습"),
        _roleplay("job", "취업 면접 연습", "실전 면접 질문으로 연습"),
    ],
    "college": [
        _link(CAREERNET, "학과 찾기", "학과정보 · 진로검사"),
        _link(KOSAF, "국가장학금 신청", "학기마다 신청 기간 확인"),
        _document("scholarship", "장학금 신청서 쓰기", "학교 · 재단 장학금 양식"),
        _roleplay("school", "진학 면접 연습", "입시 면접 질문으로 연습"),
    ],
    "training": [
        _roleplay("jobcenter", "고용센터 상담 연습", "훈련 상담 때 할 말을 미리 연습"),
        _link(CARD_GUIDE, "국민내일배움카드 발급", "훈련비 지원 카드 · 훈련과정 검색"),
        _link(QNET, "자격증 시험 일정 확인", "국가기술자격 원서접수 · 시험일정"),
        _document("resume-form", "입사지원서 쓰기", "훈련 뒤 지원할 때 쓰는 기본형 양식"),
    ],
}


def recommended_path(user: User, today: date) -> str:
    work = getattr(user, "work", None)
    if work == "student":
        return "college"
    if work == "working":
        return "training"
    if work == "job_seeking":
        return "job"
    return "college" if calc_stage(calc_age(user.birth_date, today), user.status) != "youth" else "job"


def _policies(pool: list[Policy], path: str, user: User, today: date) -> list[CareerPolicy]:
    pattern = _KEYWORDS[path]
    hits = [p for p in pool if pattern.search(f"{p.name} {p.support_content or ''}")]
    rows = []
    for p in hits:
        status = eligibility_for(user, p, today).status
        won, _, text = parse_amount(p.support_amount)
        rows.append((status != "match", won is None, -(won or 0), p.id, p, status, text))
    rows.sort(key=lambda r: r[:4])
    return [
        CareerPolicy(policy_id=p.id, name=p.name, agency=p.agency, category=p.category, eligibility=s, amount_text=t)
        for *_, p, s, t in rows[:POLICY_LIMIT]
    ]


def build_career_roadmap(db: Session, user: User, today: date) -> CareerRoadmap:
    pool = candidate_policies(db, calc_age(user.birth_date, today), user.region, user=user, today=today)
    first = recommended_path(user, today)
    order = [first] + [p for p in PATH_ORDER if p != first]
    return CareerRoadmap(
        recommended=first,
        paths=[
            CareerPathPlan(path=p, title=TITLES[p], steps=STEPS[p], policies=_policies(pool, p, user, today))
            for p in order
        ],
    )
