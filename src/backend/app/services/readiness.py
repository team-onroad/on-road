"""자립 준비도: 앱 기능 기록을 6개 영역(주거·돈·일·서류·안전·생활)으로 모아 점수와 다음 할 일을 만든다.

점수 계산 (세분화)
- 항목마다 0~1 사이 진행도(progress)를 매긴다. 다 하면 1 (완료), 하다 만 것도 그만큼 점수에 들어간다
  - 실전 연습: 안 함 0 · 해 봄 0.5 · 놓친 것 1개 이하이거나 60점 이상 0.75 · 다 챙김이거나 80점 이상 1
  - '완벽하게 대처' 항목: 놓친 것이 적을수록 (1개 0.75 · 2개 0.5 · 3개 이상 0.25), 다 챙기면 1
  - 부족한 곳 없는 생활비: 부족 0곳 1 · 1곳 0.67 · 2곳 0.33
  - 모자람 없이 한 달: 가장 잘 버틴 달 기준, 모자란 돈이 적을수록 (최대 0.75), 안 모자라면 1
  - 서류 끝까지 / 신청 준비 끝까지: 가장 많이 한 서류 · 체크리스트 진행률만큼 (최대 0.9), 끝내면 1
  - 한 달 살아보기 두 번: 한 번 0.5
- 영역 점수 = 항목 진행도 평균 × 100, 전체 점수 = 영역 점수 평균

함께 주는 것 (고도화)
- 지난 4주 점수 흐름(history)과 이번 주에 오른 점수(week_delta)
- 이번 주에 새로 해낸 것(recent)
- 다음 단계까지 남은 점수(next_level)
- 항목마다 지금 상태 한 줄(detail): 연습 횟수 · 최고 점수, 생활비 부족 곳 수 등

쓰는 기록 (모두 DB)
- 실전 연습: activity_logs(practice) + 기록에 남긴 roleplay_results
- 한 달 살아보기: activity_logs(month_life)
- 생활비 배분: simulations (가장 최근 결과의 부족 항목)
- 서류 작성: documents (작성 시작 · 내보내기)
- 정책 신청 준비: checklists (시작 · 모든 단계 완료)
"""

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.schemas.readiness import (
    ActivityCreate,
    ReadinessArea,
    ReadinessItem,
    ReadinessLevel,
    ReadinessPoint,
    ReadinessResponse,
)
from app.schemas.roleplay import NextStepOut
from app.services.home import shortage_count, user_checklists
from db.models import ActivityLog, Document, DocumentField, RoleplayResult, Simulation, User

NEXT_STEPS_MAX = 3
LEVELS = ((85, "자립 준비 완료"), (60, "거의 다 왔어요"), (30, "준비 중"), (0, "첫걸음"))
HISTORY_WEEKS = 4  # 점수 흐름: 지금 포함 4개 (3주 전 · 2주 전 · 1주 전 · 지금)
RECENT_MAX = 5


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
    # 상황별 연습 횟수 · 최고 점수 (항목 설명용, 점수 계산에는 안 씀)
    practice_count: dict[str, int] = field(default_factory=dict)
    practice_best: dict[str, int] = field(default_factory=dict)
    practice_min_missed: dict[str, int] = field(default_factory=dict)  # 가장 적게 놓친 개수
    doc_best: float = 0.0  # 가장 많이 쓴 서류의 칸 진행률 (0~1)
    checklist_best: float = 0.0  # 가장 많이 한 체크리스트 진행률 (0~1)


@dataclass(frozen=True)
class Rows:
    """준비도에 쓰는 기록을 한 번에 읽어 둔다 (지난 몇 주 점수를 다시 셀 때 DB 를 또 읽지 않게)"""

    logs: list
    saved: list
    simulations: list
    docs: list
    checklists: list
    doc_progress: dict  # 서류 id → (완료 칸, 전체 칸)


