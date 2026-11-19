"""자립 준비도: 앱에서 한 행동 기록을 6개 영역(주거·돈·일·서류·안전·생활)으로 모아 판단한다.

'해 봤으면 점수'가 아니라 행동을 따라가며 판단한다
- 실전 연습: 상황마다 연습 기록을 시간순으로 본다
  - 최근 3번을 최근일수록 무겁게 평균 (예전 한 번 잘한 것보다 지금 실력)
  - 최근 2번 연속 잘하면 꾸준함으로 더 쳐 준다
  - 오래 안 하면 줄어든다 (30일 넘으면 0.85배, 60일 넘으면 0.7배)
  - '빠짐없이' 항목은 가장 최근 연습에서 다 챙겼는지로 본다 (한 번 다 챙긴 적 있음이 아니라)
  - 같은 세션의 활동 기록과 저장된 결과는 하나로 친다
- 생활비 · 한 달 살아보기: 가장 최근 결과로 본다 (예전 최고 기록이 아니라)
- 서류 · 신청 준비: 실제로 채운 칸 · 끝낸 단계만큼
- 영역 점수 = 항목 진행도 평균 × 100, 전체 점수 = 영역 점수 평균

판단 근거 (insights): 행동 흐름에서 읽은 것을 한 줄씩
- 다시 챙겨 볼 것: 같은 항목을 연속으로 놓침 · 오래 연습 안 함 · 비상금을 연속으로 꺼냄 · 문자 사기 · 쓰다 멈춘 서류 · 기록이 끊김
- 잘하고 있는 것: 점수가 오름 · 연속으로 다 챙김 · 부족했던 생활비를 다시 맞춤 · 모자란 돈이 줄어듦
다음에 할 것은 다시 챙겨 볼 것이 있는 영역부터, 그다음 점수가 낮은 영역 순서

쓰는 기록 (모두 DB)
- 실전 연습: activity_logs(practice) + 기록에 남긴 roleplay_results (놓친 항목 이름)
- 한 달 살아보기: activity_logs(month_life)
- 생활비 배분: simulations (최근 결과와 그 전 결과)
- 서류 작성: documents (작성 시작 · 내보내기 · 마지막으로 고친 때)
- 정책 신청 준비: checklists (시작 · 모든 단계 완료)
"""

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.roleplay import get_scenarios
from app.schemas.readiness import (
    ActivityCreate,
    ReadinessArea,
    ReadinessInsight,
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
INSIGHTS_MAX = 4
WATCH_MAX = 2  # 다시 챙겨 볼 것은 최대 2개 (잘하는 것도 꼭 보이게)
MERGE_MINUTES = 10  # 같은 상황의 활동 기록과 저장 결과가 이 안이면 같은 연습
RECENT_ATTEMPTS = 3  # 실력은 최근 몇 번으로 보나
GOOD_QUALITY = 0.8  # 이만큼이면 '잘함'
DONE_COMPETENCY = 0.9  # 연습 항목 완료 기준 (오래 안 해서 줄어든 실력은 완료가 아님)
FADE = ((30, 1.0), (60, 0.85))  # (일, 배수). 60일 넘으면 FADE_FLOOR
FADE_FLOOR = 0.7
STALE_DOC_DAYS = 14  # 쓰다 멈춘 서류
IDLE_DAYS = 14  # 기록이 끊긴 것으로 보는 날


def add_activity(db: Session, user: User, body: ActivityCreate) -> int:
    row = ActivityLog(user_id=user.id, kind=body.kind, ref=body.ref, data=body.data)
    db.add(row)
    db.commit()
    return row.id


@dataclass(frozen=True)
class Attempt:
    """연습 한 번 (활동 기록과 저장 결과를 합친 것)"""

    at: datetime | None
    score: int | None = None
    missed: int | None = None
    missed_labels: tuple[str, ...] = ()

    @property
    def quality(self) -> float:
        """0~1. 점수와 놓친 개수가 둘 다 있으면 평균"""
        parts = []
        if self.score is not None:
            parts.append(self.score / 100)
        if self.missed is not None:
            parts.append(1.0 if self.missed == 0 else max(0.4, 0.9 - 0.15 * self.missed))
        return sum(parts) / len(parts) if parts else 0.5


def _attempts(practice_logs: list, saved: list) -> dict[str, tuple[Attempt, ...]]:
    """상황별 연습을 시간순으로. 같은 세션(10분 안)의 활동 기록과 저장 결과는 하나로"""
    out: dict[str, list[Attempt]] = {}
    for log in practice_logs:
        score = log.data.get("score")
        missed = log.data.get("missed")
        out.setdefault(log.ref, []).append(Attempt(
            log.created_at, score if isinstance(score, int) else None, missed if isinstance(missed, int) else None,
        ))
    for r in saved:
        labels = tuple(str(x) for x in r.missed)
        items = out.setdefault(r.scenario_id, [])
        twin = next((i for i, a in enumerate(items) if a.at and r.created_at and not a.missed_labels
                     and abs((a.at - r.created_at).total_seconds()) <= MERGE_MINUTES * 60), None)
        if twin is not None:
            a = items[twin]
            items[twin] = Attempt(a.at, a.score, len(labels), labels)
        else:
            items.append(Attempt(r.created_at, None, len(labels), labels))
    far = datetime.min.replace(tzinfo=UTC)
    return {k: tuple(sorted(v, key=lambda a: a.at or far)) for k, v in out.items()}


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
    # 세분화: 분야별 신청 준비 · 서류 개수 · 생활비 계획의 집세
    checklist_categories: frozenset[str] = frozenset()  # 신청 준비를 시작한 정책 분야
    checklist_done_categories: frozenset[str] = frozenset()  # 신청 준비를 끝낸 정책 분야
    category_best: dict[str, float] = field(default_factory=dict)  # 분야별 가장 많이 한 진행률
    doc_count: int = 0
    checklist_count: int = 0
    sim_housing: bool = False  # 가장 최근 생활비 계획에 집세가 들어 있음
    # 행동 흐름
    now: datetime | None = None
    attempts: dict[str, tuple[Attempt, ...]] = field(default_factory=dict)  # 상황별 연습 (시간순)
    sim_shortages: tuple[int, ...] = ()  # 생활비 계획 부족 곳 수 (최근 순)
    stale_docs: int = 0  # 2주 넘게 손대지 않은 쓰다 만 서류
    last_active: datetime | None = None  # 마지막으로 무언가 한 때


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
    sims = [s for s in rows.simulations if _before(s.created_at, until)]
    docs = [d for d in rows.docs if _before(d.created_at, until)]
    checklists = [c for c in rows.checklists if _before(c.checklist.created_at, until)]
    now = until or datetime.now(UTC)
    stamps = [t for t in (
        [log.created_at for log in logs] + [r.created_at for r in saved] + [x.created_at for x in sims]
        + [d.updated_at for d in docs] + [c.checklist.updated_at for c in checklists]
    ) if t is not None and t <= now]
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
        housing_checklist=any(c.policy.category in CATEGORY_CODES["housing"] for c in checklists),
        practice_count=count,
        practice_best=best,
        practice_min_missed=min_missed,
        doc_best=max((done / total for done, total in (rows.doc_progress.get(d.id, (0, 0)) for d in docs) if total), default=0.0),
        checklist_best=max((c.progress.checked_steps / max(c.progress.total_steps, 1) for c in checklists), default=0.0),
        checklist_categories=frozenset(c.policy.category for c in checklists),
        checklist_done_categories=frozenset(
            c.policy.category for c in checklists if c.next_step is None and _before(c.checklist.updated_at, until)
        ),
        category_best=_category_best(checklists),
        doc_count=len(docs),
        checklist_count=len(checklists),
        sim_housing=latest is not None and (latest.housing or 0) > 0,
        now=now,
        attempts=_attempts(practice, saved),
        sim_shortages=tuple(shortage_count(x) for x in sims),
        stale_docs=sum(
            1 for d in docs
            if d.status != "exported" and d.updated_at is not None and (now - d.updated_at).days >= STALE_DOC_DAYS
        ),
        last_active=max(stamps, default=None),
    )


