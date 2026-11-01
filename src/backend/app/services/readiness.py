"""자립 준비도: 앱 기능 기록을 6개 영역(주거·돈·일·서류·안전·생활)으로 모아 점수와 다음 할 일을 만든다.

쓰는 기록 (모두 DB)
- 실전 연습: activity_logs(practice) + 기록에 남긴 roleplay_results
- 한 달 살아보기: activity_logs(month_life)
- 생활비 배분: simulations (가장 최근 결과의 부족 항목)
- 서류 작성: documents (작성 시작 · 내보내기)
- 정책 신청 준비: checklists (시작 · 모든 단계 완료)
"""

from collections.abc import Callable
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.schemas.readiness import ActivityCreate, ReadinessArea, ReadinessItem, ReadinessResponse
from app.schemas.roleplay import NextStepOut
from app.services.home import shortage_count, user_checklists
from db.models import ActivityLog, Document, RoleplayResult, Simulation, User

NEXT_STEPS_MAX = 3
LEVELS = ((85, "자립 준비 완료"), (60, "거의 다 왔어요"), (30, "준비 중"), (0, "첫걸음"))


def add_activity(db: Session, user: User, body: ActivityCreate) -> int:
    row = ActivityLog(user_id=user.id, kind=body.kind, ref=body.ref, data=body.data)
    db.add(row)
    db.commit()
    return row.id


@dataclass(frozen=True)
class Facts:
    practiced: frozenset[str]  # 한 번이라도 연습한 상황
    clean: frozenset[str]  # 놓친 것 없이 끝낸 상황
    months: tuple[dict, ...]  # 한 달 살아보기 결과 (최근 순)
    has_simulation: bool
    latest_shortages: int | None
    doc_started: bool
    doc_exported: bool
    checklist_started: bool
    checklist_done: bool
    housing_checklist: bool


def collect(db: Session, user: User) -> Facts:
    logs = list(db.scalars(
        select(ActivityLog).where(ActivityLog.user_id == user.id).order_by(ActivityLog.created_at.desc(), ActivityLog.id.desc())
    ))
    saved = list(db.scalars(select(RoleplayResult).where(RoleplayResult.user_id == user.id)))
    practice = [log for log in logs if log.kind == "practice" and log.ref]
    practiced = {log.ref for log in practice} | {r.scenario_id for r in saved}
    clean = {log.ref for log in practice if log.data.get("missed", 1) == 0} | {r.scenario_id for r in saved if not r.missed}
    months = tuple(log.data for log in logs if log.kind == "month_life")
    latest = db.scalars(
        select(Simulation).where(Simulation.user_id == user.id).order_by(Simulation.created_at.desc(), Simulation.id.desc()).limit(1)
    ).first()
    docs = list(db.scalars(select(Document).where(Document.user_id == user.id)))
    checklists = user_checklists(db, user)
    return Facts(
        practiced=frozenset(practiced),
        clean=frozenset(clean),
        months=months,
        has_simulation=latest is not None,
        latest_shortages=shortage_count(latest) if latest else None,
        doc_started=bool(docs),
        doc_exported=any(d.status == "exported" for d in docs),
        checklist_started=bool(checklists),
        checklist_done=any(c.next_step is None for c in checklists),
        housing_checklist=any(c.policy.category == "주거" for c in checklists),
    )


@dataclass(frozen=True)
class Item:
    key: str
    label: str
    done: Callable[[Facts], bool]
    step: NextStepOut


def _practice(sid: str, label: str, clean: bool = False) -> Item:
    def done(f: Facts) -> bool:
        return sid in (f.clean if clean else f.practiced)
    return Item(f"practice-{sid}{'-clean' if clean else ''}", label, done,
                NextStepOut(kind="practice", label=f"{label} 하러 가기", scenario_id=sid))


def _month(key: str, label: str, done: Callable[[Facts], bool], step_label: str) -> Item:
    return Item(key, label, done, NextStepOut(kind="month", label=step_label))


