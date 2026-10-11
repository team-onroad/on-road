from collections.abc import Iterator
from functools import lru_cache

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.config import get_settings


def make_engine(url: str) -> Engine:
    # 세션 시간대를 한국 시간으로 고정 (TIMESTAMPTZ 를 +09:00 으로 돌려받음)
    return create_engine(
        url,
        pool_pre_ping=True,
        connect_args={"options": "-c timezone=Asia/Seoul"},
    )


@lru_cache
def get_engine() -> Engine:
    url = get_settings().database_url
    if not url:
        raise RuntimeError("DATABASE_URL 이 설정되지 않았습니다. 프로젝트 루트의 .env 를 확인하세요.")
    return make_engine(url)


@lru_cache
def _session_factory() -> sessionmaker[Session]:
    return sessionmaker(bind=get_engine(), expire_on_commit=False)


def get_db() -> Iterator[Session]:
    """FastAPI 의존성: 요청마다 세션을 열고 닫는다."""
    with _session_factory()() as session:
        yield session
