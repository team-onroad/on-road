"""PATCH /users/{user_id} 규칙을 실제 uvicorn 서버 + HTTP 요청으로 검증한다 (api.md 3.12).

TestClient 는 네트워크 없이 앱을 직접 호출하므로, /docs 에서 보내는 것과 같은 실제 HTTP 요청으로 한 번 더 확인한다.
"""

import json

ONBOARDING = {
    "name": "김지민",
    "birth_date": "2005-03-15",
    "phone": "01098765432",
    "region": "서울",
    "status": "leaving_soon",
    "d_date": "2026-11-12",
}
# /docs(Swagger UI)가 보내는 것과 같은 헤더
HEADERS = {"accept": "application/json", "Content-Type": "application/json"}


def _post_user(http, **overrides):
    res = http.post("/api/users", content=json.dumps({**ONBOARDING, **overrides}), headers=HEADERS)
    assert res.status_code == 201, res.text
    return res.json()


def _patch(http, user_id, raw_body: str):
    # 본문을 문자열 그대로 보내서, 직렬화 과정 없이 사용자가 입력한 JSON 이 전달되게 한다.
    return http.patch(f"/api/users/{user_id}", content=raw_body, headers=HEADERS)


def _get(http, user_id):
    res = http.get(f"/api/users/{user_id}", headers={"accept": "application/json"})
    assert res.status_code == 200, res.text
    return res.json()


def test_reported_repro_left_care_clears_d_date(http):
    # 1. status=leaving_soon, d_date=2026-11-12 사용자 생성
    created = _post_user(http, status="leaving_soon", d_date="2026-11-12")
    assert (created["d_date"], created["d_day"]) == ("2026-11-12", 39)

    # 2. PATCH {"status": "left_care"} 만 전송
    res = _patch(http, created["user_id"], '{"status": "left_care"}')

    # 3. d_date, d_day 는 null
    assert res.status_code == 200, res.text
    data = res.json()
    assert (data["status"], data["stage"], data["d_date"], data["d_day"]) == (
        "left_care", "youth", None, None,
    )
    assert _get(http, created["user_id"]) == data  # DB 에도 반영


def test_left_care_with_d_date_in_same_request_saves_it(http):
    created = _post_user(http, status="leaving_soon", d_date="2026-11-12")
    res = _patch(http, created["user_id"], '{"status": "left_care", "d_date": "2027-03-01"}')

    assert res.status_code == 200, res.text
    assert (res.json()["d_date"], res.json()["d_day"]) == ("2027-03-01", 148)
    assert _get(http, created["user_id"])["d_date"] == "2027-03-01"


def test_already_left_care_keeps_d_date(http):
    created = _post_user(http, status="left_care", d_date="2027-03-01")
    res = _patch(http, created["user_id"], '{"status": "left_care"}')

    assert res.status_code == 200, res.text
    assert res.json()["d_date"] == "2027-03-01"
    assert _get(http, created["user_id"])["d_date"] == "2027-03-01"


def test_left_care_twice_first_with_d_date_then_status_only(http):
    """퇴소 처리를 두 번 보낸 경우: 두 번째 요청 때는 이미 left_care 이므로 d_date 를 유지한다."""
    created = _post_user(http, status="leaving_soon", d_date="2026-11-12")
    _patch(http, created["user_id"], '{"status": "left_care", "d_date": "2026-11-12"}')

    res = _patch(http, created["user_id"], '{"status": "left_care"}')

    assert res.json()["d_date"] == "2026-11-12"


def test_phone_null_clears(http):
    created = _post_user(http)
    res = _patch(http, created["user_id"], '{"phone": null}')

    assert res.status_code == 200, res.text
    assert res.json()["phone"] is None
    assert _get(http, created["user_id"])["phone"] is None


def test_phone_empty_string_clears(http):
    created = _post_user(http)
    res = _patch(http, created["user_id"], '{"phone": ""}')

    assert res.status_code == 200, res.text
    assert _get(http, created["user_id"])["phone"] is None


def test_d_date_null_clears(http):
    created = _post_user(http)
    res = _patch(http, created["user_id"], '{"d_date": null}')

    assert res.status_code == 200, res.text
    assert (res.json()["d_date"], res.json()["d_day"]) == (None, None)
    assert _get(http, created["user_id"])["d_date"] is None


def test_other_fields_untouched_when_status_only(http):
    created = _post_user(http)
    data = _patch(http, created["user_id"], '{"status": "left_care"}').json()

    for key in ("name", "birth_date", "phone", "region", "created_at"):
        assert data[key] == created[key]
