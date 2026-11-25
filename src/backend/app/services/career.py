"""진로 로드맵 (api.md 3.33). 규칙만 쓴다 (AI 없음).

- 방향 3개: 취업 준비(job) · 대학 진학(college) · 직업훈련 · 자격증(training)
- 단계: 앱의 실전 연습(roleplay_scenarios.json) · 서류 양식(forms.json) · 앱 기능 · 공공기관 공식 사이트를 순서대로 잇는다
- 내 조건(나이 · 상황 · 지금 하는 일 · 사는 지역 · 받을 수 있는 정책)에 따라 할 일을 더하고, 더한 까닭(reason)을 붙인다
- 할 일마다 펼쳐 보는 '이렇게 해요'(guide)를 둔다
- 정책: 받을 수 있는 정책(candidate_policies) 중 방향별 낱말이 이름 · 지원 내용에 들어간 것
- 추천 방향: work(학생 · 재직 · 구직) → 성장 단계 순으로 고른다
"""

import re
from datetime import date

from sqlalchemy.orm import Session

from app import ai_content
from app.money import policy_amount
from app.rules import calc_age, calc_stage
from app.schemas.career import CareerPathPlan, CareerPolicy, CareerRoadmap, CareerStep
from app.services.policies import candidate_policies, eligibility_for
from db.models import Policy, User

POLICY_LIMIT = 4
PATH_ORDER = ("job", "college", "training")
TITLES = {"job": "취업 준비", "college": "대학 진학", "training": "직업훈련 · 자격증"}
_KEYWORDS = {
    "job": re.compile(r"취업|구직|청년\s*도전|일자리|채용|면접|청년챙김"),
    "college": re.compile(r"대학|등록금|장학|진학|입학"),
    "training": re.compile(r"훈련|교육비|자격|능력개발|학습|역량"),
}

# 공식 사이트 (공공기관 누리집 첫 화면 · 공식 안내 화면만. 11/24 · 11/25 확인)
CAREERNET = ("커리어넷", "https://www.career.go.kr", None)  # 한국직업능력연구원
WORK24 = ("고용24", "https://www.work24.go.kr", "1350")  # 고용노동부
CARD_GUIDE = ("고용24 국민내일배움카드 발급 안내", "https://www.work24.go.kr/hr/h/a/1100/selectIssuGudn.do", "1350")
KOSAF = ("한국장학재단", "https://www.kosaf.go.kr", "1599-2000")
QNET = ("큐넷", "https://www.q-net.or.kr", "1644-8000")  # 한국산업인력공단
JARIPON = ("자립정보ON", "https://jaripon.ncrc.or.kr", "1855-2455")  # 아동권리보장원 · 자립준비청년 상담센터 전화
ADIGA = ("대입정보포털 어디가", "https://www.adiga.kr", "1600-1615")  # 한국대학교육협의회 대입 상담 전화


def _link(step_id, site, title, detail, guide=(), reason=None) -> CareerStep:
    return CareerStep(step_id=step_id, kind="link", title=title, detail=f"{site[0]}, {detail}", url=site[1], phone=site[2],
                      guide=list(guide), reason=reason)


def _roleplay(step_id, scenario_id, title, detail, guide=(), reason=None) -> CareerStep:
    return CareerStep(step_id=step_id, kind="roleplay", title=title, detail=detail, scenario_id=scenario_id,
                      guide=list(guide), reason=reason)


def _document(step_id, template_id, title, detail, guide=(), reason=None) -> CareerStep:
    return CareerStep(step_id=step_id, kind="document", title=title, detail=detail, template_id=template_id,
                      guide=list(guide), reason=reason)


def _app(step_id, target, title, detail, guide=(), reason=None) -> CareerStep:
    return CareerStep(step_id=step_id, kind="app", title=title, detail=detail, target=target, guide=list(guide), reason=reason)


