"""서류 양식·초안 (api.md 3.20~3.22, 부록)."""

import json
import logging
import re
import uuid
from datetime import date, datetime, timedelta, timezone

import pytest

from app.forms import FORMS_PATH
from app.services.documents import NOTICE_EMPTY, NOTICE_RECORDS
from tests.factories import make_policy, make_user
from tests.test_dashboard_api import ALL_STEPS, _checklist, _set_time, _simulate

KST = timezone(timedelta(hours=9))
APP_ORDER = ["scholarship", "program", "allowance", "move-in", "resume-form"]
RAW = {t["template_id"]: t for t in json.loads(FORMS_PATH.read_text(encoding="utf-8"))}
FIELD_KEYS = {"field_id", "label", "easy_label", "guide", "caution", "example", "multiline", "essay", "outline"}


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def _doc(api, user_id, type_="resume", **extra):
    return api.post(f"/api/users/{user_id}/documents", json={"type": type_, **extra})


def _texts(data):
    return {s["title"]: [(x["text"], x["source"]) for x in s["sentences"]] for s in data["sections"]}


# ------------------------------------------------------------------ 3.20 목록


def test_list_all(client):
    res = client.get("/api/forms/templates")
    assert res.status_code == 200
    templates = res.json()["templates"]
    assert [t["template_id"] for t in templates] == APP_ORDER
    assert templates[0] == {
        "template_id": "scholarship", "name": "장학금 신청서",
        "description": "학교·재단 장학금 신청에 자주 쓰는 양식", "stages": ["teen", "youth"],
    }
    assert all(set(t) == {"template_id", "name", "description", "stages"} for t in templates)


@pytest.mark.parametrize(
    ("stage", "ids"),
    [
        ("child", []),
        ("teen", ["scholarship", "program"]),
        ("youth", ["scholarship", "allowance", "move-in", "resume-form"]),
    ],
)
def test_list_by_stage(client, stage, ids):
    res = client.get("/api/forms/templates", params={"stage": stage})
    assert [t["template_id"] for t in res.json()["templates"]] == ids


@pytest.mark.parametrize("stage", ["adult", "", "YOUTH"])
def test_list_invalid_stage(client, stage):
    _assert_error(client.get("/api/forms/templates", params={"stage": stage}), 422, "VALIDATION_ERROR")


# ------------------------------------------------------------------ 3.21 항목


@pytest.mark.parametrize("template_id", APP_ORDER)
def test_detail_matches_file(client, template_id):
    res = client.get(f"/api/forms/templates/{template_id}")
    assert res.status_code == 200
    assert res.json() == RAW[template_id]
    assert all(set(f) == FIELD_KEYS for f in res.json()["fields"])


def test_detail_reason_field(client):
    fields = {f["field_id"]: f for f in client.get("/api/forms/templates/scholarship").json()["fields"]}
    assert fields["reason"] == {
        "field_id": "reason", "label": "신청 사유", "easy_label": "장학금이 필요한 이유",
        "guide": "지금 상황과 장학금을 어디에 쓸지 솔직하게 써요.",
        "caution": "과장하지 말고 사실만 써요. 확인 서류를 요구할 수 있어요.",
        "example": None, "multiline": True, "essay": True,
        "outline": ["지금 나의 상황", "장학금이 필요한 이유", "받게 되면 어디에 쓸지", "앞으로의 계획"],
    }
    assert fields["birth"]["example"] == "2007.03.15"


@pytest.mark.parametrize(
    ("template_id", "field_ids"),
    [
        ("scholarship", ["name", "birth", "phone", "address", "school", "reason", "plan"]),
        ("program", ["name", "birth", "phone", "guardian", "motive"]),
        ("allowance", ["name", "birth", "phone", "address", "end-date", "account"]),
        ("move-in", ["name", "before", "after", "head"]),
        ("resume-form", ["name", "birth", "phone", "address", "edu", "career", "license", "intro"]),
    ],
)
def test_detail_field_order(client, template_id, field_ids):
    fields = client.get(f"/api/forms/templates/{template_id}").json()["fields"]
    assert [f["field_id"] for f in fields] == field_ids


def test_outline_has_no_numbering(client):
    for t in APP_ORDER:
        for f in client.get(f"/api/forms/templates/{t}").json()["fields"]:
            if f["outline"] is not None:
                assert f["essay"] is True
                assert all(re.match(r"^\d+\) ", line) is None for line in f["outline"])


def test_detail_unknown(client):
    _assert_error(client.get("/api/forms/templates/nope"), 404, "FORM_NOT_FOUND")


# ------------------------------------------------------------------ 3.22 초안: 기록 없음


def test_resume_without_records(api, db_session):
    user = make_user(db_session)
    res = _doc(api, user.id)

    assert res.status_code == 200
    assert res.json() == {
        "type": "resume",
        "sections": [
            {"title": "기본 정보", "sentences": [{"text": "이름, 연락처, 이메일을 적어 주세요.", "source": None}]},
            {"title": "학력", "sentences": [{"text": "최종 학교부터 적어 주세요.", "source": None}]},
            {"title": "경험·활동", "sentences": [{"text": "아르바이트, 교육, 봉사 경험을 적어 주세요.", "source": None}]},
            {"title": "자격증", "sentences": [{"text": "가진 자격증을 적어 주세요.", "source": None}]},
        ],
        "notice": NOTICE_EMPTY,
    }


