from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.deps import DB, BodyUser, openapi_body, validated_body
from app.llm_client import Llm, get_llm
from app.roleplay import Scenario, Stage, get_scenario
from app.schemas.roleplay import (
    FeedbackRequest,
    FeedbackResponse,
    Opening,
    ReplyRequest,
    ReplyResponse,
    ScenarioList,
)
from app.services import roleplay as service
from app.services.users import get_user

router = APIRouter(prefix="/coach/roleplay", tags=["AI 롤플레잉"])


def _path_scenario(scenario_id: str) -> Scenario:
    return get_scenario(scenario_id)


# 본문보다 먼저 선언: 상황 404 → 사용자 404 → 422 (정책 상세와 같은 순서)
PathScenario = Annotated[Scenario, Depends(_path_scenario)]

NOT_FOUND = {404: {"description": "SCENARIO_NOT_FOUND"}}
BODY_ERRORS = {
    404: {"description": "SCENARIO_NOT_FOUND / USER_NOT_FOUND (user_id 형식 오류 포함)"},
    422: {"description": "VALIDATION_ERROR"},
}


@router.get(
    "/scenarios",
    response_model=ScenarioList,
    summary="연습 상황 목록",
    responses={422: {"description": "VALIDATION_ERROR"}},
)
def list_scenarios(stage: Stage | None = None) -> ScenarioList:
    return service.scenario_list(stage)


@router.get(
    "/{scenario_id}/opening",
    response_model=Opening,
    summary="첫 대사",
    responses={**NOT_FOUND, 422: {"description": "VALIDATION_ERROR"}},
)
def get_opening(scenario: PathScenario, stage: Stage | None = None, variant_id: str | None = None) -> Opening:
    """variant_id 를 주면 그 전개로 (다시 해 보기), 없으면 무작위"""
    return service.opening(scenario, stage, variant_id)


@router.post(
    "/{scenario_id}/reply",
    response_model=ReplyResponse,
    summary="대화 한 턴",
    responses={**BODY_ERRORS, 503: {"description": "AI_UNAVAILABLE"}},
    openapi_extra=openapi_body(ReplyRequest),
)
def post_reply(
    scenario: PathScenario,
    body: Annotated[ReplyRequest, Depends(validated_body(ReplyRequest))],
    llm: Annotated[Llm, Depends(get_llm)],
) -> ReplyResponse:
    return service.reply(scenario, body, llm)


@router.post(
    "/{scenario_id}/feedback",
    response_model=FeedbackResponse,
    summary="피드백",
    responses=BODY_ERRORS,
    openapi_extra=openapi_body(FeedbackRequest),
)
def post_feedback(
    scenario: PathScenario,
    body: Annotated[FeedbackRequest, Depends(validated_body(FeedbackRequest))],
    user: BodyUser,
    db: DB,
) -> FeedbackResponse:
    user = user or get_user(db, body.user_id)
    return service.give_feedback(db, user, scenario, body)
