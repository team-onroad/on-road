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
            else:
                assert s.url and s.url.startswith("https://www.") and s.url.split("/")[2].endswith(".go.kr") \
                    or s.url.startswith("https://www.q-net.or.kr")


def test_unknown_user(api):
    res = api.get(f"/api/users/{uuid.uuid4()}/career-roadmap")
    assert res.status_code == 404
