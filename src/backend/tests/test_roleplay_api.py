"""AI 롤플레잉 (api.md 3.16~3.19, 3.13 / db-schema.md 3.6)."""

import json
import logging
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import text

from app import llm_mock
from app.crisis import CRISIS_MESSAGE
from app.llm_client import Llm, get_llm
from app.main import app
from app.roleplay import FINISHED_GOOD, SCENARIOS_PATH, get_scenarios, level_for, lines_for
from tests.conftest import TABLES
from tests.factories import make_user

KST = timezone(timedelta(hours=9))
APP_ORDER = ["store", "bank", "community-center", "landlord", "parttime", "school", "job"]
OPENING = {"role": "assistant", "content": "다음 분 오세요. 무슨 일로 오셨어요?"}
RAW = {s["scenario_id"]: s for s in json.loads(SCENARIOS_PATH.read_text(encoding="utf-8"))}


class FakeRoleplayLlm:
    def __init__(self):
        self.output: object = {"reply": "상대역 대사", "is_finished": False}
        self.error: Exception | None = None
        self.calls: list[tuple] = []

    def roleplay_reply(self, scenario, history, message):
        self.calls.append((scenario, history, message))
        if self.error is not None:
            raise self.error
        return self.output

    def as_llm(self) -> Llm:
        return Llm(lambda *a: "counsel", lambda *a: "x", self.roleplay_reply, lambda *a: [])


@pytest.fixture
def llm(api):
    fake = FakeRoleplayLlm()
    app.dependency_overrides[get_llm] = fake.as_llm
    yield fake
    app.dependency_overrides.pop(get_llm, None)


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


def _user_turns(*texts):
    turns = [OPENING]
    for t in texts:
        turns += [{"role": "user", "content": t}, {"role": "assistant", "content": "대사"}]
    return turns


def _reply(api, user, scenario_id="community-center", message="네 이사 왔어요", history=None, **extra):
    body = {"user_id": str(user.id), "message": message, "history": history or [OPENING], **extra}
    return api.post(f"/api/coach/roleplay/{scenario_id}/reply", json=body)


def _feedback(api, user, scenario_id="community-center", history=None, **extra):
    body = {"user_id": str(user.id), "history": history or _user_turns("이사 왔어요"), **extra}
    return api.post(f"/api/coach/roleplay/{scenario_id}/feedback", json=body)


# ------------------------------------------------------------------ 상황 데이터


def test_scenarios_file_matches_app_dummy():
    assert list(get_scenarios()) == APP_ORDER
    s = get_scenarios()["community-center"]
    assert (s.category, s.level, s.stages) == ("생활", "NORMAL", ["youth"])
    assert [c.label for c in s.checkpoints] == ["신분증 준비", "확정일자 함께 받기", "임대차 계약서 챙기기"]


@pytest.mark.parametrize(
    ("scenario_id", "stage", "level"),
    [
        ("parttime", "youth", "HARD"),
        ("parttime", "teen", "NORMAL"),
        ("parttime", None, "NORMAL"),
        ("job", "youth", "HARD"),
        ("bank", "teen", "EASY"),
        ("store", "child", "EASY"),
        ("job", "child", "EASY"),  # 아동은 모두 쉬움
        ("community-center", "teen", "NORMAL"),
    ],
)
def test_level_for(scenario_id, stage, level):
    assert level_for(get_scenarios()[scenario_id], stage) == level


def test_lines_for_mixes_hard_lines():
    s = get_scenarios()["parttime"]
    assert lines_for(s, "NORMAL") == s.lines
    assert lines_for(s, "HARD") == [
        s.lines[0], s.hard_lines[0], s.lines[1], s.hard_lines[1], s.lines[2],
    ]
    job = get_scenarios()["job"]
    assert len(lines_for(job, "HARD")) == 6


# ------------------------------------------------------------------ 3.16 목록


