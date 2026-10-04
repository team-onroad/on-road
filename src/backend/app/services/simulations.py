"""시뮬레이션 처리 (docs/api.md 3.7, 3.8, 3.14 / docs/db-schema.md 3.5).

result JSONB 구조 (db-schema.md 3.5):
    {"shortages": [{item, input, minimum, gap}],
     "alternatives": [{label, allocations, remaining, remaining_shortages: [{item, input, minimum, gap}]}],
     "related_policy_ids": [...]}
항목 이름(label)은 저장하지 않고 응답할 때 api.md 1.2 이름을 붙인다.
"""

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.codes import ALLOCATION_ITEMS, ALLOCATION_LABELS
from app.criteria import Criteria
from app.errors import AppError
from app.ids import parse_id
from app.rules import calc_age
from app.schemas.simulations import (
    Allocations,
    Alternative,
    CriteriaItemOut,
    CriteriaResponse,
    RelatedPolicy,
    Shortage,
    SimulationCreate,
    SimulationResponse,
)
from app.services.policies import candidate_policies
from db.models import Policy, Simulation, User


def simulation_not_found() -> AppError:
    return AppError(404, "SIMULATION_NOT_FOUND", "시뮬레이션 기록을 찾을 수 없습니다.")


def sum_exceeds_income() -> AppError:
    return AppError(422, "SUM_EXCEEDS_INCOME", "배분 합계가 총소득보다 큽니다.")


def criteria_response(criteria: Criteria) -> CriteriaResponse:
    return CriteriaResponse(
        criteria_version=criteria.version,
        items=[
            CriteriaItemOut(key=k, label=v.label, minimum=v.minimum) for k, v in criteria.items.items()
        ],
    )


def find_shortages(allocations: dict[str, int], criteria: Criteria) -> list[dict]:
    """입력값이 기준 최소값보다 적은 항목. 기준표 항목 순서."""
    return [
        {"item": k, "input": allocations[k], "minimum": item.minimum, "gap": item.minimum - allocations[k]}
        for k, item in criteria.items.items()
        if allocations[k] < item.minimum
    ]


MAX_ALTERNATIVES = 3


def _ro(word: str) -> str:
    """조사 '로/으로'. 받침이 있으면(ㄹ 받침 제외) '으로'."""
    code = ord(word[-1]) - 0xAC00
    if 0 <= code <= 11171 and code % 28 not in (0, 8):
        return f"{word}으로"
    return f"{word}로"


def _fill(alloc: dict[str, int], items: list[str], amount: int, minimum: dict[str, int]) -> tuple[int, list[str]]:
    """amount 를 부족 항목에 기준표 순서로 배정한다. (쓴 금액, 금액을 받은 항목)"""
    used, received = 0, []
    for k in items:
        x = min(minimum[k] - alloc[k], amount - used)
        if x > 0:
            alloc[k] += x
            used += x
            received.append(k)
    return used, received


def _alternative(
    total_income: int,
    alloc: dict[str, int],
    used_remaining: bool,
    donors: list[str],
    received: set[str],
    criteria: Criteria,
) -> dict:
    remaining_shortages = find_shortages(alloc, criteria)
    labels = criteria.items
    sources = "·".join(labels[d].label for d in donors)
    if used_remaining and donors:
        source = f"남은 금액과 {_ro(sources)}"
    elif used_remaining:
        source = _ro("남은 금액")
    else:
        source = _ro(sources)
    targets = "·".join(labels[k].label for k in ALLOCATION_ITEMS if k in received)
    action = "일부 보완" if remaining_shortages else "보완"
    return {
        "label": f"{source} {targets} {action}",
        "allocations": alloc,
        "remaining": total_income - sum(alloc.values()),
        "remaining_shortages": remaining_shortages,
    }