def test_activity_record_without_records(api, db_session):
    user = make_user(db_session)
    assert _texts(_doc(api, user.id, "activity_record").json()) == {
        "관심 분야": [("관심을 갖게 된 계기를 직접 적어 주세요.", None)],
        "활동 내용": [("참여한 활동(동아리, 봉사, 프로그램)을 적어 주세요.", None)],
        "배운 점과 계획": [("활동을 통해 배운 점과 앞으로의 계획을 적어 주세요.", None)],
    }


def test_cover_letter_without_records(api, db_session):
    user = make_user(db_session)
    data = _doc(api, user.id, "cover_letter").json()
    assert [s["title"] for s in data["sections"]] == ["나를 소개합니다", "준비해 온 과정", "앞으로의 계획"]
    assert _texts(data)["준비해 온 과정"] == [("목표를 위해 해 온 일을 적어 주세요.", None)]
    assert data["notice"] == NOTICE_EMPTY


# ------------------------------------------------------------------ 3.22 초안: 기록 있음


def test_records_order_and_sources(api, db_session):
    user = make_user(db_session)
    base = datetime(2026, 10, 1, 9, 0, tzinfo=KST)
    older = _checklist(api, user, make_policy(db_session, name="의료비 지원"), checked_steps=ALL_STEPS)
    newer = _checklist(api, user, make_policy(db_session, name="자립수당"), checked_steps=ALL_STEPS)
    _checklist(api, user, make_policy(db_session, name="진행 중"), checked_steps=("target_check",))
    _set_time(db_session, "checklists", "updated_at", older, base)
    _set_time(db_session, "checklists", "updated_at", newer, base + timedelta(hours=1))
    _simulate(api, user)
    _simulate(api, user)

    data = _doc(api, user.id, app_records={"saved_policy_count": 2}).json()

    assert _texts(data)["경험·활동"] == [
        ("자립수당 신청 절차를 스스로 확인하고 서류를 준비해 신청을 마쳤습니다.", "체크리스트 완료 · 자립수당"),
        ("의료비 지원 신청 절차를 스스로 확인하고 서류를 준비해 신청을 마쳤습니다.", "체크리스트 완료 · 의료비 지원"),
        ("생활비 계획을 2번 세워 보며 지출을 관리하는 연습을 했습니다.", "생활 시뮬레이션 2회"),
        ("자립에 필요한 제도 2가지를 찾아보고 정리했습니다.", "저장한 정책 2개"),
    ]
    assert data["notice"] == NOTICE_RECORDS


def test_same_updated_at_uses_larger_id(api, db_session):
    user = make_user(db_session)
    a = _checklist(api, user, make_policy(db_session, name="가"), checked_steps=ALL_STEPS)
    b = _checklist(api, user, make_policy(db_session, name="나"), checked_steps=ALL_STEPS)
    for cid in (a, b):
        _set_time(db_session, "checklists", "updated_at", cid, datetime(2026, 10, 1, tzinfo=KST))
    sources = [s for _, s in _texts(_doc(api, user.id).json())["경험·활동"]]
    assert sources == ["체크리스트 완료 · 나", "체크리스트 완료 · 가"]


def test_inactive_policy_checklist_included(api, db_session):
    user = make_user(db_session)
    policy = make_policy(db_session, name="종료된 정책")
    _checklist(api, user, policy, checked_steps=ALL_STEPS)
    policy.is_active = False
    db_session.commit()
    assert _texts(_doc(api, user.id).json())["경험·활동"][0][1] == "체크리스트 완료 · 종료된 정책"


@pytest.mark.parametrize(
    ("type_", "section"),
    [("resume", "경험·활동"), ("activity_record", "활동 내용"), ("cover_letter", "준비해 온 과정")],
)
def test_records_section_by_type(api, db_session, type_, section):
    user = make_user(db_session)
    _simulate(api, user)
    texts = _texts(_doc(api, user.id, type_).json())
    assert texts[section] == [("생활비 계획을 1번 세워 보며 지출을 관리하는 연습을 했습니다.", "생활 시뮬레이션 1회")]


