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


def test_talk_mode_fixes_part_of_a_date(api, db_session, doc_llm):
    """"2008.01.01 맞아요?" → "2월 1일임" 처럼 일부만 고쳐 말해도 알아듣는다"""
    from datetime import date

    user = make_user(db_session, name="김민수", birth_date=date(2008, 1, 1))
    doc_llm.form = {"title": "신청서", "fields": [
        {"label": "생년월일", "type": "date"}, {"label": "신청 사유", "type": "text"},
    ]}
    d = _up(api, user, build(), file_name="a.hwpx").json()
    birth = next(f for f in d["fields"] if f["label"] == "생년월일")
    assert birth["value"] == "2008.01.01"
    url = f"/api/documents/{d['document_id']}/interview"
    r = api.post(url, json={"user_id": str(user.id)}).json()
    for _ in range(5):  # 표 구조로 앞쪽 칸(성명 등)도 생긴다: 생년월일 칸까지 '네'로 넘긴다
        if r["question"]["field_id"] == birth["field_id"]:
            break
        r = api.post(url, json={"user_id": str(user.id), "message": "네"}).json()
    assert r["question"]["field_id"] == birth["field_id"], r
    r = api.post(url, json={"user_id": str(user.id), "message": "2월 1일임"}).json()
    got = api.get(f"/api/documents/{d['document_id']}?user_id={user.id}").json()
    assert next(f for f in got["fields"] if f["field_id"] == birth["field_id"])["value"] == "2008.02.01", r
    # 내 정보의 생일도 바꿀지 확인 카드 → 승인하면 DB 와 다른 서류 칸까지
    change = next(c for c in r["profile_changes"] if c["field"] == "birth_date")
    assert change["proposed"] == "2008-02-01"
    assert api.patch(f"/api/users/{user.id}", json={"birth_date": change["proposed"]}).status_code == 200
    assert api.get(f"/api/users/{user.id}").json()["birth_date"] == "2008-02-01"


def test_chat_detects_every_profile_field():
    """상담 · 문서 대화에서 DB 내 정보(이름 · 생일 · 지역 · 전화 · 상태 · 퇴소일 · 하는 일)가 바뀌면 확인 카드 후보"""
    from datetime import date
    from types import SimpleNamespace

    from app.profile_signal import detect

    user = SimpleNamespace(name="김민수", birth_date=date(2008, 1, 1), region="서울", phone="01012345678",
                           status="leaving_soon", d_date=None, work=None)
    today = date(2026, 11, 22)
    fields = lambda m: {c.field: c.proposed for c in detect(m, user, today)}  # noqa: E731
    assert fields("제 이름은 정서현이에요") == {"name": "정서현"}
    assert fields("제 생일 2월 1일이에요") == {"birth_date": "2008-02-01"}
    assert fields("부산으로 이사했어요") == {"region": "부산"}
    assert fields("번호 010-9999-8888로 바꿨어요") == {"phone": "01099998888"}
    assert fields("저 이번에 퇴소했어요")["status"] == "left_care"
    assert fields("알바 시작했어요") == {"work": "working"}
    assert fields("제 이름이 뭐였지?") == {} and fields("부산 정책 알려줘") == {}


