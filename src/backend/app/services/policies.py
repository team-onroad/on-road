"""정책 상세·검색 처리 (docs/api.md 1.3, 1.4, 3.5, 3.6, 4장 / docs/db-schema.md 3.2, 5.3)."""

import logging
from datetime import date

from pydantic import ValidationError
from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from app.codes import NATIONWIDE
from app.config import get_settings
from app import ai_content, pii
from app.errors import AppError
from app.ids import parse_id
from app.money import parse_period_years, policy_amount
from app.cache import TtlCache
from app.rag_client import SearchPolicies, get_search_policies
from app.rules import (
    CHILD_AGE_MAX, calc_age, is_child_household_policy, is_child_policy, is_in_care_child_policy, is_outdated, judge_age, judge_eligibility,
    care_status, leave_status, policy_district,
)
from app.schemas.policies import (
    EasyLevelOption,
    EasyTerm,
    EasyText,
    Eligibility,
    FormGuide,
    Evidence,
    PolicyDetail,
    RagOutput,
    RelatedPolicy,
    RequiredDoc,
    SearchResponse,
    SearchResult,
)
from app.services.credentials import required_education
from db.models import Policy, User
from db.seed import DUMMY_PREFIX

logger = logging.getLogger(__name__)

# 서비스 노출 조건 (db-schema.md 3.2)
VISIBLE = and_(Policy.is_active.is_(True), Policy.easy_text_verified.is_(True))

NO_EVIDENCE = SearchResponse(answer_status="no_evidence", answer=None, results=[])


def policy_not_found() -> AppError:
    return AppError(404, "POLICY_NOT_FOUND", "정책을 찾을 수 없습니다.")


def rag_unavailable() -> AppError:
    return AppError(503, "RAG_UNAVAILABLE", "정책 검색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")


def is_hidden_dummy(policy: Policy) -> bool:
    """시연용 더미 정책(D001~)은 실제 정책을 쓰는 서버(RAG_MODE=real)에서 검색 · 로드맵 · 추천에 넣지 않는다.
    '(더미)' 정책이 실제 사용자 목록과 금액 합계에 섞이지 않게. 시연 사용자의 기록에서 id 로 여는 것은 된다.
    mock 모드(로컬 · 테스트)는 더미가 유일한 정책이라 그대로 쓴다"""
    return get_settings().rag_mode == "real" and policy.policy_key.startswith(DUMMY_PREFIX)


def get_visible_policy(db: Session, policy_id: str) -> Policy:
    """policy_id 가 정수가 아니거나 범위 밖이거나, 정책이 없거나 노출 대상이 아니면 404."""
    pid = parse_id(policy_id)
    if pid is None:
        raise policy_not_found()
    policy = db.scalars(select(Policy).where(Policy.id == pid, VISIBLE)).one_or_none()
    if policy is None:
        raise policy_not_found()
    return policy


def family_ages(user: User, today: date) -> list[int]:
    """자녀 · 동생의 만 나이 (아동 대상 정책 판정에 씀)"""
    return [calc_age(m.birth_date, today) for m in getattr(user, "family", None) or []]


def family_ages_for(user: User, policy: Policy, today: date) -> list[int]:
    """이 정책을 받을 수 있는 가족의 나이만.
    - 소년소녀가정(아동이 가정을 이끄는 세대) 정책: 성인인 사용자와 사는 가족은 해당 없음 → 없음
    - 지금 보호받는 아동 전용 정책(가정위탁아동 · 보호대상아동 · 시설 아동): 사용자의 자녀는 보호 아동이 아니므로 빼고,
      동생은 아직 시설 · 위탁에서 보호받고 있을 수 있어서 넣는다 (판정은 '확인 필요')
    - 그 밖의 아동 정책: 자녀 · 동생 모두"""
    name, target = getattr(policy, "name", "") or "", getattr(policy, "target_description", None)
    if is_child_household_policy(name):
        return []
    members = getattr(user, "family", None) or []
    if is_in_care_child_policy(name, target):
        members = [m for m in members if m.relation == "sibling"]
    return [calc_age(m.birth_date, today) for m in members]


