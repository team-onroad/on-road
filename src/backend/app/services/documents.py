"""서류 양식, 이력서·자기소개서·활동 기록 초안 (api.md 3.20~3.22). 앱 generateDocument 와 같은 규칙"""

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.forms import get_template, get_templates
from app.schemas.forms import (
    AppRecords,
    DocumentRequest,
    DocumentResponse,
    Section,
    Sentence,
    TemplateDetail,
    TemplateList,
    TemplateSummary,
)
from app.services.home import user_checklists
from db.models import Simulation, User

NOTICE_EMPTY = "앱에 쌓인 기록이 아직 적어서 빈칸이 많아요. 기록이 쌓일수록 초안이 풍부해져요."
NOTICE_RECORDS = "앱에 기록된 내용만 근거로 만들었어요. 없는 경험은 넣지 않았어요."
INTEREST_SOURCE = "흥미 탐색 결과"


def template_list(stage: str | None) -> TemplateList:
    return TemplateList(
        templates=[
            TemplateSummary(**t.model_dump(exclude={"fields"}))
            for t in get_templates().values()
            if stage is None or stage in t.stages
        ]
    )


def template_detail(template_id: str) -> TemplateDetail:
    return TemplateDetail(**get_template(template_id).model_dump())


def _blank(hint: str) -> Sentence:
    return Sentence(text=hint, source=None)


def _records(db: Session, user: User, app_records: AppRecords) -> list[Sentence]:
    """완료 체크리스트(3.13 순서) → 시뮬레이션 횟수 → 저장한 정책 수"""
    records = [
        Sentence(
            text=f"{s.policy.name} 신청 절차를 스스로 확인하고 서류를 준비해 신청을 마쳤습니다.",
            source=f"체크리스트 완료 · {s.policy.name}",
        )
        for s in user_checklists(db, user)
        if s.next_step is None
    ]
    sims = db.scalar(select(func.count()).select_from(Simulation).where(Simulation.user_id == user.id))
    if sims:
        records.append(Sentence(
            text=f"생활비 계획을 {sims}번 세워 보며 지출을 관리하는 연습을 했습니다.",
            source=f"생활 시뮬레이션 {sims}회",
        ))
    saved = app_records.saved_policy_count
    if saved:
        records.append(Sentence(
            text=f"자립에 필요한 제도 {saved}가지를 찾아보고 정리했습니다.",
            source=f"저장한 정책 {saved}개",
        ))
    return records


def generate(db: Session, user: User, body: DocumentRequest) -> DocumentResponse:
    app_records = body.app_records or AppRecords()
    records = _records(db, user, app_records)
    interest = (
        [Sentence(text=app_records.interest_summary, source=INTEREST_SOURCE)]
        if app_records.interest_summary
        else []
    )

    def or_blank(hint: str) -> list[Sentence]:
        return records or [_blank(hint)]

    if body.type == "activity_record":
        sections = [
            Section(title="관심 분야", sentences=interest + [_blank("관심을 갖게 된 계기를 직접 적어 주세요.")]),
            Section(title="활동 내용", sentences=or_blank("참여한 활동(동아리, 봉사, 프로그램)을 적어 주세요.")),
            Section(title="배운 점과 계획", sentences=[_blank("활동을 통해 배운 점과 앞으로의 계획을 적어 주세요.")]),
        ]
    elif body.type == "resume":
        sections = [
            Section(title="기본 정보", sentences=[_blank("이름, 연락처, 이메일을 적어 주세요.")]),
            Section(title="학력", sentences=[_blank("최종 학교부터 적어 주세요.")]),
            Section(title="경험·활동", sentences=or_blank("아르바이트, 교육, 봉사 경험을 적어 주세요.")),
            Section(title="자격증", sentences=[_blank("가진 자격증을 적어 주세요.")]),
        ]
    else:
        sections = [
            Section(title="나를 소개합니다", sentences=interest + [_blank("나를 한 문장으로 표현해 보세요.")]),
            Section(title="준비해 온 과정", sentences=or_blank("목표를 위해 해 온 일을 적어 주세요.")),
            Section(title="앞으로의 계획", sentences=[_blank("지원한 곳에서 하고 싶은 일을 적어 주세요.")]),
        ]
    # 흥미 탐색 결과는 기록 수에 안 셈 (앱과 같음)
    return DocumentResponse(type=body.type, sections=sections, notice=NOTICE_RECORDS if records else NOTICE_EMPTY)
