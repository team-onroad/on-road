"""이수한 교육 · 취득한 자격증 (api.md 3.37)

- 정해진 교육 2가지(code)는 정책 조건과 이어진다
  - settlement: 자립정착금 의무교육. 자립지원전담기관에서 듣고 '의무교육확인서'를 내야 정착금이 나온다 (지역 정착금 대부분)
  - finance: 자립수당 자립교육. 서민금융진흥원 · 아동권리보장원 온라인 자립교육 이수증(또는 재무상담 확인서)을 낸다
- 로드맵(3.32)은 이 교육이 필요한 정책에 이수 여부를 붙이고, 이력서 초안(3.20)은 자격증 · 교육을 채운다
"""

import re
from datetime import date

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.errors import AppError
from app.ids import parse_id
from app.schemas.credentials import (
    Credential, CredentialCreate, CredentialList, EducationOption, RoadmapEducation,
)
from db.models import Policy, User, UserCredential

EDUCATIONS: dict[str, tuple[str, str]] = {
    "settlement": ("자립정착금 의무교육", "자립지원전담기관에서 듣는 금융 · 주거 자립교육이에요. 확인서를 내야 정착금이 나와요"),
    "finance": ("자립수당 자립교육", "서민금융진흥원 · 아동권리보장원 온라인 자립교육이에요. 이수증을 내야 자립수당을 신청할 수 있어요"),
}
_TODO = {
    "settlement": "자립지원전담기관 의무교육을 먼저 들어야 받을 수 있어요",
    "finance": "온라인 자립교육 이수증(또는 재무상담 확인서)이 있어야 신청할 수 있어요",
}
# 이수한 지 오래되면 확인서를 다시 받아야 하는 곳이 있다 (수료 후 1년이 지나면 인정 안 함)
STALE_DAYS = 365

_ALLOWANCE = re.compile(r"자립\s*(지원)?\s*수당")
_FINANCE = re.compile(r"이수증|재무\s*상담")
_SETTLEMENT = re.compile(
    r"의무\s*(?:자립)?\s*교육|자립\s*교육\s*(?:이수|수료)|교육\s*수료\s*(?:확인서|자)|정착금\s*(?:관련\s*)?(?:의무)?\s*교육"
)


def credential_not_found() -> AppError:
    return AppError(404, "CREDENTIAL_NOT_FOUND", "등록한 교육 · 자격증을 찾을 수 없습니다.")


def education_options() -> list[EducationOption]:
    return [EducationOption(code=c, name=n, description=d) for c, (n, d) in EDUCATIONS.items()]


def _policy_text(policy: Policy) -> str:
    docs = " ".join(str(d.get("name") or "") for d in (policy.required_docs or []) if isinstance(d, dict))
    return " ".join(filter(None, (policy.target_description, policy.support_content, policy.apply_method, docs)))


def required_education(policy: Policy) -> str | None:
    """이 정책을 받으려면 들어야 하는 정해진 교육 (code). 없으면 None"""
    text = _policy_text(policy)
    if _ALLOWANCE.search(policy.name) and _FINANCE.search(text):
        return "finance"
    if _SETTLEMENT.search(text):
        return "settlement"
    return None


def _out(row: UserCredential) -> Credential:
    return Credential(credential_id=row.id, kind=row.kind, code=row.code, name=row.name,
                      done_on=row.done_on, created_at=row.created_at)


def add(db: Session, user: User, body: CredentialCreate, today: date) -> tuple[Credential, bool]:
    """(등록 항목, 새로 넣었는지). 같은 종류 · 같은 이름이 있으면 날짜만 바꾼다 (날짜를 안 주면 그대로)"""
    if body.done_on is not None and body.done_on > today:
        raise AppError(422, "VALIDATION_ERROR", "요청 값이 올바르지 않습니다. (done_on)")
    name = body.name or EDUCATIONS[body.code][0]
    row = db.scalars(select(UserCredential).where(
        UserCredential.user_id == user.id, UserCredential.kind == body.kind, UserCredential.name == name,
    )).one_or_none()
    if row is not None:
        if body.done_on is not None:
            row.done_on = body.done_on
        if body.code is not None:
            row.code = body.code
        db.commit()
        db.refresh(row)
        return _out(row), False
    row = UserCredential(user_id=user.id, kind=body.kind, code=body.code, name=name, done_on=body.done_on)
    db.add(row)
    db.commit()
    db.refresh(row)
    return _out(row), True


def remove(db: Session, user: User, credential_id: str) -> None:
    """없는 항목이어도 성공. 형식 오류만 404"""
    cid = parse_id(credential_id)
    if cid is None:
        raise credential_not_found()
    db.execute(delete(UserCredential).where(UserCredential.user_id == user.id, UserCredential.id == cid))
    db.commit()


def rows(db: Session, user: User, kind: str | None = None) -> list[UserCredential]:
    q = select(UserCredential).where(UserCredential.user_id == user.id)
    if kind is not None:
        q = q.where(UserCredential.kind == kind)
    return list(db.scalars(q.order_by(UserCredential.created_at.desc(), UserCredential.id.desc())))


def credential_list(db: Session, user: User, kind: str | None = None) -> CredentialList:
    items = [_out(r) for r in rows(db, user, kind)]
    return CredentialList(count=len(items), credentials=items)


def done_educations(db: Session, user: User) -> dict[str, date | None]:
    """이수한 정해진 교육 {code: 이수한 날}. 같은 code 가 여럿이면 가장 최근 날"""
    got: dict[str, date | None] = {}
    for r in rows(db, user, "education"):
        if r.code is None:
            continue
        if r.code not in got or (r.done_on is not None and (got[r.code] is None or r.done_on > got[r.code])):
            got[r.code] = r.done_on
    return got


def roadmap_education(code: str | None, done: dict[str, date | None], today: date) -> RoadmapEducation | None:
    if code is None:
        return None
    name = EDUCATIONS[code][0]
    if code not in done:
        return RoadmapEducation(code=code, name=name, done=False, done_on=None, note=_TODO[code])
    done_on = done[code]
    if done_on is not None and (today - done_on).days > STALE_DAYS:
        note = "이수한 지 1년이 넘었어요. 확인서를 지금도 인정하는지 기관에 물어보세요"
    else:
        note = f"{name}을 이수했어요"
    return RoadmapEducation(code=code, name=name, done=True, done_on=done_on, note=note)
