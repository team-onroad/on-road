"""정책 상세·검색 처리 (docs/api.md 1.3, 1.4, 3.5, 3.6, 4장 / docs/db-schema.md 3.2, 5.3)."""

import logging
from datetime import date

from pydantic import ValidationError
from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from app.codes import NATIONWIDE
from app.errors import AppError
from app.ids import parse_id
from app.rag_client import SearchPolicies
from app.rules import calc_age, is_outdated, judge_eligibility
from app.schemas.policies import (
    Eligibility,
    Evidence,
    PolicyDetail,
    RagOutput,
    SearchResponse,
    SearchResult,
)
from db.models import Policy, User

logger = logging.getLogger(__name__)

# 서비스 노출 조건 (db-schema.md 3.2)
VISIBLE = and_(Policy.is_active.is_(True), Policy.easy_text_verified.is_(True))

NO_EVIDENCE = SearchResponse(answer_status="no_evidence", answer=None, results=[])


def policy_not_found() -> AppError:
    return AppError(404, "POLICY_NOT_FOUND", "정책을 찾을 수 없습니다.")


def rag_unavailable() -> AppError:
    return AppError(503, "RAG_UNAVAILABLE", "정책 검색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")


def get_visible_policy(db: Session, policy_id: str) -> Policy:
    """policy_id 가 정수가 아니거나 범위 밖이거나, 정책이 없거나 노출 대상이 아니면 404."""
    pid = parse_id(policy_id)
    if pid is None:
        raise policy_not_found()
    policy = db.scalars(select(Policy).where(Policy.id == pid, VISIBLE)).one_or_none()
    if policy is None:
        raise policy_not_found()
    return policy


def eligibility_for(user: User, policy: Policy, today: date) -> Eligibility:
    age = calc_age(user.birth_date, today)
    return Eligibility(
        **judge_eligibility(age, user.region, policy.age_min, policy.age_max, policy.region)
    )


def to_detail(policy: Policy, today: date, user: User | None) -> PolicyDetail:
    return PolicyDetail(
        policy_id=policy.id,
        policy_key=policy.policy_key,
        name=policy.name,
        agency=policy.agency,
        contact=policy.contact,
        category=policy.category,
        region=policy.region,
        age_min=policy.age_min,
        age_max=policy.age_max,
        target_description=policy.target_description,
        support_content=policy.support_content,
        support_amount=policy.support_amount,
        support_period=policy.support_period,
        apply_method=policy.apply_method,
        required_docs=list(policy.required_docs),
        apply_url=policy.apply_url,
        source_url=policy.source_url,
        original_text=policy.original_text,
        easy_text=policy.easy_text,
        checked_at=policy.checked_at,
        is_outdated=is_outdated(policy.checked_at, today),
        eligibility=eligibility_for(user, policy, today) if user is not None else None,
    )


def candidate_policies(db: Session, age: int, region: str) -> list[Policy]:
    """검색 1단계: 노출 대상 + 연령·지역 필터 (db-schema.md 1장, 5.3). excluded 정책은 빠진다."""
    stmt = (
        select(Policy)
        .where(
            VISIBLE,
            or_(Policy.region == NATIONWIDE, Policy.region == region),
            or_(Policy.age_min.is_(None), Policy.age_min <= age),
            or_(Policy.age_max.is_(None), Policy.age_max >= age),
        )
        .order_by(Policy.id)
    )
    return list(db.scalars(stmt))


def _call_rag(rag: SearchPolicies, question: str, keys: list[str]) -> RagOutput:
    # 질문 내용은 로그에 남기지 않는다. 예외 메시지에 질문이 들어 있을 수 있어 예외 종류만 남긴다.
    try:
        raw = rag(question, keys)
    except Exception as e:
        logger.error("RAG 함수 오류: %s", type(e).__name__)
        raise rag_unavailable() from None
    try:
        output = RagOutput.model_validate(raw)
    except ValidationError:
        logger.error("RAG 함수 출력 형식 오류 (api.md 4장 형식과 다름)")
        raise rag_unavailable() from None
    if output.status == "answered" and not output.answer:
        logger.error("RAG 함수 출력 형식 오류: answered 인데 answer 가 없음")
        raise rag_unavailable()
    return output


def search(db: Session, user: User, question: str, today: date, rag: SearchPolicies) -> SearchResponse:
    age = calc_age(user.birth_date, today)
    candidates = candidate_policies(db, age, user.region)
    if not candidates:
        return NO_EVIDENCE  # 후보가 없으면 RAG 를 호출하지 않는다 (api.md 4장)

    by_key = {p.policy_key: p for p in candidates}
    output = _call_rag(rag, question, list(by_key))
    if output.status == "no_evidence":
        return NO_EVIDENCE

    # evidences 를 policy_key 별로 묶는다. 후보 밖 policy_key 는 버리고, 처음 나온 순서를 유지한다.
    grouped: dict[str, list[Evidence]] = {}
    for ev in output.evidences:
        if ev.policy_key in by_key:
            grouped.setdefault(ev.policy_key, []).append(Evidence(content=ev.content))
    if not grouped:
        return NO_EVIDENCE

    results = []
    for key, evidences in grouped.items():
        p = by_key[key]
        results.append(
            SearchResult(
                policy_id=p.id,
                policy_key=p.policy_key,
                name=p.name,
                agency=p.agency,
                category=p.category,
                checked_at=p.checked_at,
                is_outdated=is_outdated(p.checked_at, today),
                eligibility=eligibility_for(user, p, today),
                target_description=p.target_description,
                support_content=p.support_content,
                support_amount=p.support_amount,
                support_period=p.support_period,
                original_text=p.original_text,
                easy_text=p.easy_text,
                source_url=p.source_url,
                evidences=evidences,
            )
        )
    return SearchResponse(answer_status="answered", answer=output.answer, results=results)
