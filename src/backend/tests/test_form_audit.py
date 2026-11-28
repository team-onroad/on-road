"""양식 전수 검수 (2026-11-29): 모든 서버 양식을 사용자처럼 만들기 → 칸 채우기 → 검사 → 내보내기 → PDF 받기,
그리고 검수에서 찾은 칸 모양(대시로 나뉜 번호 칸 · 칸 이름이 든 괄호 · 찍힌 연도 · 안내 글자 위 빈 줄)과 칸 검사"""

import json
from pathlib import Path

import pymupdf
import pytest

from app import ai_content, doc_export, doc_spec
from app.services.writer import _field_warnings
from formguide.schema import FormSpec
from tests.factories import make_policy, make_user
from tests.test_writer_api import (  # noqa: F401  (fixture)
    _create,
    doc_llm,
    sample_value,
)

FORMS = Path(__file__).resolve().parents[3] / "data" / "forms"
pytestmark = pytest.mark.skipif(not (FORMS / "index.json").exists(), reason="양식 없음")


def _form_ids() -> list[str]:
    return sorted(p.stem for p in FORMS.glob("*.pdf") if (FORMS / f"{p.stem}.json").exists())


def _render(form_id: str, values: dict[str, str]):
    spec = FormSpec.model_validate_json((FORMS / f"{form_id}.json").read_text(encoding="utf-8"))
    rows = [(f, values.get(f["field_id"], "")) for f in doc_spec.from_form_spec(spec)]
    out = pymupdf.open(stream=doc_export.render_overlay(FORMS / f"{form_id}.pdf", spec.title, rows), filetype="pdf")
    return spec, out


def _box(spec, field_id):
    return next(f.box for f in spec.fields if f.id == field_id)


def _words(page, rect):
    return [w[4] for w in page.get_text("words", clip=pymupdf.Rect(rect))]


# ------------------------------------------------------------------ 칸 모양


def test_split_phone_boxes_get_each_part():
    """"□□□-□□□□-□□□□" 칸: 덩이마다 대시 사이 자리에"""
    spec, out = _render("jindo-finance-consent", {"consenter1_phone": "010-1234-5678"})
    box = pymupdf.Rect(_box(spec, "consenter1_phone")["rect"])
    words = out[0].get_text("words", clip=box)
    d1, d2 = sorted((w for w in words if w[4] == "-"), key=lambda w: w[0])
    digits = sorted((w for w in words if w[4] != "-"), key=lambda w: w[0])
    assert "".join(w[4] for w in digits) == "01012345678"
    assert [sum(1 for w in digits if lo <= w[0] < hi) for lo, hi in ((0, d1[0]), (d1[2], d2[0]), (d2[2], 999))] == [3, 4, 4]
    assert out.page_count == 2  # 별지 없음


def test_phone_written_inside_labelled_parens():
    """"(전화 :      )": 괄호 안에 쓰고 괄호 밖으로 나가지 않는다"""
    spec, out = _render("mohw-account-change", {"applicant_phone": "010-1234-5678"})
    page = out[0]
    rect = pymupdf.Rect(_box(spec, "applicant_phone")["rect"])
    phone = next(w for w in page.get_text("words", clip=rect) if "010-1234-5678" in w[4])
    opener = next(w for w in page.get_text("words", clip=rect) if w[4].startswith("(전화"))
    close = max((w for w in page.get_text("words", clip=rect) if w[4] == ")"), key=lambda w: w[0])  # 값 뒤에 다시 쓴 괄호
    assert opener[2] < phone[0] and phone[2] <= close[0] + 0.5


def test_mobile_phone_with_printed_zero():
    """"휴대전화(0   )   -" : 찍힌 0 뒤에 10, 가운데, 끝자리"""
    spec, out = _render("jeonse-rent-apply", {"mobile_phone": "010-1234-5678"})
    words = _words(out[0], _box(spec, "mobile_phone")["rect"])
    assert {"10", "1234", "5678"} <= set(words) and "010-1234-5678" not in words


def test_dot_date_after_printed_year():
    """"2026.  .  ." : 찍힌 연도는 건너뛰고 월 · 일만"""
    spec, out = _render("jindo-privacy-consent", {"date": "2026.11.29"})
    words = _words(out[1], _box(spec, "date")["rect"])
    assert "11" in words and "29" in words and words.count("2026.") == 1
    assert out.page_count == 2


