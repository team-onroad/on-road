"""작성한 서류 → PDF·DOCX (docs/document-ai.md 3.31).

- 원본 양식이 있는 정책 신청서(data/forms/{form_id}.pdf + 칸별 값 자리 box): 원본 양식 위에 작성 내용을 채운다.
  글자가 칸에 다 들어가지 않거나 값 자리를 못 찾은 칸은 "별지 참조"로 두고 마지막에 별지 쪽을 붙인다.
- 원본 양식이 없는 앱 양식: 칸 이름·작성 내용을 표로 정리한 문서로 만든다.
- 민감 칸(주민등록번호·계좌·서명)과 공무원 기재란은 비워 둔다 (표 문서에서는 "직접 작성"·"담당자 기재"로 표시).
- DOCX 는 원본 배치를 옮길 수 없어 항상 표 문서로 만든다.
"""

import io
import re
from functools import lru_cache
from pathlib import Path

import pymupdf
from docx import Document as DocxDocument
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Pt, RGBColor

WRITABLE = ("profile", "user")
EMPTY_TEXT = {"handwrite": "(양식에 직접 작성)", "office": "(담당자 기재란)"}
FOOTER = "온로드 문서 도우미로 작성한 사본입니다. 제출 전 공식 양식과 내용을 확인해 주세요."
ATTACHMENT_FOOTER = "온로드 문서 도우미로 작성한 별지입니다. 신청서와 함께 제출해 주세요."
SEE_ATTACHMENT = "별지 참조"

PAGE_W, PAGE_H = pymupdf.paper_size("a4")
MARGIN = 50
LABEL_W = 130
GAP = 10
FONT_SIZE = 10.5
LINE_H = FONT_SIZE * 1.55
PAD = 6
GRAY = (0.45, 0.45, 0.45)
LINE = (0.75, 0.75, 0.75)
INK = (0.05, 0.1, 0.35)  # 원본 양식 위에 쓰는 글자 (볼펜 느낌의 진한 남색)
FONT_NAME = "kr"
OVERLAY_SIZES = (10, 9, 8, 7)
BOX_INSET = 2.5
_DATE_RE = re.compile(r"(\d{4})\D+(\d{1,2})\D+(\d{1,2})")


@lru_cache
def _font() -> pymupdf.Font:
    return pymupdf.Font("cjk")  # PyMuPDF 내장 한글 지원 글꼴 (Droid Sans Fallback)


def _use_font(page: pymupdf.Page) -> None:
    page.insert_font(fontname=FONT_NAME, fontbuffer=_font().buffer)


def _wrap(text: str, width: float, size: float) -> list[str]:
    font = _font()
    lines: list[str] = []
    for para in text.split("\n") or [""]:
        line = ""
        for ch in para:
            if font.text_length(line + ch, fontsize=size) > width and line:
                lines.append(line)
                line = ch.lstrip() if ch == " " else ch
            else:
                line += ch
        lines.append(line)
    return lines


def _display(spec: dict, value: str) -> tuple[str, bool]:
    """(보여 줄 글, 회색 여부)"""
    if spec["value_source"] in EMPTY_TEXT:
        return EMPTY_TEXT[spec["value_source"]], True
    return value.strip() or "-", not value.strip()


# ---------------- 표 문서 (앱 양식, 별지) ----------------


