"""자립 준비도 기록: readiness_snapshots, last_week_score, recent_scores"""

from datetime import date, timedelta

from sqlalchemy import text

from tests.factories import make_user

TODAY = date(2026, 10, 4)  # clock fixture 기본값


def _readiness(api, user):
    res = api.get(f"/api/users/{user.id}/readiness")
    assert res.status_code == 200, res.text
    return res.json()


def _practice(api, user, sid):
    api.post(f"/api/users/{user.id}/activities", json={"kind": "practice", "ref": sid, "data": {"good": 2, "missed": 0}})


def _snapshots(db_session, user):
    return db_session.execute(
        text("SELECT snapshot_date, score, areas FROM readiness_snapshots WHERE user_id = :u ORDER BY snapshot_date"),
        {"u": user.id},
    ).mappings().all()


def _insert(db_session, user, day, score):
    db_session.execute(
        text("INSERT INTO readiness_snapshots (user_id, score, areas, snapshot_date) VALUES (:u, :s, '{}', :d)"),
        {"u": user.id, "s": score, "d": day},
    )
    db_session.commit()


def test_first_view_saves_today(api, db_session):
    user = make_user(db_session)
    r = _readiness(api, user)

    rows = _snapshots(db_session, user)
    assert len(rows) == 1
    assert (rows[0]["snapshot_date"], rows[0]["score"]) == (TODAY, r["score"])
    assert rows[0]["areas"] == {a["key"]: a["score"] for a in r["areas"]}
    assert r["last_week_score"] is None
    assert r["recent_scores"] == [{"date": "2026-10-04", "score": r["score"]}]


def test_same_day_overwrites(api, db_session):
    user = make_user(db_session)
    first = _readiness(api, user)["score"]
    _practice(api, user, "scam-call")
    _practice(api, user, "phone-store")
    r = _readiness(api, user)

    assert r["score"] > first
    rows = _snapshots(db_session, user)
    assert [(x["snapshot_date"], x["score"]) for x in rows] == [(TODAY, r["score"])]
    assert rows[0]["areas"]["safety"] == 75
    assert r["recent_scores"] == [{"date": "2026-10-04", "score": r["score"]}]


def test_new_day_adds_row(api, db_session, clock):
    user = make_user(db_session)
    _readiness(api, user)
    clock.today = TODAY + timedelta(days=1)
    r = _readiness(api, user)

    assert [x["snapshot_date"] for x in _snapshots(db_session, user)] == [TODAY, TODAY + timedelta(days=1)]
    assert [s["date"] for s in r["recent_scores"]] == ["2026-10-05", "2026-10-04"]  # 최신순


def test_last_week_score_is_latest_on_or_before_7_days_ago(api, db_session):
    user = make_user(db_session)
    _insert(db_session, user, TODAY - timedelta(days=10), 5)
    _insert(db_session, user, TODAY - timedelta(days=7), 20)
    _insert(db_session, user, TODAY - timedelta(days=6), 40)

    assert _readiness(api, user)["last_week_score"] == 20


def test_last_week_score_uses_older_row_when_7_days_ago_missing(api, db_session):
    user = make_user(db_session)
    _insert(db_session, user, TODAY - timedelta(days=12), 15)
    _insert(db_session, user, TODAY - timedelta(days=3), 40)

    assert _readiness(api, user)["last_week_score"] == 15


def test_last_week_score_null_when_only_recent_rows(api, db_session):
    user = make_user(db_session)
    _insert(db_session, user, TODAY - timedelta(days=6), 40)

    assert _readiness(api, user)["last_week_score"] is None


def test_recent_scores_latest_7_days_with_rows(api, db_session):
    user = make_user(db_session)
    for days_ago in (1, 2, 4, 6, 9, 13, 20, 30):  # 비어 있는 날은 건너뜀
        _insert(db_session, user, TODAY - timedelta(days=days_ago), days_ago)
    r = _readiness(api, user)

    assert len(r["recent_scores"]) == 7
    assert r["recent_scores"][0] == {"date": "2026-10-04", "score": r["score"]}
    assert [s["score"] for s in r["recent_scores"][1:]] == [1, 2, 4, 6, 9, 13]
    assert len(_snapshots(db_session, user)) == 9  # 오래된 기록도 지우지 않음


def test_other_users_not_mixed(api, db_session):
    user = make_user(db_session)
    other = make_user(db_session)
    _insert(db_session, other, TODAY - timedelta(days=7), 90)
    _insert(db_session, other, TODAY - timedelta(days=1), 90)

    r = _readiness(api, user)
    assert r["last_week_score"] is None
    assert [s["date"] for s in r["recent_scores"]] == ["2026-10-04"]
    assert len(_snapshots(db_session, other)) == 2


def test_unknown_user_saves_nothing(api, db_session):
    assert api.get("/api/users/00000000-0000-0000-0000-000000000000/readiness").status_code == 404
    assert db_session.execute(text("SELECT count(*) FROM readiness_snapshots")).scalar() == 0


def test_deleted_with_user(api, db_session):
    user = make_user(db_session)
    _readiness(api, user)
    db_session.execute(text("DELETE FROM users WHERE id = :u"), {"u": user.id})
    db_session.commit()
    assert db_session.execute(text("SELECT count(*) FROM readiness_snapshots")).scalar() == 0


def test_no_cache_header(api, db_session):
    user = make_user(db_session)
    assert api.get(f"/api/users/{user.id}/readiness").headers["cache-control"] == "no-cache"