def _search(step_id, query, title, detail, guide=(), reason=None) -> CareerStep:
    return CareerStep(step_id=step_id, kind="search", title=title, detail=detail, query=query, guide=list(guide), reason=reason)


# ── 할 일마다 '이렇게 해요' (어디서 · 무엇을 · 무엇을 챙기는지). 공식 안내에 있는 것만 ──
G_INTEREST = ["질문에 답하면 흥미 유형이 나와요", "어울리는 직업을 고르면 그 직업이 되는 길과 필요한 자격증을 보여 줘요",
              "고른 직업은 이 화면의 '관심 직업'에 들어가요"]
G_CAREERNET_JOB = ["직업정보에서 하는 일과 되는 길을 찾아봐요", "진로심리검사로 적성과 흥미를 한 번 더 볼 수 있어요"]
G_CAREERNET_MAJOR = ["학과정보에서 배우는 과목과 졸업 뒤 진로를 찾아봐요", "진로심리검사로 적성과 흥미를 한 번 더 볼 수 있어요"]
G_JARIPON = ["선배 자립준비청년 상담사가 직접 상담해요", "전화 1855-2455, 평일 9시부터 18시까지",
             "카카오톡 '아동자립지원' 채널에서 1:1 채팅 상담도 돼요"]
G_JOBCENTER = ["고용센터에 처음 갔을 때 할 말을 연습해요", "빠뜨린 말이 있으면 연습이 끝난 뒤 알려 줘요"]
G_WORK24 = ["고용24에 회원가입하고 이력서를 올려요", "구직 신청을 하면 채용 정보와 고용센터 상담을 받을 수 있어요",
            "모르는 건 고용노동부 고객상담센터 1350에 물어봐요"]
G_RESUME = ["기본형 입사지원서 양식을 앱에서 채워요", "학력, 경력, 자격증 순서로 쓰고, AI와 같이 다듬을 수 있어요"]
G_PARTTIME = ["자주 나오는 아르바이트 면접 질문에 답해 봐요", "가능한 요일과 시간을 미리 정리해 두면 답하기 쉬워요"]
G_INTERVIEW = ["실전 면접 질문으로 연습해요", "자기소개와 지원한 까닭은 미리 1분 분량으로 준비해 둬요"]
G_CONTRACT = ["근로계약서를 사진으로 올리면 빠진 내용을 찾아 줘요",
              "임금, 일하는 시간, 쉬는 날, 연차 휴가, 일하는 곳과 하는 일이 적혀 있는지 봐요", "계약서는 한 부를 꼭 받아 둬요"]
G_BANK = ["급여를 받을 내 이름 통장을 만드는 연습을 해요", "신분증을 챙겨 은행에 가요"]
G_WAGE = ["밀린 임금을 요청하는 말을 연습해요", "해결이 안 되면 고용노동부 고객상담센터 1350에 상담할 수 있어요"]
G_ADIGA = ["학과 정보와 대학별 입시 정보를 찾아봐요", "전화 1600-1615로 대입 상담을 받을 수 있어요"]
G_KOSAF = ["한국장학재단 누리집이나 앱에서 학기마다 신청해요", "신청 기간이 정해져 있어서 학기마다 다시 확인해요"]
G_SEARCH = ["내 나이, 지역, 상황에 맞는 정책만 보여 줘요", "마음에 드는 정책은 신청 준비로 이어서 챙겨요"]
G_SCHOLARSHIP_FORM = ["학교나 재단 장학금 신청서 양식을 앱에서 채워요", "가정 형편과 학업 계획을 쓰는 칸은 AI와 같이 다듬을 수 있어요"]
G_SCHOOL = ["입시 면접 질문으로 연습해요", "지원 동기와 학과에서 하고 싶은 공부를 미리 정리해 둬요"]
G_CARD = ["고용24에서 국민내일배움카드 발급을 신청해요", "발급되면 훈련비 일부를 지원받아 훈련과정을 들을 수 있어요"]
G_COURSE = ["고용24에서 훈련과정을 찾아 수강 신청해요", "관심 직업과 이어지는 과정인지 훈련 내용을 먼저 봐요"]
G_QNET = ["국가기술자격 시험 일정과 원서 접수를 볼 수 있어요", "원서 접수 기간을 놓치지 않게 일정에 넣어 둬요"]


