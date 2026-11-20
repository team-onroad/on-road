"""AI 롤플레잉 (api.md 3.16~3.19). 대화 내용은 저장·로그 안 함"""

from sqlalchemy.orm import Session

from app.crisis import CRISIS_MESSAGE, is_crisis
from collections.abc import Iterator

from app import sse
from app.llm_client import Llm, ai_unavailable, call_llm
import logging
from collections import OrderedDict

from app.roleplay import FINISHED_GOOD, FINISHED_MIN_TURNS, Plan, Scenario, feedback_items, get_scenarios, grade, level_for, hint, lines_for, next_steps, pick_plan, my_better, plan_of, progress, all_checks, _norm
from app.schemas.roleplay import (
    BetterOut,
    FeedbackItemOut,
    FeedbackRequest,
    FeedbackResponse,
    HintCheck,
    HintRequest,
    HintResponse,
    NextStepOut,
    Opening,
    ReplyRequest,
    ReplyResponse,
    ScenarioList,
    ScenarioOut,
)
from db.models import ActivityLog, RoleplayResult, User

logger = logging.getLogger(__name__)

# AI 판정 결과를 잠깐 기억한다 (같은 대화로 힌트 · 피드백을 다시 불러도 LLM 을 또 부르지 않게). 대화 내용 대신 해시만 키로 쓴다
_JUDGE_CACHE: "OrderedDict[int, frozenset[str]]" = OrderedDict()
_JUDGE_CACHE_MAX = 512


def _turns(history) -> tuple[list[str], list[str]]:
    """사용자 말 목록과, 사용자 말마다 바로 앞의 상대 말 (되묻기 판단용)"""
    users, before, last = [], [], ""
    for h in history:
        if h.role == "assistant":
            last = h.content
        elif h.role == "user":
            users.append(h.content)
            before.append(last)
    return users, before


def _judged(s: Scenario, plan: Plan, history, llm: Llm | None) -> frozenset[str]:
    """모든 항목을 AI 가 대화 전체(상대 말 포함)로 뜻을 본다. 낱말 규칙 결과와 합친다.
    AI 가 안 되면 낱말 규칙 결과 그대로 (오류로 막지 않음)"""
    turns = [h.content for h in history if h.role == "user"]
    if llm is None or not turns:
        return frozenset()
    checks = all_checks(s, plan)
    dialogue = [{"role": h.role, "content": h.content} for h in history if h.role in ("user", "assistant")]
    key = hash((s.scenario_id, tuple(c.label for c in checks), tuple((d["role"], _norm(d["content"])) for d in dialogue)))
    if key in _JUDGE_CACHE:
        _JUDGE_CACHE.move_to_end(key)
        return _JUDGE_CACHE[key]
    try:
        done = llm.judge_checks(
            [{"label": c.label, "example": c.example or ""} for c in checks], turns, history=dialogue,
        )
        result = frozenset(x for x in done if isinstance(x, str))
    except Exception as e:  # 대화 내용은 로그에 남기지 않는다
        logger.warning("챙길 것 AI 판정 실패: %s", type(e).__name__)
        return frozenset()
    _JUDGE_CACHE[key] = result
    if len(_JUDGE_CACHE) > _JUDGE_CACHE_MAX:
        _JUDGE_CACHE.popitem(last=False)
    return result


def scenario_list(stage: str | None) -> ScenarioList:
    return ScenarioList(
        scenarios=[
            ScenarioOut(
                scenario_id=s.scenario_id,
                title=s.title,
                category=s.category,
                partner=s.partner,
                setting=s.setting,
                goal=s.goal,
                level=level_for(s, stage),
                stages=s.stages,
            )
            for s in get_scenarios().values()
            if stage is None or stage in s.stages
        ]
    )


def opening(s: Scenario, stage: str | None, variant_id: str | None = None) -> Opening:
    """할 때마다 전개를 고르고, 난이도만큼 돌발 상황을 정한다 (같은 상황 다른 전개)"""
    level = level_for(s, stage)
    plan = pick_plan(s, level, variant_id)
    v = plan.variant
    return Opening(
        scenario_id=s.scenario_id, level=level, message=s.opening,
        variant_id=v.variant_id if v else None,
        situation=v.title if v and v.situation else None,
        surprise_ids=[x.surprise_id for x in plan.surprises],
        surprise_count=len(plan.surprises),
    )


def _llm_scenario(s: Scenario, stage: str | None, plan: Plan) -> dict:
    # 체크 항목·준비 서류·고친 말 예시는 넘기지 않음
    level = level_for(s, stage)
    return {
        "scenario_id": s.scenario_id,
        "title": s.title,
        "partner": s.partner,
        "setting": s.setting,
        "goal": s.goal,
        "level": level,
        "situation": plan.variant.situation if plan.variant else "",
        "surprises": [x.line for x in plan.surprises],
        "lines": lines_for(s, level, plan),
    }


def _is_reply(v: object) -> bool:
    return (
        isinstance(v, dict)
        and isinstance(v.get("reply"), str)
        and bool(v["reply"].strip())
        and isinstance(v.get("is_finished"), bool)
    )


