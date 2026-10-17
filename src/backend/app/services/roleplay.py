"""AI 롤플레잉 (api.md 3.16~3.19). 대화 내용은 저장·로그 안 함"""

from sqlalchemy.orm import Session

from app.crisis import CRISIS_MESSAGE, is_crisis
from app.llm_client import Llm, call_llm
from app.roleplay import Scenario, feedback, get_scenarios, level_for, lines_for
from app.schemas.roleplay import (
    BetterOut,
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


def opening(s: Scenario, stage: str | None) -> Opening:
    return Opening(scenario_id=s.scenario_id, level=level_for(s, stage), message=s.opening)


def _llm_scenario(s: Scenario, stage: str | None) -> dict:
    # 체크 항목·준비 서류·고친 말 예시는 넘기지 않음
    level = level_for(s, stage)
    return {
        "scenario_id": s.scenario_id,
        "title": s.title,
        "partner": s.partner,
        "setting": s.setting,
        "goal": s.goal,
        "level": level,
        "lines": lines_for(s, level),
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
        _llm_scenario(s, body.stage),
        [h.model_dump() for h in body.history],
        body.message,
    )
    return ReplyResponse(reply=result["reply"], is_finished=result["is_finished"])


def give_feedback(db: Session, user: User, s: Scenario, body: FeedbackRequest) -> FeedbackResponse:
    good, missed = feedback(s, [h.content for h in body.history if h.role == "user"])
    roleplay_id = None
    if body.save:
        row = RoleplayResult(user_id=user.id, scenario_id=s.scenario_id, good=good, missed=missed)
        db.add(row)
        db.commit()
        roleplay_id = row.id
    return FeedbackResponse(
        roleplay_id=roleplay_id,
        good=good,
        missed=missed,
        documents=s.documents,
        better=[BetterOut(before=b.before, after=b.after) for b in s.better],
    )
