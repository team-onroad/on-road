"""올린 양식(사진·PDF) → 문서 AI (POST /api/documents/upload, app/form_upload.py)."""

import base64

import pymupdf
import pytest

from tests.factories import make_user
from tests.test_writer_api import doc_llm  # noqa: F401  (fixture)

def _pdf(lines: list[str]) -> bytes:
    doc = pymupdf.open()
    page = doc.new_page()
    y = 72
    page.insert_font(fontname="ko", fontbuffer=pymupdf.Font("cjk").buffer)
    for line in lines:
        page.insert_text((72, y), line, fontname="ko", fontsize=11)
        y += 20
    return doc.tobytes()


def _png() -> bytes:
    pix = pymupdf.Pixmap(pymupdf.csRGB, pymupdf.IRect(0, 0, 400, 300), False)
    pix.clear_with(255)
    return pix.tobytes("png")


def _up(api, user, data: bytes, **kw):
    body = {"user_id": str(user.id), "file_base64": base64.b64encode(data).decode(), **kw}
    return api.post("/api/documents/upload", json=body)


PHOTO_FORM = {
    "title": "주거지원 신청서",
    "fields": [
        {"label": "성 명", "section": "신청인", "input_type": "text", "guide": "이름을 적어요."},
        {"label": "연락처", "input_type": "phone", "guide": "휴대폰 번호"},
        {"label": "주소", "input_type": "address", "guide": "사는 곳"},
        {"label": "주민등록번호", "input_type": "text", "guide": "번호", "example": "000000-0000000"},
        {"label": "계좌번호", "input_type": "text", "guide": "받을 통장"},
        {"label": "신청 사유", "input_type": "text", "guide": "까닭"},
        {"label": "서명", "input_type": "text", "guide": "서명"},
        {"label": "접수번호", "type": "text", "guide": "기관"},
    ],
}


def test_photo_form_becomes_document(api, db_session, doc_llm):
    user = make_user(db_session, name="김민지", phone="01012345678")
    doc_llm.form = PHOTO_FORM
    res = _up(api, user, _png(), file_name="IMG_1234.png")
    assert res.status_code == 201, res.text
    d = res.json()
    assert d["source_type"] == "upload" and d["title"] == "주거지원 신청서"
    fields = {f["label"]: f for f in d["fields"]}
    assert list(fields) == ["성 명", "연락처", "주소", "주민등록번호", "계좌번호", "신청 사유", "서명"]  # 기관 칸 버림
    assert fields["성 명"]["value"] == "김민지" and fields["성 명"]["value_source"] == "profile"
    assert fields["연락처"]["value"] == "010-1234-5678"
    assert fields["주민등록번호"]["value_source"] == "handwrite" and fields["주민등록번호"]["example"] is None
    assert fields["계좌번호"]["input_type"] == "account" and fields["계좌번호"]["value_source"] == "user"
    assert fields["서명"]["value_source"] == "handwrite"
    assert fields["신청 사유"]["essay"] is True
    assert d["notices"][0].startswith("사진에서 AI가 읽은 칸이에요.")
    name, (form_text, images) = doc_llm.calls[-1]
    assert name == "read_form" and form_text is None and images[0].startswith("data:image/jpeg;base64,")
    assert len(images[0]) < 200_000  # 흑백 JPG 로 줄여 보낸다

    # 같은 사진을 다시 올리면 쓰던 문서 (LLM 다시 안 부름)
    calls = len(doc_llm.calls)
    again = _up(api, user, _png())
    assert again.status_code == 200 and again.json()["document_id"] == d["document_id"]
    assert len(doc_llm.calls) == calls

    # 대화로 채우기도 그대로 된다
    q = api.post(f"/api/documents/{d['document_id']}/interview", json={"user_id": str(user.id)}).json()["question"]
    assert q["field_id"] == "f1"


def test_text_pdf_sends_only_text(api, db_session, doc_llm):
    user = make_user(db_session)
    pdf = _pdf(["자립 지원 신청서", "성명", "연락처", "주소", "신청 사유", "위 내용이 사실임을 확인하며 자립 지원을 신청합니다", "서명"])
    doc_llm.form = {"title": "", "fields": [
        {"label": "성명", "type": "text"}, {"label": "연락처", "type": "phone"}, {"label": "보호자 직업", "type": "text"},
    ]}
    res = _up(api, user, pdf, file_name="자립지원_신청서.pdf")
    assert res.status_code == 201, res.text
    name, (form_text, images) = doc_llm.calls[-1]
    assert name == "read_form" and "연락처" in form_text and images == []
    assert [f["label"] for f in res.json()["fields"]] == ["성명", "연락처"]  # PDF 글자에 없는 칸은 버림
    assert res.json()["title"] == "자립지원_신청서"  # LLM 제목이 없으면 파일 이름


@pytest.mark.parametrize("data, code", [
    (b"hello world", "UPLOAD_INVALID"),
    (b"\x00\x00\x00\x18ftypheic" + b"0" * 20, "UPLOAD_INVALID"),
])
def test_bad_files(api, db_session, doc_llm, data, code):
    user = make_user(db_session)
    res = _up(api, user, data)
    assert res.status_code == 422 and res.json()["error"]["code"] == code


def test_unreadable_form(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_llm.form = {"title": "", "fields": [{"label": "접수번호", "guide": "x"}]}
    res = _up(api, user, _png())
    assert res.status_code == 422 and res.json()["error"]["code"] == "FORM_UNREADABLE"


def test_ai_unavailable(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_llm.error = RuntimeError("down")
    res = _up(api, user, _png())
    assert res.status_code == 503 and res.json()["error"]["code"] == "AI_UNAVAILABLE"


def test_create_rejects_upload_type(api, db_session):
    user = make_user(db_session)
    res = api.post("/api/documents", json={"user_id": str(user.id), "source_type": "upload", "source_id": "x"})
    assert res.status_code == 422


def test_date_parts_become_one_date_field(api, db_session, doc_llm, clock):
    user = make_user(db_session)
    doc_llm.form = {"title": "", "fields": [
        {"label": "신청 사유", "type": "text"}, {"label": "년", "type": "text"}, {"label": "월", "type": "text"},
        {"label": "일", "type": "text"}, {"label": "생년월일", "type": "text"},
    ]}
    d = _up(api, user, _png()).json()
    fields = [(f["label"], f["input_type"]) for f in d["fields"]]
    assert fields == [("신청 사유", "text"), ("년 월 일", "date"), ("생년월일", "date")]
    # 신청 날짜는 오늘 날짜로 미리 채운다 (확인만 받는다)
    got = api.get(f"/api/documents/{d['document_id']}", params={"user_id": str(user.id)}).json()
    date_field = next(f for f in got["fields"] if f["label"] == "년 월 일")
    assert date_field["value"] == clock.today.strftime("%Y.%m.%d") and date_field["is_completed"] is False