def build_alternatives(
    total_income: int, allocations: dict[str, int], shortages: list[dict], criteria: Criteria
) -> list[dict]:
    """부족 항목을 채우는 대안 0~3개 (api.md 3.8). 랜덤 없이 항상 같은 결과.

    1. 남은 금액을 부족 항목에 기준표 순서로 먼저 배정한다. 이것만으로 다 채워지면 대안은 그 1개.
    2. 그래도 부족하면 기준보다 많이 배분된(부족하지 않은) 항목에서 옮긴다.
       여유 금액(입력 - 기준)이 큰 항목부터 쓰고(같으면 기준표 순서), 기준값 아래로는 줄이지 않는다.
    3. 대안 1: 옮길 항목을 위 순서대로 필요한 만큼 쓴다. 다 못 채워도 넣는다 (remaining_shortages 에 남음).
       대안 2, 3: 앞선 대안에서 쓴 항목을 빼고 같은 방식으로 만들되, 부족분을 모두 채울 때만 넣는다.
    4. 옮길 항목이 없으면 남은 금액만 쓴 대안 1개, 남은 금액도 없으면 대안 없음.
    """
    if not shortages:
        return []
    minimum = {k: v.minimum for k, v in criteria.items.items()}
    short_items = [s["item"] for s in shortages]

    base = dict(allocations)
    used_remaining, received = _fill(base, short_items, total_income - sum(allocations.values()), minimum)
    need = sum(minimum[k] - base[k] for k in short_items)
    if need == 0:
        return [_alternative(total_income, base, True, [], set(received), criteria)]

    pool = sorted(
        (k for k in ALLOCATION_ITEMS if k not in short_items and allocations[k] > minimum[k]),
        key=lambda k: (-(allocations[k] - minimum[k]), ALLOCATION_ITEMS.index(k)),
    )
    alternatives: list[dict] = []
    while pool and len(alternatives) < MAX_ALTERNATIVES:
        alloc, donors, got, left = dict(base), [], set(received), need
        for d in pool:
            if left == 0:
                break
            x = min(alloc[d] - minimum[d], left)
            alloc[d] -= x
            _, to = _fill(alloc, short_items, x, minimum)
            got.update(to)
            donors.append(d)
            left -= x
        if alternatives and left > 0:
            break  # 두 번째 대안부터는 부족분을 모두 채울 때만
        alternatives.append(_alternative(total_income, alloc, used_remaining > 0, donors, got, criteria))
        pool = [d for d in pool if d not in donors]

    if not alternatives and used_remaining > 0:
        alternatives.append(_alternative(total_income, base, True, [], set(received), criteria))
    return alternatives


def _related_policies(db: Session, user: User, item_keys: list[str], criteria: Criteria, today: date) -> list[Policy]:
    """부족 항목과 연결된 분야의 노출 정책 중 사용자 기준 excluded 가 아닌 것.

    순서: 부족 항목 순서(기준표 순서) → 항목의 policy_categories 순서 → policy_id. 중복은 처음 것만 남긴다.
    """
    candidates = candidate_policies(db, calc_age(user.birth_date, today), user.region)  # id 오름차순
    ordered: dict[int, Policy] = {}
    for key in item_keys:
        for category in criteria.items[key].policy_categories:
            for p in candidates:
                if p.category == category:
                    ordered.setdefault(p.id, p)
    return list(ordered.values())


def _refilter_related(db: Session, user: User, policy_ids: list[int], today: date) -> list[Policy]:
    """3.14: 저장된 관련 정책 중 지금도 노출 대상이고 excluded 가 아닌 것만, 저장된 순서대로."""
    current = {p.id: p for p in candidate_policies(db, calc_age(user.birth_date, today), user.region)}
    return [current[pid] for pid in policy_ids if pid in current]


def _shortage_out(s: dict) -> Shortage:
    return Shortage(label=ALLOCATION_LABELS[s["item"]], **s)


def to_response(sim: Simulation, related: list[Policy]) -> SimulationResponse:
    allocations = {k: getattr(sim, k) for k in ALLOCATION_ITEMS}
    result = sim.result
    return SimulationResponse(
        simulation_id=sim.id,
        criteria_version=sim.criteria_version,
        total_income=sim.total_income,
        allocations=Allocations(**allocations),
        remaining=sim.total_income - sum(allocations.values()),
        shortages=[_shortage_out(s) for s in result["shortages"]],
        alternatives=[
            Alternative(
                label=a["label"],
                allocations=Allocations(**a["allocations"]),
                remaining=a["remaining"],
                remaining_shortages=[_shortage_out(s) for s in a["remaining_shortages"]],
            )
            for a in result["alternatives"]
        ],
        related_policies=[
            RelatedPolicy(policy_id=p.id, name=p.name, agency=p.agency, category=p.category) for p in related
        ],
        created_at=sim.created_at,
    )


def create_simulation(
    db: Session, user: User, body: SimulationCreate, criteria: Criteria, today: date
) -> SimulationResponse:
    allocations = body.allocations.model_dump()
    if body.allocations.total() > body.total_income:
        raise sum_exceeds_income()

    shortages = find_shortages(allocations, criteria)
    alternatives = build_alternatives(body.total_income, allocations, shortages, criteria)
    related = _related_policies(db, user, [s["item"] for s in shortages], criteria, today)

    sim = Simulation(
        user_id=user.id,
        total_income=body.total_income,
        **allocations,
        result={
            "shortages": shortages,
            "alternatives": alternatives,
            "related_policy_ids": [p.id for p in related],
        },
        criteria_version=criteria.version,
    )
    db.add(sim)
    db.commit()
    db.refresh(sim)
    return to_response(sim, related)


def get_simulation(db: Session, user: User, simulation_id: str, today: date) -> SimulationResponse:
    """다른 사용자의 기록, 없는 기록, 형식이 잘못된 ID 는 모두 404 SIMULATION_NOT_FOUND."""
    sid = parse_id(simulation_id)
    if sid is None:
        raise simulation_not_found()
    sim = db.scalars(
        select(Simulation).where(Simulation.id == sid, Simulation.user_id == user.id)
    ).one_or_none()
    if sim is None:
        raise simulation_not_found()
    related = _refilter_related(db, user, sim.result["related_policy_ids"], today)
    return to_response(sim, related)
