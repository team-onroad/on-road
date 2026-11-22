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
    assert d["notices"][0].startswith("이 양식은 칸 위치를 읽지 못해")  # 사진: 칸 이름·내용 PDF
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


def test_scattered_date_parts_are_merged(api, db_session, doc_llm):
    user = make_user(db_session)
    doc_llm.form = {"title": "", "fields": [
        {"label": "년", "type": "date"}, {"label": "신청 사유", "type": "text"},
        {"label": "월:", "type": "date"}, {"label": "일", "type": "date"},
    ]}
    d = _up(api, user, _png()).json()
    assert [(f["label"], f["input_type"]) for f in d["fields"]] == [("년 월 일", "date"), ("신청 사유", "text")]
    assert d["source_id"].startswith("upload-v3-")


def test_hwp_and_hwpx_read_as_text():
    """한글 파일(hwp·hwpx)도 PDF 처럼 글자를 꺼내 칸을 찾는다"""
    import io
    import zipfile
    from pathlib import Path

    from app.form_upload import read_source

    hwp = Path(__file__).resolve().parents[2] / "자립준비청년 자립수당 신청서.hwp"
    if hwp.exists():
        text, images = read_source(hwp.read_bytes())
        assert images == [] and "계좌번호" in text and "신청서" in text
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        body = "".join(f"<hp:p><hp:run><hp:t>{t}</hp:t></hp:run></hp:p>" for t in ["장학금 신청서", "성명", "연락처", "주소", "신청 사유를 구체적으로 적어 주세요", "학업 계획을 이번 학기와 일 년 뒤로 나눠 적어 주세요"])
        z.writestr("Contents/section0.xml", f'<hs:sec xmlns:hs="h" xmlns:hp="p">{body}</hs:sec>')
    text, images = read_source(buf.getvalue())
    assert images == [] and text.splitlines()[:3] == ["장학금 신청서", "성명", "연락처"]


def test_to_fields_keeps_same_label_in_other_sections():
    from app.form_upload import to_fields
    raw = [
        {"label": "성명", "section": "신청인", "type": "text"},
        {"label": "성명", "section": "대리인", "type": "text"},
        {"label": "연락처", "section": "대리인", "type": "phone"},
        {"label": "성명", "section": "가족 1"}, {"label": "성명", "section": "가족 2"},
        {"label": "확인자", "type": "text"},
    ]
    fields = to_fields(raw, "신청인 성명 대리인 성명 연락처 가족 확인자")
    assert [f["easy_label"] for f in fields] == ["신청인 성명", "대리인 성명", None, "가족 1 성명", "가족 2 성명"]
    assert fields[0]["profile_key"] == "name"
    assert all(f["profile_key"] is None for f in fields[1:])  # 대리인 · 가족 칸에 내 정보를 넣지 않는다


def test_pdf_table_rows_are_read():
    import pymupdf
    from app.form_upload import read_source
    doc = pymupdf.open()
    page = doc.new_page()
    font = "korea"
    rows = [("신청인 정보", "대리인 정보"), ("성명", "성명"), ("연락처", "연락처")]
    for i, (a, b) in enumerate(rows):
        y = 100 + i * 30
        for j, text in enumerate((a, b)):
            r = pymupdf.Rect(72 + j * 200, y, 272 + j * 200, y + 30)
            page.draw_rect(r, color=(0, 0, 0))
            page.insert_textbox(r + (4, 6, 0, 0), text, fontname=font, fontsize=10)
    page.insert_text((72, 300), "아래 칸을 빠짐없이 적어 주세요. 대리 신청이면 대리인 정보도 적어요." * 2, fontname=font, fontsize=8)
    text, _ = read_source(doc.tobytes())
    assert "[표 구조]" in text
    assert "성명 | 성명" in text