def test_records_from_other_users_excluded(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    _checklist(api, other, make_policy(db_session), checked_steps=ALL_STEPS)
    _simulate(api, other)
    assert _doc(api, user.id).json()["notice"] == NOTICE_EMPTY


def test_saved_policy_only_counts_as_record(api, db_session):
    user = make_user(db_session)
    data = _doc(api, user.id, app_records={"saved_policy_count": 1}).json()
    assert data["notice"] == NOTICE_RECORDS
    assert _texts(data)["경험·활동"] == [("자립에 필요한 제도 1가지를 찾아보고 정리했습니다.", "저장한 정책 1개")]


# ------------------------------------------------------------------ 흥미 탐색 결과


@pytest.mark.parametrize(
    ("type_", "section", "hint"),
    [("activity_record", "관심 분야", "관심을 갖게 된 계기를 직접 적어 주세요."),
     ("cover_letter", "나를 소개합니다", "나를 한 문장으로 표현해 보세요.")],
)
def test_interest_line_first_then_blank(api, db_session, type_, section, hint):
    user = make_user(db_session)
    data = _doc(api, user.id, type_, app_records={"interest_summary": "  만들기를 좋아해요  "}).json()
    assert _texts(data)[section] == [("만들기를 좋아해요", "흥미 탐색 결과"), (hint, None)]
    assert data["notice"] == NOTICE_EMPTY  # 흥미는 기록 수에 안 셈


def test_resume_has_no_interest_line(api, db_session):
    user = make_user(db_session)
    data = _doc(api, user.id, app_records={"interest_summary": "만들기"}).json()
    assert all(s != "흥미 탐색 결과" for sec in _texts(data).values() for _, s in sec)


@pytest.mark.parametrize("interest", [None, "", "   "])
def test_empty_interest_is_none(api, db_session, interest):
    user = make_user(db_session)
    data = _doc(api, user.id, "cover_letter", app_records={"interest_summary": interest}).json()
    assert _texts(data)["나를 소개합니다"] == [("나를 한 문장으로 표현해 보세요.", None)]


def test_interest_500_allowed(api, db_session):
    user = make_user(db_session)
    assert _doc(api, user.id, "cover_letter", app_records={"interest_summary": "가" * 500}).status_code == 200


# ------------------------------------------------------------------ 단계와 type


@pytest.mark.parametrize("type_", ["activity_record", "resume", "cover_letter"])
@pytest.mark.parametrize("status", ["in_care", "left_care"])
def test_all_types_for_any_stage(api, db_session, type_, status):
    user = make_user(db_session, birth_date=date(2010, 1, 1), status=status)  # 만 16세
    assert _doc(api, user.id, type_).status_code == 200


# ------------------------------------------------------------------ 404 / 422


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
def test_unknown_user(api, db_session, user_id):
    _assert_error(_doc(api, user_id, "bad-type", unknown=1), 404, "USER_NOT_FOUND")


def test_unknown_user_before_malformed_json(api, db_session):
    res = api.post(f"/api/users/{uuid.uuid4()}/documents", content="{bad",
                   headers={"content-type": "application/json"})
    _assert_error(res, 404, "USER_NOT_FOUND")


def test_app_records_null_or_missing(api, db_session):
    user = make_user(db_session)
    assert _doc(api, user.id, app_records=None).json()["notice"] == NOTICE_EMPTY
    assert _doc(api, user.id, app_records={}).json()["notice"] == NOTICE_EMPTY
    assert _doc(api, user.id).json()["notice"] == NOTICE_EMPTY


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"type": None},
        {"type": "essay"},
        {"type": "RESUME"},
        {"type": "resume", "unknown": 1},
        {"type": "resume", "app_records": "x"},
        {"type": "resume", "app_records": {"unknown": 1}},
        {"type": "resume", "app_records": {"saved_policy_count": -1}},
        {"type": "resume", "app_records": {"saved_policy_count": 2**31}},
        {"type": "resume", "app_records": {"saved_policy_count": "2"}},
        {"type": "resume", "app_records": {"saved_policy_count": 2.0}},
        {"type": "resume", "app_records": {"saved_policy_count": True}},
        {"type": "resume", "app_records": {"saved_policy_count": None}},
        {"type": "resume", "app_records": {"interest_summary": 1}},
        {"type": "resume", "app_records": {"interest_summary": "가" * 501}},
    ],
)
def test_invalid_body(api, db_session, body):
    user = make_user(db_session)
    _assert_error(api.post(f"/api/users/{user.id}/documents", json=body), 422, "VALIDATION_ERROR")


def test_malformed_json(api, db_session):
    user = make_user(db_session)
    res = api.post(f"/api/users/{user.id}/documents", content="{bad", headers={"content-type": "application/json"})
    _assert_error(res, 422, "VALIDATION_ERROR")


def test_saved_policy_count_max_allowed(api, db_session):
    user = make_user(db_session)
    assert _doc(api, user.id, app_records={"saved_policy_count": 2**31 - 1}).status_code == 200


# ------------------------------------------------------------------ 저장·로그 안 함


def test_interest_not_logged(api, db_session, caplog):
    secret = "비밀흥미-12345"
    user = make_user(db_session)
    caplog.set_level(logging.DEBUG)
    _doc(api, user.id, "cover_letter", app_records={"interest_summary": secret})
    _doc(api, user.id, "cover_letter", app_records={"interest_summary": secret * 100})  # 422
    assert secret not in caplog.text


def test_never_says_apply_possible(api, db_session):
    user = make_user(db_session)
    _checklist(api, user, make_policy(db_session), checked_steps=ALL_STEPS)
    for t in ("activity_record", "resume", "cover_letter"):
        assert "신청 가능" not in _doc(api, user.id, t).text
    assert "신청 가능" not in FORMS_PATH.read_text(encoding="utf-8")
