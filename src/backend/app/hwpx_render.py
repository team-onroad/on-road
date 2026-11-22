"""한글 hwpx 양식 → 같은 표 구조의 PDF.

서버에는 한글 프로그램이 없어서 hwpx 를 그대로 PDF 로 바꿀 수 없다. 대신 hwpx 안에 적힌
쪽 크기 · 표의 줄 · 칸 위치(cellAddr) · 합친 칸(cellSpan) · 칸 크기(cellSz) 로 표를 같은 모양으로 다시 그리고,
칸 글자와 표 밖 문단 글자를 제자리에 쓴다. 이렇게 만든 PDF 위에 formguide/layout.py 가 값 자리를 찾고,
내보낼 때 작성 내용을 채운다 (app/form_layouts.py).

- 따라 하는 것: 칸 배경색, 칸 테두리(변마다 있음/없음 · 굵기 · 색 · 실선/점선/이중선), 글자 크기 · 색 · 굵게,
  가로 정렬(왼쪽/가운데/오른쪽), 칸 안 세로 정렬 (header.xml 의 borderFill · charPr · paraPr)
- 따라 하지 않는 것: 글꼴 모양(한글 기본 글꼴 하나로 쓴다), 그림 · 도형 · 머리말
- 테두리가 없는 변도 표 모양을 찾을 수 있게 아주 가는 흰 선을 그려 둔다 (보이지 않음, 값 자리 찾기용)
- 단위: hwpx 는 1/7200 인치 (HWPUNIT). PDF 포인트 = HWPUNIT / 100
"""

import io
import re
import zipfile
from dataclasses import dataclass
from xml.etree import ElementTree

import pymupdf

FONT_NAME = "onroadform"  # 내보낼 때 쓰는 글꼴 이름(doc_export.FONT_NAME)과 다르게
TEXT_SIZE = 10.0
CELL_SIZES = (9.0, 8.0, 7.0, 6.0)
CELL_PAD = 2.5
LINE_GAP = 1.35
EMPTY_PARA = 6.0  # 빈 문단 높이
BORDER = 0.6
MIN_COL = 12.0
MIN_ROW = 14.0
A4 = (595.0, 842.0)
DEFAULT_MARGIN = 56.0


def _tag(el) -> str:
    return el.tag.rsplit("}", 1)[-1]


def _pt(v: str | None, default: float = 0.0) -> float:
    try:
        return int(v) / 100 if v is not None else default
    except ValueError:
        return default


# ---------------- 스타일 (header.xml) ----------------

_MM = 72 / 25.4
_DASHES = {"DASH": "[3 2] 0", "DOT": "[1 1.5] 0", "DASH_DOT": "[3 1.5 1 1.5] 0", "DASH_DOT_DOT": "[3 1.5 1 1.5 1 1.5] 0",
           "LONG_DASH": "[6 2] 0", "CIRCLE": "[1 1.5] 0"}


@dataclass
class _Side:
    kind: str  # SOLID · DASH · DOUBLE … / NONE
    width: float  # pt
    color: tuple[float, float, float]


@dataclass
class _Box:
    sides: dict[str, _Side]  # left · right · top · bottom
    fill: tuple[float, float, float] | None


@dataclass
class _Char:
    size: float
    color: tuple[float, float, float]
    bold: bool


@dataclass
class _Styles:
    boxes: dict[str, _Box]
    chars: dict[str, _Char]
    aligns: dict[str, str]  # paraPr id → LEFT · CENTER · RIGHT · JUSTIFY …


def _rgb(value: str | None) -> tuple[float, float, float] | None:
    if not value or not re.fullmatch(r"#[0-9A-Fa-f]{6}", value):
        return None
    return tuple(int(value[i: i + 2], 16) / 255 for i in (1, 3, 5))  # type: ignore[return-value]


def _width(value: str | None) -> float:
    m = re.match(r"([\d.]+)\s*mm", value or "")
    return max(float(m.group(1)) * _MM, 0.3) if m else BORDER