def _rows(db: Session, user: User) -> Rows:
    docs = list(db.scalars(select(Document).where(Document.user_id == user.id)))
    return Rows(
        logs=list(db.scalars(
            select(ActivityLog).where(ActivityLog.user_id == user.id)
            .order_by(ActivityLog.created_at.desc(), ActivityLog.id.desc())
        )),
        saved=list(db.scalars(select(RoleplayResult).where(RoleplayResult.user_id == user.id))),
        simulations=list(db.scalars(
            select(Simulation).where(Simulation.user_id == user.id)
            .order_by(Simulation.created_at.desc(), Simulation.id.desc())
        )),
        docs=docs,
        checklists=user_checklists(db, user),
        doc_progress=_doc_progress(db, [d.id for d in docs]),
    )


def _doc_progress(db: Session, doc_ids: list[int]) -> dict[int, tuple[int, int]]:
    if not doc_ids:
        return {}
    out: dict[int, tuple[int, int]] = {}
    for row in db.scalars(select(DocumentField).where(DocumentField.document_id.in_(doc_ids))):
        done, total = out.get(row.document_id, (0, 0))
        out[row.document_id] = (done + int(row.is_completed), total + 1)
    return out


def _before(t: datetime | None, until: datetime | None) -> bool:
    return until is None or (t is not None and t <= until)


def collect(db: Session, user: User, rows: Rows | None = None, until: datetime | None = None) -> Facts:
    """until 이 있으면 그때까지 한 기록만으로 센다 (지난 점수 흐름)"""
    rows = rows or _rows(db, user)
    logs = [log for log in rows.logs if _before(log.created_at, until)]
    saved = [r for r in rows.saved if _before(r.created_at, until)]
    practice = [log for log in logs if log.kind == "practice" and log.ref]
    practiced = {log.ref for log in practice} | {r.scenario_id for r in saved}
    clean = {log.ref for log in practice if log.data.get("missed", 1) == 0} | {r.scenario_id for r in saved if not r.missed}
    count: dict[str, int] = {}
    best: dict[str, int] = {}
    min_missed: dict[str, int] = {}
    for log in practice:
        count[log.ref] = count.get(log.ref, 0) + 1
        score = log.data.get("score")
        if isinstance(score, int):
            best[log.ref] = max(best.get(log.ref, 0), score)
        missed = log.data.get("missed")
        if isinstance(missed, int):
            min_missed[log.ref] = min(min_missed.get(log.ref, missed), missed)
    for r in saved:
        min_missed[r.scenario_id] = min(min_missed.get(r.scenario_id, len(r.missed)), len(r.missed))
    months = tuple(log.data for log in logs if log.kind == "month_life")
    latest = next((s for s in rows.simulations if _before(s.created_at, until)), None)
    docs = [d for d in rows.docs if _before(d.created_at, until)]
    checklists = [c for c in rows.checklists if _before(c.checklist.created_at, until)]
    return Facts(
        practiced=frozenset(practiced),
        clean=frozenset(clean),
        months=months,
        has_simulation=latest is not None,
        latest_shortages=shortage_count(latest) if latest else None,
        doc_started=bool(docs),
        doc_exported=any(d.status == "exported" and _before(d.exported_at, until) for d in docs),
        checklist_started=bool(checklists),
        # 끝낸 때가 따로 없어서 마지막으로 고친 때로 본다
        checklist_done=any(c.next_step is None and _before(c.checklist.updated_at, until) for c in checklists),
        housing_checklist=any(c.policy.category == "주거" for c in checklists),
        practice_count=count,
        practice_best=best,
        practice_min_missed=min_missed,
        doc_best=max((done / total for done, total in (rows.doc_progress.get(d.id, (0, 0)) for d in docs) if total), default=0.0),
        checklist_best=max((c.progress.checked_steps / max(c.progress.total_steps, 1) for c in checklists), default=0.0),
    )


@dataclass(frozen=True)
class Item:
    key: str
    label: str
    done: Callable[[Facts], bool]
    step: NextStepOut
    detail: Callable[[Facts], str | None] = lambda f: None
    # 하다 만 정도 (0~1). 없으면 done 으로 0 또는 1
    partial: Callable[[Facts], float] | None = None

    def progress(self, f: Facts) -> float:
        if self.done(f):
            return 1.0
        return max(0.0, min(0.99, self.partial(f))) if self.partial else 0.0


