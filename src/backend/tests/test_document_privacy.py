"""서류 개인정보: 내용 암호화 저장, 오래된 서류 개인정보 칸 비우기, 동시 생성, 요청 크기 제한."""

import pytest
from cryptography.fernet import Fernet
from sqlalchemy import text

from app import sealing
from app.config import get_settings
from app.services import writer as writer_service

from tests.factories import make_user
from tests.test_writer_api import _complete_all, _create, _field, _patch, doc_llm  # noqa: F401  (fixture)


@pytest.fixture
def secret_key(monkeypatch):
    key = Fernet.generate_key().decode()
    monkeypatch.setattr(get_settings(), "doc_secret_key", key)
    sealing.reset()
    yield key
    monkeypatch.setattr(get_settings(), "doc_secret_key", None)
    sealing.reset()


def _raw_segments(db_session, doc_id, field_id):
    return db_session.execute(
        text("SELECT segments::text FROM document_fields WHERE document_id = :d AND field_id = :f"),
        {"d": doc_id, "f": field_id},
    ).scalar_one()


# ------------------------------------------------------------------ 암호화


def test_field_values_are_sealed_in_db_but_plain_in_api(api, db_session, doc_llm, secret_key):
    user = make_user(db_session)
    data = _create(api, user, source_id="allowance", context="자립수당 신청서 써야 해").json()
    doc_id = data["document_id"]

    res = _patch(api, user, doc_id, "account", value="123-456-789012")
    assert res.status_code == 200
    assert res.json()["field"]["value"] == "123-456-789012"

    raw = _raw_segments(db_session, doc_id, "account")
    assert "123-456-789012" not in raw and '"sealed"' in raw
    raw_context = db_session.execute(text("SELECT context FROM documents WHERE id = :d"), {"d": doc_id}).scalar_one()
    assert raw_context.startswith(sealing.TEXT_PREFIX) and "자립수당" not in raw_context

    again = api.get(f"/api/documents/{doc_id}?user_id={user.id}").json()
    assert _field(again, "account")["value"] == "123-456-789012"
    assert again["context"] == "자립수당 신청서 써야 해"


def test_plain_values_saved_before_key_still_read(api, db_session, doc_llm, monkeypatch):
    monkeypatch.setattr(get_settings(), "doc_secret_key", None)
    sealing.reset()
    user = make_user(db_session)
    doc_id = _create(api, user, source_id="allowance").json()["document_id"]
    _patch(api, user, doc_id, "account", value="123-456-789012")
    assert "123-456-789012" in _raw_segments(db_session, doc_id, "account")  # 키가 없으면 평문

    monkeypatch.setattr(get_settings(), "doc_secret_key", Fernet.generate_key().decode())
    sealing.reset()
    try:
        data = api.get(f"/api/documents/{doc_id}?user_id={user.id}").json()
        assert _field(data, "account")["value"] == "123-456-789012"
    finally:
        monkeypatch.setattr(get_settings(), "doc_secret_key", None)
        sealing.reset()


def test_key_rotation_reads_old_key(monkeypatch):
    old, new = Fernet.generate_key().decode(), Fernet.generate_key().decode()
    monkeypatch.setattr(get_settings(), "doc_secret_key", old)
    sealing.reset()
    sealed = sealing.seal_json([{"text": "010-1234-5678", "source": "user"}])
    monkeypatch.setattr(get_settings(), "doc_secret_key", f"{new},{old}")
    sealing.reset()
    try:
        assert sealing.open_json(sealed) == [{"text": "010-1234-5678", "source": "user"}]
    finally:
        monkeypatch.setattr(get_settings(), "doc_secret_key", None)
        sealing.reset()


# ------------------------------------------------------------------ 오래된 서류


