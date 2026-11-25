"""맞춤 신청 로드맵 (api.md 3.32). 규칙만 쓴다 (AI 없음).

1. 받을 수 있는 정책: candidate_policies (나이 · 지역 · 시군구 · 가족, excluded 제외)
2. 단계
   - before_leave: 신청 방법에 "종료 30일 전" 같은 사전신청 기간이 있는 정책. 신청 시작일 = 퇴소 예정일 - N일
   - at_leave: 대상에 보호종료 · 퇴소 · 자립준비가 들어간 정책. 신청 시작일 = 퇴소 예정일
   - now: 나머지. 퇴소 후 사용자는 모든 정책이 now
3. 금액은 app/money.py 로 읽고, 월 금액은 기간(년)을 곱해 최대 총액을 낸다
"""

import re
from datetime import date, timedelta

from sqlalchemy.orm import Session

from app import ai_content
from app.money import parse_period_years, policy_amount
from app.rules import calc_age, extended_care_ok, is_in_care_child_policy
from app.schemas.chat import PersonalPlan, PlanItem
from app.schemas.roadmap import (
    PolicyRoadmap, RecommendedPolicy, RoadmapAlternative, RoadmapItem, RoadmapStageGroup, RoadmapSummary,
    SameKindGroup, SameKindPicked, SameKindRow,
)
from app.schemas.policies import SameKind, SameKindPolicy
from app.services.policies import candidate_policies, eligibility_for
from db.models import Policy, User

BEFORE_LEAVE_STATUSES = ("in_care", "leaving_soon")
STAGE_TITLES = {
    "before_leave": "퇴소 전에 미리 신청",
    "at_leave": "퇴소하면 바로 신청",
    "now": "지금 신청할 수 있어요",
}
_PRE_APPLY = re.compile(r"종료\s*(?:전|예정일?\s*전)\s*(\d{1,3})\s*일|종료\s*(\d{1,3})\s*일\s*전")
_LEAVER = re.compile(r"보호\s*종료|퇴소|자립\s*준비")


def pre_apply_days(policy: Policy) -> int | None:
    """"보호종료 30일 전부터 사전 신청" · "종료 전 30일 이내" → 30"""
    text = " ".join(t for t in (policy.apply_method, policy.target_description) if t)
    m = _PRE_APPLY.search(text)
    return int(m.group(1) or m.group(2)) if m else None


def for_leavers(policy: Policy) -> bool:
    return bool(_LEAVER.search(policy.target_description or ""))


def _add_years(d: date, years: int) -> date:
    try:
        return d.replace(year=d.year + years)
    except ValueError:  # 2월 29일
        return d.replace(year=d.year + years, day=28)


def age_until(birth: date, age_max: int | None) -> date | None:
    """나이 상한(만 N세 이하)이 끝나는 날 = 만 N+1세 생일 전날. 아동 정책(17세 이하)은 셈하지 않는다"""
    if age_max is None or age_max < 18:
        return None
    return _add_years(birth, age_max + 1) - timedelta(days=1)


def _max_total(won: int | None, cycle: str | None, years: int | None) -> int | None:
    if won is None:
        return None
    if cycle in ("once", None):  # 주기가 없는 금액(자립정착금 "1,000만 원" 등)은 한 번 받는 돈으로 본다
        return won
    if years is None:
        return None
    if cycle == "monthly":
        return won * 12 * years
    if cycle == "yearly":
        return won * years
    return None


