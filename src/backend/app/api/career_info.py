"""진로 외부 정보 (docs/api.md 3.34): 앱 안에서 시험일정 · 훈련과정 · 직업정보 · 채용공고를 보여 준다"""

from typing import Annotated, Any

from fastapi import APIRouter, Query

from app.api.deps import Today
from app.services import external

router = APIRouter(prefix="/career", tags=["진로 외부 정보"])

Name = Annotated[str, Query(min_length=1, max_length=40)]
Region = Annotated[str | None, Query(max_length=20, description="시·도 이름 (예: 서울특별시). 이 지역을 먼저 보여 준다")]


@router.get("/exams", summary="자격증 시험 일정 (큐넷)")
def get_exams(cert: Name, today: Today) -> dict[str, Any]:
    return external.exam_schedule(cert, today)


@router.get("/trainings", summary="국민내일배움카드 훈련과정 (고용24)")
def get_trainings(keyword: Name, today: Today, region: Region = None) -> dict[str, Any]:
    return external.trainings(keyword, region, today)


@router.get("/job-info", summary="직업 정보 (커리어넷)")
def get_job_info(name: Name) -> dict[str, Any]:
    return external.job_info(name)


@router.get("/postings", summary="채용 공고 (고용24)")
def get_postings(keyword: Name, region: Region = None) -> dict[str, Any]:
    return external.postings(keyword, region)