def _category_best(checklists: list) -> dict[str, float]:
    best: dict[str, float] = {}
    for c in checklists:
        p = c.progress.checked_steps / max(c.progress.total_steps, 1)
        best[c.policy.category] = max(best.get(c.policy.category, 0.0), p)
    return best


# 정책 분야 코드 (app/codes.py POLICY_CATEGORIES). 예전 한글 값도 같이 받는다
CATEGORY_CODES: dict[str, tuple[str, ...]] = {
    "housing": ("housing", "주거"),
    "money": ("living_cost", "finance", "independence", "생활·금융", "자립"),
    "job": ("employment", "취업"),
    "health": ("medical", "건강"),
}


def _in(cats: frozenset[str], group: str) -> bool:
    return any(c in cats for c in CATEGORY_CODES[group])


def _group_best(f: "Facts", group: str) -> float:
    return max((f.category_best.get(c, 0.0) for c in CATEGORY_CODES[group]), default=0.0)


def _policy_start(key: str, group: str, label: str, step_label: str, query: str) -> "Item":
    return Item(key, label, lambda f: _in(f.checklist_categories, group),
                NextStepOut(kind="search", label=step_label, query=query))


def _policy_done(key: str, group: str, label: str, step_label: str, query: str) -> "Item":
    return Item(key, label, lambda f: _in(f.checklist_done_categories, group),
                NextStepOut(kind="search", label=step_label, query=query),
                lambda f: f"신청 준비 {round(_group_best(f, group) * 100)}%" if _in(f.checklist_categories, group) else None,
                lambda f: 0.9 * _group_best(f, group))