def test_list_all(client):
    res = client.get("/api/coach/roleplay/scenarios")
    assert res.status_code == 200
    scenarios = res.json()["scenarios"]
    assert [s["scenario_id"] for s in scenarios] == APP_ORDER
    first = scenarios[2]
    assert first == {
        "scenario_id": "community-center", "title": "주민센터 전입신고", "category": "생활",
        "partner": "주민센터 직원", "setting": "새 집으로 이사하고 전입신고를 하러 왔어요.",
        "goal": "전입신고와 확정일자까지 챙기기", "level": "NORMAL", "stages": ["youth"],
    }
    for s in scenarios:  # 체크 항목·준비 서류·대사는 응답에 없음
        assert set(s) == {"scenario_id", "title", "category", "partner", "setting", "goal", "level", "stages"}
        raw = RAW[s["scenario_id"]]
        assert {k: raw[k] for k in s if k != "level"} == {k: v for k, v in s.items() if k != "level"}


@pytest.mark.parametrize(
    ("stage", "ids"),
    [
        ("child", ["store"]),
        ("teen", ["bank", "parttime", "school"]),
        ("youth", ["bank", "community-center", "landlord", "parttime", "job"]),
    ],
)
def test_list_by_stage(client, stage, ids):
    res = client.get("/api/coach/roleplay/scenarios", params={"stage": stage})
    assert [s["scenario_id"] for s in res.json()["scenarios"]] == ids


def test_list_level_by_stage(client):
    def level(stage):
        res = client.get("/api/coach/roleplay/scenarios", params={"stage": stage}).json()
        return {s["scenario_id"]: s["level"] for s in res["scenarios"]}["parttime"]

    assert (level("youth"), level("teen")) == ("HARD", "NORMAL")


@pytest.mark.parametrize("stage", ["adult", "", "YOUTH"])
def test_list_invalid_stage_is_422(client, stage):
    _assert_error(client.get("/api/coach/roleplay/scenarios", params={"stage": stage}), 422, "VALIDATION_ERROR")


# ------------------------------------------------------------------ 3.17 첫 대사


def test_opening(client):
    res = client.get("/api/coach/roleplay/community-center/opening", params={"variant_id": "basic"})
    assert res.status_code == 200
    data = res.json()
    assert {k: data[k] for k in ("scenario_id", "level", "message", "variant_id", "situation")} == {
        "scenario_id": "community-center", "level": "NORMAL", "message": "다음 분 오세요. 무슨 일로 오셨어요?",
        "variant_id": "basic", "situation": None,
    }
    assert data["surprise_count"] == 1 and len(data["surprise_ids"]) == 1  # 보통: 돌발 1개


@pytest.mark.parametrize(
    ("scenario_id", "stage", "level"),
    [("parttime", "youth", "HARD"), ("parttime", "teen", "NORMAL"), ("store", "youth", "EASY")],  # 대상 아니어도 200
)
def test_opening_level_by_stage(client, scenario_id, stage, level):
    res = client.get(f"/api/coach/roleplay/{scenario_id}/opening", params={"stage": stage})
    assert res.status_code == 200
    assert res.json()["level"] == level
    assert res.json()["message"] == RAW[scenario_id]["opening"]


@pytest.mark.parametrize("scenario_id", ["community-center", "job"])
def test_opening_child_not_target(client, scenario_id):
    # 아동은 대상 단계가 아닌 상황도 EASY (3.16 아동 규칙)
    res = client.get(f"/api/coach/roleplay/{scenario_id}/opening", params={"stage": "child"})
    assert res.status_code == 200
    data = res.json()
    assert (data["level"], data["message"], data["surprise_ids"]) == ("EASY", RAW[scenario_id]["opening"], [])


def test_opening_unknown_scenario(client):
    _assert_error(client.get("/api/coach/roleplay/nope/opening"), 404, "SCENARIO_NOT_FOUND")


def test_opening_invalid_stage(client):
    res = client.get("/api/coach/roleplay/store/opening", params={"stage": "adult"})
    _assert_error(res, 422, "VALIDATION_ERROR")