def test_value_goes_above_printed_hint():
    """안내 글자 "(첨부하는 증명자료 참조)" 가 칸 아래쪽에 있으면 그 위 빈 줄에 크게 쓴다"""
    text = "대학 재학 중이라 졸업할 때까지 보호가 필요합니다"
    spec, out = _render("child-extend-care", {"extension_reason": text})
    hint = next(w for w in out[0].get_text("words") if w[4] == "(첨부하는")
    written = [w for w in out[0].get_text("words") if w[4] in text.split()]
    assert written and all(w[3] <= hint[1] + 0.5 for w in written)


def test_no_mid_word_break_for_names_and_numbers():
    """좁은 칸의 띄어쓰기 없는 값은 한 줄로 (글자 중간 줄바꿈 X)"""
    spec, out = _render("welfare-apply", {"family1_phone": "010-1234-5678", "family1_name": "남궁민수"})
    assert "010-1234-5678" in _words(out[0], _box(spec, "family1_phone")["rect"])
    assert "남궁민수" in _words(out[0], _box(spec, "family1_name")["rect"])


def test_attachment_wraps_at_spaces():
    lines = doc_export._wrap("경상남도 창원시 마산회원구 내서읍 중리상곡로 123, 한빛아파트 104동 1502호", 200, 10.5)
    assert all(not line.startswith(("502호", "02호")) for line in lines)
    assert "1502호" in " ".join(lines).split()


def test_boxes_stay_out_of_label_cells():
    """칸 왼쪽이 칸 이름 칸(세로선) 안으로 들어가 이름 글자 위에 겹쳐 쓰던 칸"""
    for form_id, field_id, line_x in [
        ("lease-leaver-apply", "applicant_name", 194), ("lease-leaver-apply", "facility_name", 194),
        ("lease-leaver-apply", "desired_move_in_date", 272), ("lease-orphan-apply", "applicant_name", 194),
        ("mohw-account-change", "applicant_address", 232), ("mohw-account-change", "current_bank", 232),
        ("mohw-didim-withdraw", "deposit_bank_name", 340.7), ("mohw-didim-withdraw", "deposit_account_number", 340.7),
        ("jeonse-rent-apply", "name", 143),
    ]:
        spec = FormSpec.model_validate_json((FORMS / f"{form_id}.json").read_text(encoding="utf-8"))
        assert _box(spec, field_id)["rect"][0] > line_x, (form_id, field_id)


def test_office_fields_are_not_required():
    for path in FORMS.glob("*.json"):
        if path.stem in ("index", "sources", "fill_missing_review") or path.stem.endswith(".extracted"):
            continue
        for f in json.loads(path.read_text(encoding="utf-8"))["fields"]:
            if f["value_source"] == "office":
                assert not f.get("required"), (path.stem, f["id"])


# ------------------------------------------------------------------ 칸 검사


@pytest.mark.parametrize(("label", "input_type", "value", "bad"), [
    ("생년월일", "date", "2007.02.30", True),
    ("생년월일", "date", "2007.13.01", True),
    ("생년월일", "date", "2099.01.01", True),
    ("생년월일", "date", "2007.03.15", False),
    ("신청일", "date", "2026.11.29", False),
    ("만 나이", "number", "150", True),
    ("만 나이", "number", "24", False),
    ("계획연도", "number", "27", True),
    ("계획연도", "number", "2027", False),
    ("월세", "number", "300000", False),
])
def test_value_checks(label, input_type, value, bad):
    spec = {"label": label, "input_type": input_type, "value_source": "user", "options": None, "essay": False,
            "profile_key": None}
    assert bool(_field_warnings(spec, value)) is bad


# ------------------------------------------------------------------ 사용자 흐름 (모든 양식)


def _value(f: dict) -> str:
    label = f["label"].replace(" ", "")
    if f.get("options"):
        return f["options"][0]
    t = f["input_type"]
    if t == "date":
        return "2005.03.15" if "생년월일" in label else "2026.11.20"
    if t == "phone":
        return "02-123-4567" if "유선" in label or "집" in label else "010-1234-5678"
    if t == "account":
        return "110-123-456789"
    if t == "number":
        if "나이" in label or "연령" in label:
            return "24"
        if label.endswith("연도"):
            return "2027"
        return "300000"
    return sample_value(f)


def _open(api, db_session, user, form_id):
    meta = ai_content.form_meta(form_id) or {}
    body = {"user_id": str(user.id), "source_type": "policy_form", "form_id": form_id}
    if meta.get("scope") != "common":
        keys = [k for k, links in ai_content._forms_index()["by_policy"].items() if any(x["form_id"] == form_id for x in links)]
        own = json.loads((FORMS / f"{form_id}.json").read_text(encoding="utf-8"))["policy_key"]
        from db.models import Policy

        key = keys[0] if keys else own
        policy = db_session.query(Policy).filter_by(policy_key=key).one_or_none() or make_policy(db_session, policy_key=key)
        body["policy_id"] = policy.id
    return api.post("/api/documents", json=body)