def test_resume_values_stay_out_of_label_cells():
    """이력서: 값이 칸 이름 칸을 침범하지 않는다. 반복 줄은 다음 줄로, 단위 칸은 단위 앞에, 괄호 칸은 괄호 안에"""
    from app import doc_export
    from tests.resume_sample import build as resume

    pdf = form_layouts.base_pdf(resume(), "hwpx")
    f = lambda i, label, sec=None, t="text": dict(field_id=i, label=label, section=sec, input_type=t,  # noqa: E731
                                                  value_source="user", guide="g", options=None)
    fields = [f("f1", "성명"), f("f2", "생년월일", t="date"), f("f3", "나이"), f("f7", "희망 직무"),
              f("f8", "기간", "학력 1"), f("f9", "학교명", "학력 1"), f("f11", "기간", "학력 2"), f("f12", "학교명", "학력 2")]
    boxes = {x["field_id"]: x["box"] for x in form_layouts.attach_boxes(fields, pdf)}
    assert boxes["f3"]["how"] == "table_right" and boxes["f3"]["template_text"] == "만 세"  # 나이 칸 이름 칸이 아니라 값 칸
    assert boxes["f11"]["rect"][1] == boxes["f8"]["rect"][3]  # 학력 2 기간은 학력 1 바로 아래 줄
    assert boxes["f12"]["rect"][1] == boxes["f9"]["rect"][3]
    values = {"f1": "김민수", "f2": "2007.03.15", "f3": "19", "f7": "웹 개발", "f8": "2020.03 ~ 2023.02",
              "f9": "인천 남동고등학교", "f11": "2023.03 ~ 2026.02", "f12": "동양미래대학교"}
    out = doc_export.render_overlay(pdf, "이력서", [(dict(x, box=boxes[x["field_id"]]), values[x["field_id"]]) for x in fields])
    with pymupdf.open(stream=out, filetype="pdf") as doc:
        text = "".join(page.get_text() for page in doc)
        assert doc.page_count == 1 and "별지" not in text  # 모두 제자리에 들어갔다
        page = doc[0]
        age = page.search_for("19")[0]
        assert age.x0 > boxes["f3"]["rect"][0]  # 나이 값은 값 칸 안
        job = page.search_for("웹")[0]
        paren = page.search_for("(", clip=pymupdf.Rect(boxes["f7"]["rect"]))[0]
        assert job.x0 > paren.x1  # 괄호 안


def test_resume_upload_splits_label_and_value_cells(api, db_session, doc_llm):
    """AI 가 칸을 일부만 읽어도 표의 값 칸마다 칸이 생기고, 값은 값 칸에만 들어간다"""
    from tests.resume_sample import build as resume

    user = make_user(db_session, name="김민수", phone="01012345678")
    doc_llm.form = {"title": "이력서", "fields": [
        {"label": "성명", "type": "text"}, {"label": "학교명", "section": "학력사항", "type": "text"},
    ]}
    d = _up(api, user, resume(), file_name="이력서.hwpx").json()
    labels = [(f["section"], f["label"]) for f in d["fields"]]
    assert labels == [
        (None, "성명"), (None, "생년월일"), (None, "나이"), (None, "연락처"), (None, "주소"), (None, "이메일"),
        (None, "희망 직무"), ("학력사항 1", "기간"), ("학력사항 1", "학교명"), ("학력사항 1", "전공"),
        ("학력사항 2", "기간"), ("학력사항 2", "학교명"), ("학력사항 2", "전공"),
    ]


def test_styled_forms_split_titles_headers_and_values():
    """표 폭 전체 제목 줄("학력사항", "1. 신청인")은 묶음 이름, "사진" 칸은 묶음이 아니고, 선택지 줄은 열 머리줄이 아니다"""
    from app import form_upload
    from tests import styled_forms as S

    resume = form_layouts.from_structure(form_upload.to_fields(S.RESUME_LLM["fields"], None),
                                         form_layouts.base_pdf(S.resume_hwpx(), "hwpx"))
    got = [(f.get("section"), f["label"]) for f in resume if (f.get("box") or {}).get("how") == "table_slot"]
    assert got[:7] == [(None, x) for x in ("성명", "생년월일", "연락처", "이메일", "주소", "희망 직무", "희망 연봉")]
    assert ("학력사항 2", "졸업 구분") in got and ("경력사항 1", "담당 업무") in got and (None, "자기소개") in got
    assert any(f["label"] == "작성일" and f.get("box") for f in resume)

    pdf = S.scholarship_pdf()
    scholar = form_layouts.from_structure(form_upload.to_fields(S.SCHOLAR_LLM["fields"], None), pdf)
    got = {(f.get("section"), f["label"]) for f in scholar}
    assert {("1. 신청인", "성명"), ("1. 신청인", "직전 학기 성적"), ("1. 신청인", "이수 학점"), ("2. 신청 사유", "신청 사유")} <= got
    assert not any("[ ]" in f["label"] or f["label"].startswith("1.") for f in scholar)
    assert not any("접수" in f["label"] for f in scholar)


