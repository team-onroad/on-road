import base64
import binascii
from typing import Annotated

from fastapi import APIRouter, Depends

from app import doc_risk, form_upload
from app.api.deps import BodyUser, Today, openapi_body, validated_body
from app.doc_llm import DocLlm, get_doc_llm
from app.schemas.risk import RiskCheckRequest, RiskCheckResponse

router = APIRouter(prefix="/documents", tags=["문서 AI"])


@router.post(
    "/risk-check",
    response_model=RiskCheckResponse,
    summary="서류 속 숨겨진 위험 찾기",
    description="등기부등본 · 임대차 계약서 · 근로계약서 사진이나 PDF 를 올리면 규칙 파일(doc_risk_rules.json)대로 "
    "위험 · 확인 필요 · 괜찮음을 알려 준다. 숫자 규칙(최저임금 · 빚 비율 · 날짜)은 서버가 다시 계산한다. "
    "파일과 결과는 저장하지 않는다.",
    responses={
        404: {"description": "USER_NOT_FOUND"},
        422: {"description": "VALIDATION_ERROR / UPLOAD_INVALID"},
        503: {"description": "AI_UNAVAILABLE"},
    },
    openapi_extra=openapi_body(RiskCheckRequest),
)
def risk_check(
    _user: BodyUser,
    body: Annotated[RiskCheckRequest, Depends(validated_body(RiskCheckRequest))],
    today: Today,
    doc_llm: Annotated[DocLlm, Depends(get_doc_llm)],
) -> RiskCheckResponse:
    try:
        data = base64.b64decode(body.file_base64, validate=True)
    except (binascii.Error, ValueError):
        raise form_upload.bad_file("파일을 읽지 못했어요. 다시 올려 주세요.") from None
    c = body.context
    ctx = doc_risk.Context(
        landlord_name=c.landlord_name if c else None,
        address=c.address if c else None,
        deposit=c.deposit if c else None,
        market_price=c.market_price if c else None,
    )
    return RiskCheckResponse.model_validate(doc_risk.check(data, body.doc_type, ctx, today, doc_llm))
