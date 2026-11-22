"""작성한 서류 → PDF·DOCX (docs/document-ai.md 3.31).

- 원본 양식이 있는 정책 신청서(data/forms/{form_id}.pdf + 칸별 값 자리 box): 원본 양식 위에 작성 내용을 채운다.
  글자가 칸에 다 들어가지 않거나 값 자리를 못 찾은 칸은 "별지 참조"로 두고 마지막에 별지 쪽을 붙인다.
- 원본 양식이 없는 앱 양식: 칸 이름·작성 내용을 표로 정리한 문서로 만든다.
- 민감 칸(주민등록번호·서명)과 공무원 기재란은 비워 둔다 (표 문서에서 서명·주민번호 칸은 빈칸, 공무원 기재란은 "담당자 기재"로 표시).
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
EMPTY_TEXT = {"handwrite": "", "office": "(담당자 기재란)"}  # 서명·주민번호 칸은 빈칸 그대로
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
FONT_NAME = "onroadkr"  # 양식 PDF 에 원래 있는 글꼴 이름과 겹치지 않게 (겹치면 그 글꼴의 일부 글자만 쓰게 됨)
OVERLAY_SIZES = (10, 9, 8, 7)
BOX_INSET = 2.5
SIGN_SIZE = 9
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
            elif state["y"] > MARGIN + 60:
                state["y"] += SECTION_END_GAP  # 묶음이 끝나고 묶음 없는 칸: 앞 묶음에 붙어 보이지 않게 띄운다
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


SECTION_END_GAP = 14  # 묶음 표가 끝난 뒤 묶음 없는 칸 앞 여백 (pt)


def render_pdf(title: str, rows: list[tuple[dict, str]]) -> bytes:
    doc = pymupdf.open()
    _draw_table(doc, title, rows, FOOTER)
    return _to_bytes(doc)


# ---------------- 원본 양식 위에 채우기 ----------------


def has_layout(pdf: Path | bytes | None, rows: list[tuple[dict, str]]) -> bool:
    """원본 양식 위에 채울 수 있는지. pdf 는 정책 신청서 파일 경로 또는 올린 양식의 바탕 PDF 바이트"""
    if not pdf or (isinstance(pdf, Path) and not pdf.is_file()):
        return False
    return any(spec.get("box") for spec, _ in rows)


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
    placed = []
    left = rect.x0
    for number, slot in zip((m.group(1), str(int(m.group(2))), str(int(m.group(3)))), slots, strict=True):
        w = font.text_length(number, fontsize=size)
        if slot.x0 - 3 - w < left + 1:  # 숫자가 앞 칸(항목 칸)이나 앞 단위 글자를 침범한다
            return False
        placed.append((slot.x0 - 3 - w, slot.y1 - slot.height * 0.2, number))
        left = slot.x1
    for x, y, number in placed:
        page.insert_text((x, y), number, fontname=FONT_NAME, fontsize=size, color=INK)
    return True


def _cover_words(page: pymupdf.Page, rect: pymupdf.Rect, only: str | None = None) -> None:
    """칸 안 안내 글자를 칸 바탕색으로 가린다 (only 가 있으면 그 글자만)"""
    paper = _paper(page, rect)
    for w in page.get_text("words", clip=rect):
        if only is None or w[4] == only:
            page.draw_rect(pymupdf.Rect(w[:4]) + (-0.5, -0.5, 0.5, 0.5), color=None, fill=paper, width=0)


def _fill_parens(page: pymupdf.Page, rect: pymupdf.Rect, text: str) -> bool:
    """"(          )" 칸에 값이 길면: 닫는 괄호를 가리고 칸 끝까지 쓴 뒤 값 바로 뒤에 ")" 를 다시 쓴다"""
    words = sorted(page.get_text("words", clip=rect), key=lambda w: w[0])
    opens = [w for w in words if w[4] == "("]
    closes = [w for w in words if w[4] == ")"]
    if len(opens) != 1 or len(closes) != 1 or len(words) != 2 or "\n" in text:
        return False
    font = _font()
    inside = closes[0][0] - opens[0][2] - 4
    if font.text_length(text, fontsize=OVERLAY_SIZES[1]) <= inside:
        return False  # 괄호 안에 충분히 들어간다
    close_w = font.text_length(")", fontsize=OVERLAY_SIZES[0])
    x0 = opens[0][2] + 2
    for size in OVERLAY_SIZES:
        w = font.text_length(text, fontsize=size)
        if x0 + w + 2 + close_w <= rect.x1 and size <= rect.height * 0.85:
            _cover_words(page, rect, only=")")
            baseline = rect.y0 + (rect.height + size * 0.7) / 2
            page.insert_text((x0, baseline), text, fontname=FONT_NAME, fontsize=size, color=INK)
            page.insert_text((x0 + w + 2, baseline), ")", fontname=FONT_NAME, fontsize=size, color=(0, 0, 0))
            return True
    return False


def _check_mark(page: pymupdf.Page, x: float, y: float, size: float = 7.5) -> None:
    """글꼴과 상관없이 선으로 그린 체크 표시. (x, y) 는 가운데"""
    h = size / 2
    page.draw_polyline(
        [(x - h, y), (x - h * 0.25, y + h * 0.8), (x + h, y - h * 0.9)], color=INK, width=1.3,
    )


def _fill_checkbox(
    page: pymupdf.Page, rect: pymupdf.Rect, value: str, options: list[str], marks: dict | None = None,
) -> bool:
    """고른 선택지 앞 "[ ]"·□ 안에 체크 표시. 양식 준비 때 찾아 둔 자리(marks)가 있으면 그 자리,
    없으면 선택지 글자 앞 "["를 찾는다 (선택지 앞에 "["가 없는 글자 — 칸 이름 등 — 는 건너뛴다)"""
    chosen = [p.strip() for p in value.split(",") if p.strip() in options]
    if not chosen:
        return False
    if marks and all(opt in marks for opt in chosen):
        for opt in chosen:
            _check_mark(page, *marks[opt])
        return True
    for opt in chosen:
        mark = None
        for hit in page.search_for(opt, clip=rect + (-2, -2, 2, 2)):
            area = pymupdf.Rect(hit.x0 - 28, hit.y0 - 3, hit.x0 + 1, hit.y1 + 3)
            opens = [b for b in page.search_for("[", clip=area) if b.x1 <= hit.x0 + 1]
            if not opens:
                continue
            left = max(opens, key=lambda b: b.x0)
            closes = [b for b in page.search_for("]", clip=area) if b.x0 >= left.x1 - 0.5]
            right_x = min((b.x0 for b in closes), default=hit.x0)
            mark = ((left.x1 + right_x) / 2, (hit.y0 + hit.y1) / 2)
            break
        if mark is None:
            return False
        _check_mark(page, *mark)
    return True


_UNITS = ("년", "월", "일", "세")
_NUM_RE = re.compile(r"\d+")


def _fill_units(page: pymupdf.Page, rect: pymupdf.Rect, value: str, size: float) -> bool:
    """양식에 단위 글자가 미리 있는 칸 ("    년   월   일", "   년  월 ~   년  월", "만    세"):
    값의 숫자를 단위 글자 앞에 하나씩 맞춰 쓴다. 숫자 개수와 단위 개수가 다르면 False"""
    nums = _NUM_RE.findall(value)
    slots = sorted(
        ((hit, unit) for unit in _UNITS for hit in page.search_for(unit, clip=rect)),
        key=lambda x: (round(x[0].y0 / 4), x[0].x0),
    )
    if not nums or len(nums) != len(slots):
        return False
    font = _font()
    placed = []
    left = rect.x0
    words = page.get_text("words", clip=rect)
    for num, (slot, unit) in zip(nums, slots, strict=True):
        text = str(int(num)) if unit in ("월", "일") else num
        # 앞 단위 글자뿐 아니라 사이의 "~" 같은 글자도 피한다
        before = [w[2] for w in words if w[2] <= slot.x0 + 0.5 and abs(w[1] - slot.y0) < slot.height]
        left = max([left, *before])
        room = slot.x0 - 2 - max(left, rect.x0)
        s = size
        while font.text_length(text, fontsize=s) > room and s > 7:
            s -= 0.5
        width = font.text_length(text, fontsize=s)
        if width > room:
            return False
        placed.append((slot.x0 - 2 - width, slot.y1 - slot.height * 0.2, text, s))
        left = slot.x1
    for x, y, text, s in placed:
        page.insert_text((x, y), text, fontname=FONT_NAME, fontsize=s, color=INK)
    return True


def _paper(page: pymupdf.Page, rect: pymupdf.Rect) -> tuple[float, float, float]:
    """칸 바탕색 (칸 모서리 안쪽 한 점). 회색 칸의 안내 글자를 가릴 때 같은 색으로"""
    spot = pymupdf.Rect(rect.x0 + 0.5, rect.y0 + 0.5, rect.x0 + 2.5, rect.y0 + 2.5)
    try:
        pix = page.get_pixmap(clip=spot, alpha=False)
        r, g, b = pix.pixel(0, 0)[:3]
        return r / 255, g / 255, b / 255
    except Exception:  # 바탕색을 못 읽으면 흰색
        return 1.0, 1.0, 1.0


def _free_part(page: pymupdf.Page, rect: pymupdf.Rect) -> pymupdf.Rect:
    """칸 안에 이미 글자("(          )", "만    세")가 있으면 그 글자를 피한 가장 넓은 빈 곳"""
    words = sorted(page.get_text("words", clip=rect), key=lambda w: w[0])
    if not words:
        return rect
    # "(          )": 괄호 안
    opens = [w for w in words if w[4].endswith("(")]
    closes = [w for w in words if w[4].startswith(")")]
    if opens and closes and closes[-1][0] - opens[0][2] >= 16:
        return pymupdf.Rect(opens[0][2] + 2, rect.y0, closes[-1][0] - 2, rect.y1)
    edges = [rect.x0] + [v for w in words for v in (w[0], w[2])] + [rect.x1]
    gaps = [(edges[i + 1] - edges[i], edges[i], edges[i + 1]) for i in range(0, len(edges) - 1, 2)]
    width, x0, x1 = max(gaps)
    if width < 16:
        return rect
    return pymupdf.Rect(x0 + 2, rect.y0, x1 - 2, rect.y1)


def _fill_box(page: pymupdf.Page, rect: pymupdf.Rect, text: str, align: str = "left") -> bool:
    """칸에 들어가면 True. 한 줄이면 세로 가운데, 여러 줄이면 위에서부터. 글자 크기를 줄여 가며 맞춘다.
    align="right": 칸 오른쪽 끝에 붙여 쓴다 (예: "____은행" 의 은행 이름)"""
    font = _font()
    if rect.is_empty or rect.width < 4:
        return False
    for size in OVERLAY_SIZES:
        if size > rect.height * 0.85 and size > OVERLAY_SIZES[-1]:
            continue  # 칸 높이보다 큰 글자는 위아래 칸으로 넘친다
        width = font.text_length(text, fontsize=size)
        if "\n" not in text and width <= rect.width:
            baseline = rect.y0 + (rect.height + size * 0.7) / 2
            x = rect.x1 - width if align == "right" else rect.x0
            page.insert_text((x, baseline), text, fontname=FONT_NAME, fontsize=size, color=INK)
            return True
        if page.insert_textbox(rect, text, fontname=FONT_NAME, fontsize=size, color=INK, lineheight=1.25) >= 0:
            return True
    return False


def render_overlay(pdf: Path | bytes, title: str, rows: list[tuple[dict, str]]) -> bytes:
    doc = pymupdf.open(stream=pdf, filetype="pdf") if isinstance(pdf, bytes) else pymupdf.open(pdf)
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
        if box.get("how") == "confirm":  # 유의사항 확인 열: 항목마다 모두 체크
            for x, y in box.get("spots") or []:
                _check_mark(page, x, y)
            continue
        if box.get("suffix") and text.replace(" ", "").endswith(box["suffix"]):
            text = text.replace(" ", "")[: -len(box["suffix"])] or text  # "국민은행" → 양식의 "은행" 앞에 "국민"
        if box.get("sign_mark"):  # 양식에 "(서명 또는 인)" 글자가 없으면 이름 오른쪽에 그려 둔다
            mark_w = _font().text_length(box["sign_mark"], fontsize=SIGN_SIZE)
            baseline = rect.y0 + (rect.height + SIGN_SIZE * 0.7) / 2
            page.insert_text((rect.x1 - mark_w, baseline), box["sign_mark"], fontname=FONT_NAME,
                             fontsize=SIGN_SIZE, color=(0.2, 0.2, 0.2))
            rect = pymupdf.Rect(rect.x0, rect.y0, rect.x1 - mark_w - 6, rect.y1)
        if box.get("how") == "checkbox":
            options = spec.get("options") or box.get("options") or []
            if not _fill_checkbox(page, pymupdf.Rect(box["rect"]), text, options, box.get("marks")):
                leftovers.append((spec, text))
            continue
        is_date = box.get("how") == "date_line" or (template and all(u in template for u in ("년", "월", "일")))
        if is_date and _fill_date(page, rect, text, OVERLAY_SIZES[0]):
            continue
        whole = _DATE_RE.search(text)
        if is_date and whole and all(u in template for u in ("년", "월", "일")):
            # 단위 글자 앞이 좁아 숫자가 항목 칸으로 넘친다: 안내 글자를 가리고 "2004년 2월 1일" 로 쓴다
            _cover_words(page, rect)
            if _fill_box(page, rect, f"{whole.group(1)}년 {int(whole.group(2))}월 {int(whole.group(3))}일"):
                continue
        if "(" in template and ")" in template and _fill_parens(page, rect, text):
            continue
        if (is_date or any(u in template for u in _UNITS)) and _fill_units(page, rect, text, OVERLAY_SIZES[0]):
            continue
        if any(u in template for u in _UNITS) and not _NUM_RE.sub("", template).strip(" 년월일세만~-.()/"):
            # 단위 글자 자리에 숫자가 안 들어가면 ("2020.03 ~ 2023.02" 인데 칸이 좁다): 안내 글자를 가리고 값만 쓴다
            _cover_words(page, rect)
            template = ""
        if template:  # 안내 글자를 덮지 않게 빈 곳에
            rect = _free_part(page, rect)
        if not _fill_box(page, rect, text, box.get("align", "left")):
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
            else:
                d.add_paragraph()  # 묶음이 끝나고 묶음 없는 칸: 앞 표와 떼어 놓는다
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
