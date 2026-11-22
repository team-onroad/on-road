"""한글 hwpx 양식 → 같은 표 구조의 PDF.

서버에는 한글 프로그램이 없어서 hwpx 를 그대로 PDF 로 바꿀 수 없다. 대신 hwpx 안에 적힌
쪽 크기 · 표의 줄 · 칸 위치(cellAddr) · 합친 칸(cellSpan) · 칸 크기(cellSz) 로 표를 같은 모양으로 다시 그리고,
칸 글자와 표 밖 문단 글자를 제자리에 쓴다. 이렇게 만든 PDF 위에 formguide/layout.py 가 값 자리를 찾고,
내보낼 때 작성 내용을 채운다 (app/form_layouts.py).

- 글꼴 · 글자 크기 · 칸 색은 따라 하지 않는다 (양식의 칸 구조와 글자 위치를 맞추는 게 목적)
- 그림 · 도형 · 머리말은 그리지 않는다
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


@dataclass
class _Cell:
    col: int
    row: int
    col_span: int
    row_span: int
    width: float
    height: float
    text: str


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
    def __init__(self, width: float, height: float, margins: tuple[float, float, float, float]):
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

    def paragraph(self, text: str) -> None:
        if not text.strip():
            self.room(EMPTY_PARA)
            self.y += EMPTY_PARA
            return
        # 앞 공백은 남긴다 ("      년   월   일" 처럼 공백으로 자리를 맞춘 줄)
        for line in self._wrap(text.rstrip(), self.content_w, TEXT_SIZE):
            h = TEXT_SIZE * LINE_GAP
            self.room(h)
            self.page.insert_text((self.left, self.y + TEXT_SIZE), line, fontname=FONT_NAME, fontsize=TEXT_SIZE)
            self.y += h

    def _cell_lines(self, text: str, width: float) -> tuple[float, list[str]]:
        inner = max(width - CELL_PAD * 2, 4)
        for size in CELL_SIZES:
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
        # 글자가 칸보다 길면 그 줄을 늘린다 (한 줄짜리 칸만)
        for c in cells:
            if c.row_span == 1 and c.text:
                w = sum(cols[c.col: c.col + c.col_span])
                size, lines = self._cell_lines(c.text, w)
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
            page.draw_rect(rect, color=(0, 0, 0), width=BORDER)
            if c.text:
                size, lines = self._cell_lines(c.text, rect.width)
                block = len(lines) * size * LINE_GAP
                ty = rect.y0 + max((rect.height - block) / 2, CELL_PAD)
                for line in lines:
                    page.insert_text(
                        (rect.x0 + CELL_PAD, ty + size), line, fontname=FONT_NAME, fontsize=size,
                    )
                    ty += size * LINE_GAP
        self.y += 4

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
                        writer.paragraph("".join(text))
                    text = []
                    writer.table(ch)
        writer.paragraph("".join(text))


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
    width, height, margins = _page_setup(roots[0])
    writer = _Writer(width, height, margins)
    for i, root in enumerate(roots):
        if i:
            writer.new_page()
        _body(root, writer)
    return writer.to_bytes()
