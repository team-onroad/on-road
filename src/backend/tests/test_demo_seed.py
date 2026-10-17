"""db/demo_seed.py (시연 데이터)."""

import hashlib
import json
from datetime import date

import pytest
from sqlalchemy import func, select, text

from app.config import get_settings
from db.demo_seed import DEMO_EASY_TEXT, DEMO_USER_ID, seed_demo
from db.models import Checklist, ChecklistItem, Policy, RoleplayResult, Simulation, User
from db.seed import SeedError
from tests.factories import make_policy, make_user

TODAY = date(2026, 10, 4)  # clock fixture 와 같은 날
UID = str(DEMO_USER_ID)


def _count(db_session, model):
    db_session.expire_all()
    return db_session.scalar(select(func.count()).select_from(model))


def _policy(db_session, key):
    db_session.expire_all()
    return db_session.scalars(select(Policy).where(Policy.policy_key == key)).one()


def _home(api):
    checklists = api.get(f"/api/users/{UID}/growth").json()["checklists"]
    return {c["policy"]["name"]: c for c in checklists}


def test_policies(db_session):
    seed_demo(db_session, TODAY)

    p001 = _policy(db_session, "P001")
    assert (p001.is_active, p001.easy_text_verified, p001.easy_text) == (True, True, DEMO_EASY_TEXT)
    for key in ("D001", "D002", "D003", "D004"):
        assert _policy(db_session, key).is_active is True


def test_policies_json_not_changed(db_session):
    path = get_settings().policies_json_path
    before = hashlib.sha256(path.read_bytes()).hexdigest()
    seed_demo(db_session, TODAY)
    assert hashlib.sha256(path.read_bytes()).hexdigest() == before
    assert json.loads(path.read_text(encoding="utf-8"))[0]["is_active"] is False


def test_reactivates_dummy(db_session):
    seed_demo(db_session, TODAY)
    db_session.execute(text("UPDATE policies SET is_active = false WHERE policy_key LIKE 'D%'"))
    db_session.commit()

    seed_demo(db_session, TODAY)

    assert _policy(db_session, "D001").is_active is True


def test_user(api, db_session):
    seed_demo(db_session, TODAY)

    data = api.get(f"/api/users/{UID}").json()
    assert {k: data[k] for k in ("user_id", "name", "birth_date", "age", "phone", "region", "status", "stage",
                                 "d_date", "d_day")} == {
        "user_id": UID, "name": "김민지", "birth_date": "2007-03-15", "age": 19, "phone": None, "region": "서울",
        "status": "leaving_soon", "stage": "youth", "d_date": "2026-12-31", "d_day": 88,
    }


def test_p001_checklist(api, db_session):
    seed_demo(db_session, TODAY)
    summary = _home(api)["자립준비청년 자립수당 지급"]
    detail = api.get(f"/api/checklists/{summary['checklist_id']}", params={"user_id": UID}).json()

    assert detail["progress"] == {"checked_steps": 2, "total_steps": 4, "percent": 50}
    assert [s["is_checked"] for s in detail["steps"]] == [True, True, False, False]
    docs = next(s for s in detail["steps"] if s["step_key"] == "doc_prepare")["documents"]
    checked = [d["label"] for d in docs if d["is_checked"]]
    not_applicable = [d["label"] for d in docs if d["is_not_applicable"]]
    assert checked == [
        "자립준비청년(보호종료아동) 자립수당 지급 신청서 1부",
        "신분증: 주민등록증·여권·운전면허증·장애인등록증 중 1종",
    ]
    assert not_applicable == ["위임장", "대리인 신분증", "신청인과의 관계 증빙서류"]
    assert detail["document_progress"] == {"checked": 2, "total": len(docs) - 3}
    assert summary["document_progress"] == detail["document_progress"]


def test_d001_checklist_completed(api, db_session):
    seed_demo(db_session, TODAY)
    summary = _home(api)["(더미) 자립 생활 지원금"]
    assert summary["progress"] == {"checked_steps": 4, "total_steps": 4, "percent": 100}
    assert summary["document_progress"] == {"checked": 3, "total": 3}
    assert summary["next_step"] is None


def test_d002_checklist_untouched(api, db_session):
    seed_demo(db_session, TODAY)
    summary = _home(api)["(더미) 청년 월세 지원"]
    assert summary["progress"] == {"checked_steps": 0, "total_steps": 4, "percent": 0}
    assert summary["document_progress"] == {"checked": 0, "total": 3}


def _simulation(api, simulation_id):
    return api.get(f"/api/simulations/{simulation_id}", params={"user_id": UID}).json()


def test_simulations(api, db_session):
    seed_demo(db_session, TODAY)
    sims = api.get(f"/api/users/{UID}/growth").json()["simulations"]  # 최신순
    assert [(s["total_income"], s["remaining"], s["shortage_count"]) for s in sims] == [
        (1_000_000, 130_000, 1), (1_000_000, 30_000, 0),
    ]

    short = _simulation(api, sims[0]["simulation_id"])
    assert short["shortages"] == [
        {"item": "housing", "label": "주거비", "input": 300_000, "minimum": 400_000, "gap": 100_000},
    ]
    assert [p["name"] for p in short["related_policies"]] == ["(더미) 청년 월세 지원"]

    enough = _simulation(api, sims[1]["simulation_id"])
    assert (enough["shortages"], enough["alternatives"], enough["related_policies"]) == ([], [], [])


