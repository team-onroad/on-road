from fastapi import FastAPI

from app.api import health, users
from app.errors import register_error_handlers


def create_app() -> FastAPI:
    app = FastAPI(title="On-Road API", version="0.1.0")
    register_error_handlers(app)
    app.include_router(health.router, prefix="/api")
    app.include_router(users.router, prefix="/api")
    return app


app = create_app()
