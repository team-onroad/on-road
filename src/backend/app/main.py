import logging
import threading
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.gzip import GZipMiddleware
from starlette.requests import Request

from app.body_limit import BodyLimitMiddleware
from app.api import chat, checklists, forms, health, policies, risk, roleplay, simulations, users, writer
from app.config import get_settings
from app.criteria import get_criteria
from app.errors import register_error_handlers
from app.doc_risk import get_rules
from app.forms import get_templates
from app.rag_client import _real_search_policies
from app.roleplay import get_scenarios

logger = logging.getLogger(__name__)

# (경로 시작, 앱이 다시 쓸 수 있는 초). 파일에서 읽는 고정 데이터만
STATIC_GETS = (
    ("/api/coach/roleplay/scenarios", 600),
    ("/api/simulations/criteria", 3600),
    ("/api/forms/templates", 600),
)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    # 기준표 형식이 잘못되면 여기서 CriteriaError 가 나서 서버가 시작되지 않는다.
    criteria = get_criteria()
    logger.info("시뮬레이션 기준표 버전: %s", criteria.version)
    get_scenarios()  # 롤플레잉 상황·서류 양식 파일 형식 오류도 시작 시 실패
    get_templates()
    get_rules()  # 서류 위험 규칙 (연습 상황 id 확인 포함)
    _warm_up_rag()
    yield


def _warm_up_rag() -> None:
    """RAG 를 실제로 쓰면 Chroma · OpenAI 연결을 백그라운드에서 미리 연다.
    서버가 잠들었다 깬 뒤 첫 검색이 라이브러리를 불러오느라 오래 걸리지 않게 (시작은 막지 않는다)"""
    if get_settings().rag_mode != "real":
        return

    def run() -> None:
        try:
            _real_search_policies()
            from rag.policy_search import warm_up

            warm_up()
            logger.info("RAG 미리 열기 완료")
        except Exception as e:  # 실패해도 첫 검색 때 다시 연다
            logger.warning("RAG 미리 열기 실패: %s", type(e).__name__)

    threading.Thread(target=run, name="rag-warm-up", daemon=True).start()


def create_app() -> FastAPI:
    app = FastAPI(title="On-Road API", version="0.1.0", lifespan=lifespan)
    register_error_handlers(app)
    # 응답 속도: 1KB 넘는 응답은 gzip 으로 줄여 보낸다 (대시보드 · 정책 · 서류 JSON 이 크다)
    app.add_middleware(GZipMiddleware, minimum_size=1000)
    # 아주 큰 요청은 본문을 다 받기 전에 413 (사진 · PDF 올리기만 14MB, 나머지 1MB)
    app.add_middleware(BodyLimitMiddleware)

    @app.middleware("http")
    async def cache_headers(request: Request, call_next):
        """거의 안 바뀌는 조회는 앱(OkHttp)이 잠깐 저장해 다시 쓰게 한다"""
        response = await call_next(request)
        if request.method == "GET" and response.status_code == 200:
            max_age = next((age for prefix, age in STATIC_GETS if request.url.path.startswith(prefix)), None)
            if max_age is not None:
                response.headers.setdefault("Cache-Control", f"public, max-age={max_age}")
            else:
                response.headers.setdefault("Cache-Control", "no-cache")  # 사용자별 · 자주 바뀌는 것은 늘 새로
        return response

    app.include_router(health.router, prefix="/api")
    app.include_router(users.router, prefix="/api")
    app.include_router(policies.router, prefix="/api")
    app.include_router(simulations.router, prefix="/api")
    app.include_router(checklists.router, prefix="/api")
    app.include_router(chat.router, prefix="/api")
    app.include_router(roleplay.router, prefix="/api")
    app.include_router(forms.router, prefix="/api")
    app.include_router(writer.router, prefix="/api")
    app.include_router(risk.router, prefix="/api")
    return app


app = create_app()