def _count_detail(n: int, unit: str) -> str | None:
    return f"{n}가지 {unit}" if n else None


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


def _days_since(f: Facts, t: datetime | None) -> int | None:
    if t is None or f.now is None:
        return None
    return max(0, (f.now - t).days)


def _fade(days: int | None) -> float:
    if days is None:
        return 1.0
    return next((m for limit, m in FADE if days <= limit), FADE_FLOOR)


def competency(f: Facts, sid: str) -> float:
    """상황 하나의 지금 실력 (0~1): 최근 3번을 최근일수록 무겁게, 연속으로 잘하면 더, 오래 안 하면 덜"""
    att = f.attempts.get(sid, ())
    if not att:
        return 0.0
    last = att[-RECENT_ATTEMPTS:]
    weights = range(1, len(last) + 1)
    value = sum(w * a.quality for w, a in zip(weights, last)) / sum(weights)
    if len(att) >= 2 and att[-1].quality >= GOOD_QUALITY and att[-2].quality >= GOOD_QUALITY:
        value += 0.1  # 꾸준함
    return round(min(1.0, value) * _fade(_days_since(f, att[-1].at)), 3)


def _practice_detail(sid: str, clean: bool) -> Callable[[Facts], str | None]:
    def detail(f: Facts) -> str | None:
        att = f.attempts.get(sid, ())
        if not att:
            return "아직 안 해 봤어요"
        text = f"{len(att)}번 해 봤어요"
        last = att[-1]
        if last.score is not None:
            text += f" · 최근 {last.score}점"
        elif last.missed is not None:
            text += " · 최근 다 챙겼어요" if last.missed == 0 else f" · 최근 {last.missed}개 놓쳤어요"
        days = _days_since(f, last.at)
        if days is not None and days > FADE[0][0]:
            text += f" · {days}일 전"
        if clean and last.missed != 0:
            text += " · 다음엔 빠짐없이"
        return text
    return detail


