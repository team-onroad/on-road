"""서류 속 숨겨진 위험 찾기 스키마"""

from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr

from app.doc_risk import DocType, Status
from app.schemas.writer import UPLOAD_B64_MAX

Money = Annotated[StrictInt, Field(ge=0, le=10**13)]


class RiskContext(BaseModel):
    """다른 서류와 맞춰 볼 계약 정보 (등기부등본 확인 때). 저장하지 않는다"""

    model_config = ConfigDict(extra="forbid")

    landlord_name: Annotated[StrictStr, Field(max_length=40)] | None = None
    address: Annotated[StrictStr, Field(max_length=200)] | None = None
    deposit: Money | None = None
    market_price: Money | None = None


class RiskCheckRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str
    doc_type: DocType
    file_base64: Annotated[StrictStr, Field(min_length=1, max_length=UPLOAD_B64_MAX)]
    file_name: Annotated[StrictStr, Field(max_length=200)] | None = None
    context: RiskContext | None = None


class RiskPractice(BaseModel):
    scenario_id: str
    title: str


class RiskFinding(BaseModel):
    rule_id: str
    title: str
    status: Status
    evidence: str | None  # 서류에 적힌 근거 문장 (주민등록번호·계좌번호는 가림)
    explain: str
    action: str | None
    practice: RiskPractice | None


class RiskCounts(BaseModel):
    danger: int
    check: int
    ok: int
    unknown: int


class RiskSummary(BaseModel):
    level: Status
    headline: str
    counts: RiskCounts


class RiskCheckResponse(BaseModel):
    doc_type: DocType
    label: str
    rules_version: str
    summary: RiskSummary
    findings: list[RiskFinding]  # 위험 → 확인 필요 → 찾을 수 없음 → 괜찮음
    notice: str