def test_opening_unknown_scenario_before_invalid_stage(client):
    res = client.get("/api/coach/roleplay/nope/opening", params={"stage": "adult"})
    _assert_error(res, 404, "SCENARIO_NOT_FOUND")


# ------------------------------------------------------------------ 3.18 대화 한 턴


def test_mock_reply_in_order_then_finish(api, db_session):
    user = make_user(db_session)
    lines = RAW["community-center"]["lines"]
    history = [OPENING]
    replies = []
    for _ in range(len(lines) + 1):
        res = _reply(api, user, history=history)
        assert res.status_code == 200
        replies.append(res.json())
        history = history + [{"role": "user", "content": "네"}, {"role": "assistant", "content": res.json()["reply"]}]

    assert replies == [{"reply": line, "is_finished": False} for line in lines] + [
        {"reply": "네, 오늘은 여기까지 할게요. 수고했어요.", "is_finished": True}
    ]


@pytest.mark.parametrize(("stage", "count"), [("youth", 5), ("teen", 3), (None, 3)])
def test_mock_reply_hard_lines(api, db_session, stage, count):
    user = make_user(db_session)
    raw = RAW["parttime"]
    history = [{"role": "assistant", "content": raw["opening"]}]
    replies = []
    while True:
        extra = {"stage": stage} if stage else {}
        data = _reply(api, user, scenario_id="parttime", history=history, **extra).json()
        if data["is_finished"]:
            break
        replies.append(data["reply"])
        history = history + [{"role": "user", "content": "네"}, {"role": "assistant", "content": data["reply"]}]
    assert len(replies) == count
    if stage == "youth":
        assert replies[1] == raw["hard_lines"][0]


def test_llm_receives_scenario_without_answers(api, db_session, llm):
    user = make_user(db_session)
    history = [{"role": "assistant", "content": " 반가워요 "}]

    res = _reply(api, user, scenario_id="parttime", message="  안녕하세요  ", history=history, stage="youth")

    assert res.json() == {"reply": "상대역 대사", "is_finished": False}
    scenario, sent_history, message = llm.calls[0]
    assert scenario == {
        "scenario_id": "parttime", "title": "아르바이트 면접", "partner": "카페 점장",
        "setting": "동네 카페 아르바이트 면접을 봐요.", "goal": "나를 소개하고 근무 조건 확인하기",
        "level": "HARD", "situation": "", "surprises": [], "lines": lines_for(get_scenarios()["parttime"], "HARD"),
    }
    assert sent_history == [{"role": "assistant", "content": "반가워요"}]
    assert message == "안녕하세요"
    assert all(v not in str(llm.calls) for v in ("테스트", str(user.id)))


@pytest.mark.parametrize("message", ["죽고 싶어요", "자 해 했어요"])
def test_reply_crisis_skips_llm(api, db_session, llm, message):
    user = make_user(db_session)
    llm.error = RuntimeError("불리면 안 됨")
    res = _reply(api, user, message=message)
    assert res.status_code == 200
    assert res.json() == {"reply": CRISIS_MESSAGE, "is_finished": True}
    assert llm.calls == []


def test_reply_crisis_only_current_message(api, db_session, llm):
    user = make_user(db_session)
    history = [OPENING, {"role": "user", "content": "죽고 싶어"}, {"role": "assistant", "content": "x"}]
    assert _reply(api, user, history=history).json()["reply"] == "상대역 대사"


def test_reply_llm_exception_is_503(api, db_session, llm):
    user = make_user(db_session)
    llm.error = RuntimeError("down")
    _assert_error(_reply(api, user), 503, "AI_UNAVAILABLE")


@pytest.mark.parametrize(
    "output",
    [
        None, "대사", {"reply": "대사"}, {"is_finished": False}, {"reply": "", "is_finished": False},
        {"reply": "  ", "is_finished": True}, {"reply": 1, "is_finished": False},
        {"reply": "대사", "is_finished": "false"}, {"reply": "대사", "is_finished": 0},
    ],
)
def test_reply_invalid_llm_output_is_503(api, db_session, llm, output):
    user = make_user(db_session)
    llm.output = output
    _assert_error(_reply(api, user), 503, "AI_UNAVAILABLE")