def for_family(user: User, policy: Policy, today: date) -> bool:
    """본인 나이로는 빠지지만 가족(자녀 · 동생) 나이가 맞아 보이는 아동 대상 정책 (family_ages_for 기준)"""
    if not is_child_policy(policy.age_max):
        return False
    if judge_age(calc_age(user.birth_date, today), policy.age_min, policy.age_max) != "excluded":
        return False
    return any(judge_age(a, policy.age_min, policy.age_max) == "match" for a in family_ages_for(user, policy, today))


def eligibility_for(user: User, policy: Policy, today: date) -> Eligibility:
    age = calc_age(user.birth_date, today)
    return Eligibility(
        **judge_eligibility(age, user.region, policy.age_min, policy.age_max, policy.region,
                            district=policy_district(policy.agency), user_district=getattr(user, "district", None),
                            family_ages=family_ages_for(user, policy, today)),
        leave=leave_status(getattr(user, "status", None), getattr(policy, "name", ""), getattr(policy, "target_description", None), age),
        care=care_status(getattr(user, "care_type", None), getattr(policy, "name", ""), getattr(policy, "target_description", None)),
    )


RELATED_LIMIT = 3


def related_policies(db: Session, policy: Policy, user: User | None, today: date) -> list[RelatedPolicy]:
    """정책 상세의 '같이 보면 좋은 정책' (규칙, AI 없음).
    사용자가 있으면 그 사람이 받을 수 있는 정책(candidate_policies) 중에서, 없으면 노출 정책 중에서
    같은 분야를 먼저, 금액이 있는 정책을 먼저 최대 3개 고른다. 자기 자신은 뺀다"""
    if user is not None:
        pool = candidate_policies(db, calc_age(user.birth_date, today), user.region, user=user, today=today)
    else:
        pool = [p for p in db.scalars(select(Policy).where(VISIBLE).order_by(Policy.id)) if not is_hidden_dummy(p)]
    pool = [p for p in pool if p.id != policy.id and p.category == policy.category]
    pool.sort(key=lambda p: (policy_amount(p)[0] is None, p.id))
    return [
        RelatedPolicy(
            policy_id=p.id, policy_key=p.policy_key, name=p.name, agency=p.agency,
            category=p.category, amount_text=policy_amount(p)[2],
            benefit=ai_content.policy_benefit(p.policy_key),
        )
        for p in pool[:RELATED_LIMIT]
    ]


def to_detail(policy: Policy, today: date, user: User | None,
              related: list[RelatedPolicy] | None = None) -> PolicyDetail:
    amount_won, amount_cycle, amount_text = policy_amount(policy)
    guide = ai_content.apply_guide(policy.policy_key) or {}
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
        required_docs=[RequiredDoc(name=d["name"], condition=d.get("condition")) for d in policy.required_docs],
        apply_url=policy.apply_url,
        source_url=policy.source_url,
        original_text=policy.original_text,
        easy_text=policy.easy_text,
        checked_at=policy.checked_at,
        is_outdated=is_outdated(policy.checked_at, today),
        eligibility=eligibility_for(user, policy, today) if user is not None else None,
        amount_won=amount_won,
        amount_cycle=amount_cycle,
        amount_text=amount_text,
        period_years=parse_period_years(policy.support_period),
        benefit=ai_content.policy_benefit(policy.policy_key),
        conditions=ai_content.policy_section(policy.policy_key, "target"),
        required_education=required_education(policy),
        exclusions=ai_content.policy_section(policy.policy_key, "exclusion"),
        apply_ways=guide.get("ways", []),
        apply_deadline=guide.get("deadline"),
        apply_steps=ai_content.policy_fact_items(policy.policy_key, "apply"),
        doc_facts=ai_content.policy_fact_items(policy.policy_key, "docs"),
        related=related or [],
    )


