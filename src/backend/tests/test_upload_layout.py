"""올린 양식을 원본 규격으로 내보내기 · 같은 양식 다시 올리기 · 내 정보 바뀌면 서류 칸에도 (app/form_layouts.py)"""

import base64

import pymupdf

from app import form_layouts, hwpx_render
from tests.factories import make_user
from tests.hwpx_sample import build
from tests.test_writer_api import doc_llm  # noqa: F401  (fixture)

FORM = {"title": "자립 지원 신청서", "fields": [
    {"label": "성명", "section": "신청인", "type": "text"},
    {"label": "생년월일", "type": "date"},
    {"label": "주소", "type": "address"},
    {"label": "휴대전화", "type": "phone"},
    {"label": "이메일", "type": "text"},
    {"label": "신청 방법", "type": "checkbox", "options": ["방문", "온라인"]},
]}


def _up(api, user, data: bytes, **kw):
    body = {"user_id": str(user.id), "file_base64": base64.b64encode(data).decode(), **kw}
    return api.post("/api/documents/upload", json=body)


def _pdf_text(api, user, doc_id) -> str:
    content = api.get(f"/api/documents/{doc_id}/file?user_id={user.id}&format=pdf").content
    with pymupdf.open(stream=content, filetype="pdf") as pdf:
        return "".join(p.get_text() for p in pdf)


def _fill_and_export(api, user, d):
    base = f"/api/documents/{d['document_id']}"
    values = {"주소": "인천광역시 남동구 구월로 12", "이메일": "minsu@example.com", "신청 방법": "방문"}
    for f in d["fields"]:
        value = values.get(f["label"], f["value"])
        r = api.patch(f"{base}/fields/{f['field_id']}?user_id={user.id}", json={"value": value, "is_completed": True})
        assert r.status_code == 200, r.text
    r = api.post(f"{base}/export", json={"user_id": str(user.id)})
    assert r.status_code == 200, r.text
    return r.json()


def test_hwpx_renders_same_table_structure():
    pdf = hwpx_render.render(build())
    with pymupdf.open(stream=pdf, filetype="pdf") as doc:
        page = doc[0]
        text = page.get_text()
        assert "자립 지원 신청서" in text and "생년월일" in text and "[ ]방문" in text
        assert len(page.find_tables().tables) == 1


def test_hwpx_upload_exports_on_original_layout(api, db_session, doc_llm):
    user = make_user(db_session, name="김민수", phone="01012345678")
    doc_llm.form = FORM
    d = _up(api, user, build(), file_name="신청서.hwpx").json()
    out = _fill_and_export(api, user, d)
    assert out["pdf_layout"] == "original"
    text = _pdf_text(api, user, d["document_id"])
    # 양식 글자와 작성 내용이 한 쪽에 같이 있다 (칸 이름·내용 표가 아님)
    assert "신청인" in text and "김민수" in text and "인천광역시 남동구 구월로 12" in text and "minsu@example.com" in text


def test_pdf_upload_with_table_exports_on_original_layout(api, db_session, doc_llm):
    user = make_user(db_session, name="김민수", phone="01012345678")
    doc_llm.form = FORM
    pdf = hwpx_render.render(build())  # 표가 그려진 일반 PDF 양식
    d = _up(api, user, pdf, file_name="신청서.pdf").json()
    out = _fill_and_export(api, user, d)
    assert out["pdf_layout"] == "original"
    assert "인천광역시 남동구 구월로 12" in _pdf_text(api, user, d["document_id"])


def test_same_form_again_opens_saved_document_without_reading(api, db_session, doc_llm):
    """같은 양식을 다시 받은 파일(바이트가 다름)로 올려도 쓰던 문서를 연다. AI 로 다시 읽지 않는다"""
    user = make_user(db_session)
    doc_llm.form = FORM
    first = _up(api, user, build(), file_name="a.hwpx")
    assert first.status_code == 201
    calls = len([c for c in doc_llm.calls if c[0] == "read_form"])
    again = _up(api, user, build() + b"", file_name="다시 받은 파일.hwpx")
    pdf_copy = hwpx_render.render(build())
    assert again.status_code == 200 and again.json()["document_id"] == first.json()["document_id"]
    assert len([c for c in doc_llm.calls if c[0] == "read_form"]) == calls
    # 같은 양식의 PDF 판도 글자가 같아 같은 양식으로 본다
    from app.form_upload import read_source, source_id

    assert source_id(pdf_copy, read_source(pdf_copy)[0]) != ""


def test_photo_stays_table(api, db_session, doc_llm):
    """사진은 글자 위치를 몰라 원본 규격 바탕이 없다"""
    assert form_layouts.base_pdf(b"\xff\xd8\xff", "jpg") is None


def test_profile_change_updates_all_documents(api, db_session, doc_llm):
    """내 이름이 바뀌면 내 이름이 들어간 모든 서류 칸이 바뀐다. 일부러 다르게 적은 칸은 그대로"""
    user = make_user(db_session, name="김민수", phone="01012345678")
    doc_llm.form = {"title": "신청서", "fields": [
        {"label": "성명", "section": "신청인", "type": "text"},
        {"label": "휴대전화", "type": "phone"},
        {"label": "신청 사유", "type": "text"},
    ]}
    d = _up(api, user, build(), file_name="a.hwpx").json()
    template = api.post("/api/documents", json={"user_id": str(user.id), "source_type": "template",
                                                 "source_id": "scholarship"}).json()
    name_field = next(f for f in template["fields"] if f["value"] == "김민수")
    r = api.patch(f"/api/users/{user.id}", json={"name": "정서현", "phone": "01099998888"})
    assert r.status_code == 200, r.text
    got = api.get(f"/api/documents/{d['document_id']}?user_id={user.id}").json()
    by = {f["label"]: f["value"] for f in got["fields"]}
    assert by["성명"] == "정서현" and by["휴대전화"] == "010-9999-8888"
    got2 = api.get(f"/api/documents/{template['document_id']}?user_id={user.id}").json()
    assert next(f for f in got2["fields"] if f["field_id"] == name_field["field_id"])["value"] == "정서현"


def test_profile_change_keeps_hand_edited_value(api, db_session, doc_llm):
    user = make_user(db_session, name="김민수")
    doc_llm.form = {"title": "신청서", "fields": [{"label": "성명", "section": "신청인", "type": "text"}]}
    d = _up(api, user, build(), file_name="a.hwpx").json()
    fid = d["fields"][0]["field_id"]
    r = api.patch(f"/api/documents/{d['document_id']}/fields/{fid}?user_id={user.id}", json={"value": "홍길동"})
    assert r.status_code == 200, r.text
    api.patch(f"/api/users/{user.id}", json={"name": "정서현"})
    got = api.get(f"/api/documents/{d['document_id']}?user_id={user.id}").json()
    assert got["fields"][0]["value"] == "홍길동"
