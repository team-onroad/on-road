"""스트리밍 응답 (Server-Sent Events). 앱이 답을 다 기다리지 않고 문장이 나오는 대로 받는다.

이벤트 (한 이벤트 = "event: 이름\\ndata: JSON\\n\\n"):
- delta: {"text": "문장"} 이 여러 번
- done:  보통 응답과 같은 JSON (+ "replace": 앞에 보낸 문장을 이 답으로 바꿔야 하면 true). 항상 마지막에 한 번
- error: {"error": {"code", "message"}} (보통 응답의 오류 본문과 같은 모양). done 대신 마지막에 한 번

요청 형식 · 사용자 · 상황 오류처럼 답을 만들기 전에 알 수 있는 오류는 스트림이 아니라 보통 HTTP 오류로 준다.
"""

import json
from collections.abc import Iterable, Iterator

from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.errors import AppError

MEDIA_TYPE = "text/event-stream"


def event(name: str, data: BaseModel | dict, **extra: object) -> str:
    body = data.model_dump(mode="json") if isinstance(data, BaseModel) else dict(data)
    body.update(extra)
    return f"event: {name}\ndata: {json.dumps(body, ensure_ascii=False)}\n\n"


def delta(text: str) -> str:
    return event("delta", {"text": text})


def error(e: AppError) -> str:
    return event("error", {"error": {"code": e.code, "message": e.message}})


def response(events: Iterable[str]) -> StreamingResponse:
    # 중간 프록시가 모아 두지 않고 바로 보내게 (Render · nginx)
    return StreamingResponse(
        iter(events) if not isinstance(events, Iterator) else events,
        media_type=MEDIA_TYPE,
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