# 방향마다 순서대로: 나를 알기 → 상담 받기 → 준비 · 신청 → 연습 → 일을 시작한 뒤 챙길 것
STEPS: dict[str, list[CareerStep]] = {
    "job": [
        _app("job-interest", "interest", "흥미 탐색으로 맞는 일 찾기", "어울리는 직업과 그 직업이 되는 길", G_INTEREST),
        _link("job-careernet", CAREERNET, "직업 정보 더 알아보기", "직업정보, 진로검사", G_CAREERNET_JOB),
        _link("job-jaripon", JARIPON, "자립 상담 받기", "선배 자립준비청년 상담사와 취업 고민 상담", G_JARIPON),
        _roleplay("job-jobcenter", "jobcenter", "고용센터 상담 연습", "처음 방문해서 할 말을 미리 연습", G_JOBCENTER),
        _link("job-work24", WORK24, "구직 등록", "채용 정보 검색, 청년 지원 신청", G_WORK24),
        _document("job-resume", "resume-form", "입사지원서 쓰기", "기본형 이력서 양식", G_RESUME),
        _roleplay("job-parttime", "parttime", "아르바이트 면접 연습", "자주 나오는 질문으로 연습", G_PARTTIME),
        _roleplay("job-interview", "job", "취업 면접 연습", "실전 면접 질문으로 연습", G_INTERVIEW),
        _app("job-contract", "risk", "근로계약서 확인", "서류 위험 찾기로 빠진 내용 찾기", G_CONTRACT),
        _roleplay("job-bank", "bank", "급여 받을 통장 만들기", "은행 계좌 개설 연습", G_BANK),
        _roleplay("job-wage", "wage", "임금을 못 받았을 때", "밀린 알바비 받기 연습", G_WAGE),
    ],
    "college": [
        _app("college-interest", "interest", "흥미 탐색으로 맞는 길 찾기", "어울리는 직업과 관련 학과", G_INTEREST),
        _link("college-adiga", ADIGA, "학과와 대학 찾기", "학과정보, 입학 상담", G_ADIGA),
        _link("college-careernet", CAREERNET, "학과 정보 더 알아보기", "학과정보, 진로검사", G_CAREERNET_MAJOR),
        _link("college-jaripon", JARIPON, "자립 상담 받기", "선배 자립준비청년 상담사와 진학 고민 상담", G_JARIPON),
        _link("college-kosaf", KOSAF, "국가장학금 신청", "학기마다 신청 기간 확인", G_KOSAF),
        _search("college-scholarship-search", "자립준비청년 장학금", "받을 수 있는 장학금 찾기", "내 조건에 맞는 장학, 등록금 지원", G_SEARCH),
        _document("college-scholarship", "scholarship", "장학금 신청서 쓰기", "학교, 재단 장학금 양식", G_SCHOLARSHIP_FORM),
        _roleplay("college-interview", "school", "진학 면접 연습", "입시 면접 질문으로 연습", G_SCHOOL),
        _search("college-housing", "대학생 전세임대", "학교 다닐 집 찾기", "대학생 주거 지원", G_SEARCH),
    ],
    "training": [
        _app("training-interest", "interest", "흥미 탐색으로 맞는 일 찾기", "어울리는 직업과 필요한 자격", G_INTEREST),
        _link("training-jaripon", JARIPON, "자립 상담 받기", "선배 자립준비청년 상담사와 훈련 고민 상담", G_JARIPON),
        _roleplay("training-jobcenter", "jobcenter", "고용센터 상담 연습", "훈련 상담 때 할 말을 미리 연습", G_JOBCENTER),
        _link("training-card", CARD_GUIDE, "국민내일배움카드 발급", "훈련비 지원 카드", G_CARD),
        _link("training-course", WORK24, "훈련과정 찾기", "훈련과정 검색, 수강 신청", G_COURSE),
        _link("training-qnet", QNET, "자격증 시험 일정 확인", "국가기술자격 원서접수, 시험일정", G_QNET),
        _document("training-resume", "resume-form", "입사지원서 쓰기", "훈련 뒤 지원할 때 쓰는 기본형 양식", G_RESUME),
        _roleplay("training-interview", "job", "취업 면접 연습", "실전 면접 질문으로 연습", G_INTERVIEW),
        _app("training-contract", "risk", "근로계약서 확인", "서류 위험 찾기로 빠진 내용 찾기", G_CONTRACT),
    ],
}