def _practice_detail(sid: str, clean: bool) -> Callable[[Facts], str | None]:
    def detail(f: Facts) -> str | None:
        n = f.practice_count.get(sid, 0) + (1 if sid in f.practiced and sid not in f.practice_count else 0)
        if n == 0:
            return "아직 안 해 봤어요"
        best = f.practice_best.get(sid)
        text = f"{n}번 해 봤어요" + (f" · 최고 {best}점" if best is not None else "")
        if clean and sid not in f.clean:
            text += " · 챙길 것을 모두 챙기면 완료"
        return text
    return detail


def _practice(sid: str, label: str, clean: bool = False) -> Item:
    def done(f: Facts) -> bool:
        if clean:
            return sid in f.clean
        return sid in f.clean or f.practice_best.get(sid, 0) >= 80

    def partial(f: Facts) -> float:
        if sid not in f.practiced:
            return 0.0
        missed = f.practice_min_missed.get(sid)
        if clean:
            return 1 - min(missed if missed is not None else 3, 3) / 4
        if f.practice_best.get(sid, 0) >= 60 or (missed is not None and missed <= 1):
            return 0.75
        return 0.5

    return Item(f"practice-{sid}{'-clean' if clean else ''}", label, done,
                NextStepOut(kind="practice", label=f"{label} 하러 가기", scenario_id=sid),
                _practice_detail(sid, clean), partial)


def _month_detail(f: Facts) -> str | None:
    if not f.months:
        return "아직 안 해 봤어요"
    last = f.months[0]
    return f"{len(f.months)}번 해 봤어요 · 지난번 남은 돈 {last.get('left', 0)}만원"


def _budget_detail(f: Facts) -> str | None:
    if not f.has_simulation:
        return "아직 계획이 없어요"
    if f.latest_shortages:
        return f"지난 계획에서 부족한 곳 {f.latest_shortages}곳"
    return "지난 계획은 부족한 곳이 없어요"


def _month(
    key: str, label: str, done: Callable[[Facts], bool], step_label: str,
    partial: Callable[[Facts], float] | None = None,
) -> Item:
    return Item(key, label, done, NextStepOut(kind="month", label=step_label), _month_detail, partial)


AREAS: tuple[tuple[str, str, tuple[Item, ...]], ...] = (
    ("housing", "주거", (
        _practice("realestate", "원룸 계약 연습"),
        _practice("community-center", "전입신고 연습"),
        _practice("landlord", "집주인에게 수리 요청 연습"),
        Item("housing-policy", "주거 지원 신청 준비 시작", lambda f: f.housing_checklist,
             NextStepOut(kind="search", label="주거 지원 찾아보기", query="자립준비청년 주거 지원 공공임대 전세임대")),
    )),
    ("money", "돈", (
        Item("budget", "생활비 계획 세우기", lambda f: f.has_simulation, NextStepOut(kind="budget", label="생활비 계획 세우기"),
             _budget_detail),
        Item("budget-balanced", "부족한 곳 없는 생활비 계획", lambda f: f.latest_shortages == 0,
             NextStepOut(kind="budget", label="부족한 곳 다시 맞춰 보기"), _budget_detail,
             lambda f: {1: 0.67, 2: 0.33}.get(f.latest_shortages or 0, 0.0) if f.latest_shortages else 0.0),
        _practice("bank", "은행 계좌 개설 연습"),
        _month("month-no-shortage", "모자람 없이 한 달 버티기",
               lambda f: any(m.get("shortage", 1) == 0 for m in f.months), "한 달 살아보기에서 버텨 보기",
               # 가장 잘 버틴 달: 모자란 돈(만원)이 적을수록, 30만원 이상 모자라면 0
               lambda f: 0.75 * max(0.0, 1 - min(m.get("shortage", 30) for m in f.months) / 30) if f.months else 0.0),
    )),
    ("job", "일", (
        _practice("parttime", "아르바이트 면접 연습"),
        _practice("job", "취업 면접 연습"),
        _practice("wage", "밀린 알바비 받기 연습"),
        _practice("jobcenter", "고용센터 상담 연습"),
    )),
    ("documents", "서류", (
        Item("doc-start", "서류 하나 써 보기", lambda f: f.doc_started, NextStepOut(kind="forms", label="서류 작성 도우미 열기")),
        Item("doc-export", "서류 끝까지 완성하기", lambda f: f.doc_exported, NextStepOut(kind="forms", label="쓰던 서류 마무리하기"),
             lambda f: f"가장 많이 쓴 서류 {round(f.doc_best * 100)}%" if f.doc_started and not f.doc_exported else None,
             lambda f: 0.9 * f.doc_best),
        Item("checklist-start", "정책 신청 준비 시작하기", lambda f: f.checklist_started,
             NextStepOut(kind="search", label="받을 수 있는 정책 찾아보기", query="자립준비청년 지원")),
        Item("checklist-done", "신청 준비 끝까지 하기", lambda f: f.checklist_done,
             NextStepOut(kind="search", label="신청할 정책 찾아보기", query="자립준비청년 자립수당 자립정착금"),
             lambda f: f"가장 많이 한 신청 준비 {round(f.checklist_best * 100)}%" if f.checklist_started and not f.checklist_done else None,
             lambda f: 0.9 * f.checklist_best),
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
        _month("month-twice", "한 달 살아보기 두 번 해 보기", lambda f: len(f.months) >= 2, "다른 한 달 살아보기",
               lambda f: 0.5 if f.months else 0.0),
    )),
)


