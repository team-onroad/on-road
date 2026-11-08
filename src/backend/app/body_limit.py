"""요청 크기 제한.

본문을 다 받은 뒤 스키마에서 길이를 검사하면, 그 전에 아주 큰 요청이 서버 메모리를 다 쓸 수 있다.
그래서 본문을 받는 단계에서 막는다.
- Content-Length 가 한도를 넘으면 본문을 읽기 전에 바로 413
- 길이를 안 알려 주고(chunked) 보내도, 받은 양이 한도를 넘는 순간 413
- 사진 · PDF 를 올리는 경로만 크게 (10MB 파일의 base64 + 여유), 나머지는 1MB
"""

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.errors import AppError, error_response
from app.schemas.writer import UPLOAD_B64_MAX

DEFAULT_LIMIT = 1 * 1024 * 1024
UPLOAD_LIMIT = UPLOAD_B64_MAX + 256 * 1024
# 사진 · PDF 를 올리는 경로
UPLOAD_PATHS = ("/api/documents/upload", "/api/documents/risk-check")

CODE = "PAYLOAD_TOO_LARGE"


def too_large(limit: int) -> AppError:
    mb = max(1, round(limit / 1024 / 1024))
    return AppError(413, CODE, f"보낸 내용이 너무 커요. {mb}MB 이하로 보내 주세요.")


def limit_for(path: str) -> int:
    return UPLOAD_LIMIT if path.rstrip("/") in UPLOAD_PATHS else DEFAULT_LIMIT


class BodyLimitMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        limit = limit_for(scope["path"])

        length = next((v for k, v in scope.get("headers", []) if k == b"content-length"), None)
        if length is not None:
            try:
                too_big = int(length) > limit
            except ValueError:
                too_big = False
            if too_big:
                err = too_large(limit)
                await error_response(err.status_code, err.code, err.message)(scope, receive, send)
                return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    # 본문을 읽는 곳(request.body)에서 나므로 공통 에러 처리기가 413 으로 보낸다
                    raise too_large(limit)
            return message

        await self.app(scope, limited_receive, send)