def candidate_policies(db: Session, age: int, region: str, user: User | None = None,
                       today: date | None = None) -> list[Policy]:
    """검색 1단계: 노출 대상 + 연령·지역 필터 (db-schema.md 1장, 5.3). excluded 정책은 빠진다.
    user 를 주면 사용자 기준으로 한 번 더 거른다: 다른 시 · 군 · 구 정책은 빼고(users.district),
    아동 대상 정책(나이 상한 17세 이하)은 가족(자녀 · 동생) 나이가 맞으면 넣는다 (db-schema.md 5.3)"""
    own_age = and_(
        or_(Policy.age_min.is_(None), Policy.age_min <= age),
        or_(Policy.age_max.is_(None), Policy.age_max >= age),
    )
    has_family = user is not None and bool(getattr(user, "family", None))
    age_ok = or_(own_age, Policy.age_max <= CHILD_AGE_MAX) if has_family else own_age
    stmt = (
        select(Policy)
        .where(
            VISIBLE,
            or_(Policy.region == NATIONWIDE, Policy.region == region),
            age_ok,
        )
        .order_by(Policy.id)
    )
    policies = [p for p in db.scalars(stmt) if not is_hidden_dummy(p)]
    if user is None:
        return policies
    # 퇴소한 사용자에게 '지금 보호받는 아동' 전용 정책(가정위탁아동 지원 등)은 본인 정책이 아니다.
    # 자녀 · 동생 나이가 맞는 아동 정책(for_family)은 가족을 위해 그대로 둔다
    if user.status == "left_care":
        policies = [p for p in policies
                    if not is_in_care_child_policy(p.name, p.target_description) or for_family(user, p, today)]
    # 시설 · 위탁 보호 중인 사람은 소년소녀가정(아동이 가정을 이끄는 세대)이 아니다
    if user.status in ("in_care", "leaving_soon"):
        policies = [p for p in policies if not is_child_household_policy(p.name) or for_family(user, p, today)]
    # 보호받던 곳이 다른 제도(아동복지시설 출신에게 청소년쉼터 퇴소자 수당 등)는 뺀다
    # (가족을 위한 아동 정책은 가족의 보호 경로를 모르니 그대로 둔다)
    return [p for p in policies
            if (e := eligibility_for(user, p, today)).status != "excluded"
            and (e.care != "excluded" or for_family(user, p, today))]


# 같은 질문 · 같은 후보 정책이면 RAG 결과를 24시간 다시 쓴다 (RAG 는 2~4초, 캐시는 0.4초 · 11/23 배포 서버 측정).
# 정책 데이터 · 검색 색인은 배포 때만 바뀌고 배포하면 서버가 다시 켜져 캐시가 비워지므로 길게 둬도 오래된 답을 주지 않는다
# (10분이던 것: 시연 질문을 미리 돌려 둬도 10분 뒤면 다시 몇 초씩 걸렸다). 답변 AI 대신 안내(fallback)는 담지 않는다
RAG_CACHE: TtlCache[RagOutput] = TtlCache("rag_search", ttl_seconds=24 * 60 * 60, max_size=256)


def _normalize(question: str) -> str:
    return " ".join(question.split()).lower()


def _call_rag(rag: SearchPolicies, question: str, keys: list[str], answer: bool = True) -> RagOutput:
    # 실제 서버의 RAG 함수만 캐시한다 (테스트에서 바꿔 끼운 함수는 매번 부른다)
    if rag is not get_search_policies():
        return _call_rag_now(rag, question, keys, answer)
    key = (_normalize(question), tuple(sorted(keys)), answer)
    output = RAG_CACHE.get_or_set(key, lambda: _call_rag_now(rag, question, keys, answer))
    if output.fallback:
        RAG_CACHE.discard(key)  # 답변 AI 가 늦어 대신한 결과는 다시 쓰지 않는다 (다음엔 제대로 답하게)
    return output


def _call_rag_now(rag: SearchPolicies, question: str, keys: list[str], answer: bool = True) -> RagOutput:
    # 질문 내용은 로그에 남기지 않는다. 예외 메시지에 질문이 들어 있을 수 있어 예외 종류만 남긴다.
    try:
        question = pii.mask(question)  # 검색 질문도 OpenAI 로 나가므로 개인정보를 가린다
        raw = rag(question, keys) if answer else rag(question, keys, answer=False)
    except Exception as e:
        logger.error("RAG 함수 오류: %s", type(e).__name__)
        raise rag_unavailable() from None
    try:
        output = RagOutput.model_validate(raw)
    except ValidationError:
        logger.error("RAG 함수 출력 형식 오류 (api.md 4장 형식과 다름)")
        raise rag_unavailable() from None
    if answer and output.status == "answered" and not output.answer:
        logger.error("RAG 함수 출력 형식 오류: answered 인데 answer 가 없음")
        raise rag_unavailable()
    return output


