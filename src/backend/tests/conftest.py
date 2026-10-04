"""테스트 공통 설정.

DB 가 필요한 테스트는 .env 의 TEST_DATABASE_URL 로 별도 테스트 DB 를 쓴다.
- DB 가 없으면 자동으로 만들고, 세션 시작 시 마이그레이션을 base → head 로 새로 적용한다.
- 테스트마다 모든 테이블을 비운다.
- TEST_DATABASE_URL 이 없거나 DB 에 연결할 수 없으면 DB 테스트는 skip 된다.
"""

from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import Engine, create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from app.clock import get_today
from app.config import BACKEND_DIR, get_settings
from app.main import app
from db.session import get_db, make_engine

TABLES = ("users", "policies", "checklists", "checklist_items", "simulations")


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(app) as c:
        yield c


def _alembic_config(url: str) -> Config:
    cfg = Config(str(BACKEND_DIR / "alembic.ini"))
    cfg.attributes["database_url"] = url
    cfg.attributes["configure_logger"] = False
    return cfg


@pytest.fixture(scope="session")
def db_engine() -> Iterator[Engine]:
    settings = get_settings()
    if not settings.test_database_url:
        pytest.skip("TEST_DATABASE_URL 이 .env 에 설정되지 않음")
    url = make_url(settings.test_database_url)
    if settings.database_url and make_url(settings.database_url).database == url.database:
        pytest.fail("TEST_DATABASE_URL 이 개발 DB(DATABASE_URL)와 같습니다. 다른 DB 이름을 쓰세요.")

    admin = create_engine(url.set(database="postgres"), isolation_level="AUTOCOMMIT")
    try:
        with admin.connect() as conn:
            exists = conn.execute(
                text("SELECT 1 FROM pg_database WHERE datname = :name"), {"name": url.database}
            ).scalar()
            if not exists:
                conn.execute(text(f'CREATE DATABASE "{url.database}"'))
    except OperationalError:
        pytest.skip("PostgreSQL 에 연결할 수 없음 (docker compose up -d 로 DB 컨테이너 실행 필요)")
    finally:
        admin.dispose()

    url_str = url.render_as_string(hide_password=False)
    cfg = _alembic_config(url_str)
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")

    engine = make_engine(url_str)
    yield engine
    engine.dispose()


@pytest.fixture
def db_session(db_engine: Engine) -> Iterator[Session]:
    with db_engine.begin() as conn:
        conn.execute(text(f"TRUNCATE {', '.join(TABLES)} RESTART IDENTITY CASCADE"))
    with Session(db_engine) as session:
        yield session
        session.rollback()


@dataclass
class FixedClock:
    """API 가 쓰는 '오늘 날짜(한국 시간)'. 테스트 안에서 clock.today = date(...) 로 바꿀 수 있다."""

    today: date = date(2026, 10, 4)


@pytest.fixture
def clock() -> Iterator[FixedClock]:
    fixed = FixedClock()
    app.dependency_overrides[get_today] = lambda: fixed.today
    yield fixed
    app.dependency_overrides.pop(get_today, None)


@pytest.fixture
def api(db_engine: Engine, db_session: Session, clock: FixedClock) -> Iterator[TestClient]:
    """테스트 DB 에 연결되고 오늘 날짜가 고정된 TestClient. (db_session 은 테이블 비우기용)"""

    def _test_db() -> Iterator[Session]:
        with Session(db_engine, expire_on_commit=False) as session:
            yield session

    app.dependency_overrides[get_db] = _test_db
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.pop(get_db, None)