WORK_LABEL = {"student": "학생", "working": "일해요", "job_seeking": "일 찾는 중", "resting": "쉬는 중"}


def _personal(path: str, user: User, today: date, pool: list[Policy]) -> list[CareerStep]:
    """기본 할 일에 내 조건으로 더하는 할 일 (넣을 자리 뒤 step_id, 할 일). reason 에 까닭을 적는다"""
    age = calc_age(user.birth_date, today)
    work = getattr(user, "work", None)
    steps = [s.model_copy() for s in STEPS[path]]

    def insert_after(after_id: str | None, step: CareerStep) -> None:
        idx = next((i for i, s in enumerate(steps) if s.step_id == after_id), -1)
        steps.insert(idx + 1, step)

    def has_policy(pattern: str) -> Policy | None:
        rx = re.compile(pattern)
        return next((p for p in pool if rx.search(p.name)), None)

    # 상황: 퇴소 전이면 퇴소 전에 진로 상담 · 보호 연장 상담, 퇴소 후면 사후관리 기관 연결
    if user.status in ("in_care", "leaving_soon"):
        for s in steps:
            if s.step_id.endswith("-jaripon"):
                s.reason = "퇴소 전이라 미리 상담해 두면 퇴소한 뒤 바로 이어서 도움을 받을 수 있어요"
        if path == "college":
            insert_after("college-jaripon", CareerStep(
                step_id="college-extend", kind="app", target="chat", query="대학에 다니는 동안 보호를 연장할 수 있을까?", title="보호 연장 상담하기",
                detail="대학에 다니는 동안 보호를 이어 갈 수 있는지 상담",
                guide=["시설 선생님이나 자립지원전담기관에 보호 연장이 필요한지 상담해요", "진학이 정해지면 바로 이야기해 두는 게 좋아요"],
                reason="지금 보호를 받고 있어서 진학하면 보호 연장을 같이 생각해 볼 수 있어요"))
    elif user.status == "left_care":
        insert_after(f"{path}-jaripon" if any(s.step_id == f"{path}-jaripon" for s in steps) else f"{path}-interest", _search(
            f"{path}-aftercare", "자립지원 전담기관", "자립지원전담기관 사후관리 받기", "보호가 끝난 뒤 맞춤 사례관리",
            ["사는 시·도의 자립지원전담기관에서 진로, 취업, 생활을 같이 관리해 줘요", "보호가 끝난 지 5년 안이면 사후관리 대상이에요"],
            reason="보호가 끝났으니 시·도 자립지원전담기관의 사후관리를 받을 수 있어요"))

    # 나이: 만 18~34세 청년이면 국민취업지원제도 (취업 · 훈련 방향)
    if path in ("job", "training") and 18 <= age <= 34:
        insert_after(f"{path}-work24" if path == "job" else f"{path}-card", _link(
            f"{path}-kua", WORK24, "국민취업지원제도 신청", "취업 상담, 직업훈련, 구직촉진수당",
            ["고용24에서 온라인으로 신청하거나 가까운 고용센터에 가요", "상담사와 취업 계획을 세우고 훈련, 일 경험을 연결받아요",
             "모르는 건 고용노동부 고객상담센터 1350에 물어봐요"],
            reason=f"만 {age}세라 청년(만 18~34세) 대상이에요"))

    # 지금 하는 일
    if work == "resting" and path == "job":
        p = has_policy(r"청년\s*도전")
        if p:
            insert_after("job-jaripon", _search(
                "job-challenge", "청년도전지원사업", "청년도전지원사업 알아보기", "쉬고 있는 청년이 천천히 다시 시작하는 프로그램",
                G_SEARCH, reason="지금 하는 일이 '쉬는 중'이라 부담 없이 시작하는 프로그램부터 볼 수 있어요"))
    if work == "working" and path == "training":
        for s in steps:
            if s.step_id == "training-card":
                s.reason = "지금 일하고 있어도 국민내일배움카드로 훈련을 들을 수 있어요"
    if work == "student" and path == "college":
        for s in steps:
            if s.step_id == "college-kosaf":
                s.reason = "학생이라 학기마다 국가장학금을 챙겨야 해요"

    # 사는 지역: 지역 청년 일자리 · 교육 지원 찾기
    if user.region and path in ("job", "training"):
        region = user.region + (f" {user.district}" if getattr(user, "district", None) else "")
        insert_after(f"{path}-work24" if path == "job" else f"{path}-course", _search(
            f"{path}-local", f"{user.region} 청년 {'취업' if path == 'job' else '교육'}",
            f"{user.region} 청년 {'일자리' if path == 'job' else '교육'} 지원 찾기", f"{region}에서 받을 수 있는 지원",
            G_SEARCH, reason=f"{region}에 살아서 지역 지원도 같이 볼 수 있어요"))
    return steps


