"""활동 기록 · 자립 준비도 · 롤플레잉 힌트"""

from tests.factories import make_user


def _readiness(api, user):
    return api.get(f"/api/users/{user.id}/readiness").json()


def _practice(api, user, sid, missed=0):
    return api.post(f"/api/users/{user.id}/activities", json={"kind": "practice", "ref": sid, "data": {"good": 2, "missed": missed}})


MONTH = {"income": 120, "left": 20, "shortage": 0, "used_emergency": 0, "home": "STUDIO", "good": 3, "bad": 1, "bad_events": ["sale"]}


def test_empty_user_starts_at_zero(api, db_session):
    user = make_user(db_session)
    r = _readiness(api, user)
    assert (r["score"], r["level"]) == (0, "첫걸음")
    assert [a["key"] for a in r["areas"]] == ["housing", "money", "job", "documents", "safety", "life"]
    assert all(a["score"] == 0 and a["next_step"] for a in r["areas"])
    assert len(r["next_steps"]) == 3 and r["weakest"] == "housing"  # 같은 점수면 앞 영역부터


def test_practice_and_month_raise_score(api, db_session):
    user = make_user(db_session)
    assert _practice(api, user, "scam-call").status_code == 201
    assert _practice(api, user, "phone-store", missed=2).status_code == 201
    assert api.post(f"/api/users/{user.id}/activities", json={"kind": "month_life", "data": MONTH}).status_code == 201
    areas = {a["key"]: a for a in _readiness(api, user)["areas"]}
    assert areas["safety"]["done_count"] == 4  # 연습 · 완벽 대처 · 휴대폰 · 사기 안 당함
    assert areas["safety"]["next_step"] is None and areas["safety"]["score"] == 100
    assert areas["money"]["done_count"] == 1  # 모자람 없는 한 달
    assert areas["life"]["done_count"] == 1
    r = _readiness(api, user)
    assert r["weakest"] != "safety" and r["score"] > 0


def test_missed_practice_is_not_clean(api, db_session):
    user = make_user(db_session)
    _practice(api, user, "scam-call", missed=1)
    safety = next(a for a in _readiness(api, user)["areas"] if a["key"] == "safety")
    assert [i["done"] for i in safety["items"]][:2] == [True, False]
    assert safety["next_step"]["scenario_id"] == "scam-call"


def test_month_with_scam_mistake(api, db_session):
    user = make_user(db_session)
    api.post(f"/api/users/{user.id}/activities", json={"kind": "month_life", "data": {**MONTH, "shortage": 5, "bad_events": ["smishing"]}})
    areas = {a["key"]: a for a in _readiness(api, user)["areas"]}
    assert not next(i for i in areas["safety"]["items"] if i["key"] == "month-no-scam")["done"]
    assert not next(i for i in areas["money"]["items"] if i["key"] == "month-no-shortage")["done"]


def test_activity_validation(api, db_session):
    user = make_user(db_session)
    url = f"/api/users/{user.id}/activities"
    assert api.post(url, json={"kind": "practice", "data": {"good": 1, "missed": 0}}).status_code == 422  # ref 없음
    assert api.post(url, json={"kind": "practice", "ref": "bank", "data": {"good": "1", "missed": 0}}).status_code == 422
    assert api.post(url, json={"kind": "month_life", "ref": "x", "data": MONTH}).status_code == 422
    assert api.post(url, json={"kind": "month_life", "data": {**MONTH, "home": "CASTLE"}}).status_code == 422
    assert api.post(url, json={"kind": "chat", "data": {}}).status_code == 422
    assert api.post("/api/users/00000000-0000-0000-0000-000000000000/activities",
                    json={"kind": "practice", "ref": "bank", "data": {"good": 1, "missed": 0}}).status_code == 404


def test_readiness_unknown_user(api):
    assert api.get("/api/users/nope/readiness").status_code == 404


# ------------------------------------------------------------------ 힌트


def _hint(client, sid, history, **extra):
    return client.post(f"/api/coach/roleplay/{sid}/hint", json={"history": history, **extra})


def test_hint_first_missing_basic(client):
    r = _hint(client, "scam-call", [{"role": "assistant", "content": "여보세요, 수사관입니다."}]).json()
    assert r["kind"] == "basic" and r["label"] == "개인정보 알려 주지 않기" and r["example"] and r["remaining"] == 3


def test_hint_prefers_surprise_just_heard(client):
    history = [
        {"role": "assistant", "content": "여보세요"},
        {"role": "user", "content": "네"},
        {"role": "assistant", "content": "지금 전화 끊으시면 바로 체포영장이 나갑니다."},
    ]
    r = _hint(client, "scam-call", history, surprise_ids=["sc-arrest"]).json()
    assert (r["kind"], r["label"]) == ("surprise", "협박에 흔들리지 않고 끊기")


def test_hint_closing_when_all_done(client):
    said = "전화로는 개인정보 못 알려 드려요. 끊고 직접 확인할게요. 112에 신고할게요."
    history = [{"role": "assistant", "content": "여보세요"}, {"role": "user", "content": said}]
    r = _hint(client, "scam-call", history).json()
    assert r == {"done": True, "label": "마무리 인사하기", "example": r["example"], "kind": "closing", "remaining": 0}


def test_hint_unknown_scenario(client):
    assert _hint(client, "nope", [{"role": "assistant", "content": "x"}]).status_code == 404