AREAS: tuple[tuple[str, str, tuple[Item, ...]], ...] = (
    ("housing", "주거", (
        _practice("realestate", "원룸 계약 연습"),
        _practice("community-center", "전입신고 연습"),
        _practice("landlord", "집주인에게 수리 요청 연습"),
        Item("housing-policy", "주거 지원 신청 준비 시작", lambda f: f.housing_checklist,
             NextStepOut(kind="search", label="주거 지원 찾아보기", query="자립준비청년 주거 지원 공공임대 전세임대")),
    )),
    ("money", "돈", (
        Item("budget", "생활비 계획 세우기", lambda f: f.has_simulation, NextStepOut(kind="budget", label="생활비 계획 세우기")),
        Item("budget-balanced", "부족한 곳 없는 생활비 계획", lambda f: f.latest_shortages == 0,
             NextStepOut(kind="budget", label="부족한 곳 다시 맞춰 보기")),
        _practice("bank", "은행 계좌 개설 연습"),
        _month("month-no-shortage", "모자람 없이 한 달 버티기",
               lambda f: any(m.get("shortage", 1) == 0 for m in f.months), "한 달 살아보기에서 버텨 보기"),
    )),
    ("job", "일", (
        _practice("parttime", "아르바이트 면접 연습"),
        _practice("job", "취업 면접 연습"),
        _practice("wage", "밀린 알바비 받기 연습"),
        _practice("jobcenter", "고용센터 상담 연습"),
    )),
    ("documents", "서류", (
        Item("doc-start", "서류 하나 써 보기", lambda f: f.doc_started, NextStepOut(kind="forms", label="서류 작성 도우미 열기")),
        Item("doc-export", "서류 끝까지 완성하기", lambda f: f.doc_exported, NextStepOut(kind="forms", label="쓰던 서류 마무리하기")),
        Item("checklist-start", "정책 신청 준비 시작하기", lambda f: f.checklist_started,
             NextStepOut(kind="search", label="받을 수 있는 정책 찾아보기", query="자립준비청년 지원")),
        Item("checklist-done", "신청 준비 끝까지 하기", lambda f: f.checklist_done,
             NextStepOut(kind="search", label="신청할 정책 찾아보기", query="자립준비청년 자립수당 자립정착금")),
    )),
    ("safety", "안전", (
        _practice("scam-call", "수상한 전화 대처 연습"),
        _practice("scam-call", "수상한 전화 완벽하게 대처하기", clean=True),
        _practice("phone-store", "휴대폰 개통 연습"),
        _month("month-no-scam", "한 달 살아보기에서 사기 피하기",
               lambda f: bool(f.months) and not any("smishing" in m.get("bad_events", []) for m in f.months[:1]),
               "한 달 살아보기에서 사기 피해 보기"),
    )),
    ("life", "생활", (
        _practice("hospital", "병원 진료 연습"),
        _practice("neighbor", "층간소음 이야기 연습"),
        _month("month-done", "한 달 살아보기 끝까지 하기", lambda f: bool(f.months), "한 달 살아보기 해 보기"),
        _month("month-twice", "한 달 살아보기 두 번 해 보기", lambda f: len(f.months) >= 2, "다른 한 달 살아보기"),
    )),
)


def readiness(db: Session, user: User) -> ReadinessResponse:
    facts = collect(db, user)
    areas: list[ReadinessArea] = []
    for key, label, items in AREAS:
        marks = [(i, i.done(facts)) for i in items]
        done_count = sum(d for _, d in marks)
        todo = next((i for i, d in marks if not d), None)
        areas.append(ReadinessArea(
            key=key, label=label,
            score=round(100 * done_count / len(items)),
            done_count=done_count, total=len(items),
            items=[ReadinessItem(key=i.key, label=i.label, done=d) for i, d in marks],
            next_step=todo.step if todo else None,
        ))
    score = round(sum(a.score for a in areas) / len(areas))
    open_areas = sorted((a for a in areas if a.next_step), key=lambda a: a.score)  # 낮은 순 (같으면 원래 순서)
    return ReadinessResponse(
        score=score,
        level=next(name for cut, name in LEVELS if score >= cut),
        areas=areas,
        weakest=open_areas[0].key if open_areas else None,
        next_steps=[a.next_step for a in open_areas[:NEXT_STEPS_MAX] if a.next_step],
    )
