"""공통 에러 형식 (docs/api.md 1.1).

모든 에러는 {"error": {"code": ..., "message": ...}} 형식으로 응답한다.
"""

import logging
import traceback
from pathlib import PurePath

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import Response

logger = logging.getLogger(__name__)


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
    fields = {
        ".".join(p for p in err["loc"] if isinstance(p, str) and p != "body")
        for err in exc.errors()
    }
    fields.discard("")
    message = "요청 값이 올바르지 않습니다."
    if fields:
        message += f" ({', '.join(sorted(fields))})"
    return error_response(422, "VALIDATION_ERROR", message)


async def _http_error_handler(_: Request, exc: StarletteHTTPException) -> JSONResponse:
    if exc.status_code == 404:
        return error_response(404, "NOT_FOUND", "요청한 경로를 찾을 수 없습니다.")
    if exc.status_code == 405:
        return error_response(405, "METHOD_NOT_ALLOWED", "허용되지 않는 메서드입니다.")
    return error_response(exc.status_code, "HTTP_ERROR", str(exc.detail))


def _route_path(request: Request) -> str:
    """실제 경로에는 user_id 가 들어 있어 경로 값 자리를 {이름}으로 바꿔 남김"""
    names = {str(v): k for k, v in request.path_params.items()}
    return "/".join(f"{{{names[seg]}}}" if seg in names else seg for seg in request.url.path.split("/"))


def _frames(exc: BaseException) -> str:
    """우리 코드 위치만 (라이브러리 줄 제외)"""
    frames = [
        f for f in traceback.extract_tb(exc.__traceback__)
        if "site-packages" not in PurePath(f.filename).parts and PurePath(f.filename).name != "errors.py"
    ]
    return "\n".join(f"  {'/'.join(PurePath(f.filename).parts[-2:])}:{f.lineno} in {f.name}" for f in frames)


async def _unhandled_error(request: Request, call_next: RequestResponseEndpoint) -> Response:
    """정해 둔 오류 밖의 예외 → 500 INTERNAL_ERROR.
    예외 메시지·요청 값에 개인정보가 있을 수 있어 경로 틀, 예외 종류, 코드 위치만 로그에 남김.
    예외를 다시 던지지 않아 서버 기본 로그에도 메시지가 남지 않음"""
    try:
        return await call_next(request)
    except Exception as exc:
        logger.error("처리 안 된 오류: %s %s %s\n%s", request.method, _route_path(request), type(exc).__name__, _frames(exc))
        return error_response(500, "INTERNAL_ERROR", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")


def register_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(AppError, _app_error_handler)
    app.add_exception_handler(RequestValidationError, _validation_error_handler)
    app.add_exception_handler(StarletteHTTPException, _http_error_handler)
    # 다른 미들웨어보다 먼저 등록해 가장 안쪽에서 잡음
    app.add_middleware(BaseHTTPMiddleware, dispatch=_unhandled_error)
