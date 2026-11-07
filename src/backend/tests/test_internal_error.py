"""처리 안 된 예외 → 500 INTERNAL_ERROR, 개인정보 없는 로그"""

import logging

import pytest

from app.services import readiness, users
from tests.factories import make_user

SECRET = "홍길동 010-1234-5678"
BODY = {"name": "김비밀", "birth_date": "2005-03-15", "region": "서울", "status": "leaving_soon"}
EXPECTED = {"error": {"code": "INTERNAL_ERROR", "message": "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."}}


def _boom(*_args, **_kwargs):
    raise RuntimeError(SECRET)


@pytest.fixture
def broken(monkeypatch):
    monkeypatch.setattr(readiness, "readiness", _boom)
    monkeypatch.setattr(users, "create_user", _boom)


def _error_logs(caplog):
    return [r.getMessage() for r in caplog.records if r.name == "app.errors"]


def test_get_returns_internal_error(api, db_session, broken, caplog):
    user = make_user(db_session)
    with caplog.at_level(logging.ERROR):
        res = api.get(f"/api/users/{user.id}/readiness")

    assert res.status_code == 500
    assert res.json() == EXPECTED
    logs = _error_logs(caplog)
    assert len(logs) == 1
    assert logs[0].startswith("처리 안 된 오류: GET /api/users/{user_id}/readiness RuntimeError\n")
    assert "api/users.py" in logs[0] and "_boom" in logs[0]  # 코드 위치
    assert "starlette" not in logs[0] and "fastapi" not in logs[0]  # 라이브러리 줄 제외
    assert SECRET not in caplog.text and str(user.id) not in caplog.text


def test_body_not_logged(api, broken, caplog):
    with caplog.at_level(logging.DEBUG):
        res = api.post("/api/users", json=BODY)

    assert res.status_code == 500
    assert res.json() == EXPECTED
    assert _error_logs(caplog)[0].startswith("처리 안 된 오류: POST /api/users RuntimeError")
    for value in (SECRET, "김비밀", "2005-03-15"):
        assert value not in caplog.text


def test_live_server_logs_nothing_else(api, http, broken, caplog):
    # 실제 uvicorn 에서도 500 형식 같고, 서버 기본 로그에 예외 메시지가 안 남음
    with caplog.at_level(logging.DEBUG):
        res = http.post("/api/users", json=BODY)

    assert res.status_code == 500
    assert res.json() == EXPECTED
    assert SECRET not in caplog.text
    assert not [r for r in caplog.records if r.name.startswith("uvicorn") and r.levelno >= logging.ERROR]


def test_known_errors_unchanged(api, db_session, caplog):
    with caplog.at_level(logging.ERROR):
        assert api.get("/api/users/nope").json()["error"]["code"] == "USER_NOT_FOUND"
        assert api.get("/api/no-such-path").json()["error"]["code"] == "NOT_FOUND"
        assert api.post("/api/users", json={}).json()["error"]["code"] == "VALIDATION_ERROR"
    assert _error_logs(caplog) == []


def test_next_request_works(api, db_session, monkeypatch):
    user = make_user(db_session)
    monkeypatch.setattr(readiness, "readiness", _boom)
    assert api.get(f"/api/users/{user.id}/readiness").status_code == 500
    monkeypatch.undo()
    assert api.get(f"/api/users/{user.id}/readiness").status_code == 200