def recommended_path(user: User, today: date) -> str:
    work = getattr(user, "work", None)
    if work == "student":
        return "college"
    if work == "working":
        return "training"
    if work == "job_seeking":
        return "job"
    return "college" if calc_stage(calc_age(user.birth_date, today), user.status) != "youth" else "job"


def _match_text(p: Policy) -> str:
    """방향별 낱말을 찾을 글: 정책 이름 + 받는 것(카드 요약의 지원 내용 구절).
    지원 내용 원문 전체는 '대학생 전세임대', '교육생'처럼 다른 뜻으로 걸리는 말이 많아서 쓰지 않는다 (요약이 없을 때만)"""
    facts = ai_content.policy_benefits(p.policy_key)
    return f"{p.name} {' '.join(facts) if facts else (p.support_content or '')}"


def _policies(pool: list[Policy], path: str, user: User, today: date) -> list[CareerPolicy]:
    pattern = _KEYWORDS[path]
    hits = [p for p in pool if pattern.search(_match_text(p))]
    rows = []
    for p in hits:
        status = eligibility_for(user, p, today).status
        won, _, text = policy_amount(p)
        rows.append((status != "match", won is None, -(won or 0), p.id, p, status, text))
    rows.sort(key=lambda r: r[:4])
    return [
        CareerPolicy(policy_id=p.id, name=p.name, agency=p.agency, category=p.category, eligibility=s, amount_text=t,
                     benefit=ai_content.policy_benefit(p.policy_key))
        for *_, p, s, t in rows[:POLICY_LIMIT]
    ]


def build_career_roadmap(db: Session, user: User, today: date) -> CareerRoadmap:
    pool = candidate_policies(db, calc_age(user.birth_date, today), user.region, user=user, today=today)
    first = recommended_path(user, today)
    order = [first] + [p for p in PATH_ORDER if p != first]
    return CareerRoadmap(
        recommended=first,
        paths=[
            CareerPathPlan(path=p, title=TITLES[p], steps=_personal(p, user, today, pool), policies=_policies(pool, p, user, today))
            for p in order
        ],
    )