def reply(s: Scenario, body: ReplyRequest, llm: Llm) -> ReplyResponse:
    if is_crisis(body.message):
        return ReplyResponse(reply=CRISIS_MESSAGE, is_finished=True)
    result = call_llm(
        "roleplay_reply",
        llm.roleplay_reply,
        _is_reply,
        _llm_scenario(s, body.stage, plan_of(s, body.variant_id, body.surprise_ids)),
        [h.model_dump() for h in body.history],
        body.message,
    )
    return ReplyResponse(reply=result["reply"], is_finished=result["is_finished"])


def reply_stream(s: Scenario, body: ReplyRequest, llm: Llm) -> Iterator[str]:
    """POST /reply 와 같은 답을 문장이 나오는 대로 delta 로, 마지막에 done {reply, is_finished, replace}.
    음성 연습은 첫 문장이 오자마자 읽기 시작한다"""
    if is_crisis(body.message):
        return iter([sse.event("done", ReplyResponse(reply=CRISIS_MESSAGE, is_finished=True), replace=False)])
    scenario = _llm_scenario(s, body.stage, plan_of(s, body.variant_id, body.surprise_ids))
    history = [h.model_dump() for h in body.history]

    def events() -> Iterator[str]:
        try:
            if llm.roleplay_stream is None:  # 스트리밍 함수가 없으면 보통 답을 한 번에
                out = llm.roleplay_reply(scenario, history, body.message)
                if not _is_reply(out):
                    raise ValueError("형식 오류")
                yield sse.delta(out["reply"])
                yield sse.event("done", ReplyResponse(reply=out["reply"], is_finished=out["is_finished"]), replace=False)
                return
            for ev in llm.roleplay_stream(scenario, history, body.message):
                if isinstance(ev.get("delta"), str) and ev["delta"].strip():
                    yield sse.delta(ev["delta"])
                elif isinstance(ev.get("final"), str) and ev["final"].strip():
                    res = ReplyResponse(reply=ev["final"], is_finished=ev.get("is_finished") is True)
                    yield sse.event("done", res, replace=bool(ev.get("replace")))
                    return
            raise ValueError("마지막 답이 없음")
        except Exception as e:  # 대화 내용은 로그에 남기지 않는다
            logger.error("LLM 함수 오류: roleplay_stream %s", type(e).__name__)
            yield sse.error(ai_unavailable())

    return events()


def give_feedback(db: Session, user: User, s: Scenario, body: FeedbackRequest, llm: Llm | None = None) -> FeedbackResponse:
    plan = plan_of(s, body.variant_id, body.surprise_ids)
    turns, before = _turns(body.history)
    items, score = feedback_items(s, turns, plan, _judged(s, plan, body.history, llm), before)
    good = [i.label for i in items if i.ok]
    missed = [i.label for i in items if not i.ok]
    if len(turns) >= FINISHED_MIN_TURNS:
        good.append(FINISHED_GOOD)
    # save 와 상관없이 연습 1번으로 기록 (자립 준비도·성장 기록용)
    db.add(ActivityLog(
        user_id=user.id, kind="practice", ref=s.scenario_id,
        data={"good": len(good), "missed": len(missed), "score": score},
    ))
    row = None
    if body.save:
        row = RoleplayResult(user_id=user.id, scenario_id=s.scenario_id, good=good, missed=missed)
        db.add(row)
    db.commit()
    roleplay_id = row.id if row else None
    better = list(s.better) + (list(plan.variant.better) if plan.variant else [])
    return FeedbackResponse(
        roleplay_id=roleplay_id,
        good=good,
        missed=missed,
        documents=s.documents,
        better=[BetterOut(before=before, after=after) for before, after in my_better(better, turns)],
        score=score,
        grade=grade(score),
        items=[FeedbackItemOut(label=i.label, ok=i.ok, kind=i.kind, example=i.example) for i in items],
        situation=plan.variant.title if plan.variant and plan.variant.situation else None,
        retry_lines=[i.example for i in items if not i.ok and i.example],
        next_steps=[
            NextStepOut(kind=f.kind, label=f.label, query=f.query, scenario_id=f.scenario_id) for f in next_steps(s, items)
        ],
    )


def give_hint(s: Scenario, body: HintRequest, llm: Llm | None = None) -> HintResponse:
    """낱말 규칙 + (낱말에 안 걸린 항목만) AI 뜻 판정. 결과 화면과 같은 판정을 쓴다"""
    plan = plan_of(s, body.variant_id, body.surprise_ids)
    turns, before = _turns(body.history)
    last = next((h.content for h in reversed(body.history) if h.role == "assistant"), "")
    judged = _judged(s, plan, body.history, llm)
    h = hint(s, turns, last, plan, judged, before)
    partner = [t.content for t in body.history if t.role == "assistant"]
    checks = [HintCheck(label=i.label, ok=i.ok, kind=i.kind) for i in progress(s, turns, partner, plan, judged, before)]
    return HintResponse(
        done=h.kind == "closing", label=h.label, example=h.example, kind=h.kind, remaining=h.remaining, checks=checks,
    )
