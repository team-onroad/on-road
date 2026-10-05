from typing import Annotated

from fastapi import APIRouter, Depends, status

from app.api.deps import DB, BodyUser, Today, openapi_body, validated_body
from app.criteria import Criteria, get_criteria
from app.schemas.simulations import CriteriaResponse, SimulationCreate, SimulationResponse
from app.services import simulations as service
from app.services.users import get_user

router = APIRouter(prefix="/simulations", tags=["시뮬레이션"])

CriteriaDep = Annotated[Criteria, Depends(get_criteria)]


@router.get("/criteria", response_model=CriteriaResponse, summary="배분 기준표")
def get_criteria_table(criteria: CriteriaDep) -> CriteriaResponse:
    return service.criteria_response(criteria)


@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    response_model=SimulationResponse,
    summary="시뮬레이션 제출",
    responses={
        404: {"description": "USER_NOT_FOUND (user_id 형식 오류 포함)"},
        422: {"description": "VALIDATION_ERROR / SUM_EXCEEDS_INCOME"},
    },
    openapi_extra=openapi_body(SimulationCreate),
)
def create_simulation(
    body: Annotated[SimulationCreate, Depends(validated_body(SimulationCreate))],
    user: BodyUser,
    db: DB,
    today: Today,
    criteria: CriteriaDep,
) -> SimulationResponse:
    user = user or get_user(db, body.user_id)
    return service.create_simulation(db, user, body, criteria, today)


@router.get(
    "/{simulation_id}",
    response_model=SimulationResponse,
    summary="시뮬레이션 결과 조회",
    responses={404: {"description": "USER_NOT_FOUND / SIMULATION_NOT_FOUND"}},
)
def get_simulation(simulation_id: str, user_id: str, db: DB, today: Today) -> SimulationResponse:
    user = get_user(db, user_id)  # 사용자 먼저 확인
    return service.get_simulation(db, user, simulation_id, today)