def _styles(header: bytes | None) -> _Styles:
    out = _Styles({}, {}, {})
    if not header:
        return out
    try:
        root = ElementTree.fromstring(header)
    except ElementTree.ParseError:
        return out
    for el in root.iter():
        tag = _tag(el)
        if tag == "borderFill":
            sides = {}
            fill = None
            for ch in el.iter():
                t = _tag(ch)
                if t in ("leftBorder", "rightBorder", "topBorder", "bottomBorder"):
                    sides[t[:-6]] = _Side(ch.get("type", "NONE"), _width(ch.get("width")), _rgb(ch.get("color")) or (0, 0, 0))
                elif t == "winBrush":
                    fill = _rgb(ch.get("faceColor"))
            if fill == (1.0, 1.0, 1.0):
                fill = None
            out.boxes[el.get("id", "")] = _Box(sides, fill)
        elif tag == "charPr":
            size = _pt(el.get("height"), TEXT_SIZE * 100) if el.get("height") else TEXT_SIZE
            bold = any(_tag(ch) == "bold" for ch in el)
            out.chars[el.get("id", "")] = _Char(min(max(size, 6.0), 28.0), _rgb(el.get("textColor")) or (0, 0, 0), bold)
        elif tag == "paraPr":
            align = next((ch.get("horizontal") for ch in el if _tag(ch) == "align"), None)
            out.aligns[el.get("id", "")] = align or "LEFT"
    return out


def _first_style(el, styles: _Styles) -> tuple[_Char | None, str]:
    """문단(또는 칸) 안 첫 글자의 글자 모양과 문단 정렬"""
    char, align = None, "LEFT"
    for p in el.iter():
        if _tag(p) != "p":
            continue
        align = styles.aligns.get(p.get("paraPrIDRef", ""), "LEFT")
        for run in p:
            if _tag(run) == "run" and any(_tag(t) == "t" and (t.text or "").strip() for t in run):
                char = styles.chars.get(run.get("charPrIDRef", ""))
                break
        if char is not None:
            break
    return char, align


@dataclass
class _Cell:
    col: int
    row: int
    col_span: int
    row_span: int
    width: float
    height: float
    text: str
    box: str = ""  # borderFillIDRef
    valign: str = "CENTER"
    el: object = None


def _own_text(p) -> str:
    """문단 p 의 글자 (안쪽 표 글자 제외)"""
    out: list[str] = []

    def walk(el):
        for ch in el:
            tag = _tag(ch)
            if tag == "tbl":
                continue
            if tag == "t":
                out.append(ch.text or "")
                for sub in ch:  # <hp:t>앞<hp:tab/>뒤</hp:t>
                    if _tag(sub) in ("tab", "fwSpace", "hwSpace", "nbSpace"):
                        out.append(" ")
                    out.append(sub.tail or "")
            else:
                walk(ch)

    walk(p)
    return "".join(out)


def _cell_text(tc) -> str:
    """칸 안 문단들 (칸 안에 또 표가 있으면 그 글자도 줄로 이어 붙인다)"""
    lines: list[str] = []

    def walk(el):
        for ch in el:
            tag = _tag(ch)
            if tag == "p":
                lines.append(_own_text(ch).rstrip())  # 앞 공백은 남긴다 ("    년    월    일")
                for tbl in (x for x in ch.iter() if _tag(x) == "tbl"):
                    for inner in (x for x in tbl.iter() if _tag(x) == "tc"):
                        t = _cell_text_flat(inner)
                        if t:
                            lines.append(t)
            elif tag != "tbl":
                walk(ch)

    walk(tc)
    while lines and not lines[-1].strip():
        lines.pop()
    while lines and not lines[0].strip():
        lines.pop(0)
    return "\n".join(lines)


def _cell_text_flat(tc) -> str:
    return " ".join(_own_text(p).strip() for p in tc.iter() if _tag(p) == "p" and _own_text(p).strip())