def _score(facts: Facts) -> tuple[int, list[ReadinessArea], dict[str, bool]]:
    areas: list[ReadinessArea] = []
    marks_all: dict[str, bool] = {}
    for key, label, items in AREAS:
        marks = [(i, i.progress(facts)) for i in items]
        done_count = sum(p >= 1 for _, p in marks)
        todo = next((i for i, p in marks if p < 1), None)
        for i, p in marks:
            marks_all[i.key] = marks_all.get(i.key, False) or p >= 1
        areas.append(ReadinessArea(
            key=key, label=label,
            score=round(100 * sum(p for _, p in marks) / len(items)),
            done_count=done_count, total=len(items),
            items=[
                ReadinessItem(key=i.key, label=i.label, done=p >= 1, progress=round(p, 2), detail=i.detail(facts))
                for i, p in marks
            ],
            next_step=todo.step if todo else None,
        ))
    return round(sum(a.score for a in areas) / len(areas)), areas, marks_all


def _level(score: int) -> str:
    return next(name for cut, name in LEVELS if score >= cut)


def _next_level(score: int) -> ReadinessLevel | None:
    """다음 단계 이름과 남은 점수 (이미 맨 위면 null)"""
    higher = [(cut, name) for cut, name in LEVELS if cut > score]
    if not higher:
        return None
    cut, name = min(higher)
    return ReadinessLevel(label=name, points=cut - score)


def readiness(db: Session, user: User, now: datetime | None = None) -> ReadinessResponse:
    now = now or datetime.now(UTC)
    rows = _rows(db, user)
    score, areas, marks = _score(collect(db, user, rows))

    # 지난 4주 흐름: 일주일 간격으로 그때까지의 기록만 다시 센다
    history: list[ReadinessPoint] = []
    past_marks: dict[str, bool] = {}
    for weeks_ago in range(HISTORY_WEEKS - 1, 0, -1):
        until = now - timedelta(weeks=weeks_ago)
        s, _, m = _score(collect(db, user, rows, until))
        history.append(ReadinessPoint(weeks_ago=weeks_ago, score=s))
        if weeks_ago == 1:
            past_marks = m
    history.append(ReadinessPoint(weeks_ago=0, score=score))
    week_delta = score - history[-2].score

    labels = {i.key: i.label for _, _, items in AREAS for i in items}
    recent = [labels[k] for k, d in marks.items() if d and not past_marks.get(k, False)][:RECENT_MAX]

    open_areas = sorted((a for a in areas if a.next_step), key=lambda a: a.score)  # 낮은 순 (같으면 원래 순서)
    return ReadinessResponse(
        score=score,
        level=_level(score),
        areas=areas,
        weakest=open_areas[0].key if open_areas else None,
        next_steps=[a.next_step for a in open_areas[:NEXT_STEPS_MAX] if a.next_step],
        week_delta=week_delta,
        history=history,
        recent=recent,
        next_level=_next_level(score),
    )
