"""원본 양식 위에 채우기: 검수한 서버 양식(data/forms)의 칸 모양별 (점 날짜 · 찍힌 연도 · 괄호 전화 · ○ 표시 · 예시 글자 덮기)"""

import json
from pathlib import Path

import pymupdf
import pytest

from app import doc_export, doc_spec
from formguide.schema import FormSpec

FORMS = Path(__file__).resolve().parents[3] / "data" / "forms"


def _render(form_id: str, values: dict[str, str]):
    spec = FormSpec.model_validate_json((FORMS / f"{form_id}.json").read_text(encoding="utf-8"))
    rows = [(f, values.get(f["field_id"], "")) for f in doc_spec.from_form_spec(spec)]
    out = pymupdf.open(stream=doc_export.render_overlay(FORMS / f"{form_id}.pdf", spec.title, rows), filetype="pdf")
    return spec, out


def _box(spec, field_id):
    return next(f.box for f in spec.fields if f.id == field_id)


def _words_in(page, rect):
    return [w[4] for w in page.get_text("words", clip=pymupdf.Rect(rect))]


pytestmark = pytest.mark.skipif(not (FORMS / "child-extend-care.pdf").exists(), reason="양식 PDF 없음")


def test_dot_date_fills_before_each_dot():
    spec, out = _render("child-extend-care", {"extension_start": "2027-01-01", "extension_end": "2028-12-31"})
    assert out.page_count == 1  # 별지 없이 원본에
    start = _words_in(out[0], _box(spec, "extension_start")["rect"])
    assert {"2027", "1"} <= set(start)
    assert {"2028", "12", "31"} <= set(_words_in(out[0], _box(spec, "extension_end")["rect"]))


def test_printed_year_is_not_written_again():
    spec, out = _render("jindo-duplicate-confirm", {"date": "2026-11-27"})
    words = _words_in(out[0], _box(spec, "date")["rect"])
    assert "11" in words and "27" in words
    assert words.count("2026") == 0  # "2026년" 이 이미 찍혀 있음


def test_phone_in_parens_template():
    spec, out = _render("lease-leaver-apply", {"applicant_mobile": "010-1234-5678"})
    words = _words_in(out[0], _box(spec, "applicant_mobile")["rect"])
    assert {"1234", "5678"} <= set(words)
    assert out.page_count == 1


def test_circle_marks_for_word_choices():
    spec, out = _render("mohw-didim-plan", {"has_sponsor": "있음"})
    box = _box(spec, "has_sponsor")
    ovals = [d for d in out[0].get_drawings() if pymupdf.Rect(d["rect"]).intersects(pymupdf.Rect(box["circles"]["있음"]))]
    assert ovals


def test_cover_example_text():
    spec, out = _render("mohw-aftercare-consent", {"phone": "010-9999-8888"})
    rect = _box(spec, "phone")["rect"]
    assert "010-9999-8888" in "".join(_words_in(out[0], rect))
    # 예시 글자는 덮였다 (글자는 남아 있어도 위에 칸 바탕색 사각형이 그려짐)
    fills = [d for d in out[0].get_drawings() if d.get("fill") and pymupdf.Rect(d["rect"]).intersects(pymupdf.Rect(rect))]
    assert fills


def test_checkbox_options_have_no_commas():
    for path in FORMS.glob("*.json"):
        if path.stem in ("index", "sources", "fill_missing_review") or path.stem.endswith(".extracted"):
            continue
        for f in json.loads(path.read_text(encoding="utf-8"))["fields"]:
            for o in (f.get("box") or {}).get("options") or []:
                assert "," not in o, (path.stem, f["id"], o)