def _cells(tbl) -> list[_Cell]:
    out: list[_Cell] = []
    for tr in tbl:
        if _tag(tr) != "tr":
            continue
        for tc in tr:
            if _tag(tc) != "tc":
                continue
            addr = span = size = None
            for ch in tc:
                tag = _tag(ch)
                if tag == "cellAddr":
                    addr = ch
                elif tag == "cellSpan":
                    span = ch
                elif tag == "cellSz":
                    size = ch
            if addr is None:
                continue
            out.append(_Cell(
                col=int(addr.get("colAddr", 0)), row=int(addr.get("rowAddr", 0)),
                col_span=max(1, int(span.get("colSpan", 1)) if span is not None else 1),
                row_span=max(1, int(span.get("rowSpan", 1)) if span is not None else 1),
                width=_pt(size.get("width") if size is not None else None),
                height=_pt(size.get("height") if size is not None else None),
                text=_cell_text(tc),
                box=tc.get("borderFillIDRef", ""),
                valign=next((x.get("vertAlign") for x in tc if _tag(x) == "subList"), None) or "CENTER",
                el=tc,
            ))
    return out


def _sizes(cells: list[_Cell], n: int, pos, span, length, minimum: float) -> list[float]:
    """칸 크기로 열 너비(또는 줄 높이)를 푼다. 합친 칸은 아직 모르는 열에 남은 길이를 나눠 준다"""
    sizes: list[float | None] = [None] * n
    for c in cells:
        if span(c) == 1 and 0 <= pos(c) < n:
            sizes[pos(c)] = max(sizes[pos(c)] or 0.0, length(c))
    for c in sorted(cells, key=span):
        idx = [i for i in range(pos(c), min(pos(c) + span(c), n))]
        unknown = [i for i in idx if sizes[i] is None]
        if not unknown:
            continue
        known = sum(sizes[i] or 0.0 for i in idx if sizes[i] is not None)
        share = max((length(c) - known) / len(unknown), minimum)
        for i in unknown:
            sizes[i] = share
    filled = [s for s in sizes if s]
    fallback = sum(filled) / len(filled) if filled else minimum * 4
    return [max(s or fallback, minimum) for s in sizes]