def _item(policy: Policy, user: User, today: date, leave: date | None,
          in_care: bool = False, at_leave_only: bool = False) -> tuple[str, RoadmapItem]:
    """at_leave_only: 오늘 나이로는 안 되지만 퇴소일 나이로는 되는 정책 (17세가 18세에 퇴소하는 경우 등).
    in_care: 아직 시설 · 위탁 보호 중인데 퇴소일을 모르는 사용자 (퇴소해야 받는 정책은 '퇴소하면 신청', 날짜 없음)"""
    won, cycle, text = policy_amount(policy)
    years = parse_period_years(policy.support_period)
    days = pre_apply_days(policy)

    extended = (user.status in BEFORE_LEAVE_STATUSES and calc_age(user.birth_date, today) >= 18
                and extended_care_ok(policy.target_description))
    if extended:
        # 보호연장 중(18세 이상, 아직 보호 중)이고 연장한 사람도 받는 정책: 퇴소를 기다리지 않는다
        stage, apply_from, note = "now", None, "보호를 연장한 지금 신청"
    elif leave is not None and days is not None:
        stage, apply_from = "before_leave", leave - timedelta(days=days)
        note = f"퇴소 {days}일 전부터 미리 신청"
    elif leave is not None and (for_leavers(policy) or at_leave_only):
        stage, apply_from = "at_leave", leave
        note = "퇴소한 날부터 신청"
    elif in_care and (days is not None or for_leavers(policy)) and not is_in_care_child_policy(
            policy.name, policy.target_description):
        stage, apply_from, note = "at_leave", None, "퇴소하면 신청"
    else:
        stage, apply_from, note = "now", None, "지금 신청"
    if apply_from is not None and apply_from < today:
        apply_from = today

    return stage, RoadmapItem(
        policy_id=policy.id,
        policy_key=policy.policy_key,
        name=policy.name,
        agency=policy.agency,
        category=policy.category,
        eligibility=eligibility_for(user, policy, leave if at_leave_only and leave else today).status,
        amount_won=won,
        amount_cycle=cycle,
        amount_text=text,
        benefit=ai_content.policy_benefit(policy.policy_key),
        period_years=years,
        max_total_won=_max_total(won, cycle, years),
        apply_from=apply_from,
        until=_add_years(leave, years) if leave is not None and years else None,
        age_until=age_until(user.birth_date, policy.age_max),
        age_max=policy.age_max if policy.age_max is not None and policy.age_max >= 18 else None,
        region=policy.region,
        apply_note=note,
    )


# 같은 종류라 보통 하나만 받는 지원. 지자체마다 이름만 다르게 올라온 같은 제도도 여기서 묶는다.
# 원문에 '중복 불가'가 적힌 정책은 적어서, 같은 종류 안에서는 하나만 받는다고 보고 사용자에게 가장 유리한 것 하나를 고른다
_SAME_KIND = (
    ("allowance", "자립수당", re.compile(r"자립\s*(지원)?\s*수당")),
    ("settlement", "자립정착금", re.compile(r"(자립)?\s*정착금")),
    ("challenge", "청년도전지원사업", re.compile(r"청년\s*도전")),
    ("college", "대학 진학 자금", re.compile(r"대학\s*(입학|진학|등록|생활)")),
    ("scholarship", "장학금", re.compile(r"장학")),
    ("lease", "전세임대", re.compile(r"전세\s*(임대|주택)")),
)
_KIND_TITLE = {key: title for key, title, _ in _SAME_KIND}


def _kind_key(item: RoadmapItem) -> str:
    for key, _, pattern in _SAME_KIND:
        if pattern.search(item.name):
            return key
    return f"policy-{item.policy_id}"


def _max_sum(items: list[RoadmapItem]) -> int:
    """같은 종류는 가장 큰 금액 하나만 더한다 (자립수당 두 개를 함께 받는다고 보지 않는다)"""
    best: dict[str, int] = {}
    for i in items:
        key = _kind_key(i)
        best[key] = max(best.get(key, 0), i.amount_won or 0)
    return sum(best.values())


def _benefit_value(item: RoadmapItem) -> int:
    """비교용 받는 돈: 최대 총액 → 1회 금액 → 월 금액 × 12. 읽지 못하면 0"""
    if item.max_total_won is not None:
        return item.max_total_won
    if item.amount_won is None:
        return 0
    return item.amount_won * (12 if item.amount_cycle == "monthly" else 1)


def _pick_same_kind(groups: dict[str, list[RoadmapItem]]) -> None:
    """같은 종류는 하나만 남기고 나머지는 alternatives 로 붙인다.
    고르는 순서: 조건이 맞는 것 → (자립수당은 앱이 직접 확인한 P 정책) → 받는 돈이 큰 것 → 내 지역 정책 → 먼저 신청 → 번호 순"""
    order = {"before_leave": 0, "at_leave": 1, "now": 2}
    kinds: dict[str, list[tuple[str, RoadmapItem]]] = {}
    for stage, items in groups.items():
        for i in items:
            key = _kind_key(i)
            if not key.startswith("policy-"):
                kinds.setdefault(key, []).append((stage, i))
    for key, members in kinds.items():
        if len(members) <= 1:
            continue
        keep = min(members, key=lambda si: (
            si[1].eligibility != "match",
            key == "allowance" and not si[1].policy_key.startswith("P"),
            -_benefit_value(si[1]),
            si[1].region == "전국",
            order[si[0]],
            si[1].policy_id,
        ))[1]
        others = [i for _, i in members if i is not keep]
        top = max(_benefit_value(i) for i in others)
        if _benefit_value(keep) > top:
            reason = f"같은 종류 {len(members)}개 중 받는 돈이 가장 커요"
        elif key == "allowance":
            # 아동복지시설 · 가정위탁(보건복지부)과 청소년복지시설(성평등가족부) 수당은 대상이 다르지만 함께 받을 수 없다
            reason = "자립수당은 하나만 받을 수 있어요"
        elif keep.region != "전국":
            reason = f"같은 종류 {len(members)}개 중 내 지역 정책이에요"
        else:
            reason = f"같은 종류 {len(members)}개 중 조건이 가장 잘 맞아요"
        keep.kind = key
        keep.kind_title = _KIND_TITLE[key]
        keep.pick_reason = reason
        keep.alternatives = [
            RoadmapAlternative(policy_id=i.policy_id, name=i.name, region=i.region, amount_text=i.amount_text,
                               eligibility=i.eligibility)
            for i in sorted(others, key=lambda i: (-_benefit_value(i), i.policy_id))
        ]
        for stage in groups:
            groups[stage] = [i for i in groups[stage] if i is keep or i not in others]