@pytest.mark.parametrize("form_id", _form_ids())
def test_every_form_fills_checks_exports_and_downloads(api, db_session, doc_llm, form_id):
    user = make_user(db_session, name="김민지", phone="01012345678")
    res = _open(api, db_session, user, form_id)
    assert res.status_code in (200, 201), res.text
    data = res.json()
    doc_id = data["document_id"]
    for f in data["fields"]:
        if not f["writable"]:
            continue
        body = {"is_completed": True}
        if not f["value"]:
            body["value"] = _value(f)
        r = api.patch(f"/api/documents/{doc_id}/fields/{f['field_id']}?user_id={user.id}", json=body)
        assert r.status_code == 200, (f["field_id"], r.text)
    check = api.get(f"/api/documents/{doc_id}/check?user_id={user.id}").json()
    assert check["ready"], check["problems"]
    res = api.post(f"/api/documents/{doc_id}/export", json={"user_id": str(user.id)})
    assert res.status_code == 200, res.text
    assert res.json()["pdf_layout"] == "original"
    pdf = api.get(f"/api/documents/{doc_id}/file?format=pdf&user_id={user.id}")
    assert pdf.status_code == 200
    out = pymupdf.open(stream=pdf.content, filetype="pdf")
    base = pymupdf.open(FORMS / f"{form_id}.pdf")
    assert out.page_count >= base.page_count
    # 원본 쪽 크기 · 쪽 수 그대로 (서식 유지), 원래 찍힌 글자도 그대로 남아 있다
    for i in range(base.page_count):
        assert out[i].rect == base[i].rect
        before = {w[4] for w in base[i].get_text("words")}
        after = {w[4] for w in out[i].get_text("words")}
        assert before <= after
    docx = api.get(f"/api/documents/{doc_id}/file?format=docx&user_id={user.id}")
    assert docx.status_code == 200 and docx.content[:2] == b"PK"


def test_existing_document_gets_fixed_boxes_and_new_field(api, db_session, doc_llm):
    """검수 전에 만든 서류: 다시 열면 고친 칸 자리 · 새로 찾은 칸(작성일)이 들어온다. 쓴 값은 그대로"""
    from db.models import Document, DocumentField

    user = make_user(db_session)
    data = _open(api, db_session, user, "jindo-privacy-consent").json()
    doc = db_session.get(Document, int(data["document_id"]))
    old = [dict(f) for f in doc.spec if f["field_id"] != "date"]
    old[0] = {**old[0], "box": {**old[0]["box"], "rect": [0, 0, 10, 10]}}
    doc.spec = old
    db_session.query(DocumentField).filter_by(document_id=doc.id, field_id="date").delete()
    db_session.commit()
    again = _open(api, db_session, user, "jindo-privacy-consent").json()
    assert "date" in [f["field_id"] for f in again["fields"]]
    db_session.refresh(doc)
    assert doc.spec[0]["box"]["rect"] != [0, 0, 10, 10]


def test_interview_asks_again_for_values_check_would_block():
    """대화로 채우기: 검사에서 막힐 값은 받지 않고 바로 다시 묻는다 (검사 결과와 같게)"""
    from datetime import date

    from app.services.interview import _normalize

    today = date(2026, 11, 29)
    birth = {"field_id": "b", "label": "생년월일", "input_type": "date"}
    assert _normalize(birth, "2099년 1월 1일", today)[0] is None
    assert _normalize(birth, "2005년 3월 15일", today)[0] == "2005.03.15"
    age = {"field_id": "a", "label": "만 나이", "input_type": "number"}
    assert _normalize(age, "150", today)[0] is None
    assert _normalize(age, "24", today)[0] == "24"


def test_short_answer_fields_are_not_essays():
    """좁은 칸 · '기타' · '여부' · '비고' 칸은 10자 이상 검사를 하지 않는다 (동거여부 '동거' · 해지사유 기타 '이민')"""
    from app.services.writer import _field_warnings

    spec = FormSpec.model_validate_json((FORMS / "welfare-apply.json").read_text(encoding="utf-8"))
    fields = {f["field_id"]: f for f in doc_spec.from_form_spec(spec)}
    assert not fields["family1_living_together"]["essay"]
    assert _field_warnings(fields["family1_living_together"], "동거") == []
    spec = FormSpec.model_validate_json((FORMS / "child-leave-care.json").read_text(encoding="utf-8"))
    assert {f["field_id"]: f for f in doc_spec.from_form_spec(spec)}["leave_reason"]["essay"]
