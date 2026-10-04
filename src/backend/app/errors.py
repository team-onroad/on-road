"""공통 에러 형식 (docs/api.md 1.1).

모든 에러는 {"error": {"code": ..., "message": ...}} 형식으로 응답한다.
"""

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class AppError(Exception):
    def __init__(self, status_code: int, code: str, message: str):
        self.status_code = status_code
        self.code = code
        self.message = message


def error_response(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"error": {"code": code, "message": message}},
    )


async def _app_error_handler(_: Request, exc: AppError) -> JSONResponse:
    return error_response(exc.status_code, exc.code, exc.message)


async def _validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    # 요청 값(개인정보 포함 가능)은 응답·로그에 그대로 남기지 않고 문제 필드 위치만 알려준다.
    fields = sorted({".".join(str(p) for p in err["loc"] if p != "body") for err in exc.errors()})
    message = "요청 값이 올바르지 않습니다."
    if fields:
        message += f" ({', '.join(f for f in fields if f)})"
    return error_response(422, "VALIDATION_ERROR", message)


async def _http_error_handler(_: Request, exc: StarletteHTTPException) -> JSONResponse:
    if exc.status_code == 404:
        return error_response(404, "NOT_FOUND", "요청한 경로를 찾을 수 없습니다.")
    if exc.status_code == 405:
        return error_response(405, "METHOD_NOT_ALLOWED", "허용되지 않는 메서드입니다.")
    return error_response(exc.status_code, "HTTP_ERROR", str(exc.detail))


def register_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(AppError, _app_error_handler)
    app.add_exception_handler(RequestValidationError, _validation_error_handler)
    app.add_exception_handler(StarletteHTTPException, _http_error_handler)