def test_hwpx_table_rows_with_nested_table_and_split_runs():
    import io
    import zipfile
    from app.form_upload import hwpx_text
    ns = 'xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph" xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"'

    def p(*runs):
        return "<hp:p>" + "".join(f"<hp:run><hp:t>{r}</hp:t></hp:run>" for r in runs) + "</hp:p>"

    def tc(inner):
        return f"<hp:tc><hp:subList>{inner}</hp:subList></hp:tc>"

    def tr(*cells):
        return "<hp:tr>" + "".join(cells) + "</hp:tr>"

    nested = f'<hp:p><hp:run><hp:tbl>{tr(tc(p("은행명")), tc(p("계좌", "번호")))}</hp:tbl></hp:run></hp:p>'
    table = tr(tc(p("성", "명")), tc(p("성명"))) + tr(tc(p("신") + p("청") + p("인")), tc(nested))
    xml = f'<?xml version="1.0"?><hs:sec {ns}><hp:p><hp:run><hp:tbl>{table}</hp:tbl></hp:run></hp:p></hs:sec>'
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("Contents/section0.xml", xml)
    rows = hwpx_text(buf.getvalue()).split("[표 구조]")[1]
    assert "성명 | 성명" in rows
    assert "은행명 | 계좌번호" in rows
    assert "신청인" in rows


def test_sectioned_form_interview_flow(api, db_session, doc_llm):
    """신청인 · 대리인 · 가족 1/2 묶음이 있는 양식: 대리인 칸엔 내 정보가 안 들어가고, 대리 신청 여부를 묻고,
    가족 1 첫 칸에서 '없어요'면 가족 줄을 모두 비운다"""
    user = make_user(db_session, name="김민지", phone="01012345678")
    pdf = _pdf(["자립 지원 신청서", "신청인 성명 연락처", "대리인 성명 연락처", "가족사항 관계 성명", "신청 사유",
                "위 내용이 사실임을 확인하며 신청합니다"])
    doc_llm.form = {"title": "자립 지원 신청서", "fields": [
        {"label": "성명", "section": "신청인", "type": "text"},
        {"label": "연락처", "section": "신청인", "type": "phone"},
        {"label": "성명", "section": "대리인", "type": "text"},
        {"label": "연락처", "section": "대리인", "type": "phone"},
        {"label": "관계", "section": "가족 1"}, {"label": "성명", "section": "가족 1"},
        {"label": "관계", "section": "가족 2"}, {"label": "성명", "section": "가족 2"},
        {"label": "신청 사유", "type": "text"},
    ]}
    d = _up(api, user, pdf, file_name="a.pdf").json()
    by = {f["field_id"]: f for f in d["fields"]}
    assert [by[f]["value"] for f in ("f1", "f2")] == ["김민지", "010-1234-5678"]
    assert all(not by[f]["value"] for f in ("f3", "f4", "f5", "f6", "f7", "f8"))  # 다른 사람 칸은 비어 있다
    assert by["f3"]["easy_label"] == "대리인 성명"

    url = f"/api/documents/{d['document_id']}/interview"
    say = lambda m: api.post(url, json={"user_id": str(user.id), "message": m}).json()  # noqa: E731
    say("네")
    say("네")  # 신청인 칸 확인
    r = say("아니요 제가 직접 해요")  # 대리 신청 여부
    assert r["message"] == "대리인 칸은 비워 둘게요."
    assert r["question"]["field_id"] == "f5"
    r = say("없어요")
    assert r["message"] == "가족 칸은 더 비워 둘게요."
    assert r["question"]["field_id"] == "f9"

    # 신청 사유 → 초안 확인 → 내보내기: 묶음 머리글과 같은 이름 칸이 PDF · Word 로 나간다
    r = say("보호종료 후 월세가 부담돼서 주거비 지원이 필요해요")
    r = say("좋아요")
    assert r["done"] is True, r
    for fmt in ("pdf", "docx"):
        f = api.get(f"/api/documents/{d['document_id']}/file?user_id={user.id}&format={fmt}")
        assert f.status_code == 200 and len(f.content) > 1000
    pdf = pymupdf.open(stream=api.get(f"/api/documents/{d['document_id']}/file?user_id={user.id}&format=pdf").content)
    text = "".join(p.get_text() for p in pdf)
    assert "대리인" in text and "가족 1" in text and "김민지" in text
