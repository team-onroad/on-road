import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import chat, checklists, forms, health, policies, roleplay, simulations, users, writer
from app.criteria import get_criteria
from app.errors import register_error_handlers
from app.forms import get_templates
from app.roleplay import get_scenarios

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    # 기준표 형식이 잘못되면 여기서 CriteriaError 가 나서 서버가 시작되지 않는다.
    criteria = get_criteria()
    logger.info("시뮬레이션 기준표 버전: %s", criteria.version)
    get_scenarios()  # 롤플레잉 상황·서류 양식 파일 형식 오류도 시작 시 실패
    get_templates()
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="On-Road API", version="0.1.0", lifespan=lifespan)
    register_error_handlers(app)
    app.include_router(health.router, prefix="/api")
    app.include_router(users.router, prefix="/api")
    app.include_router(policies.router, prefix="/api")
    app.include_router(simulations.router, prefix="/api")
    app.include_router(checklists.router, prefix="/api")
    app.include_router(chat.router, prefix="/api")
    app.include_router(roleplay.router, prefix="/api")
    app.include_router(forms.router, prefix="/api")
    app.include_router(writer.router, prefix="/api")
    return app


app = create_app()
