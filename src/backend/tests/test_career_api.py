"""GET /users/{user_id}/career-roadmap (api.md 3.33)."""

import json
import uuid
from datetime import date
from pathlib import Path

import pytest

from app.services.career import STEPS
from tests.factories import make_policy, make_user

APP_DIR = Path(__file__).resolve().parents[1] / "app"


def _path(data, name):
    return next(p for p in data["paths"] if p["path"] == name)


@pytest.mark.parametrize(
    ("work", "expected"),
    [("student", "college"), ("working", "training"), ("job_seeking", "job"), (None, "job")],
)
def test_recommended_path_follows_work(api, db_session, work, expected):
    user = make_user(db_session, work=work)
    data = api.get(f"/api/users/{user.id}/career-roadmap").json()
    assert data["recommended"] == expected
    assert [p["path"] for p in data["paths"]][0] == expected
    assert sorted(p["path"] for p in data["paths"]) == ["college", "job", "training"]


def test_teen_defaults_to_college(api, db_session):
    user = make_user(db_session, birth_date=date(2011, 1, 1), status="in_care")  # 15세
    assert api.get(f"/api/users/{user.id}/career-roadmap").json()["recommended"] == "college"


def test_policies_match_path_keywords_and_eligibility(api, db_session):
    job = make_policy(db_session, name="청년도전지원사업", category="employment", support_amount=None)
    college = make_policy(db_session, name="대학 등록금 지원", category="education", support_amount="200만 원")
    make_policy(db_session, name="부산 취업 지원", region="부산")  # 지역이 달라 빠짐
    user = make_user(db_session)

    data = api.get(f"/api/users/{user.id}/career-roadmap").json()

    assert [p["policy_id"] for p in _path(data, "job")["policies"]] == [job.id]
    col = _path(data, "college")["policies"]
    assert [p["policy_id"] for p in col] == [college.id]
    assert col[0]["amount_text"] == "200만 원"
    assert _path(data, "training")["policies"] == []


def test_steps_point_to_existing_app_features():
    scenarios = {s["scenario_id"] for s in json.loads((APP_DIR / "roleplay_scenarios.json").read_text())}
    forms = json.loads((APP_DIR / "forms.json").read_text())
    forms = forms if isinstance(forms, list) else forms.get("forms", list(forms.values()))
    templates = {f["template_id"] for f in forms}
    for steps in STEPS.values():
        for s in steps:
            if s.kind == "roleplay":
                assert s.scenario_id in scenarios
            elif s.kind == "document":
                assert s.template_id in templates
            elif s.kind == "app":
                assert s.target in {"interest", "risk", "chat"}
            elif s.kind == "search":
                assert s.query
            else:
                # 공공기관 공식 사이트만 (정부 .go.kr · 공공기관 .or.kr 중 확인한 곳)
                host = s.url.split("/")[2]
                assert s.url.startswith("https://")
                assert host.endswith(".go.kr") or host in {"www.q-net.or.kr", "jaripon.ncrc.or.kr", "www.adiga.kr"}


def test_every_step_has_guide():
    """할 일마다 펼쳐 보는 '이렇게 해요'가 있다"""
    for steps in STEPS.values():
        for s in steps:
            assert s.guide, s.step_id


def _steps(data, path):
    return {s["step_id"]: s for s in _path(data, path)["steps"]}


def test_personal_steps_follow_my_conditions(api, db_session):
    """나이 · 상황 · 지역에 따라 할 일이 더해지고 까닭(reason)이 붙는다"""
    from datetime import date as _d

    young = make_user(db_session, birth_date=_d(2003, 5, 1), status="left_care", region="서울")
    data = api.get(f"/api/users/{young.id}/career-roadmap").json()
    job = _steps(data, "job")
    assert "만 18~34세" in job["job-kua"]["reason"]
    assert "job-aftercare" in job and job["job-aftercare"]["reason"]
    assert job["job-local"]["query"] == "서울 청년 취업"
    assert "college-extend" not in _steps(data, "college")

    care = make_user(db_session, birth_date=_d(2009, 3, 1), status="in_care", region="부산")
    data = api.get(f"/api/users/{care.id}/career-roadmap").json()
    col = _steps(data, "college")
    assert col["college-extend"]["target"] == "chat" and col["college-extend"]["query"]
    assert col["college-jaripon"]["reason"]
    assert "job-kua" not in _steps(data, "job")  # 만 17세라 청년 대상이 아님
    assert "job-aftercare" not in _steps(data, "job")


def test_step_ids_unique_and_stable():
    """앱은 step_id 로 끝낸 일 · 일정을 기억한다: 방향 안팎에서 겹치면 안 되고, 방향 이름으로 시작한다"""
    ids = [s.step_id for steps in STEPS.values() for s in steps]
    assert len(ids) == len(set(ids))
    for path, steps in STEPS.items():
        assert all(s.step_id.startswith(f"{path}-") for s in steps)


def test_unknown_user(api):
    res = api.get(f"/api/users/{uuid.uuid4()}/career-roadmap")
    assert res.status_code == 404
