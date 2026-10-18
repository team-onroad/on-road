"""데모 시연용 데이터.

실행 (src/backend 에서):
    python -m db.demo_seed

- 정책: P001(policies.json)을 노출 상태로, 더미 D001~D004 를 활성 상태로 upsert
- 사용자: 고정 user_id. 이미 있으면 지우고 다시 만듦 (체크리스트·시뮬레이션·롤플레잉 기록도 같이 삭제)
- 체크리스트·시뮬레이션·롤플레잉 피드백은 API 와 같은 서비스 함수로 저장
- 여러 번 실행해도 결과 같음
"""

import sys
import uuid
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.clock import today_kst
from app.config import get_settings
from app.criteria import get_criteria
from app.roleplay import get_scenario
from app.rules import calc_age, calc_stage
from app.schemas.checklists import ItemToggle
from app.schemas.roleplay import FeedbackRequest
from app.schemas.simulations import SimulationCreate
from app.schemas.users import UserCreate
from app.services import checklists, roleplay, simulations
from db.models import ChecklistItem, Policy, User
from db.seed import DUMMY_POLICIES_PATH, SeedError, SeedResult, _upsert, load_policy_file, validate_policies

DEMO_USER_ID = uuid.UUID("00000000-0000-4000-8000-000000000001")
DEMO_USER = {
    "name": "김민지",
    "birth_date": "2007-03-15",
    "region": "서울",
    "status": "leaving_soon",
    "d_date": "2026-12-31",
}

DEMO_POLICY_KEY = "P001"
# 임시 쉬운 말. 검증된 변환문이 나오면 교체
DEMO_EASY_TEXT = (
    "보호가 끝난 자립준비청년에게 매달 50만 원을 본인 통장으로 주는 제도예요. "
    "아동복지시설이나 가정위탁에서 보호가 끝나기 전 2년 넘게 계속 보호를 받은 사람이 대상이에요. "
    "18세 이후 보호가 끝났다면 끝난 날부터 5년, 15세 이후 일찍 끝났다면 18세가 된 때부터 5년 동안 받을 수 있어요. "
    "돈은 매달 20일에 들어오고, 주말이나 공휴일이면 그 전날 들어와요. "
    "원래 가정으로 돌아갔거나 다른 자립지원수당을 받고 있으면 받을 수 없어요. "
    "내가 해당되는지는 주소지 행정복지센터나 129에 꼭 확인해 보세요."
)

P001_CHECKED_STEPS = ("target_check", "condition_check")
P001_CHECKED_DOCS = (
    "자립준비청년(보호종료아동) 자립수당 지급 신청서 1부",
    "신분증: 주민등록증·여권·운전면허증·장애인등록증 중 1종",
)
P001_NOT_APPLICABLE_DOCS = ("위임장", "대리인 신분증", "신청인과의 관계 증빙서류")
COMPLETED_POLICY_KEY = "D001"
UNTOUCHED_POLICY_KEY = "D002"
ALL_STEPS = ("target_check", "condition_check", "doc_prepare", "apply")

# 임시 기준표(TEMP-2026-10-v1) 기준. 나중 것이 홈 최근 시뮬레이션
SIMULATIONS = (
    # 부족 없음
    {
        "total_income": 1_000_000,
        "allocations": {"housing": 450_000, "food": 280_000, "transport": 70_000, "telecom": 50_000, "other": 120_000},
    },
    # 주거비만 부족
    {
        "total_income": 1_000_000,
        "allocations": {"housing": 300_000, "food": 300_000, "transport": 70_000, "telecom": 50_000, "other": 150_000},
    },
)

ROLEPLAYS = (
    # 확정일자를 말하지 않아 missed 1개
    ("community-center", ("전입신고 하러 왔어요", "여기 신분증이요", "계약서 가져왔어요")),
    # 체크 항목 모두 good
    ("bank", ("계좌 만들러 왔어요", "네, 신분증 여기 있어요", "월급 받을 통장이에요", "체크카드도 같이 만들어 주세요")),
)