def test_reply_unknown_scenario_before_user_and_body(api, db_session, llm):
    body = {"user_id": str(uuid.uuid4()), "message": ""}
    res = api.post("/api/coach/roleplay/nope/reply", json=body)
    _assert_error(res, 404, "SCENARIO_NOT_FOUND")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
def test_reply_unknown_user_before_body(api, db_session, llm, user_id):
    body = {"user_id": user_id, "message": "", "history": [], "stage": "adult"}
    _assert_error(api.post("/api/coach/roleplay/store/reply", json=body), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize(
    "extra",
    [
        {"history": []},
        {"history": None},
        {"history": "x"},
        {"history": [OPENING] * 21},
        {"history": [{"role": "system", "content": "x"}]},
        {"history": [{"role": "user", "content": " "}]},
        {"history": [{"role": "user", "content": "가" * 1001}]},
        {"message": ""},
        {"message": "   "},
        {"message": "가" * 501},
        {"message": None},
        {"stage": "adult"},
        {"stage": 1},
        {"unknown": 1},
    ],
)
def test_reply_invalid_body_is_422(api, db_session, llm, extra):
    user = make_user(db_session)
    body = {"user_id": str(user.id), "message": "네", "history": [OPENING], **extra}
    _assert_error(api.post("/api/coach/roleplay/store/reply", json=body), 422, "VALIDATION_ERROR")
    assert llm.calls == []


def test_reply_history_missing_is_422(api, db_session, llm):
    user = make_user(db_session)
    res = api.post("/api/coach/roleplay/store/reply", json={"user_id": str(user.id), "message": "네"})
    _assert_error(res, 422, "VALIDATION_ERROR")


def test_reply_limits_allowed(api, db_session, llm):
    user = make_user(db_session)
    history = [{"role": "user", "content": "가" * 1000}] * 20
    assert _reply(api, user, message="나" * 500, history=history).status_code == 200


def test_reply_stage_null_uses_base_level(api, db_session, llm):
    user = make_user(db_session)
    assert _reply(api, user, scenario_id="parttime", stage=None).status_code == 200
    assert llm.calls[0][0]["level"] == "NORMAL"


def test_reply_malformed_json(api, llm):
    res = api.post("/api/coach/roleplay/store/reply", content="{bad", headers={"content-type": "application/json"})
    _assert_error(res, 422, "VALIDATION_ERROR")


# ------------------------------------------------------------------ 3.19 피드백


def test_feedback_good_and_missed(api, db_session):
    user = make_user(db_session)
    res = _feedback(api, user, history=_user_turns("신분증 여기요", "확정 일자도요"))  # 띄어쓰기 다르면 못 찾음

    assert res.status_code == 200
    data = res.json()
    assert {k: data[k] for k in ("roleplay_id", "good", "missed", "documents", "better")} == {
        "roleplay_id": None,
        "good": ["신분증 준비"],
        "missed": ["확정일자 함께 받기", "임대차 계약서 챙기기"],
        "documents": ["신분증", "임대차 계약서 (확정일자용)"],
        "better": [{"before": "이사 왔어요", "after": "이사해서 전입신고하고, 확정일자도 같이 받고 싶어요"}],
    }
    assert data["score"] == 30 and data["grade"] == "한 번 더 해 봐요"
    assert data["retry_lines"] == ["확정일자도 같이 받고 싶어요.", "임대차 계약서 원본 가져왔어요."]


def test_feedback_keywords_across_turns(api, db_session):
    user = make_user(db_session)
    # 이어 붙인 문장에서 찾음: "이번" + " " + "주" → "이번 주"
    data = _feedback(api, user, scenario_id="landlord", history=_user_turns("어제부터 고장이에요 이번", "주 안에요")).json()
    assert data["good"] == ["고장 난 시점 설명", "수리가 급한 이유"]
    assert data["missed"] == ["수리비 부담 확인"]


@pytest.mark.parametrize(("turns", "finished"), [(2, False), (3, True), (4, True)])
def test_feedback_finished_good(api, db_session, turns, finished):
    user = make_user(db_session)
    data = _feedback(api, user, history=_user_turns(*["네"] * turns)).json()
    assert (FINISHED_GOOD in data["good"]) is finished
    if finished:
        assert data["good"][-1] == FINISHED_GOOD


def test_feedback_only_user_turns_count(api, db_session):
    user = make_user(db_session)
    history = [OPENING, {"role": "assistant", "content": "신분증 주세요"}, {"role": "user", "content": "네"}]
    assert _feedback(api, user, history=history).json()["good"] == []


def test_feedback_empty_documents(api, db_session):
    user = make_user(db_session)
    data = _feedback(api, user, scenario_id="store", history=_user_turns("공책 주세요", "감사합니다")).json()
    assert data["documents"] == []
    assert data["good"] == ["원하는 물건 말하기", "고맙다고 인사하기"]


def test_feedback_crisis_not_handled(api, db_session):
    user = make_user(db_session)
    data = _feedback(api, user, history=_user_turns("죽고 싶어")).json()
    assert "roleplay_id" in data and "contacts" not in data


def test_feedback_not_saved_by_default(api, db_session):
    user = make_user(db_session)
    assert _feedback(api, user).json()["roleplay_id"] is None
    assert _feedback(api, user, save=False).json()["roleplay_id"] is None
    assert db_session.execute(text("SELECT count(*) FROM roleplay_results")).scalar() == 0


def test_feedback_save(api, db_session):
    user = make_user(db_session)
    data = _feedback(api, user, history=_user_turns("신분증", "네", "네"), save=True).json()

    assert isinstance(data["roleplay_id"], int)
    row = db_session.execute(
        text("SELECT user_id, scenario_id, good, missed FROM roleplay_results WHERE id = :id"),
        {"id": data["roleplay_id"]},
    ).one()
    assert row.user_id == user.id
    assert row.scenario_id == "community-center"
    assert row.good == ["신분증 준비", FINISHED_GOOD] == data["good"]
    assert row.missed == ["확정일자 함께 받기", "임대차 계약서 챙기기"] == data["missed"]


def test_feedback_save_twice_creates_two(api, db_session):
    user = make_user(db_session)
    a = _feedback(api, user, save=True).json()["roleplay_id"]
    b = _feedback(api, user, save=True).json()["roleplay_id"]
    assert b > a


@pytest.mark.parametrize("save", ["true", 1, 0, None, "false"])
def test_feedback_save_must_be_bool(api, db_session, save):
    user = make_user(db_session)
    _assert_error(_feedback(api, user, save=save), 422, "VALIDATION_ERROR")


@pytest.mark.parametrize(
    "history",
    [
        [],
        None,
        [OPENING],
        [OPENING, {"role": "assistant", "content": "x"}],
        [{"role": "user", "content": "x"}] * 21,
        [{"role": "user", "content": ""}],
    ],
)
def test_feedback_invalid_history_is_422(api, db_session, history):
    user = make_user(db_session)
    body = {"user_id": str(user.id), "history": history}
    _assert_error(api.post("/api/coach/roleplay/store/feedback", json=body), 422, "VALIDATION_ERROR")


def test_feedback_history_missing_is_422(api, db_session):
    user = make_user(db_session)
    res = api.post("/api/coach/roleplay/store/feedback", json={"user_id": str(user.id)})
    _assert_error(res, 422, "VALIDATION_ERROR")


def test_feedback_unknown_extra_field_is_422(api, db_session):
    user = make_user(db_session)
    _assert_error(_feedback(api, user, message="x"), 422, "VALIDATION_ERROR")


def test_feedback_unknown_scenario_first(api, db_session):
    body = {"user_id": str(uuid.uuid4()), "history": [], "save": "x"}
    _assert_error(api.post("/api/coach/roleplay/nope/feedback", json=body), 404, "SCENARIO_NOT_FOUND")


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid"])
def test_feedback_unknown_user_before_body(api, db_session, user_id):
    body = {"user_id": user_id, "history": [], "save": "x"}
    _assert_error(api.post("/api/coach/roleplay/store/feedback", json=body), 404, "USER_NOT_FOUND")
    assert db_session.execute(text("SELECT count(*) FROM roleplay_results")).scalar() == 0


def test_feedback_user_delete_cascades(api, db_session):
    user = make_user(db_session)
    _feedback(api, user, save=True)
    db_session.execute(text("DELETE FROM users WHERE id = :id"), {"id": user.id})
    db_session.commit()
    assert db_session.execute(text("SELECT count(*) FROM roleplay_results")).scalar() == 0


# ------------------------------------------------------------------ 저장·로그 안 함


def test_conversation_not_stored_or_logged(api, db_session, llm, caplog):
    secret = "비밀대화-신분증-12345"
    user = make_user(db_session)
    caplog.set_level(logging.DEBUG)
    history = [OPENING, {"role": "user", "content": secret}, {"role": "assistant", "content": f"{secret}-대사"}]

    _reply(api, user, message=secret, history=history)
    llm.error = RuntimeError(f"failed for {secret}")
    _reply(api, user, message=secret, history=history)
    _reply(api, user, message=f"{secret} 죽고 싶어", history=history)
    _feedback(api, user, history=history, save=True)

    assert secret not in caplog.text
    for table in TABLES:
        rows = db_session.execute(text(f"SELECT * FROM {table}")).all()
        assert all(secret not in str(row) for row in rows)


def test_never_says_apply_possible():
    assert "신청 가능" not in SCENARIOS_PATH.read_text(encoding="utf-8")
    assert "신청 가능" not in llm_mock.ROLEPLAY_END


# ------------------------------------------------------------------ 3.13 성장 기록


def _set_created(db_session, roleplay_id, ts):
    db_session.execute(text("UPDATE roleplay_results SET created_at = :ts WHERE id = :id"),
                       {"ts": ts, "id": roleplay_id})
    db_session.commit()


def test_growth_roleplays(api, db_session):
    user = make_user(db_session)
    a = _feedback(api, user, history=_user_turns("신분증", "확정일자", "네"), save=True).json()["roleplay_id"]
    b = _feedback(api, user, scenario_id="bank", save=True).json()["roleplay_id"]
    _feedback(api, user, save=False)
    _set_created(db_session, a, datetime(2026, 10, 2, 20, 0, tzinfo=KST))
    _set_created(db_session, b, datetime(2026, 10, 1, 9, 0, tzinfo=KST))

    data = api.get(f"/api/users/{user.id}/growth").json()

    assert data["summary"]["roleplay_count"] == 2
    assert data["roleplays"] == [
        {"roleplay_id": a, "scenario_id": "community-center", "title": "주민센터 전입신고",
         "good_count": 3, "missed_count": 1, "created_at": "2026-10-02T20:00:00+09:00"},
        {"roleplay_id": b, "scenario_id": "bank", "title": "은행 계좌 개설",
         "good_count": 0, "missed_count": 3, "created_at": "2026-10-01T09:00:00+09:00"},
    ]


def test_growth_roleplays_tie_uses_larger_id(api, db_session):
    user = make_user(db_session)
    ids = [_feedback(api, user, save=True).json()["roleplay_id"] for _ in range(3)]
    for rid in ids:
        _set_created(db_session, rid, datetime(2026, 10, 1, tzinfo=KST))
    roleplays = api.get(f"/api/users/{user.id}/growth").json()["roleplays"]
    assert [r["roleplay_id"] for r in roleplays] == sorted(ids, reverse=True)


def test_growth_removed_scenario_uses_id_as_title(api, db_session):
    user = make_user(db_session)
    db_session.execute(
        text("INSERT INTO roleplay_results (user_id, scenario_id, good, missed) "
             "VALUES (:u, 'removed-scenario', '[\"a\"]', '[]')"),
        {"u": user.id},
    )
    db_session.commit()
    item = api.get(f"/api/users/{user.id}/growth").json()["roleplays"][0]
    assert (item["scenario_id"], item["title"], item["good_count"], item["missed_count"]) == (
        "removed-scenario", "removed-scenario", 1, 0,
    )


def test_growth_excludes_other_users_roleplays(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    _feedback(api, other, save=True)
    data = api.get(f"/api/users/{user.id}/growth").json()
    assert (data["summary"]["roleplay_count"], data["roleplays"]) == (0, [])


def test_growth_roleplays_without_limit(api, db_session):
    user = make_user(db_session)
    for _ in range(12):
        _feedback(api, user, save=True)
    assert len(api.get(f"/api/users/{user.id}/growth").json()["roleplays"]) == 12


def test_growth_roleplay_fields_not_null(api, db_session):
    user = make_user(db_session)
    _feedback(api, user, save=True)
    item = api.get(f"/api/users/{user.id}/growth").json()["roleplays"][0]
    assert set(item) == {"roleplay_id", "scenario_id", "title", "good_count", "missed_count", "created_at"}
    assert all(v is not None for v in item.values())


# ------------------------------------------------------------------ 같은 상황 다른 전개 · 돌발 상황 · 피드백


def test_opening_picks_variant_and_surprises_by_level(client):
    seen = {client.get("/api/coach/roleplay/landlord/opening", params={"stage": "youth"}).json()["variant_id"]
            for _ in range(30)}
    assert seen <= {"basic", "refuse", "text-record"} and len(seen) >= 2  # 할 때마다 다른 전개
    hard = client.get("/api/coach/roleplay/job/opening", params={"stage": "youth", "variant_id": "gap"}).json()
    assert hard["level"] == "HARD" and hard["situation"] == "공백기를 집요하게 묻는 면접관"
    assert hard["surprise_count"] == 2 and hard["surprise_ids"][0] == "job-family"  # 어려움: HARD 돌발 먼저


def test_reply_sends_variant_and_surprise_to_llm(api, db_session, llm):
    user = make_user(db_session)
    history = [{"role": "assistant", "content": "여보세요?"}]
    res = _reply(api, user, scenario_id="landlord", history=history, stage="youth",
                 variant_id="refuse", surprise_ids=["ll-cost-split", "nope"])
    assert res.status_code == 200
    scenario = llm.calls[0][0]
    assert "세입자가 고쳐 써야" in scenario["situation"]
    assert scenario["surprises"] == ["수리비는 반반 내요. 세입자도 쓰는 거니까."]  # 모르는 id 는 무시
    assert scenario["lines"][1] == "수리비는 반반 내요. 세입자도 쓰는 거니까."  # 두 번째 대사 자리에 끼워 넣음


def test_feedback_with_variant_and_surprise(api, db_session):
    user = make_user(db_session)
    turns = _user_turns("어제부터 안 돼요", "보일러는 집주인이 책임지는 걸로 알아요", "금요일까지 날짜 정해 주세요")
    data = _feedback(api, user, scenario_id="landlord", history=turns,
                     variant_id="refuse", surprise_ids=["ll-cost-split"]).json()
    kinds = {i["label"]: (i["ok"], i["kind"]) for i in data["items"]}
    assert kinds["집주인 수리 책임 근거 말하기"] == (True, "situation")
    assert kinds["수리비 반반 요구에 대응하기"] == (True, "surprise")  # "집주인" 키워드
    assert kinds["수리가 급한 이유"] == (False, "basic")
    assert data["situation"] == "집주인이 수리를 미룸"
    assert 0 < data["score"] <= 100 and data["grade"]
    assert "날이 추워서 이번 주 안에는 꼭 고쳐야 해요." in data["retry_lines"]
    assert any(b["before"] == "아 네… 그럼 다음 달에요" for b in data["better"])  # 전개별 고친 말도
