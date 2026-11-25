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
    # 수상한 전화(다 챙김) · 완벽 대처 · 사기 안 당함은 완료, 휴대폰(2개 놓침)은 실력 0.6 · 빠짐없이 0.9 × 0.6
    assert areas["safety"]["done_count"] == 3
    phone = next(i for i in areas["safety"]["items"] if i["key"] == "practice-phone-store")
    assert (phone["done"], phone["progress"]) == (False, 0.6)
    assert areas["safety"]["score"] == round(100 * (3 + 0.6 + 0.54) / 5)
    assert areas["safety"]["next_step"]["scenario_id"] == "phone-store"
    assert areas["money"]["done_count"] == 2  # 모자람 없는 한 달 · 비상금 안 꺼낸 한 달
    assert areas["life"]["done_count"] == 1
    r = _readiness(api, user)
    assert r["weakest"] != "safety" and r["score"] > 0


def test_missed_practice_is_not_clean(api, db_session):
    user = make_user(db_session)
    _practice(api, user, "scam-call", missed=1)
    safety = next(a for a in _readiness(api, user)["areas"] if a["key"] == "safety")
    # 1개 놓침: 실력 0.75 · 완벽 대처 0.9 × 0.75 (둘 다 아직 완료 아님)
    assert [(i["done"], i["progress"]) for i in safety["items"]][:2] == [(False, 0.75), (False, 0.68)]
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
    assert safety["practice-scam-call"]["detail"] == "2번 해 봤어요 · 최근 85점"
    assert "다음엔 빠짐없이" in safety["practice-scam-call-clean"]["detail"]
    assert safety["practice-phone-store"]["detail"] == "아직 안 해 봤어요"
    assert r["next_level"] == {"label": "준비 중", "points": 30 - r["score"]}


def test_empty_user_history_is_flat(api, db_session):
    r = _readiness(api, make_user(db_session))
    assert [p["score"] for p in r["history"]] == [0, 0, 0, 0]
    assert r["week_delta"] == 0 and r["recent"] == []


# ------------------------------------------------------------------ 세분화된 점수


def _item(r, area, key):
    return next(i for a in r["areas"] if a["key"] == area for i in a["items"] if i["key"] == key)


def test_practice_progress_follows_recent_attempts(api, db_session):
    """실력은 최근 3번을 최근일수록 무겁게. 한 번 잘한 게 아니라 지금 잘하는지"""
    user = make_user(db_session)
    url = f"/api/users/{user.id}/activities"

    def post(score, missed):
        api.post(url, json={"kind": "practice", "ref": "bank", "data": {"good": 2, "missed": missed, "score": score}})
        return _item(_readiness(api, user), "money", "practice-bank")

    assert post(40, 3)["progress"] == 0.42  # (0.40 + 0.45) / 2
    assert post(65, 2)["progress"] == 0.56  # (0.425 + 2 × 0.625) / 3
    item = post(85, 1)
    assert (item["done"], item["progress"]) == (False, 0.68)  # 85점 한 번으로는 아직 완료 아님
    item = post(90, 0)
    assert (item["done"], item["progress"]) == (True, 1.0)  # 최근 2번 연속 잘함 → 꾸준함 더해 완료


def test_order_matters_recent_is_heavier(api, db_session):
    """같은 기록이라도 잘하다 못하면 낮고, 못하다 잘하면 높다"""
    up, down = make_user(db_session), make_user(db_session)
    for user, scores in ((up, (50, 90)), (down, (90, 50))):
        for sc in scores:
            api.post(f"/api/users/{user.id}/activities",
                     json={"kind": "practice", "ref": "bank", "data": {"good": 2, "missed": 1, "score": sc}})
    rise = _item(_readiness(api, up), "money", "practice-bank")["progress"]
    fall = _item(_readiness(api, down), "money", "practice-bank")["progress"]
    assert rise > fall


def test_old_practice_fades(api, db_session):
    from sqlalchemy import text

    user = make_user(db_session)
    _practice(api, user, "bank")
    assert _item(_readiness(api, user), "money", "practice-bank")["progress"] == 1.0
    db_session.execute(text("UPDATE activity_logs SET created_at = now() - interval '45 days' WHERE user_id = :u"), {"u": user.id})
    db_session.commit()
    item = _item(_readiness(api, user), "money", "practice-bank")
    assert (item["done"], item["progress"]) == (False, 0.85)  # 30일 넘으면 0.85배
    assert "45일 전" in item["detail"]