class _Writer:
    def __init__(self, width: float, height: float, margins: tuple[float, float, float, float], styles: _Styles):
        self.styles = styles
        self.doc = pymupdf.open()
        self.width, self.height = width, height
        self.left, self.right, self.top, self.bottom = margins
        self.font = pymupdf.Font("cjk")
        self.page: pymupdf.Page | None = None
        self.y = 0.0
        self.new_page()

    @property
    def content_w(self) -> float:
        return self.width - self.left - self.right

    def new_page(self) -> None:
        self.page = self.doc.new_page(width=self.width, height=self.height)
        self.page.insert_font(fontname=FONT_NAME, fontbuffer=self.font.buffer)
        self.y = self.top

    def room(self, h: float) -> None:
        if self.y + h > self.height - self.bottom and self.y > self.top:
            self.new_page()

    def _wrap(self, text: str, width: float, size: float) -> list[str]:
        lines: list[str] = []
        for raw in text.split("\n"):
            line = ""
            for ch in raw:
                if self.font.text_length(line + ch, fontsize=size) > width and line:
                    lines.append(line)
                    line = ch
                else:
                    line += ch
            lines.append(line)
        return lines

    def _text(self, page, x0: float, x1: float, y: float, line: str, size: float, char: _Char | None, align: str):
        """한 줄 쓰기 (정렬 · 색 · 굵게)"""
        width = self.font.text_length(line.rstrip(), fontsize=size)
        if align == "CENTER":
            x = x0 + max((x1 - x0 - width) / 2, 0)
        elif align == "RIGHT":
            x = max(x1 - width, x0)
        else:
            x = x0
        color = char.color if char else (0, 0, 0)
        if char and char.bold:  # 굵은 글꼴이 없어서 글자 테두리를 조금 그려 굵게 보이게
            page.insert_text((x, y), line, fontname=FONT_NAME, fontsize=size, color=color,
                             render_mode=2, border_width=0.035, fill=color)
        else:
            page.insert_text((x, y), line, fontname=FONT_NAME, fontsize=size, color=color)

    def paragraph(self, text: str, p=None) -> None:
        if not text.strip():
            self.room(EMPTY_PARA)
            self.y += EMPTY_PARA
            return
        char, align = _first_style(p, self.styles) if p is not None else (None, "LEFT")
        size = char.size if char else TEXT_SIZE
        # 앞 공백은 남긴다 ("      년   월   일" 처럼 공백으로 자리를 맞춘 줄)
        for line in self._wrap(text.rstrip() if align == "LEFT" else text.strip(), self.content_w, size):
            h = size * LINE_GAP
            self.room(h)
            self._text(self.page, self.left, self.left + self.content_w, self.y + size, line, size, char, align)
            self.y += h

    def _cell_lines(self, text: str, width: float, start: float | None = None) -> tuple[float, list[str]]:
        inner = max(width - CELL_PAD * 2, 4)
        sizes = [x for x in CELL_SIZES if start is None or x <= start]
        if start is not None:
            sizes = [min(start, 11.0), *sizes]
        for size in sizes:
            lines = self._wrap(text, inner, size)
            if all(self.font.text_length(x, fontsize=size) <= inner for x in lines):
                return size, lines
        return CELL_SIZES[-1], self._wrap(text, inner, CELL_SIZES[-1])

    def table(self, tbl) -> None:
        cells = _cells(tbl)
        if not cells:
            return
        n_cols = max(int(tbl.get("colCnt", 0) or 0), max(c.col + c.col_span for c in cells))
        n_rows = max(int(tbl.get("rowCnt", 0) or 0), max(c.row + c.row_span for c in cells))
        cols = _sizes(cells, n_cols, lambda c: c.col, lambda c: c.col_span, lambda c: c.width, MIN_COL)
        rows = _sizes(cells, n_rows, lambda c: c.row, lambda c: c.row_span, lambda c: c.height, MIN_ROW)
        scale = min(1.0, self.content_w / (sum(cols) or 1))
        cols = [w * scale for w in cols]
        styled = {id(c): _first_style(c.el, self.styles) for c in cells}
        # 글자가 칸보다 길면 그 줄을 늘린다 (한 줄짜리 칸만)
        for c in cells:
            if c.row_span == 1 and c.text:
                w = sum(cols[c.col: c.col + c.col_span])
                char = styled[id(c)][0]
                size, lines = self._cell_lines(c.text, w, char.size if char else None)
                need = len(lines) * size * LINE_GAP + CELL_PAD * 2
                rows[c.row] = max(rows[c.row], need)
        xs = [self.left]
        for w in cols:
            xs.append(xs[-1] + w)
        by_row: dict[int, list[_Cell]] = {}
        for c in cells:
            by_row.setdefault(c.row, []).append(c)
        # 줄 단위로 쪽을 넘긴다. 여러 줄을 합친 칸은 시작 줄 쪽에 그린다
        row_y: dict[int, tuple[int, float]] = {}
        for r in range(n_rows):
            self.room(rows[r])
            row_y[r] = (self.doc.page_count - 1, self.y)
            self.y += rows[r]
        for c in cells:
            pno, y0 = row_y.get(c.row, (self.doc.page_count - 1, self.y))
            height = 0.0
            for r in range(c.row, min(c.row + c.row_span, n_rows)):
                if row_y[r][0] != pno:
                    break
                height += rows[r]
            x0 = xs[min(c.col, n_cols)]
            x1 = xs[min(c.col + c.col_span, n_cols)]
            rect = pymupdf.Rect(x0, y0, x1, y0 + height)
            page = self.doc[pno]
            self._cell_box(page, rect, self.styles.boxes.get(c.box) or self.styles.boxes.get(tbl.get("borderFillIDRef", "")))
            if c.text:
                char, align = styled[id(c)]
                size, lines = self._cell_lines(c.text, rect.width, char.size if char else None)
                block = len(lines) * size * LINE_GAP
                if c.valign == "TOP":
                    ty = rect.y0 + CELL_PAD
                elif c.valign == "BOTTOM":
                    ty = rect.y1 - CELL_PAD - block
                else:
                    ty = rect.y0 + max((rect.height - block) / 2, CELL_PAD)
                for line in lines:
                    self._text(page, rect.x0 + CELL_PAD, rect.x1 - CELL_PAD, ty + size, line, size, char, align)
                    ty += size * LINE_GAP
        self.y += 4

    def _cell_box(self, page, rect: pymupdf.Rect, box: _Box | None) -> None:
        """칸 배경색과 변마다 테두리. 스타일을 모르면 검은 실선"""
        if box is None:
            page.draw_rect(rect, color=(0, 0, 0), width=BORDER)
            return
        if box.fill:
            page.draw_rect(rect, color=None, fill=box.fill, width=0)
        ends = {
            "top": (rect.tl, rect.tr), "bottom": (rect.bl, rect.br),
            "left": (rect.tl, rect.bl), "right": (rect.tr, rect.br),
        }
        for name, (a, b) in ends.items():
            side = box.sides.get(name)
            if side is None or side.kind == "NONE":
                # 보이지 않는 선: 값 자리 찾기(formguide/layout.py)가 표 칸을 알아보게
                page.draw_line(a, b, color=box.fill or (1, 1, 1), width=0.1)
                continue
            if side.kind.startswith("DOUBLE") or side.kind in ("THICK_THIN", "THIN_THICK"):
                gap = max(side.width, 0.8)
                horiz = a.y == b.y
                for off in (-gap / 2, gap / 2):
                    shift = pymupdf.Point(0, off) if horiz else pymupdf.Point(off, 0)
                    page.draw_line(a + shift, b + shift, color=side.color, width=max(side.width / 2, 0.3))
                continue
            page.draw_line(a, b, color=side.color, width=side.width, dashes=_DASHES.get(side.kind))

    def to_bytes(self) -> bytes:
        self.doc.subset_fonts()
        out = self.doc.tobytes(garbage=3, deflate=True)
        self.doc.close()
        return out


