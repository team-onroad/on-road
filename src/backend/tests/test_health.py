def test_health_returns_ok(client):
    res = client.get("/api/health")

    assert res.status_code == 200
    assert res.json() == {"status": "ok"}


def test_unknown_path_uses_common_error_format(client):
    res = client.get("/api/does-not-exist")

    assert res.status_code == 404
    assert res.json() == {"error": {"code": "NOT_FOUND", "message": "요청한 경로를 찾을 수 없습니다."}}
