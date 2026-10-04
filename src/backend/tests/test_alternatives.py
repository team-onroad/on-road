"""시뮬레이션 대안(alternatives) 규칙 (api.md 3.8, 확정 규칙은 README 참고).

기준값(임시 기준표 = api.md 5장 예시): 주거비 400,000 / 식비 250,000 / 교통비 60,000 / 통신비 40,000 / 기타 100,000
"""

import itertools

import pytest
from sqlalchemy import text

from app.codes import ALLOCATION_ITEMS
from app.criteria import Criteria, get_criteria
from app.main import app
from app.services.simulations import build_alternatives, find_shortages
from tests.factories import make_policy, make_user

MIN = {"housing": 400000, "food": 250000, "transport": 60000, "telecom": 40000, "other": 100000}


def _alloc(housing, food, transport, telecom, other):
    return {"housing": housing, "food": food, "transport": transport, "telecom": telecom, "other": other}


def _alts(total, alloc):
    criteria = get_criteria()
    return build_alternatives(total, alloc, find_shortages(alloc, criteria), criteria)


def _sh(item, input_, minimum, gap):
    return {"item": item, "input": input_, "minimum": minimum, "gap": gap}


# ------------------------------------------------------------- 예시 A~F


CASES = {
    "A_spec_example": (
        900000, _alloc(300000, 300000, 100000, 50000, 100000),
        [
            {"label": "남은 금액과 식비로 주거비 보완",
             "allocations": _alloc(400000, 250000, 100000, 50000, 100000), "remaining": 0, "remaining_shortages": []},
            {"label": "남은 금액과 교통비·통신비로 주거비 보완",
             "allocations": _alloc(400000, 300000, 60000, 40000, 100000), "remaining": 0, "remaining_shortages": []},
        ],
    ),
    "B1_no_donor_with_remaining": (
        800000, _alloc(300000, 250000, 60000, 40000, 100000),
        [
            {"label": "남은 금액으로 주거비 일부 보완",
             "allocations": _alloc(350000, 250000, 60000, 40000, 100000), "remaining": 0,
             "remaining_shortages": [_sh("housing", 350000, 400000, 50000)]},
        ],
    ),
    "B2_no_donor_no_remaining": (750000, _alloc(300000, 250000, 60000, 40000, 100000), []),
    "C1_single_donor": (
        900000, _alloc(300000, 350000, 60000, 40000, 100000),
        [
            {"label": "남은 금액과 식비로 주거비 보완",
             "allocations": _alloc(400000, 300000, 60000, 40000, 100000), "remaining": 0, "remaining_shortages": []},
        ],
    ),
    "C2_remaining_is_enough": (
        1000000, _alloc(300000, 250000, 60000, 40000, 100000),
        [
            {"label": "남은 금액으로 주거비 보완",
             "allocations": _alloc(400000, 250000, 60000, 40000, 100000), "remaining": 150000,
             "remaining_shortages": []},
        ],
    ),
    "D_income_below_minimum_total": (
        800000, _alloc(300000, 200000, 100000, 50000, 150000),
        [
            {"label": "기타·교통비·통신비로 주거비 일부 보완",
             "allocations": _alloc(400000, 200000, 60000, 40000, 100000), "remaining": 0,
             "remaining_shortages": [_sh("food", 200000, 250000, 50000)]},
        ],
    ),
    "E_multiple_shortages": (
        1000000, _alloc(350000, 200000, 150000, 100000, 150000),
        [
            {"label": "남은 금액과 교통비로 주거비·식비 보완",
             "allocations": _alloc(400000, 250000, 100000, 100000, 150000), "remaining": 0, "remaining_shortages": []},
            {"label": "남은 금액과 통신비로 주거비·식비 보완",
             "allocations": _alloc(400000, 250000, 150000, 50000, 150000), "remaining": 0, "remaining_shortages": []},
            {"label": "남은 금액과 기타로 주거비·식비 보완",
             "allocations": _alloc(400000, 250000, 150000, 100000, 100000), "remaining": 0, "remaining_shortages": []},
        ],
    ),
    "F_third_cannot_cover": (
        1200000, _alloc(200000, 450000, 260000, 90000, 200000),
        [
            {"label": "식비로 주거비 보완",
             "allocations": _alloc(400000, 250000, 260000, 90000, 200000), "remaining": 0, "remaining_shortages": []},
            {"label": "교통비로 주거비 보완",
             "allocations": _alloc(400000, 450000, 60000, 90000, 200000), "remaining": 0, "remaining_shortages": []},
        ],
    ),
}


@pytest.mark.parametrize("case", list(CASES))
def test_examples(case):
    total, alloc, expected = CASES[case]
    assert _alts(total, alloc) == expected


def test_input_is_not_mutated():
    alloc = _alloc(300000, 300000, 100000, 50000, 100000)
    before = dict(alloc)
    _alts(900000, alloc)
    assert alloc == before


def test_no_shortage_no_alternatives():
    assert _alts(850000, dict(MIN)) == []


# ---------------------------------------------------------- 모든 대안의 불변 조건