def test_unit_suffix_cells_put_value_before_unit():
    """"     / 4.5", "      학점" 칸은 단위 앞에, 앞자리가 없는 "만원" 칸은 "3200만원" 으로"""
    from app import doc_export, form_upload
    from tests import styled_forms as S

    for data, kind, llm, values in (
        (S.scholarship_pdf(), "pdf", S.SCHOLAR_LLM, {"직전 학기 성적": "4.1", "이수 학점": "18"}),
        (S.resume_hwpx(), "hwpx", S.RESUME_LLM, {"희망 연봉": "3200"}),
    ):
        pdf = form_layouts.base_pdf(data, kind)
        fields = form_layouts.from_structure(form_upload.to_fields(llm["fields"], None), pdf)
        rows = [(f, values.get(f["label"], "")) for f in fields]
        with pymupdf.open(stream=doc_export.render_overlay(pdf, "t", rows), filetype="pdf") as out:
            page = out[0]
            words = page.get_text("words")
            x = lambda t: next(w[0] for w in words if w[4] == t)
            text = page.get_text()
            if kind == "pdf":
                unit_x = max(w[0] for w in words if w[4] == "학점")  # 칸 이름 "이수 학점" 말고 값 칸의 단위
                assert x("4.1") < x("/") and x("18") < unit_x
            else:
                assert "3200만원" in text


def test_official_form_keeps_every_field_ai_read():
    """실제 자립수당 신청서 PDF: 표 구조로 나눠도 AI 가 읽은 칸(통지방법 · 동의 · 유의사항 등 자리를 못 찾은 칸 포함)을 버리지 않는다"""
    import json
    from pathlib import Path

    from app import form_upload

    root = Path(__file__).resolve().parents[3] / "data" / "forms"
    spec = json.loads((root / "P001-apply.json").read_text(encoding="utf-8"))
    raw = [{"label": f["label"], "section": f.get("section"), "type": f.get("input_type", "text"), "options": f.get("options")}
           for f in spec["fields"]]
    pdf = (root / "P001-apply.pdf").read_bytes()
    llm = form_upload.to_fields(raw, None)
    old = form_layouts.attach_boxes(llm, pdf)
    new = form_layouts.from_structure(llm, pdf)
    writable = lambda fs: [f for f in fs if f["value_source"] in ("profile", "user")]
    assert len(writable(new)) >= len(writable(old))
    labels = {f["label"] for f in new}
    assert {"통지방법", "동의함/동의하지 않음", "유의사항 확인"} <= labels
    # 대리인 묶음의 "성명" 칸이 있으니 "대리인 성명" 을 따로 또 묻지 않는다
    assert "대리인 성명" not in labels and any(f["label"] == "성명" and f.get("section") == "대리인" for f in new)


def test_broken_table_reading_falls_back(monkeypatch):
    """표 구조 읽기가 실패해도 올리기는 된다 (칸 이름으로 자리 찾기로)"""
    def boom(_pdf):
        raise ValueError("bad pdf")
    monkeypatch.setattr(form_layouts, "slots", boom)
    assert form_layouts.from_structure([], hwpx_render.render(build())) is None


def test_overlay_failure_still_downloads_pdf(api, db_session, doc_llm, monkeypatch):
    """원본 규격 그리기가 실패해도 PDF 받기는 된다 (칸 이름 · 내용 표 PDF 로)"""
    from app import doc_export

    user = make_user(db_session, name="김민수", phone="01012345678")
    doc_llm.form = FORM
    d = _up(api, user, build(), file_name="신청서.hwpx").json()
    _fill_and_export(api, user, d)

    def boom(*_a, **_k):
        raise RuntimeError("draw failed")
    monkeypatch.setattr(doc_export, "render_overlay", boom)
    r = api.get(f"/api/documents/{d['document_id']}/file?user_id={user.id}&format=pdf")
    assert r.status_code == 200 and r.content[:4] == b"%PDF"