def test_clean_item_follows_latest_attempt(api, db_session):
    user = make_user(db_session)
    _practice(api, user, "scam-call", missed=2)
    assert _item(_readiness(api, user), "safety", "practice-scam-call-clean")["progress"] == 0.54
    _practice(api, user, "scam-call", missed=0)
    assert _item(_readiness(api, user), "safety", "practice-scam-call-clean")["progress"] == 1.0
    # 다음 연습에서 다시 놓치면 '빠짐없이'는 다시 아님 (한 번 해낸 적이 아니라 지금)
    _practice(api, user, "scam-call", missed=1)
    assert not _item(_readiness(api, user), "safety", "practice-scam-call-clean")["done"]


def test_month_partial_by_shortage(api, db_session):
    user = make_user(db_session)
    api.post(f"/api/users/{user.id}/activities", json={"kind": "month_life", "data": {**MONTH, "shortage": 15}})
    r = _readiness(api, user)
    assert _item(r, "money", "month-no-shortage")["progress"] == round(0.75 * 0.5, 2)
    assert _item(r, "life", "month-twice")["progress"] == 0.5
    money = next(a for a in r["areas"] if a["key"] == "money")
    # 모자람 없는 한 달 0.375 + 비상금은 안 꺼냈지만 모자람 0.5
    assert money["score"] == round(100 * (0.75 * 0.5 + 0.5) / 7)


def test_partial_never_counts_as_done(api, db_session):
    user = make_user(db_session)
    _practice(api, user, "hospital", missed=5)
    life = next(a for a in _readiness(api, user)["areas"] if a["key"] == "life")
    assert life["done_count"] == 0 and life["score"] > 0


# ------------------------------------------------------------------ 세분화 (영역마다 항목 수가 다르다)


def test_areas_have_more_than_four_items(api, db_session):
    user = make_user(db_session)
    totals = {a["key"]: a["total"] for a in _readiness(api, user)["areas"]}
    assert totals == {"housing": 7, "money": 7, "job": 6, "documents": 6, "safety": 5, "life": 6}
    assert all(len(a["items"]) == a["total"] for a in _readiness(api, user)["areas"])


def test_policy_category_codes_count(api, db_session):
    """정책 분야는 DB 에 영문 코드(housing · employment …)로 들어 있다. 예전에는 '주거'와 비교해서 주거 항목이 영영 안 됐다"""
    from tests.factories import make_policy

    user = make_user(db_session)
    for category in ("housing", "employment", "medical", "living_cost"):
        policy = make_policy(db_session, category=category)
        assert api.post("/api/checklists", json={"user_id": str(user.id), "policy_id": policy.id}).status_code == 201
    r = _readiness(api, user)
    assert _item(r, "housing", "housing-policy")["done"]
    assert _item(r, "job", "job-policy")["done"]
    assert _item(r, "life", "health-policy")["done"]
    assert _item(r, "money", "money-policy")["done"]
    assert _item(r, "documents", "checklist-two")["done"]
    # 시작만 한 주거 신청은 '끝까지'가 아직 아님
    assert not _item(r, "housing", "housing-policy-done")["done"]


def test_one_document_is_half_of_two(api, db_session):
    user = make_user(db_session)
    policy = __import__("tests.factories", fromlist=["make_policy"]).make_policy(db_session)
    api.post("/api/checklists", json={"user_id": str(user.id), "policy_id": policy.id})
    r = _readiness(api, user)
    two = _item(r, "documents", "checklist-two")
    assert (two["done"], two["progress"], two["detail"]) == (False, 0.5, "1가지 시작했어요")


# ------------------------------------------------------------------ 행동으로 본 판단 근거


def _saved(db_session, user, sid, missed, minutes_ago=0):
    from datetime import UTC, datetime, timedelta

    from db.models import RoleplayResult

    db_session.add(RoleplayResult(user_id=user.id, scenario_id=sid, good=["a"], missed=missed,
                                  created_at=datetime.now(UTC) - timedelta(minutes=minutes_ago)))
    db_session.commit()