def _practice(sid: str, label: str, clean: bool = False) -> Item:
    def done(f: Facts) -> bool:
        att = f.attempts.get(sid, ())
        if clean:  # 가장 최근 연습에서 다 챙겼고, 너무 오래되지 않음
            return bool(att) and att[-1].missed == 0 and _fade(_days_since(f, att[-1].at)) >= FADE[1][1]
        return competency(f, sid) >= DONE_COMPETENCY

    def partial(f: Facts) -> float:
        c = competency(f, sid)
        return 0.9 * c if clean else c

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
        _practice("realestate", "원룸 계약 빠짐없이 하기", clean=True),
        _practice("community-center", "전입신고 연습"),
        _practice("landlord", "집주인에게 수리 요청 연습"),
        Item("housing-policy", "주거 지원 신청 준비 시작", lambda f: f.housing_checklist,
             NextStepOut(kind="search", label="주거 지원 찾아보기", query="자립준비청년 주거 지원 공공임대 전세임대")),
        _policy_done("housing-policy-done", "housing", "주거 지원 신청 준비 끝까지 하기", "주거 지원 신청 이어서 하기",
                     "자립준비청년 주거 지원 공공임대 전세임대"),
        Item("budget-housing", "생활비 계획에 집세 넣기", lambda f: f.sim_housing,
             NextStepOut(kind="budget", label="생활비 계획에 집세 넣기")),
    )),
    ("money", "돈", (
        Item("budget", "생활비 계획 세우기", lambda f: f.has_simulation, NextStepOut(kind="budget", label="생활비 계획 세우기"),
             _budget_detail),
        Item("budget-balanced", "부족한 곳 없는 생활비 계획", lambda f: f.latest_shortages == 0,
             NextStepOut(kind="budget", label="부족한 곳 다시 맞춰 보기"), _budget_detail,
             lambda f: {1: 0.67, 2: 0.33}.get(f.latest_shortages or 0, 0.0) if f.latest_shortages else 0.0),
        _practice("bank", "은행 계좌 개설 연습"),
        _practice("bank", "은행 계좌 개설 빠짐없이 하기", clean=True),
        _month("month-no-shortage", "모자람 없이 한 달 버티기",
               lambda f: bool(f.months) and f.months[0].get("shortage", 1) == 0, "한 달 살아보기에서 버텨 보기",
               # 가장 최근 달: 모자란 돈(만원)이 적을수록, 30만원 이상 모자라면 0
               lambda f: 0.75 * max(0.0, 1 - f.months[0].get("shortage", 30) / 30) if f.months else 0.0),
        _month("month-no-emergency", "비상금 안 꺼내고 한 달 버티기",
               lambda f: bool(f.months) and f.months[0].get("used_emergency", 1) == 0 and f.months[0].get("shortage", 1) == 0,
               "한 달 살아보기에서 비상금 지켜 보기",
               lambda f: 0.5 if f.months and f.months[0].get("used_emergency", 1) == 0 else 0.0),
        _policy_start("money-policy", "money", "생활 지원 신청 준비 시작", "자립수당 · 정착금 찾아보기",
                      "자립준비청년 자립수당 자립정착금"),
    )),
    ("job", "일", (
        _practice("parttime", "아르바이트 면접 연습"),
        _practice("job", "취업 면접 연습"),
        _practice("job", "취업 면접 빠짐없이 하기", clean=True),
        _practice("wage", "밀린 알바비 받기 연습"),
        _practice("jobcenter", "고용센터 상담 연습"),
        _policy_start("job-policy", "job", "취업 지원 신청 준비 시작", "취업 지원 찾아보기", "자립준비청년 취업 지원"),
    )),
    ("documents", "서류", (
        Item("doc-start", "서류 하나 써 보기", lambda f: f.doc_started, NextStepOut(kind="forms", label="서류 작성 도우미 열기")),
        Item("doc-export", "서류 끝까지 완성하기", lambda f: f.doc_exported, NextStepOut(kind="forms", label="쓰던 서류 마무리하기"),
             lambda f: f"가장 많이 쓴 서류 {round(f.doc_best * 100)}%" if f.doc_started and not f.doc_exported else None,
             lambda f: 0.9 * f.doc_best),
        Item("doc-two", "서류 두 가지 써 보기", lambda f: f.doc_count >= 2,
             NextStepOut(kind="forms", label="다른 서류 써 보기"),
             lambda f: _count_detail(f.doc_count, "썼어요"), lambda f: 0.5 if f.doc_count == 1 else 0.0),
        Item("checklist-start", "정책 신청 준비 시작하기", lambda f: f.checklist_started,
             NextStepOut(kind="search", label="받을 수 있는 정책 찾아보기", query="자립준비청년 지원")),
        Item("checklist-done", "신청 준비 끝까지 하기", lambda f: f.checklist_done,
             NextStepOut(kind="search", label="신청할 정책 찾아보기", query="자립준비청년 자립수당 자립정착금"),
             lambda f: f"가장 많이 한 신청 준비 {round(f.checklist_best * 100)}%" if f.checklist_started and not f.checklist_done else None,
             lambda f: 0.9 * f.checklist_best),
        Item("checklist-two", "신청 준비 두 가지 시작하기", lambda f: f.checklist_count >= 2,
             NextStepOut(kind="search", label="다른 정책 찾아보기", query="자립준비청년 지원"),
             lambda f: _count_detail(f.checklist_count, "시작했어요"), lambda f: 0.5 if f.checklist_count == 1 else 0.0),
    )),
    ("safety", "안전", (
        _practice("scam-call", "수상한 전화 대처 연습"),
        _practice("scam-call", "수상한 전화 완벽하게 대처하기", clean=True),
        _practice("phone-store", "휴대폰 개통 연습"),
        _practice("phone-store", "휴대폰 개통 빠짐없이 하기", clean=True),
        _month("month-no-scam", "한 달 살아보기에서 사기 피하기",
               lambda f: bool(f.months) and not any("smishing" in m.get("bad_events", []) for m in f.months[:1]),
               "한 달 살아보기에서 사기 피해 보기"),
    )),
    ("life", "생활", (
        _practice("hospital", "병원 진료 연습"),
        _practice("store", "가게에서 물건 사기 연습"),
        _practice("neighbor", "층간소음 이야기 연습"),
        _month("month-done", "한 달 살아보기 끝까지 하기", lambda f: bool(f.months), "한 달 살아보기 해 보기"),
        _month("month-twice", "한 달 살아보기 두 번 해 보기", lambda f: len(f.months) >= 2, "다른 한 달 살아보기",
               lambda f: 0.5 if f.months else 0.0),
        _policy_start("health-policy", "health", "건강 지원 신청 준비 시작", "의료 지원 찾아보기", "자립준비청년 의료 지원"),
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


def _area_of_scenario() -> dict[str, str]:
    out: dict[str, str] = {}
    for key, _, items in AREAS:
        for i in items:
            if i.key.startswith("practice-"):
                out.setdefault(i.key.removeprefix("practice-").removesuffix("-clean"), key)
    return out


def _scenario_name(sid: str) -> str:
    s = get_scenarios().get(sid)
    return s.title if s else sid


def insights(f: Facts) -> list[ReadinessInsight]:
    """행동 흐름에서 읽은 판단 근거. 다시 챙겨 볼 것(최대 2) 먼저, 그다음 잘하고 있는 것"""
    watch: list[ReadinessInsight] = []
    good: list[ReadinessInsight] = []
    area_of = _area_of_scenario()

    def add(bucket: list, area: str, text: str) -> None:
        bucket.append(ReadinessInsight(area=area, tone="watch" if bucket is watch else "good", text=text))

    # 최근에 한 연습부터
    far = datetime.min.replace(tzinfo=UTC)
    for sid, att in sorted(f.attempts.items(), key=lambda kv: kv[1][-1].at or far, reverse=True):
        area = area_of.get(sid, "life")
        name = _scenario_name(sid)
        labeled = [a for a in att if a.missed_labels]
        repeated = sorted(set(labeled[-1].missed_labels) & set(labeled[-2].missed_labels)) if len(labeled) >= 2 else []
        if repeated:
            add(watch, area, f"‘{name}’에서 ‘{repeated[0]}’ 항목을 2번 연속 놓쳤어요")
        days = _days_since(f, att[-1].at)
        if days is not None and days > FADE[0][0] and att[-1].quality < 1:
            add(watch, area, f"‘{name}’ 연습을 한 지 {days}일 지났어요")
        if len(att) >= 2 and att[-1].missed == 0 and att[-2].missed == 0:
            add(good, area, f"‘{name}’에서 최근 2번 모두 빠짐없이 챙겼어요")
        scored = [a for a in att if a.score is not None]
        if len(scored) >= 2 and scored[-1].score - scored[-2].score >= 10:
            add(good, area, f"‘{name}’ 점수가 지난번보다 {scored[-1].score - scored[-2].score}점 올랐어요")

    if len(f.sim_shortages) >= 2 and f.sim_shortages[0] < f.sim_shortages[1]:
        add(good, "money", "생활비 계획에서 부족했던 곳을 다시 맞췄어요")
    elif f.sim_shortages and f.sim_shortages[0] >= 2:
        add(watch, "money", f"최근 생활비 계획에 부족한 곳이 {f.sim_shortages[0]}곳 있어요")

    m = f.months
    if m and "smishing" in m[0].get("bad_events", []):
        add(watch, "safety", "지난 한 달 살아보기에서 문자 사기를 당했어요")
    if len(m) >= 2 and m[0].get("used_emergency", 0) > 0 and m[1].get("used_emergency", 0) > 0:
        add(watch, "money", "한 달 살아보기에서 비상금을 2번 연속 꺼냈어요")
    if len(m) >= 2 and m[0].get("shortage", 0) < m[1].get("shortage", 0):
        add(good, "money", "한 달 살아보기에서 모자란 돈이 지난번보다 줄었어요")

    if f.stale_docs:
        add(watch, "documents", f"{STALE_DOC_DAYS}일 넘게 멈춘 서류가 {f.stale_docs}개 있어요")
    if f.doc_exported and f.checklist_done:
        add(good, "documents", "서류와 신청 준비를 끝까지 해냈어요")

    idle = _days_since(f, f.last_active)
    if idle is not None and idle >= IDLE_DAYS:
        add(watch, "life", f"{idle}일 동안 준비 기록이 없어요")

    return (watch[:WATCH_MAX] + good)[:INSIGHTS_MAX]


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
    score, areas, marks = _score(collect(db, user, rows, now))

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

    found = insights(collect(db, user, rows, now))
    watched = {i.area for i in found if i.tone == "watch"}
    # 다시 챙겨 볼 것이 있는 영역 먼저, 그다음 점수가 낮은 순 (같으면 원래 순서)
    open_areas = sorted((a for a in areas if a.next_step), key=lambda a: (a.key not in watched, a.score))
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
        insights=found,
    )
