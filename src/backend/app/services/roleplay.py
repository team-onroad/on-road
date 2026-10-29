"""AI 롤플레잉 (api.md 3.16~3.19). 대화 내용은 저장·로그 안 함"""

from sqlalchemy.orm import Session

from app.crisis import CRISIS_MESSAGE, is_crisis
from app.llm_client import Llm, call_llm
from app.roleplay import FINISHED_GOOD, FINISHED_MIN_TURNS, Plan, Scenario, feedback_items, get_scenarios, grade, level_for, lines_for, pick_plan, plan_of
from app.schemas.roleplay import (
    BetterOut,
    FeedbackItemOut,
    FeedbackRequest,
    FeedbackResponse,
    Opening,
    ReplyRequest,
    ReplyResponse,
    ScenarioList,
    ScenarioOut,
)
from db.models import RoleplayResult, User


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


def give_feedback(db: Session, user: User, s: Scenario, body: FeedbackRequest) -> FeedbackResponse:
    plan = plan_of(s, body.variant_id, body.surprise_ids)
    turns = [h.content for h in body.history if h.role == "user"]
    items, score = feedback_items(s, turns, plan)
    good = [i.label for i in items if i.ok]
    missed = [i.label for i in items if not i.ok]
    if len(turns) >= FINISHED_MIN_TURNS:
        good.append(FINISHED_GOOD)
    roleplay_id = None
    if body.save:
        row = RoleplayResult(user_id=user.id, scenario_id=s.scenario_id, good=good, missed=missed)
        db.add(row)
        db.commit()
        roleplay_id = row.id
    better = list(s.better) + (list(plan.variant.better) if plan.variant else [])
    return FeedbackResponse(
        roleplay_id=roleplay_id,
        good=good,
        missed=missed,
        documents=s.documents,
        better=[BetterOut(before=b.before, after=b.after) for b in better],
        score=score,
        grade=grade(score),
        items=[FeedbackItemOut(label=i.label, ok=i.ok, kind=i.kind, example=i.example) for i in items],
        situation=plan.variant.title if plan.variant and plan.variant.situation else None,
        retry_lines=[i.example for i in items if not i.ok and i.example],
    )
