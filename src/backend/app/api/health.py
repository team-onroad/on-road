from fastapi import APIRouter

router = APIRouter(tags=["공통"])


@router.get("/health", summary="서버 상태 확인")
def health() -> dict[str, str]:
    return {"status": "ok"}
