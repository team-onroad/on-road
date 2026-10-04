from logging.config import fileConfig

from alembic import context

from app.config import get_settings
from db.models import Base
from db.session import make_engine

config = context.config

if config.config_file_name is not None and config.attributes.get("configure_logger", True):
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def _database_url() -> str:
    # 우선순위: 코드에서 넘긴 URL(테스트) > .env 의 DATABASE_URL
    url = config.attributes.get("database_url") or get_settings().database_url
    if not url:
        raise RuntimeError("DATABASE_URL 이 설정되지 않았습니다. 프로젝트 루트의 .env 를 확인하세요.")
    return url


def run_migrations_offline() -> None:
    context.configure(
        url=_database_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    engine = make_engine(_database_url())
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
