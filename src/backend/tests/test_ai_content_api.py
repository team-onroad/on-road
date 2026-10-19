"""GET /policies/{id}/easy-text (3.23), GET /policies/{id}/form-guide (3.24)."""

import json
from datetime import date

import pytest

from app.config import get_settings
from tests.factories import make_policy, make_user


def _level(text: str, core: str | None = None, passed: bool = True, terms=None, verified=False) -> dict:
    return {"text": text, "core_text": core, "passed": passed, "terms": terms or [], "verified": verified}


@pytest.fixture
def easy_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(get_settings(), "easy_text_dir", tmp_path)
    return tmp_path


@pytest.fixture
def forms_dir(tmp_path, monkeypatch):
    d = tmp_path / "forms"
    d.mkdir()
    monkeypatch.setattr(get_settings(), "forms_dir", d)
    return d


def _write_easy(d, key, levels):
    (d / f"{key}.json").write_text(json.dumps({"policy_key": key, "levels": levels}, ensure_ascii=False), "utf-8")


def test_easy_text_real_p001_file(api, db_session):
    p = make_policy(db_session, policy_key="P001")
    res = api.get(f"/api/policies/{p.id}/easy-text", params={"level": "L3"})
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["level"] == "L3" and body["level_name"] == "조금 쉽게"
    assert [lv["level"] for lv in body["levels"]] == ["L1", "L2", "L3", "L4", "L5", "L6", "L7"]
    assert "50만 원" in body["core_text"]
    assert "[더 알아보기]" not in body["core_text"]
    assert body["terms"] and {"term", "gloss"} == set(body["terms"][0])


def test_easy_text_level_by_age_and_fallback(api, db_session, easy_dir):
    p = make_policy(db_session, policy_key="T900")
    _write_easy(easy_dir, "T900", {
        "L2": _level("둘", passed=True),
        "L5": _level("다섯 핵심\n\n[더 알아보기]\n\n자세히", terms=[{"term": "수급", "gloss": "받는 것"}]),
        "L6": _level("여섯", passed=False),  # 검사 실패 단계는 빠짐
    })
    teen = make_user(db_session, birth_date=date(2010, 1, 1))  # 2026-10-04 기준 16세 → L5
    res = api.get(f"/api/policies/{p.id}/easy-text", params={"user_id": str(teen.id)})
    body = res.json()
    assert res.status_code == 200, res.text
    assert body["recommended_level"] == "L5" and body["level"] == "L5"
    assert body["core_text"] == "다섯 핵심" and body["more_text"] == "자세히"
    assert body["terms"] == [{"term": "수급", "gloss": "받는 것"}]
    assert [lv["level"] for lv in body["levels"]] == ["L2", "L5"]

    # user 없음 → 기본 L6, L6 없으니 가장 가까운 L5
    assert api.get(f"/api/policies/{p.id}/easy-text").json()["level"] == "L5"
    # 직접 고른 단계 우선
    body = api.get(f"/api/policies/{p.id}/easy-text", params={"level": "L2"}).json()
    assert body["level"] == "L2" and body["more_text"] is None


def test_easy_text_errors(api, db_session, easy_dir):
    p = make_policy(db_session, policy_key="T901")
    res = api.get(f"/api/policies/{p.id}/easy-text")
    assert res.status_code == 404 and res.json()["error"]["code"] == "EASY_TEXT_NOT_FOUND"
    res = api.get(f"/api/policies/{p.id}/easy-text", params={"level": "L9"})
    assert res.status_code == 422
    res = api.get("/api/policies/999999/easy-text")
    assert res.json()["error"]["code"] == "POLICY_NOT_FOUND"


def _spec(form_id, key, checked=False):
    return {
        "form_id": form_id, "policy_key": key, "title": "자립수당 신청서", "source": "x.pdf", "checked": checked,
        "notes": ["주소지 행정복지센터에 제출"],
        "fields": [
            {"id": "name", "label": "성명", "value_source": "profile", "profile_key": "name",
             "required": True, "guide": "이름", "example": "홍길동"},
            {"id": "rrn", "label": "주민등록번호", "input_type": "id_number", "value_source": "handwrite",
             "guide": "직접", "example": "000000-0000000", "sensitive": True},
        ],
    }


def test_form_guide_prefill_and_checked_first(api, db_session, forms_dir):
    p = make_policy(db_session, policy_key="T902")
    (forms_dir / "a.json").write_text(json.dumps(_spec("T902-a", "T902")), "utf-8")
    (forms_dir / "b.json").write_text(json.dumps(_spec("T902-b", "T902", checked=True)), "utf-8")
    (forms_dir / "c.json").write_text(json.dumps(_spec("X-c", "OTHER")), "utf-8")
    u = make_user(db_session, name="김민지")

    body = api.get(f"/api/policies/{p.id}/form-guide", params={"user_id": str(u.id)}).json()
    assert body["form_id"] == "T902-b" and body["checked"] is True and body["policy_id"] == p.id
    name, rrn = body["items"]
    assert name["prefill"] == "김민지" and name["value_source"] == "profile"
    assert rrn["example"] is None and rrn["prefill"] is None

    body = api.get(f"/api/policies/{p.id}/form-guide").json()
    assert body["items"][0]["prefill"] is None


def test_form_guide_not_found(api, db_session, forms_dir):
    p = make_policy(db_session, policy_key="T903")
    res = api.get(f"/api/policies/{p.id}/form-guide")
    assert res.status_code == 404 and res.json()["error"]["code"] == "FORM_GUIDE_NOT_FOUND"