def _draw_table(doc: pymupdf.Document, title: str, rows: list[tuple[dict, str]], footer: str) -> None:
    """doc 끝에 새 쪽을 붙여 제목 + 칸 이름/내용 표를 그린다"""
    state = {"page": None, "y": 0.0}
    first = doc.page_count

    def new_page() -> None:
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        _use_font(page)
        state["page"], state["y"] = page, MARGIN

    def text_at(x: float, y: float, s: str, size: float = FONT_SIZE, color=(0, 0, 0)) -> None:
        state["page"].insert_text((x, y + size), s, fontname=FONT_NAME, fontsize=size, color=color)

    new_page()
    title_size = 17
    tw = _font().text_length(title, fontsize=title_size)
    text_at((PAGE_W - tw) / 2, state["y"], title, title_size)
    state["y"] += title_size + 22

    value_x = MARGIN + LABEL_W + GAP
    value_w = PAGE_W - MARGIN - value_x
    bottom = PAGE_H - MARGIN - 20
    section = object()
    for spec, value in rows:
        if spec.get("section") != section:
            section = spec.get("section")
            if section:
                if state["y"] + 30 > bottom:
                    new_page()
                state["y"] += 6
                text_at(MARGIN, state["y"], section, 12)
                state["y"] += 12 + 8
        shown, gray = _display(spec, value)
        label_lines = _wrap(spec["label"], LABEL_W - PAD, FONT_SIZE)
        value_lines = _wrap(shown, value_w - PAD * 2, FONT_SIZE)
        n = max(len(label_lines), len(value_lines))
        if state["y"] + min(n, 3) * LINE_H + PAD * 2 > bottom:
            new_page()
        # 긴 칸은 페이지를 넘겨 이어서 그린다
        while label_lines or value_lines:
            fit = max(1, int((bottom - state["y"] - PAD * 2) // LINE_H))
            part_l, label_lines = label_lines[:fit], label_lines[fit:]
            part_v, value_lines = value_lines[:fit], value_lines[fit:]
            height = max(len(part_l), len(part_v)) * LINE_H + PAD * 2
            page, y = state["page"], state["y"]
            page.draw_rect(pymupdf.Rect(MARGIN, y, PAGE_W - MARGIN, y + height), color=LINE, width=0.6)
            page.draw_line((value_x - GAP / 2, y), (value_x - GAP / 2, y + height), color=LINE, width=0.6)
            for i, s in enumerate(part_l):
                text_at(MARGIN + PAD, y + PAD + i * LINE_H, s, color=(0.15, 0.15, 0.15))
            for i, s in enumerate(part_v):
                text_at(value_x + PAD - GAP / 2, y + PAD + i * LINE_H, s, color=GRAY if gray else (0, 0, 0))
            state["y"] += height
            if label_lines or value_lines:
                new_page()

    total = doc.page_count - first
    for i in range(first, doc.page_count):
        doc[i].insert_text(
            (MARGIN, PAGE_H - MARGIN / 2), f"{footer}  ({i - first + 1}/{total})",
            fontname=FONT_NAME, fontsize=8, color=GRAY,
        )


def _to_bytes(doc: pymupdf.Document) -> bytes:
    doc.subset_fonts()
    data = doc.tobytes(garbage=3, deflate=True)
    doc.close()
    return data


def render_pdf(title: str, rows: list[tuple[dict, str]]) -> bytes:
    doc = pymupdf.open()
    _draw_table(doc, title, rows, FOOTER)
    return _to_bytes(doc)


# ---------------- 원본 양식 위에 채우기 ----------------


def has_layout(pdf_path: Path | None, rows: list[tuple[dict, str]]) -> bool:
    return pdf_path is not None and pdf_path.is_file() and any(spec.get("box") for spec, _ in rows)


def _fill_date(page: pymupdf.Page, rect: pymupdf.Rect, value: str, size: float) -> bool:
    """"    년   월   일" 칸: 숫자를 년·월·일 글자 앞에 맞춰 쓴다"""
    m = _DATE_RE.search(value)
    if not m:
        return False
    slots = []
    for unit in ("년", "월", "일"):
        hits = page.search_for(unit, clip=rect)
        if not hits:
            return False
        slots.append(hits[0])
    font = _font()
    for number, slot in zip((m.group(1), str(int(m.group(2))), str(int(m.group(3)))), slots, strict=True):
        w = font.text_length(number, fontsize=size)
        baseline = slot.y1 - slot.height * 0.2
        page.insert_text((slot.x0 - 3 - w, baseline), number, fontname=FONT_NAME, fontsize=size, color=INK)
    return True


def _fill_box(page: pymupdf.Page, rect: pymupdf.Rect, text: str) -> bool:
    """칸에 들어가면 True. 한 줄이면 세로 가운데, 여러 줄이면 위에서부터. 글자 크기를 줄여 가며 맞춘다"""
    font = _font()
    for size in OVERLAY_SIZES:
        if "\n" not in text and font.text_length(text, fontsize=size) <= rect.width:
            baseline = rect.y0 + (rect.height + size * 0.7) / 2
            page.insert_text((rect.x0, baseline), text, fontname=FONT_NAME, fontsize=size, color=INK)
            return True
        if page.insert_textbox(rect, text, fontname=FONT_NAME, fontsize=size, color=INK, lineheight=1.25) >= 0:
            return True
    return False


def render_overlay(pdf_path: Path, title: str, rows: list[tuple[dict, str]]) -> bytes:
    doc = pymupdf.open(pdf_path)
    fonted: set[int] = set()
    leftovers: list[tuple[dict, str]] = []
    for spec, value in rows:
        text = value.strip()
        if spec["value_source"] not in WRITABLE or not text:
            continue
        box = spec.get("box")
        if not box or not 0 <= box.get("page", -1) < doc.page_count:
            leftovers.append((spec, text))
            continue
        page = doc[box["page"]]
        if page.number not in fonted:
            _use_font(page)
            fonted.add(page.number)
        rect = pymupdf.Rect(box["rect"]) + (BOX_INSET, BOX_INSET, -BOX_INSET, -BOX_INSET)
        template = box.get("template_text") or ""
        if template and all(u in template for u in ("년", "월", "일")) and _fill_date(page, rect, text, OVERLAY_SIZES[0]):
            continue
        if not _fill_box(page, rect, text):
            _fill_box(page, rect, SEE_ATTACHMENT)
            leftovers.append((spec, text))
    if leftovers:
        _draw_table(doc, f"{title} (별지)", leftovers, ATTACHMENT_FOOTER)
    return _to_bytes(doc)


# ---------------- DOCX ----------------


def _korean_font(run, name: str = "맑은 고딕") -> None:
    run.font.name = name
    run._element.get_or_add_rPr().get_or_add_rFonts().set(qn("w:eastAsia"), name)


def render_docx(title: str, rows: list[tuple[dict, str]]) -> bytes:
    d = DocxDocument()
    head = d.add_paragraph()
    head.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = head.add_run(title)
    run.bold = True
    run.font.size = Pt(17)
    _korean_font(run)

    section = object()
    table = None
    for spec, value in rows:
        if spec.get("section") != section:
            section = spec.get("section")
            if section:
                p = d.add_paragraph()
                r = p.add_run(section)
                r.bold = True
                r.font.size = Pt(12)
                _korean_font(r)
                table = None
        if table is None:
            table = d.add_table(rows=0, cols=2)
            table.style = "Table Grid"
        shown, gray = _display(spec, value)
        cells = table.add_row().cells
        for cell, text, is_gray, bold in ((cells[0], spec["label"], False, True), (cells[1], shown, gray, False)):
            cell.paragraphs[0].text = ""
            for i, line in enumerate(text.split("\n")):
                para = cell.paragraphs[0] if i == 0 else cell.add_paragraph()
                r = para.add_run(line)
                r.bold = bold
                r.font.size = Pt(10.5)
                _korean_font(r)
                if is_gray:
                    r.font.color.rgb = RGBColor(0x73, 0x73, 0x73)

    foot = d.add_paragraph()
    r = foot.add_run(FOOTER)
    r.font.size = Pt(8)
    r.font.color.rgb = RGBColor(0x73, 0x73, 0x73)
    _korean_font(r)
    buf = io.BytesIO()
    d.save(buf)
    return buf.getvalue()