def _page_setup(root) -> tuple[float, float, tuple[float, float, float, float]]:
    for el in root.iter():
        if _tag(el) == "pagePr":
            w, h = _pt(el.get("width"), A4[0]), _pt(el.get("height"), A4[1])
            if el.get("landscape") == "NARROWLY" and h > w:  # 가로 방향
                w, h = h, w
            margin = next((m for m in el if _tag(m) == "margin"), None)
            if margin is None:
                return w, h, (DEFAULT_MARGIN,) * 4
            return w, h, (
                _pt(margin.get("left"), DEFAULT_MARGIN), _pt(margin.get("right"), DEFAULT_MARGIN),
                _pt(margin.get("top"), DEFAULT_MARGIN) + _pt(margin.get("header")) * 0.3,
                _pt(margin.get("bottom"), DEFAULT_MARGIN),
            )
    return A4[0], A4[1], (DEFAULT_MARGIN,) * 4


def _body(root, writer: _Writer) -> None:
    """맨 위 문단을 순서대로: 글자는 문단으로, 문단 안의 표는 표로"""
    for p in root:
        if _tag(p) != "p":
            continue
        text: list[str] = []
        for run in p:
            if _tag(run) != "run":
                continue
            for ch in run:
                tag = _tag(ch)
                if tag == "t":
                    text.append(_own_text_t(ch))
                elif tag == "tbl":
                    if "".join(text).strip():
                        writer.paragraph("".join(text), p)
                    text = []
                    writer.table(ch)
        writer.paragraph("".join(text), p)


def _own_text_t(t) -> str:
    out = [t.text or ""]
    for sub in t:
        if _tag(sub) in ("tab", "fwSpace", "hwSpace", "nbSpace"):
            out.append(" ")
        out.append(sub.tail or "")
    return "".join(out)


def render(data: bytes) -> bytes:
    """hwpx 바이트 → PDF 바이트. 읽지 못하면 ValueError"""
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as e:
        raise ValueError("hwpx 가 아님") from e
    with z:
        names = sorted(
            (n for n in z.namelist() if re.fullmatch(r"Contents/section\d+\.xml", n)),
            key=lambda n: int(re.search(r"\d+", n.rsplit("/", 1)[-1]).group()),
        )
        if not names:
            raise ValueError("본문이 없음")
        roots = [ElementTree.fromstring(z.read(n)) for n in names]
        header = z.read("Contents/header.xml") if "Contents/header.xml" in z.namelist() else None
    width, height, margins = _page_setup(roots[0])
    writer = _Writer(width, height, margins, _styles(header))
    for i, root in enumerate(roots):
        if i:
            writer.new_page()
        _body(root, writer)
    return writer.to_bytes()