def _monthly_equivalent(item: RoadmapItem) -> int:
    if item.amount_won is None:
        return 0
    return {"monthly": item.amount_won, "yearly": item.amount_won // 12}.get(item.amount_cycle or "", 0)


def _sort_key(item: RoadmapItem):
    return (
        item.eligibility != "match",
        item.apply_from or date.max,
        -_monthly_equivalent(item),
        -(item.max_total_won or 0),
        item.policy_id,
    )


def build_roadmap(db: Session, user: User, today: date) -> PolicyRoadmap:
    leave = user.d_date if user.status in BEFORE_LEAVE_STATUSES else None
    if leave is not None and leave < today:  # 예정일이 지났으면 이미 퇴소한 것으로 본다
        leave = None

    pool = candidate_policies(db, calc_age(user.birth_date, today), user.region, user=user, today=today)
    # 퇴소일 나이로만 받을 수 있는 정책 (만 18세 이상 조건 등). 17세가 퇴소할 때 18세가 되면 '퇴소하면 바로 신청'에 넣는다
    later: list[Policy] = []
    if leave is not None:
        have = {p.id for p in pool}
        later = [p for p in candidate_policies(db, calc_age(user.birth_date, leave), user.region, user=user, today=leave)
                 if p.id not in have]
    # 아직 보호 중인데 퇴소일을 안 넣은 사람 (예정일이 지난 사람은 퇴소한 것으로 본다)
    in_care = user.d_date is None and user.status in BEFORE_LEAVE_STATUSES
    groups: dict[str, list[RoadmapItem]] = {s: [] for s in STAGE_TITLES}
    for p in pool:
        stage, item = _item(p, user, today, leave, in_care=in_care)
        groups[stage].append(item)
    for p in later:
        stage, item = _item(p, user, today, leave, at_leave_only=True)
        groups[stage].append(item)
    _pick_same_kind(groups)

    items = [i for g in groups.values() for i in g]
    matched = [i for i in items if i.eligibility == "match" and i.amount_won is not None]
    return PolicyRoadmap(
        base_date=leave,
        d_day=(leave - today).days if leave is not None else None,
        summary=RoadmapSummary(
            monthly_won=_max_sum([i for i in matched if i.amount_cycle == "monthly"]),
            once_won=_max_sum([i for i in matched if i.amount_cycle in ("once", None)]),
            policy_count=len(items),
            match_count=sum(1 for i in items if i.eligibility == "match"),
        ),
        stages=[
            RoadmapStageGroup(stage=s, title=STAGE_TITLES[s], items=sorted(g, key=_sort_key))
            for s, g in groups.items() if g
        ],
    )


# ---------------- 상담 답변에 붙는 내 조건 요약 ----------------

PLAN_ITEMS = 5
PLAN_NOTICE = "공식 자료 기준 금액이에요. 실제 지급 여부와 금액은 신청 기관이 정해요."
# "퇴소하면 받을 수 있는 게 뭐야", "나 얼마 받아", "어떤 지원 있어" 같은 넓은 질문 (특정 정책 이름을 묻는 질문은 아님)
_BROAD = re.compile(
    r"받을\s*수\s*있|뭐\s*(가|를)?\s*(받|있)|무엇을?\s*받|어떤\s*(지원|혜택|정책|제도|돈)|얼마\s*(나|를|까지)?\s*받"
    r"|퇴소\s*(하면|후에?|하고|하니까)|나한테\s*맞는|내가\s*받"
)


def is_broad_question(message: str) -> bool:
    return bool(_BROAD.search(message))


def personal_plan(db: Session, user: User, today: date) -> PersonalPlan | None:
    """조건이 맞는(match) 정책이 없으면 None"""
    rm = build_roadmap(db, user, today)
    if rm.summary.match_count == 0:
        return None
    items = [i for g in rm.stages for i in g.items if i.eligibility == "match" and i.amount_won is not None]
    return PersonalPlan(
        base_date=rm.base_date,
        d_day=rm.d_day,
        monthly_won=rm.summary.monthly_won,
        once_won=rm.summary.once_won,
        match_count=rm.summary.match_count,
        items=[
            PlanItem(
                policy_id=i.policy_id, name=i.name, amount_text=i.amount_text, benefit=i.benefit, apply_from=i.apply_from,
                apply_note=i.apply_note, has_form=ai_content.find_form(i.policy_key) is not None,
            )
            for i in items[:PLAN_ITEMS]
        ],
        notice=PLAN_NOTICE,
    )


# ---------------- 홈 '나에게 맞는 지원 정책' (추천 순서) ----------------

RECOMMEND_LIMIT = 8
_UNIT = {"monthly": "매월", "yearly": "매년", "once": "한 번", None: "한 번"}


def _when_text(stage: str, item: RoadmapItem) -> str:
    if item.apply_from is not None:
        d = item.apply_from
        return f"{d.month}월 {d.day}일부터 " + ("미리 신청" if stage == "before_leave" else "신청")
    return "퇴소하면 신청" if stage == "at_leave" else "지금 신청"


def _score(stage: str, item: RoadmapItem, today: date) -> int:
    """추천 점수: 조건이 맞는 정책 > 곧 신청할 정책 > 현금이 큰 정책. 같은 점수면 로드맵 순서"""
    score = 100 if item.eligibility == "match" else 0
    if stage == "before_leave":
        days = (item.apply_from - today).days if item.apply_from else 999
        score += 40 if days <= 60 else 25
    elif stage == "now":
        score += 30
    else:
        score += 15
    score += min(_monthly_equivalent(item) // 20_000, 30)  # 월 50만 원 → 25점
    if item.amount_won is not None and item.amount_cycle in ("once", None):
        score += min(item.amount_won // 1_000_000, 15)  # 1,000만 원 → 10점
    return score


# 홈 추천에 '퇴소하면 신청' 정책을 넣는 때: 퇴소가 이만큼 남았을 때부터 (그 전에는 로드맵에서만 보여 준다)
SOON_LEAVE_DAYS = 60


def recommend(db: Session, user: User, today: date, limit: int = RECOMMEND_LIMIT) -> list[RecommendedPolicy]:
    """맞춤 신청 로드맵(3.32)과 같은 후보 · 단계로 점수를 매겨 추천 순서대로 준다 (규칙, AI 없음).
    아직 보호 중이면 지금 받을 수 있는 정책 위주로: 퇴소해야 받는 정책은 퇴소가 60일 안으로 남았을 때만 넣는다"""
    rm = build_roadmap(db, user, today)
    soon = rm.base_date is not None and (rm.base_date - today).days <= SOON_LEAVE_DAYS
    rows = []
    pairs = [(g, i) for g in rm.stages for i in g.items if g.stage != "at_leave" or soon]
    if user.status in BEFORE_LEAVE_STATUSES and not soon:
        # 사전신청도 신청이 60일 안에 열리는 것만
        pairs = [(g, i) for g, i in pairs
                 if g.stage != "before_leave" or (i.apply_from is not None and (i.apply_from - today).days <= SOON_LEAVE_DAYS)]
    for order, (group, item) in enumerate(pairs):
        cash = item.amount_won is not None
        unit = _UNIT.get(item.amount_cycle, "한 번") if cash else None
        value = item.amount_text.removeprefix("월 ").removeprefix("연 ") if cash and item.amount_text else None
        rows.append((-_score(group.stage, item, today), order, RecommendedPolicy(
            policy_id=item.policy_id, policy_key=item.policy_key, name=item.name, category=item.category,
            eligibility=item.eligibility, stage=group.stage, when_text=_when_text(group.stage, item),
            amount_unit=unit, amount_value=value, amount_text=item.amount_text, benefit=item.benefit,
            kind_title=item.kind_title, pick_reason=item.pick_reason, same_kind_count=len(item.alternatives),
            score=_score(group.stage, item, today),
        )))
    rows.sort(key=lambda r: (r[0], r[1]))
    return [r[2] for r in rows[:limit]]


# ---------------- 정책 상세 '같은 종류 중에서' ----------------

def same_kind_for(db: Session, user: User, policy_id: int, today: date) -> SameKind | None:
    """정책 상세에서: 같은 종류 중 이 정책이 내게 가장 유리한지, 아니면 어느 것이 더 나은지 (로드맵과 같은 기준)"""
    rm = build_roadmap(db, user, today)
    for item in (i for g in rm.stages for i in g.items):
        if not item.alternatives or item.kind_title is None:
            continue
        best = SameKindPolicy(policy_id=item.policy_id, name=item.name, region=item.region, amount_text=item.amount_text)
        alts = [SameKindPolicy(policy_id=a.policy_id, name=a.name, region=a.region, amount_text=a.amount_text)
                for a in item.alternatives]
        if item.policy_id == policy_id:
            return SameKind(kind_title=item.kind_title, is_best=True, reason=item.pick_reason or "", best=best, others=alts)
        if any(a.policy_id == policy_id for a in alts):
            others = [best] + [a for a in alts if a.policy_id != policy_id]
            return SameKind(
                kind_title=item.kind_title, is_best=False,
                reason=f"같은 종류인 '{item.name}'{'이' if _has_final(item.name) else '가'} 내 조건에서 더 유리해요",
                best=best, others=others,
            )
    return None


def _has_final(word: str) -> bool:
    c = word.rstrip(")").rstrip()[-1:] or "가"
    return "가" <= c <= "힣" and (ord(c) - 0xAC00) % 28 != 0


# ---------------- 같은 종류 전체 (다른 지역 것까지) ----------------

# 같은 종류마다 '왜 하나만 받는지' 한 줄
_KIND_RULE = {
    "allowance": "함께 받을 수 없어서 하나만 받아요",
    "settlement": "지역마다 하나씩 있어서 내 지역 것을 받아요",
    "challenge": "지역마다 대상 나이와 조건이 조금씩 달라서 내 지역 것을 받아요",
    "college": "비슷한 지원이라 내 조건에 맞는 것을 받아요",
    "scholarship": "비슷한 지원이라 내 조건에 맞는 것을 받아요",
    "lease": "비슷한 지원이라 내 조건에 맞는 것을 받아요",
}


def kind_of_name(name: str) -> str | None:
    for key, _, pattern in _SAME_KIND:
        if pattern.search(name):
            return key
    return None


def _short_reason(item: RoadmapItem, mine_count: int) -> str:
    if mine_count <= 1:
        return "내 조건에 맞는 건 이것 하나예요"
    if item.pick_reason and "받는 돈" in item.pick_reason:
        return "받는 돈이 가장 커서 이걸 골랐어요"
    if item.region != "전국":
        return "내 지역 정책이라 이걸 골랐어요"
    if item.kind == "challenge":
        return "내 지역 사업이 없어서 전국 사업을 골랐어요"
    if item.kind == "allowance":
        return "자립수당은 하나만 받을 수 있어요"
    return "내 조건에 가장 잘 맞아서 이걸 골랐어요"


def same_kind_group(db: Session, user: User, policy: Policy, today: date) -> SameKindGroup | None:
    """이 정책과 같은 종류 전체. 같은 종류로 묶이지 않는 정책이면 None"""
    from sqlalchemy import select

    from app.services.policies import VISIBLE, is_hidden_dummy

    key = kind_of_name(policy.name)
    if key is None:
        return None
    pattern = next(p for k, _, p in _SAME_KIND if k == key)
    pool = [p for p in db.scalars(select(Policy).where(VISIBLE).order_by(Policy.id))
            if not is_hidden_dummy(p) and pattern.search(p.name)]
    rm = build_roadmap(db, user, today)
    items = [i for g in rm.stages for i in g.items if _kind_key(i) == key]
    picked_item = items[0] if items else None
    mine_ids = {i.policy_id for i in items} | {a.policy_id for i in items for a in i.alternatives}

    def amount(p: Policy) -> tuple[int, str | None]:
        won, cycle, text = policy_amount(p)
        value = _max_total(won, cycle, parse_period_years(p.support_period)) or won or 0
        return value, text

    rows = []
    for p in pool:
        if picked_item is not None and p.id == picked_item.policy_id:
            continue
        value, text = amount(p)
        rows.append((-value, value == 0, p.id, SameKindRow(policy_id=p.id, name=p.name, region=p.region,
                                                           amount_text=text, mine=p.id in mine_ids)))
    rows.sort(key=lambda r: (r[1], r[0], r[2]))
    picked = None
    if picked_item is not None:
        picked = SameKindPicked(policy_id=picked_item.policy_id, name=picked_item.name, region=picked_item.region,
                                amount_text=picked_item.amount_text,
                                reason=_short_reason(picked_item, len(mine_ids)))
    return SameKindGroup(kind=key, kind_title=_KIND_TITLE[key], rule=_KIND_RULE[key], total=len(pool),
                         picked=picked, others=[r[3] for r in rows])