def _result(p: Policy, user: User, today: date, evidences: list[Evidence], related: bool = False) -> SearchResult:
    return SearchResult(
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
        summary=ai_content.policy_summary(p.policy_key),
        related=related,
        for_family=for_family(user, p, today),
    )


def browse(db: Session, user: User, today: date, category: str | None = None) -> SearchResponse:
    """분야별로 보기: 내가 볼 수 있는 정책(연령 · 지역)을 분야 코드로 나눈다. 검색(RAG · AI)을 쓰지 않아 빠르고 정확하다.
    category 가 없으면 전체. 결과 형식은 검색과 같다 (근거는 비어 있음)"""
    age = calc_age(user.birth_date, today)
    policies = [p for p in candidate_policies(db, age, user.region, user, today)
                if category is None or p.category == category]
    # 가족 대상 정책은 본인 정책 뒤에 (앱이 제목을 따로 단다)
    policies.sort(key=lambda p: for_family(user, p, today))
    if not policies:
        return NO_EVIDENCE
    return SearchResponse(
        answer_status="answered", answer=None, results=[_result(p, user, today, []) for p in policies],
    )


def search(db: Session, user: User, question: str, today: date, rag: SearchPolicies, answer: bool = True) -> SearchResponse:
    """answer=False: 답변 문장 없이 정책 카드만 (분야별로 보기). 답변을 만드는 LLM 을 부르지 않아 빠르다"""
    age = calc_age(user.birth_date, today)
    candidates = candidate_policies(db, age, user.region, user, today)
    if not candidates:
        return NO_EVIDENCE  # 후보가 없으면 RAG 를 호출하지 않는다 (api.md 4장)

    by_key = {p.policy_key: p for p in candidates}
    output = _call_rag(rag, question, list(by_key), answer)
    if output.status == "no_evidence":
        return NO_EVIDENCE

    # evidences 를 policy_key 별로 묶는다. 후보 밖 policy_key 는 버리고, 처음 나온 순서를 유지한다.
    # related(비슷한 정책)는 그 정책의 근거가 모두 related 일 때만 표시한다 (답변 근거와 겹치면 근거로 본다)
    grouped: dict[str, list[Evidence]] = {}
    related: dict[str, bool] = {}
    for ev in output.evidences:
        if ev.policy_key in by_key:
            grouped.setdefault(ev.policy_key, []).append(Evidence(content=ev.content))
            related[ev.policy_key] = related.get(ev.policy_key, True) and ev.related
    if not grouped:
        return NO_EVIDENCE

    results = [_result(by_key[key], user, today, evidences, related[key]) for key, evidences in grouped.items()]
    return SearchResponse(answer_status="answered", answer=output.answer if answer else None, results=results)


# ---------------- 쉬운 말 단계 (3.23) · 신청서 작성 도우미 (3.24) ----------------


def easy_text(policy: Policy, today: date, user: User | None, level: str | None) -> EasyText:
    levels = ai_content.easy_levels(policy.policy_key)
    if not levels:
        raise AppError(404, "EASY_TEXT_NOT_FOUND", "이 정책은 아직 단계별 쉬운 말이 준비되지 않았습니다.")
    age = calc_age(user.birth_date, today) if user is not None else None
    code = ai_content.pick_level(list(levels), level, age)
    chosen = levels[code]
    core, more = ai_content.split_more(chosen)
    return EasyText(
        policy_id=policy.id,
        policy_key=policy.policy_key,
        level=code,
        level_name=ai_content.level_name(code),
        recommended_level=ai_content.recommended_for(age),
        levels=[EasyLevelOption(level=c, level_name=ai_content.level_name(c)) for c in levels],
        core_text=core,
        more_text=more,
        terms=[EasyTerm(term=t["term"], gloss=t["gloss"]) for t in chosen.get("terms") or [] if t.get("gloss")],
        verified=bool(chosen.get("verified")),
    )


def form_guide(policy: Policy, user: User | None) -> FormGuide:
    spec = ai_content.find_form(policy.policy_key)
    if spec is None:
        raise AppError(404, "FORM_GUIDE_NOT_FOUND", "이 정책은 아직 신청서 작성 도우미가 준비되지 않았습니다.")
    profile = (
        ai_content.profile_of(user.name, user.birth_date, user.region, user.phone) if user is not None else None
    )
    return FormGuide(policy_id=policy.id, **ai_content.form_guide(spec, profile))