def _seed_policies(db: Session) -> dict[str, Policy]:
    items = load_policy_file(get_settings().policies_json_path) or []
    p001 = [item for item in items if isinstance(item, dict) and item.get("policy_key") == DEMO_POLICY_KEY]
    if len(p001) != 1:
        raise SeedError(f"policies.json 에 {DEMO_POLICY_KEY} 가 1개 있어야 합니다.")
    row = validate_policies(p001, dummy=False)[0]
    row.update(is_active=True, easy_text_verified=True, easy_text=DEMO_EASY_TEXT)  # DB 에서만 노출

    dummies = validate_policies(load_policy_file(DUMMY_POLICIES_PATH) or [], dummy=True)
    _upsert(db, [row, *dummies], SeedResult(source="policies", path=DUMMY_POLICIES_PATH))
    db.commit()
    keys = [DEMO_POLICY_KEY, *(d["policy_key"] for d in dummies)]
    return {p.policy_key: p for p in db.scalars(select(Policy).where(Policy.policy_key.in_(keys)))}


def _recreate_user(db: Session, today: date) -> User:
    old = db.get(User, DEMO_USER_ID)
    if old is not None:
        db.delete(old)  # ON DELETE CASCADE 로 기록도 삭제
        db.commit()
    data = UserCreate.model_validate(DEMO_USER)
    user = User(id=DEMO_USER_ID, **data.model_dump())
    user.stage = calc_stage(calc_age(user.birth_date, today), user.status)
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _items(db: Session, checklist_id: int) -> list[ChecklistItem]:
    return list(db.scalars(select(ChecklistItem).where(ChecklistItem.checklist_id == checklist_id)))


def _set(db: Session, checklist, items: list[ChecklistItem], body: dict) -> None:
    for item in items:
        checklists.toggle(db, checklist, item, ItemToggle.model_validate(body))


def _steps(items: list[ChecklistItem], keys: tuple[str, ...]) -> list[ChecklistItem]:
    return [i for i in items if i.item_type == "step" and i.step_key in keys]


def _docs(items: list[ChecklistItem], labels: tuple[str, ...]) -> list[ChecklistItem]:
    found = {i.label: i for i in items if i.item_type == "document"}
    missing = [label for label in labels if label not in found]
    if missing:
        raise SeedError(f"{DEMO_POLICY_KEY} 서류 없음 {missing} (policies.json 서류명 확인)")
    return [found[label] for label in labels]


def _seed_checklists(db: Session, user: User, policies: dict[str, Policy]) -> None:
    # 생성만 (진행률 0%). 먼저 만들어서 홈 첫 카드는 P001
    checklists.get_or_create(db, user, policies[UNTOUCHED_POLICY_KEY].id)

    checklist, _ = checklists.get_or_create(db, user, policies[DEMO_POLICY_KEY].id)
    items = _items(db, checklist.id)
    _set(db, checklist, _steps(items, P001_CHECKED_STEPS), {"is_checked": True})
    _set(db, checklist, _docs(items, P001_CHECKED_DOCS), {"is_checked": True})
    _set(db, checklist, _docs(items, P001_NOT_APPLICABLE_DOCS), {"is_not_applicable": True})

    done, _ = checklists.get_or_create(db, user, policies[COMPLETED_POLICY_KEY].id)
    done_items = _items(db, done.id)
    _set(db, done, _steps(done_items, ALL_STEPS), {"is_checked": True})
    _set(db, done, [i for i in done_items if i.item_type == "document"], {"is_checked": True})


def _seed_simulations(db: Session, user: User, today: date) -> None:
    for sim in SIMULATIONS:
        body = SimulationCreate.model_validate({"user_id": str(user.id), **sim})
        simulations.create_simulation(db, user, body, get_criteria(), today)


def _seed_roleplays(db: Session, user: User) -> None:
    for scenario_id, user_turns in ROLEPLAYS:
        scenario = get_scenario(scenario_id)
        history = [{"role": "assistant", "content": scenario.opening}]
        for said, line in zip(user_turns, scenario.lines):
            history += [{"role": "user", "content": said}, {"role": "assistant", "content": line}]
        body = FeedbackRequest.model_validate({"user_id": str(user.id), "history": history, "save": True})
        roleplay.give_feedback(db, user, scenario, body)


def seed_demo(db: Session, today: date) -> User:
    policies = _seed_policies(db)
    user = _recreate_user(db, today)
    _seed_checklists(db, user, policies)
    _seed_simulations(db, user, today)
    _seed_roleplays(db, user)
    return user


def main() -> int:
    from db.session import get_engine

    try:
        with Session(get_engine()) as db:
            user = seed_demo(db, today_kst())
            # 세션 닫기 전에 출력 (닫힌 뒤에는 속성 못 읽음)
            print(f"[demo_seed] 완료. user_id: {user.id} ({user.name}, {user.stage})")
    except SeedError as e:
        print(f"[demo_seed] 실패: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