def test_roleplays(api, db_session):
    seed_demo(db_session, TODAY)
    roleplays = api.get(f"/api/users/{UID}/growth").json()["roleplays"]  # 최신순
    assert [(r["scenario_id"], r["good_count"], r["missed_count"]) for r in roleplays] == [
        ("bank", 4, 0), ("community-center", 3, 1),
    ]
    rows = {r.scenario_id: r for r in db_session.scalars(select(RoleplayResult))}
    assert rows["community-center"].good == ["신분증 준비", "임대차 계약서 챙기기", "끝까지 대화를 이어 갔어요"]
    assert rows["community-center"].missed == ["확정일자 함께 받기"]
    assert rows["bank"].good == ["신분증 준비", "계좌 만드는 목적 설명", "체크카드 발급 여부", "끝까지 대화를 이어 갔어요"]
    assert rows["bank"].missed == []


def test_dashboard_filled(api, db_session):
    # 3.4 홈
    seed_demo(db_session, TODAY)
    data = api.get(f"/api/users/{UID}/dashboard").json()

    assert data["user"]["name"] == "김민지"
    assert data["user"]["d_day"] == 88
    names = [c["policy"]["name"] for c in data["checklists"]]
    assert names == ["자립준비청년 자립수당 지급", "(더미) 청년 월세 지원", "(더미) 자립 생활 지원금"]  # 진행 중 → 완료
    assert data["checklists"][0]["next_step"] == {"step_key": "doc_prepare", "label": "서류 준비"}
    assert data["latest_simulation"]["shortage_count"] == 1


def test_growth_filled(api, db_session):
    # 3.13 성장 기록
    seed_demo(db_session, TODAY)
    data = api.get(f"/api/users/{UID}/growth").json()
    assert data["summary"] == {
        "checklist_count": 3, "completed_checklist_count": 1, "simulation_count": 2, "roleplay_count": 2,
    }
    assert (len(data["checklists"]), len(data["simulations"]), len(data["roleplays"])) == (3, 2, 2)


@pytest.mark.parametrize("doc_type", ["resume", "cover_letter", "activity_record"])
def test_documents_filled(api, db_session, doc_type):
    # 3.22 초안: 완료 체크리스트·시뮬레이션 기록 문장이 들어감
    seed_demo(db_session, TODAY)
    res = api.post(f"/api/users/{UID}/documents", json={"type": doc_type})

    assert res.status_code == 200, res.text
    data = res.json()
    sources = [sen["source"] for sec in data["sections"] for sen in sec["sentences"] if sen["source"]]
    assert sources == ["체크리스트 완료 · (더미) 자립 생활 지원금", "생활 시뮬레이션 2회"]
    assert data["notice"] == "앱에 기록된 내용만 근거로 만들었어요. 없는 경험은 넣지 않았어요."


def test_rerun_recreates_user(db_session):
    seed_demo(db_session, TODAY)
    first_checklists = set(db_session.scalars(select(Checklist.id)))

    seed_demo(db_session, TODAY)

    assert _count(db_session, User) == 1
    assert _count(db_session, Policy) == 5
    assert _count(db_session, Checklist) == 3
    assert _count(db_session, Simulation) == 2
    assert _count(db_session, RoleplayResult) == 2
    assert set(db_session.scalars(select(Checklist.id))).isdisjoint(first_checklists)  # 지우고 다시 만듦
    docs = sum(len(_policy(db_session, k).required_docs) for k in ("P001", "D001", "D002"))
    assert _count(db_session, ChecklistItem) == 4 * 3 + docs


def test_other_users_untouched(api, db_session):
    other = make_user(db_session)
    policy = make_policy(db_session)
    api.post("/api/checklists", json={"user_id": str(other.id), "policy_id": policy.id})

    seed_demo(db_session, TODAY)
    seed_demo(db_session, TODAY)

    assert _count(db_session, User) == 2
    assert db_session.scalar(select(func.count()).select_from(Checklist).where(Checklist.user_id == other.id)) == 1


def test_missing_p001_is_error(db_session, tmp_path, monkeypatch):
    path = tmp_path / "policies.json"
    path.write_text("[]", encoding="utf-8")
    monkeypatch.setattr(get_settings(), "policies_json_path", path)

    with pytest.raises(SeedError, match="P001"):
        seed_demo(db_session, TODAY)
    assert _count(db_session, User) == 0


def test_main_prints_user(db_engine, db_session, monkeypatch, capsys):
    import db.session

    monkeypatch.setattr(db.session, "get_engine", lambda: db_engine)
    from db.demo_seed import main

    assert main() == 0
    assert f"[demo_seed] 완료. user_id: {UID} (김민지, youth)" in capsys.readouterr().out
