"""양식 글자 다시 그리기 (formguide.redraw)."""

import pymupdf

from formguide.layout import locate, save_layout_pdf
from formguide.redraw import has_broken_fonts, redraw_text
from formguide.tests.sample_form import make_form
from formguide.tests.test_layout import FIELDS


def _with_preset_check(path):
    """빈 양식에 미리 그려진 체크 표시 (작은 흰 네모 + 짧은 선 2개)"""
    doc = pymupdf.open(path)
    page = doc[0]
    page.draw_rect(pymupdf.Rect(400, 40, 408, 48), color=None, fill=(1, 1, 1))
    page.draw_polyline([(401.5, 44), (403.5, 46.5), (406.5, 41.5)], color=(0, 0, 0), width=0.8)
    out = path.with_name("checked.pdf")
    doc.save(out)
    return out


def _small_strokes(path):
    page = pymupdf.open(path)[0]
    return [d for d in page.get_drawings() if d["type"] == "s" and d["rect"].width < 8 and d["rect"].height < 8]


def test_redraw_keeps_text_and_lines(tmp_path):
    src = make_form(tmp_path / "form.pdf")
    dst = tmp_path / "clean.pdf"
    n = redraw_text(src, dst)

    before, after = pymupdf.open(src)[0], pymupdf.open(dst)[0]
    assert n > 50
    assert "".join(after.get_text().split()) == "".join(before.get_text().split())
    assert len(after.get_drawings()) == len(before.get_drawings())  # 표 선은 그대로
    assert {f[3] for f in after.get_fonts()} != {f[3] for f in before.get_fonts()}


def test_layout_same_after_redraw(tmp_path):
    src = make_form(tmp_path / "form.pdf")
    dst = tmp_path / "clean.pdf"
    redraw_text(src, dst)
    a, b = locate(src, FIELDS), locate(dst, FIELDS)
    assert a.keys() == b.keys()
    assert all(a[k]["how"] == b[k]["how"] for k in a)
    assert b["seizure"]["options"] == ["네", "아니오"]


def test_preset_check_marks_removed(tmp_path):
    src = _with_preset_check(make_form(tmp_path / "form.pdf"))
    assert len(_small_strokes(src)) == 1
    dst = tmp_path / "clean.pdf"
    redraw_text(src, dst)
    assert _small_strokes(dst) == []


def test_save_layout_pdf_redraw_option(tmp_path):
    src = make_form(tmp_path / "form.pdf")
    out = tmp_path / "forms"
    out.mkdir()
    assert has_broken_fonts(src) is False
    copied = save_layout_pdf(src, out, "A")  # 자동: 깨진 글꼴이 없으면 그대로 복사
    assert copied.read_bytes() == src.read_bytes()
    redrawn = save_layout_pdf(src, out, "B", redraw=True)
    assert redrawn.read_bytes() != src.read_bytes()
    assert not (out / "B.redraw.pdf").exists()