def test_insight_repeated_miss_and_priority(api, db_session):
    """같은 항목을 2번 연속 놓치면 '다시 챙겨 볼 것'이 되고, 그 영역이 다음 할 일 맨 앞"""
    user = make_user(db_session)
    _saved(db_session, user, "scam-call", ["개인정보 알려 주지 않기"], minutes_ago=60)
    _saved(db_session, user, "scam-call", ["개인정보 알려 주지 않기", "은행에 직접 확인하기"])
    r = _readiness(api, user)
    watch = [i for i in r["insights"] if i["tone"] == "watch"]
    assert watch[0]["area"] == "safety"
    assert watch[0]["text"].endswith("‘개인정보 알려 주지 않기’ 항목을 2번 연속 놓쳤어요")
    assert r["next_steps"][0]["scenario_id"] == "scam-call"


def test_log_and_saved_result_of_same_session_count_once(api, db_session):
    user = make_user(db_session)
    _practice(api, user, "scam-call", missed=1)
    _saved(db_session, user, "scam-call", ["개인정보 알려 주지 않기"])
    item = _item(_readiness(api, user), "safety", "practice-scam-call")
    assert item["detail"].startswith("1번 해 봤어요")


def test_insight_improvement_and_budget_fix(api, db_session):
    user = make_user(db_session)
    url = f"/api/users/{user.id}/activities"
    for sc in (55, 80):
        api.post(url, json={"kind": "practice", "ref": "job", "data": {"good": 2, "missed": 1, "score": sc}})
    low = {"housing": 100000, "food": 100000, "transport": 10000, "telecom": 10000, "other": 10000}
    ok = {"housing": 400000, "food": 250000, "transport": 60000, "telecom": 40000, "other": 100000}
    for alloc in (low, ok):
        res = api.post("/api/simulations", json={"user_id": str(user.id), "total_income": 900000, "allocations": alloc})
        assert res.status_code == 201, res.text
    texts = [i["text"] for i in _readiness(api, user)["insights"] if i["tone"] == "good"]
    assert any(t.endswith("점수가 지난번보다 25점 올랐어요") for t in texts)
    assert "생활비 계획에서 부족했던 곳을 다시 맞췄어요" in texts


def test_insight_idle_and_stale_document(api, db_session):
    from sqlalchemy import text

    user = make_user(db_session)
    assert api.post("/api/documents", json={"user_id": str(user.id), "source_type": "template", "source_id": "move-in"}).status_code in (200, 201)
    _practice(api, user, "bank")
    for table in ("documents", "activity_logs"):
        db_session.execute(text(f"UPDATE {table} SET created_at = now() - interval '20 days' WHERE user_id = :u"), {"u": user.id})
    db_session.execute(text("UPDATE documents SET updated_at = now() - interval '20 days' WHERE user_id = :u"), {"u": user.id})
    db_session.commit()
    watch = [i["text"] for i in _readiness(api, user)["insights"] if i["tone"] == "watch"]
    assert watch == ["14일 넘게 멈춘 서류가 1개 있어요", "20일 동안 준비 기록이 없어요"]


def test_feedback_then_save_counts_once(api, db_session):
    """피드백(save=false) 뒤 '기록에 남기기'(save=true)로 같은 대화를 다시 보내도 연습은 1번"""
    user = make_user(db_session)
    for _ in range(2):
        api.post(f"/api/users/{user.id}/activities",
                 json={"kind": "practice", "ref": "bank", "data": {"good": 2, "missed": 1, "score": 70}})
    assert _item(_readiness(api, user), "money", "practice-bank")["detail"].startswith("1번 해 봤어요")


def test_career_tags_add_small_job_bonus(api, db_session):
    """관심 분야를 고르면 일 영역에 3점만 더한다 (항목 수 · 다른 영역은 그대로)"""
    user = make_user(db_session)
    assert api.patch(f"/api/users/{user.id}", json={"interest_tags": ["바리스타"]}).status_code == 200
    r = _readiness(api, user)
    job = next(a for a in r["areas"] if a["key"] == "job")
    assert job["score"] == 3
    assert job["done_count"] == 0
    assert all(a["score"] == 0 for a in r["areas"] if a["key"] != "job")
    assert r["score"] == 0  # 전체 = 영역 평균 (3 / 6 → 반올림 0)
