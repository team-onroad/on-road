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
    # 수상한 전화(다 챙김) · 완벽 대처 · 사기 안 당함은 완료, 휴대폰(2개 놓침)은 해 봄(0.5)
    assert areas["safety"]["done_count"] == 3
    phone = next(i for i in areas["safety"]["items"] if i["key"] == "practice-phone-store")
    assert (phone["done"], phone["progress"]) == (False, 0.5)
    assert areas["safety"]["score"] == round(100 * 3.5 / 4)
    assert areas["safety"]["next_step"]["scenario_id"] == "phone-store"
    assert areas["money"]["done_count"] == 1  # 모자람 없는 한 달
    assert areas["life"]["done_count"] == 1
    r = _readiness(api, user)
    assert r["weakest"] != "safety" and r["score"] > 0


def test_missed_practice_is_not_clean(api, db_session):
    user = make_user(db_session)
    _practice(api, user, "scam-call", missed=1)
    safety = next(a for a in _readiness(api, user)["areas"] if a["key"] == "safety")
    # 1개 놓침: 연습 0.75 · 완벽 대처 0.75 (둘 다 아직 완료 아님)
    assert [(i["done"], i["progress"]) for i in safety["items"]][:2] == [(False, 0.75), (False, 0.75)]
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
    assert {k: r[k] for k in ("done", "label", "kind", "remaining")} == {
        "done": True, "label": "마무리 인사하기", "kind": "closing", "remaining": 0,
    }
    assert r["checks"] and all(c["ok"] for c in r["checks"])


def test_hint_unknown_scenario(client):
    assert _hint(client, "nope", [{"role": "assistant", "content": "x"}]).status_code == 404


def test_hint_checks_hide_surprise_until_heard(client):
    first = [{"role": "assistant", "content": "여보세요"}, {"role": "user", "content": "네"}]
    r = _hint(client, "scam-call", first, surprise_ids=["sc-arrest"]).json()
    assert [c["kind"] for c in r["checks"]] == ["basic"] * 3 and not any(c["ok"] for c in r["checks"])
    heard = first + [{"role": "assistant", "content": "지금 전화 끊으시면 바로 체포영장이 나갑니다."}]
    r = _hint(client, "scam-call", heard, surprise_ids=["sc-arrest"]).json()
    assert r["checks"][-1] == {"label": "협박에 흔들리지 않고 끊기", "ok": False, "kind": "surprise"}


# ------------------------------------------------------------------ 고도화: 흐름 · 이번 주 · 항목 설명


def test_history_and_week_delta(api, db_session):
    from sqlalchemy import text

    user = make_user(db_session)
    _practice(api, user, "bank")  # 2주 전에 한 것으로 옮긴다
    db_session.execute(text("UPDATE activity_logs SET created_at = now() - interval '15 days' WHERE user_id = :u"), {"u": user.id})
    db_session.commit()
    _practice(api, user, "scam-call")  # 이번 주

    r = _readiness(api, user)
    assert [p["weeks_ago"] for p in r["history"]] == [3, 2, 1, 0]
    scores = [p["score"] for p in r["history"]]
    assert scores[0] == 0 and scores[1] == scores[2] > 0  # 2주 전부터 은행 연습
    assert scores[3] == r["score"] > scores[2]
    assert r["week_delta"] == scores[3] - scores[2]
    assert "수상한 전화 대처 연습" in r["recent"] and "수상한 전화 완벽하게 대처하기" in r["recent"]
    assert "은행 계좌 개설 연습" not in r["recent"]  # 이번 주에 한 게 아님


def test_item_detail_and_next_level(api, db_session):
    user = make_user(db_session)
    api.post(f"/api/users/{user.id}/activities", json={"kind": "practice", "ref": "scam-call", "data": {"good": 2, "missed": 1, "score": 70}})
    api.post(f"/api/users/{user.id}/activities", json={"kind": "practice", "ref": "scam-call", "data": {"good": 3, "missed": 1, "score": 85}})
    r = _readiness(api, user)
    safety = {i["key"]: i for a in r["areas"] if a["key"] == "safety" for i in a["items"]}
    assert safety["practice-scam-call"]["detail"] == "2번 해 봤어요 · 최고 85점"
    assert "모두 챙기면 완료" in safety["practice-scam-call-clean"]["detail"]
    assert safety["practice-phone-store"]["detail"] == "아직 안 해 봤어요"
    assert r["next_level"] == {"label": "준비 중", "points": 30 - r["score"]}


def test_empty_user_history_is_flat(api, db_session):
    r = _readiness(api, make_user(db_session))
    assert [p["score"] for p in r["history"]] == [0, 0, 0, 0]
    assert r["week_delta"] == 0 and r["recent"] == []


# ------------------------------------------------------------------ 세분화된 점수


def _item(r, area, key):
    return next(i for a in r["areas"] if a["key"] == area for i in a["items"] if i["key"] == key)


def test_practice_progress_by_score_and_missed(api, db_session):
    user = make_user(db_session)
    url = f"/api/users/{user.id}/activities"
    api.post(url, json={"kind": "practice", "ref": "bank", "data": {"good": 1, "missed": 3, "score": 40}})
    assert _item(_readiness(api, user), "money", "practice-bank")["progress"] == 0.5  # 해 봄
    api.post(url, json={"kind": "practice", "ref": "bank", "data": {"good": 2, "missed": 2, "score": 65}})
    assert _item(_readiness(api, user), "money", "practice-bank")["progress"] == 0.75  # 60점 이상
    api.post(url, json={"kind": "practice", "ref": "bank", "data": {"good": 3, "missed": 1, "score": 85}})
    item = _item(_readiness(api, user), "money", "practice-bank")
    assert (item["done"], item["progress"]) == (True, 1.0)  # 80점 이상


def test_clean_item_scales_with_missed(api, db_session):
    user = make_user(db_session)
    _practice(api, user, "scam-call", missed=2)
    assert _item(_readiness(api, user), "safety", "practice-scam-call-clean")["progress"] == 0.5
    _practice(api, user, "scam-call", missed=0)
    assert _item(_readiness(api, user), "safety", "practice-scam-call-clean")["progress"] == 1.0


def test_month_partial_by_shortage(api, db_session):
    user = make_user(db_session)
    api.post(f"/api/users/{user.id}/activities", json={"kind": "month_life", "data": {**MONTH, "shortage": 15}})
    r = _readiness(api, user)
    assert _item(r, "money", "month-no-shortage")["progress"] == round(0.75 * 0.5, 2)
    assert _item(r, "life", "month-twice")["progress"] == 0.5
    money = next(a for a in r["areas"] if a["key"] == "money")
    assert money["score"] == round(100 * (0.75 * 0.5) / 4)


def test_partial_never_counts_as_done(api, db_session):
    user = make_user(db_session)
    _practice(api, user, "hospital", missed=5)
    life = next(a for a in _readiness(api, user)["areas"] if a["key"] == "life")
    assert life["done_count"] == 0 and life["score"] > 0