# 각 항목: 0 / 기준의 절반 / 기준값 / 기준의 1.5배 → 4^5 = 1024 가지 배분 × 총소득 4가지
_LEVELS = {k: [0, m // 2, m, m * 3 // 2] for k, m in MIN.items()}
GRID = [
    (total, alloc)
    for values in itertools.product(*(_LEVELS[k] for k in ALLOCATION_ITEMS))
    for alloc in [dict(zip(ALLOCATION_ITEMS, values))]
    for total in {sum(values), sum(values) + 30000, sum(values) + 120000, sum(values) + 1000000} - {0}
]


def test_grid_is_large_enough():
    assert len(GRID) > 4000


def test_invariants_for_all_alternatives():
    for total, alloc in GRID:
        shortages = find_shortages(alloc, get_criteria())
        short_items = {s["item"] for s in shortages}
        alts = _alts(total, alloc)
        ctx = (total, alloc)

        assert len(alts) <= 3, ctx
        if not shortages:
            assert alts == [], ctx
        for i, a in enumerate(alts):
            new = a["allocations"]
            assert set(new) == set(ALLOCATION_ITEMS), ctx
            assert sum(new.values()) <= total, ctx  # 배분 합계 ≤ 총소득
            assert a["remaining"] == total - sum(new.values()), ctx  # remaining = 총소득 − 배분 합계
            assert a["remaining"] >= 0, ctx
            for k in ALLOCATION_ITEMS:
                if new[k] < alloc[k]:  # 옮긴(줄어든) 항목은 기준값 아래로 내려가지 않음
                    assert k not in short_items, ctx
                    assert new[k] >= MIN[k], ctx
                if k in short_items:  # 부족 항목은 늘기만 하고 기준값을 넘지 않음
                    assert alloc[k] <= new[k] <= MIN[k], ctx
            assert a["remaining_shortages"] == find_shortages(new, get_criteria()), ctx
            if i > 0:  # 두 번째 대안부터는 부족분을 모두 채움
                assert a["remaining_shortages"] == [], ctx
            assert ("일부 보완" in a["label"]) == bool(a["remaining_shortages"]), ctx
        # 같은 입력이면 항상 같은 결과
        assert _alts(total, dict(alloc)) == alts, ctx


def test_never_says_apply_possible():
    labels = {a["label"] for total, alloc in GRID[::7] for a in _alts(total, alloc)}
    assert labels and all("신청 가능" not in label for label in labels)


# ------------------------------------------------------------ API (3.8, 3.14)

SPEC_RESPONSE = {
    "criteria_version": "2026-10-v1",
    "total_income": 900000,
    "allocations": _alloc(300000, 300000, 100000, 50000, 100000),
    "remaining": 50000,
    "shortages": [{"item": "housing", "label": "주거비", "input": 300000, "minimum": 400000, "gap": 100000}],
    "alternatives": [
        {"label": "남은 금액과 식비로 주거비 보완",
         "allocations": _alloc(400000, 250000, 100000, 50000, 100000), "remaining": 0, "remaining_shortages": []},
        {"label": "남은 금액과 교통비·통신비로 주거비 보완",
         "allocations": _alloc(400000, 300000, 60000, 40000, 100000), "remaining": 0, "remaining_shortages": []},
    ],
    "related_policies": [
        {"policy_id": 7, "name": "(예시) 주거 지원 정책", "agency": "(예시) 담당기관", "category": "housing"}
    ],
}


@pytest.fixture
def spec_criteria(api):
    """api.md 3.7 예시와 같은 버전(2026-10-v1)의 기준표. 값은 임시 기준표와 같다."""
    spec = Criteria.model_validate({**get_criteria().model_dump(), "version": "2026-10-v1"})
    app.dependency_overrides[get_criteria] = lambda: spec
    yield spec
    app.dependency_overrides.pop(get_criteria, None)


def test_api_response_matches_spec_example_exactly(api, db_session, spec_criteria):
    user = make_user(db_session)
    for _ in range(6):  # 예시의 policy_id 7 에 맞추기 위해 관련 없는 정책 6개를 먼저 넣음
        make_policy(db_session, category="education")
    make_policy(db_session, category="housing", name="(예시) 주거 지원 정책", agency="(예시) 담당기관")

    res = api.post("/api/simulations", json={
        "user_id": str(user.id), "total_income": 900000, "allocations": SPEC_RESPONSE["allocations"],
    })

    assert res.status_code == 201, res.text
    data = res.json()
    assert {k: v for k, v in data.items() if k not in ("simulation_id", "created_at")} == SPEC_RESPONSE


def test_get_returns_stored_alternatives(api, db_session, spec_criteria):
    user = make_user(db_session)
    submitted = api.post("/api/simulations", json={
        "user_id": str(user.id), "total_income": 800000, "allocations": _alloc(300000, 200000, 100000, 50000, 150000),
    }).json()
    assert submitted["alternatives"][0]["remaining_shortages"] == [
        {"item": "food", "label": "식비", "input": 200000, "minimum": 250000, "gap": 50000}
    ]

    # 저장 형식: db-schema.md 3.5 (항목 이름은 저장하지 않음)
    stored = db_session.execute(
        text("SELECT result FROM simulations WHERE id = :id"), {"id": submitted["simulation_id"]}
    ).scalar_one()
    assert stored["alternatives"] == CASES["D_income_below_minimum_total"][2]

    # 조회 시점에 기준표가 바뀌어도 저장된 대안을 그대로 돌려준다
    changed = Criteria.model_validate({**spec_criteria.model_dump(), "version": "2027-01-v2"})
    changed.items["food"].minimum = 0
    app.dependency_overrides[get_criteria] = lambda: changed
    data = api.get(f"/api/simulations/{submitted['simulation_id']}", params={"user_id": str(user.id)}).json()

    assert data["alternatives"] == submitted["alternatives"]
    assert data == submitted