def test_old_exported_document_keeps_writing_but_clears_personal(api, db_session, doc_llm):
    user = make_user(db_session, name="김민지", phone="01012345678")
    data = _create(api, user).json()  # 장학금 신청서: 이름 · 생년월일 · 연락처 · 주소 · 사유
    doc_id = data["document_id"]
    _complete_all(api, user, data, {"address": "서울시 A로 1", "reason": "혼자 생활하며 학비가 부담됩니다."})
    assert api.post(f"/api/documents/{doc_id}/export", json={"user_id": str(user.id)}).status_code == 200
    before = db_session.execute(text("SELECT updated_at FROM documents WHERE id = :d"), {"d": doc_id}).scalar_one()

    # 아직 기간 안: 그대로
    listed = api.get(f"/api/users/{user.id}/documents").json()
    assert listed["documents"][0]["progress"]["completed"] == data["progress"]["total"]

    db_session.execute(
        text("UPDATE documents SET exported_at = now() - interval '91 days', updated_at = now() - interval '91 days' WHERE id = :d"),
        {"d": doc_id},
    )
    db_session.commit()

    listed = api.get(f"/api/users/{user.id}/documents").json()
    assert len(listed["documents"]) == 1  # 서류는 지우지 않는다
    after = api.get(f"/api/documents/{doc_id}?user_id={user.id}").json()

    assert _field(after, "address")["value"] == ""
    assert _field(after, "phone")["value"] == ""
    assert _field(after, "name")["value"] == ""
    assert _field(after, "address")["is_completed"] is False
    assert _field(after, "reason")["value"] == "혼자 생활하며 학비가 부담됩니다."  # 쓴 문장은 남는다
    assert after["notices"][0] == writer_service.CLEARED_NOTICE
    # 개인정보가 아닌 칸은 남는다
    kept = [f for f in after["fields"] if f["writable"] and not writer_service._personal(f) and f["value"]]
    assert kept
    assert after["status"] == "exported"
    updated = db_session.execute(text("SELECT updated_at FROM documents WHERE id = :d"), {"d": doc_id}).scalar_one()
    assert updated < before  # 목록 순서를 바꾸지 않는다


def test_old_unfinished_document_clears_after_180_days(api, db_session, doc_llm):
    user = make_user(db_session, phone="01012345678")
    doc_id = _create(api, user, source_id="allowance").json()["document_id"]
    db_session.execute(text("UPDATE documents SET updated_at = now() - interval '179 days' WHERE id = :d"), {"d": doc_id})
    db_session.commit()
    assert writer_service.clear_stale(db_session) == 0

    db_session.execute(text("UPDATE documents SET updated_at = now() - interval '181 days' WHERE id = :d"), {"d": doc_id})
    db_session.commit()
    assert writer_service.clear_stale(db_session) == 1
    assert writer_service.clear_stale(db_session) == 0  # 한 번만


def test_cleared_values_are_not_reused_in_new_documents(api, db_session, doc_llm):
    user = make_user(db_session)
    first = _create(api, user, source_id="allowance").json()
    _patch(api, user, first["document_id"], "account", value="123-456-789012", is_completed=True)
    db_session.execute(text("UPDATE documents SET updated_at = now() - interval '200 days'"))
    db_session.commit()

    # 새 서류를 만들기 전에 오래된 서류부터 비우므로 계좌번호를 가져오지 않는다
    second = _create(api, user, source_id="scholarship").json()
    assert all("123-456-789012" not in f["value"] for f in second["fields"])


# ------------------------------------------------------------------ 동시 생성


def test_concurrent_create_returns_existing_instead_of_500(api, db_session, doc_llm, monkeypatch):
    user = make_user(db_session)
    first = _create(api, user).json()

    # 두 요청이 거의 동시에 와서 둘 다 '아직 없음'을 본 상황
    real = writer_service._existing
    calls = {"n": 0}

    def stale_once(*args):
        calls["n"] += 1
        return None if calls["n"] == 1 else real(*args)

    monkeypatch.setattr(writer_service, "_existing", stale_once)
    res = _create(api, user, context="다시 열기")

    assert res.status_code == 200, res.text
    assert res.json()["document_id"] == first["document_id"]
    assert res.json()["context"] == "다시 열기"
    count = db_session.execute(text("SELECT count(*) FROM documents")).scalar_one()
    assert count == 1


# ------------------------------------------------------------------ 요청 크기


def test_large_body_rejected_before_reading(api, db_session):
    user = make_user(db_session)
    big = "가" * (1024 * 1024)
    res = api.post("/api/documents", json={"user_id": str(user.id), "source_type": "template", "source_id": big})
    assert res.status_code == 413
    assert res.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"


def test_chunked_large_body_rejected(api, db_session):
    def chunks():
        for _ in range(3):
            yield b"x" * (512 * 1024)

    res = api.post("/api/documents", content=chunks(), headers={"Content-Type": "application/json"})
    assert res.status_code == 413


def test_upload_path_allows_large_files(api, db_session):
    user = make_user(db_session)
    res = api.post("/api/documents/upload", json={"user_id": str(user.id), "file_base64": "A" * (2 * 1024 * 1024)})
    assert res.status_code != 413  # 사진 · PDF 경로는 14MB 까지 (파일 내용 검사는 그다음)
