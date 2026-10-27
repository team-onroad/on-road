"""양식 PDF 에서 칸마다 "값을 쓸 자리"(좌표)를 찾는다. 문서 AI 가 원본 양식 위에 작성 내용을 채워 내보낼 때 쓴다.

찾는 방법
1. 표 칸: 칸 이름이 적힌 셀의 오른쪽 빈 셀 → 없으면 바로 아래 셀 (칸 이름 위·내용 아래 양식)
   - "(", ":" 같은 좁은 구분 셀은 건너뛴다 (예: "예금주 : ____")
2. 같은 칸 이름이 여러 번 나오면 (대상아동·대리인·보호기관의 "성명", "전화:" 등)
   - 칸 이름이나 구역 이름에 묶음 이름(예: "대리인")이 있으면 그 묶음의 칸
   - 아니면 같은 번호 구역(예: "2. 보호기관") 안의 칸
   - 아니면 양식에서 먼저 나오는 칸
3. 체크박스 칸: "[ ]선택지" 들이 있는 영역 전체와 선택지 목록(options)
4. 날짜 줄: "년 월 일" 이 한 줄에 있는 자리 (신청일 등)
5. 표가 아니면: 같은 줄에서 칸 이름 오른쪽 빈 공간
못 찾은 칸은 box 가 null 이고, 내보낼 때 별지(추가 쪽)에 적는다.

좌표는 PDF 포인트, 왼쪽 위 원점 [x0, y0, x1, y1] (pdfplumber·PyMuPDF 공통). page 는 0부터.

실행 (src 폴더에서, 이미 만든 항목 JSON 에 좌표만 다시 넣기, LLM 안 씀):
    backend\\.venv\\Scripts\\python -m formguide.layout P001-apply
"""

import argparse
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path

import pdfplumber

from formguide.redraw import has_broken_fonts, redraw_text
from formguide.schema import FormField, FormSpec

_SPACE_RE = re.compile(r"\s+")
# 셀에 원래 적힌 안내 글자만 있으면 빈 셀로 본다 (예: "    년   월   일", "(   )")
_BLANKISH_RE = re.compile(r"^[\s년월일().:/~\-·]*$")
_SECTION_RE = re.compile(r"^\s*\d+\s*\.\s*\S")
_OPTION_RE = re.compile(r"\[\s*\]\s*([^\[\]()]+)")
_WORD_RE = re.compile(r"[가-힣A-Za-z]{2,}")
DATE_LABELS = {"년월일", "신청일", "신청일자", "작성일", "작성일자", "날짜", "일자"}
TEXT_GAP = 4
PAGE_RIGHT_MARGIN = 36
MIN_BOX_W = 20
DATE_LEFT_SPACE = 60  # "년" 앞에 연도를 쓸 공간
SECTION_MAX = 20  # 구역 제목 길이 (이보다 긴 "1. …" 줄은 유의사항 문장)
MARK_BOX_MAX = 12  # 체크박스 네모(□) 크기
MARK_BRACKET_GAP = 30  # "[ ]" 의 [ 와 ] 사이 최대 거리 (제목의 "[ … ]" 묶음 괄호와 구분)


def _norm(text: str | None) -> str:
    return _SPACE_RE.sub("", text or "")


def _words(text: str | None) -> set[str]:
    return set(_WORD_RE.findall(text or ""))


@dataclass
class Cell:
    page: int
    table: int
    row: int
    col: int
    bbox: tuple[float, float, float, float]
    text: str
    group: str = ""  # 여러 줄에 걸친 왼쪽 묶음 이름 (예: "대상 아동", "대리인")
    section: str = ""  # 위쪽 번호 구역 (예: "2. 보호기관")
    order: int = 0  # 양식 읽는 순서
    extra: dict = field(default_factory=dict)

    @property
    def width(self) -> float:
        return self.bbox[2] - self.bbox[0]

    @property
    def key(self) -> tuple[int, int, int]:
        return (self.table, self.row, self.col)


def _overlaps_v(a: tuple, b: tuple) -> bool:
    return a[1] < b[3] - 0.5 and a[3] > b[1] + 0.5


def _cells(pdf) -> list[list[list[Cell | None]]]:
    """페이지·표별 셀 격자 (병합된 자리는 None). 셀마다 묶음 이름·번호 구역을 붙인다"""
    tables: list[list[list[Cell | None]]] = []
    order = 0
    for pno, page in enumerate(pdf.pages):
        for t in page.find_tables():
            grid: list[list[Cell | None]] = []
            for r, row in enumerate(t.rows):
                cells: list[Cell | None] = []
                for c, bbox in enumerate(row.cells):
                    if bbox is None:
                        cells.append(None)
                        continue
                    text = (page.crop(bbox).extract_text() or "").strip()
                    cells.append(Cell(pno, len(tables), r, c, tuple(bbox), text, order=order))
                    order += 1
                grid.append(cells)
            _annotate(grid)
            _outer_section(grid, page, t.bbox)
            tables.append(grid)
    return tables


def _annotate(grid: list[list[Cell | None]]) -> None:
    flat = [c for row in grid for c in row if c is not None]
    section = ""
    for row in grid:
        for c in row:
            if c is not None and _SECTION_RE.match(c.text) and len(c.text.split("\n")[0]) <= SECTION_MAX:
                section = c.text.split("\n")[0]
            if c is None:
                continue
            c.section = section
    # 묶음 이름: 같은 표에서 이 셀 왼쪽에 있고, 세로로 더 길게 걸친 글자 있는 셀
    for c in flat:
        h = c.bbox[3] - c.bbox[1]
        for g in flat:
            gh = g.bbox[3] - g.bbox[1]
            if (
                g is not c and g.text and g.bbox[2] <= c.bbox[0] + 0.5 and gh > h + 1
                and g.bbox[1] <= c.bbox[1] + 0.5 and g.bbox[3] >= c.bbox[3] - 0.5
                and not _SECTION_RE.match(g.text)
            ):
                c.group = g.text.replace("\n", " ")
                break


def _outer_section(grid: list[list[Cell | None]], page, table_bbox: tuple) -> None:
    """표 밖(위)에 적힌 번호 구역 제목 (예: 표 위 "2. 보호기관")을 구역이 없는 셀에 붙인다"""
    heads = [
        ln for ln in page.extract_text_lines()
        if ln["bottom"] <= table_bbox[1] + 2 and len(ln["text"]) <= SECTION_MAX and _SECTION_RE.match(ln["text"])
    ]
    if not heads:
        return
    head = max(heads, key=lambda ln: ln["bottom"])["text"].strip()
    for row in grid:
        for c in row:
            if c is not None and not c.section:
                c.section = head


def _match(label: str, cell_text: str) -> int:
    """0: 아님, 3: 같음, 2: 칸 이름으로 시작 (예: "성 명(한글)"), 1: 칸 이름으로 끝남 (예: "보호구분 기타사유")"""
    a, b = _norm(label), _norm(cell_text)
    if not a or not b:
        return 0
    if a == b:
        return 3
    if b.startswith(a) and len(a) >= 2 and len(a) / len(b) >= 0.4:
        return 2
    if b.startswith(a) and len(a) >= 2:  # 긴 셀 맨 앞의 작은 칸 이름 (예: "은행 (예금주: ) * 본인 명의 …")
        return 1
    if len(a) >= 3 and b.endswith(a):
        return 1
    if len(a) >= 3 and (a.endswith(":") and a in b or a + ":" in b):  # 셀 안의 작은 칸 이름 (예: "은행 (예금주: )")
        return 1
    return 0


def _blank(cell: Cell) -> bool:
    return bool(_BLANKISH_RE.match(cell.text))


def _right_or_below(grid: list[list[Cell | None]], cell: Cell, labels: set[str]) -> tuple[Cell | None, str]:
    for c in grid[cell.row][cell.col + 1:]:
        if c is None:
            continue
        if _blank(c):
            if c.width < MIN_BOX_W:  # "(", ":" 같은 구분 셀은 건너뛴다
                continue
            return c, "table_right"
        if _norm(c.text) in labels:
            break  # 바로 다음이 다른 칸 이름 → 아래 셀 확인
        return None, ""  # 안내 글자가 있는 셀에는 쓰지 않는다
    if cell.row + 1 < len(grid):
        x0, _, x1, _ = cell.bbox
        for c in grid[cell.row + 1]:
            if c is not None and c.bbox[0] < x1 and c.bbox[2] > x0 and _blank(c) and c.width >= MIN_BOX_W:
                return c, "table_below"
    return None, ""


def _options(text: str) -> list[str]:
    out = []
    for m in _OPTION_RE.finditer(text.replace("\n", " ")):
        opt = m.group(1).strip(" ,.·")
        if opt and opt not in out:
            out.append(opt)
    return out


def _marks(page, rect: tuple) -> dict[str, list[float]]:
    """영역 안 체크박스 선택지 → 체크 표시를 그릴 가운데 좌표.
    "[ ]선택지"(대괄호 사이가 좁은 것만, 제목의 "[ … ]" 묶음 괄호는 제외)와 □ 네모(그림) 둘 다 찾는다"""
    x0, y0, x1, y1 = rect
    # 글자 가운데가 영역 안에 있으면 포함 (셀 경계에 걸친 글자가 많다)
    chars = [
        c for c in page.chars
        if x0 - 1 <= (c["x0"] + c["x1"]) / 2 <= x1 + 1 and y0 - 1 <= (c["top"] + c["bottom"]) / 2 <= y1 + 1
    ]
    squares = []
    for r in [*page.rects, *page.curves]:  # 네모가 사각형이 아니라 닫힌 선(curve)으로 그려진 PDF 도 있다
        # 테두리가 그려진 네모만 (흰색으로 칠한 가림 상자는 체크박스가 아니다)
        is_box = r.get("stroke") and 4 <= r["width"] <= MARK_BOX_MAX and abs(r["width"] - r["height"]) <= 3
        inside = x0 - 1 <= r["x0"] and r["x1"] <= x1 + 1 and y0 - 1 <= r["top"] and r["bottom"] <= y1 + 1
        if is_box and inside and not any(abs(r["x0"] - q[0]) < 2 and abs(r["top"] - q[1]) < 2 for q in squares):
            squares.append((r["x0"], r["top"], r["x1"], r["bottom"]))
    starts: list[tuple[float, float, float, float]] = []  # (선택지 시작 x, 줄 top, 체크 x, 체크 y)
    lines: list[list[dict]] = []
    for c in sorted(chars, key=lambda c: (c["top"] + c["bottom"]) / 2):
        mid = (c["top"] + c["bottom"]) / 2
        if lines and abs(mid - (lines[-1][0]["top"] + lines[-1][0]["bottom"]) / 2) <= 3:
            lines[-1].append(c)
        else:
            lines.append([c])
    for line in lines:
        line.sort(key=lambda c: c["x0"])
        for i, c in enumerate(line):
            if c["text"] != "[":
                continue
            close = next((d for d in line[i + 1:] if d["text"] == "]"), None)
            if close is None or close["x0"] - c["x1"] > MARK_BRACKET_GAP:
                continue
            starts.append((close["x1"], c["top"], (c["x1"] + close["x0"]) / 2, (c["top"] + c["bottom"]) / 2))
    for sx0, sy0, sx1, sy1 in squares:
        starts.append((sx1, sy0, (sx0 + sx1) / 2, (sy0 + sy1) / 2))
    out: dict[str, list[float]] = {}
    for start_x, top, cx, cy in sorted(starts, key=lambda t: (round(t[1]), t[0])):
        same = [c for c in chars if abs((c["top"] + c["bottom"]) / 2 - cy) < 5 and c["x0"] >= start_x - 0.5]
        nxt = min([t[0] for t in starts if abs(t[3] - cy) < 5 and t[0] > start_x + 1] or [x1 + 1])
        text = ""
        for c in sorted(same, key=lambda c: c["x0"]):
            if c["x0"] >= nxt - 6 or c["text"] in "[]()□☐":
                if text.strip():
                    break
                continue
            text += c["text"]
        opt = " ".join(text.split()).strip(" ,.·")
        if opt and opt not in out:
            out[opt] = [round(cx, 1), round(cy, 1)]
    return out


def _checkbox_area(grid: list[list[Cell | None]], cell: Cell, page) -> dict | None:
    """체크박스 칸: 칸 이름 셀에 "[ ]"가 같이 있으면 그 줄, 아니면 칸 이름 오른쪽에서 같은 높이에 걸친 셀 전부"""
    if "[" in cell.text or _marks(page, cell.bbox):
        parts = [c for c in grid[cell.row] if c is not None and _overlaps_v(c.bbox, cell.bbox)]
    else:
        parts = [
            c for row in grid for c in row
            if c is not None and c.bbox[0] >= cell.bbox[2] - 0.5
            and c.bbox[1] >= cell.bbox[1] - 0.5 and c.bbox[3] <= cell.bbox[3] + 0.5
        ]
    parts = [c for c in parts if c.bbox[3] - c.bbox[1] <= cell.bbox[3] - cell.bbox[1] + 0.5]
    if not parts:
        return None
    rect = (min(c.bbox[0] for c in parts), min(c.bbox[1] for c in parts),
            max(c.bbox[2] for c in parts), max(c.bbox[3] for c in parts))
    marks = _marks(page, rect)
    if not marks:
        return None
    text = page.crop(rect).extract_text() or ""
    return {"rect": rect, "options": list(marks), "marks": marks, "template_text": _SPACE_RE.sub(" ", text).strip()}


def _inline_box(page, cell: Cell, label: str) -> tuple[float, float, float, float] | None:
    """한 셀 안에 칸 이름이 여럿 있는 경우 (예: "전화 : ____ / 휴대전화 : ____", "은행 (예금주: ____)").
    칸 이름 바로 뒤부터 같은 줄 다음 글자(또는 셀 끝)까지"""
    x0, y0, x1, y1 = cell.bbox
    words = page.crop(cell.bbox).extract_words()
    target = _norm(label)
    for i, w in enumerate(words):
        joined, j = "", i
        while j < len(words) and len(joined) < len(target) and abs(words[j]["top"] - w["top"]) < 3:
            joined += _norm(words[j]["text"])
            j += 1
        jc, tc = joined.rstrip(":"), target.rstrip(":")
        if not jc.endswith(tc) or len(jc) - len(tc) > len(_norm(w["text"])):
            continue
        last = words[j - 1]
        bx0 = last["x1"] + TEXT_GAP
        same_line = [v for v in words[j:] if abs(v["top"] - w["top"]) < 3 and v["x0"] > bx0 - 1]
        bx1 = min([v["x0"] - TEXT_GAP for v in same_line] or [x1 - 2])
        if bx1 - bx0 < MIN_BOX_W:
            # "____ 은행 (예금주: )"처럼 칸 이름 앞이 빈 자리면 앞에 쓴다
            before = [v for v in words[:i] if abs(v["top"] - w["top"]) < 3]
            lx0 = max([v["x1"] + TEXT_GAP for v in before] or [x0 + 2])
            lx1 = w["x0"] - TEXT_GAP
            if lx1 - lx0 >= MIN_BOX_W * 2:
                return (lx0, max(y0, w["top"] - 2), lx1, min(y1, w["bottom"] + 2))
            continue
        return (bx0, max(y0, w["top"] - 2), bx1, min(y1, w["bottom"] + 2))
    return None


def _date_line(page) -> tuple[float, float, float, float] | None:
    """한 줄에 "년", "월", "일" 이 순서대로 떨어져 있는 자리. 여러 개면 마지막 (신청일은 보통 아래쪽)"""
    words = page.extract_words()
    found = None
    for y in sorted({round(w["top"]) for w in words}):
        line = sorted((w for w in words if abs(w["top"] - y) < 3), key=lambda w: w["x0"])
        units = [w for w in line if w["text"] in ("년", "월", "일")]
        if [w["text"] for w in units][:3] == ["년", "월", "일"]:
            n, _, d = units[:3]
            found = (n["x0"] - DATE_LEFT_SPACE, n["top"] - 4, d["x1"] + 2, d["bottom"] + 4)
    return found


def _text_right(page, label: str) -> tuple[float, float, float, float] | None:
    """표가 아닌 양식: 같은 줄 칸 이름 오른쪽 빈 공간"""
    target = _norm(label)
    words = page.extract_words(keep_blank_chars=False)
    for i, w in enumerate(words):
        joined, j = "", i
        while j < len(words) and len(joined) < len(target) and abs(words[j]["top"] - w["top"]) < 3:
            joined += _norm(words[j]["text"])
            j += 1
        if joined != target:
            continue
        last = words[j - 1]
        x0 = last["x1"] + TEXT_GAP
        same_line = [v for v in words[j:] if abs(v["top"] - w["top"]) < 3 and v["x0"] > x0]
        x1 = min([v["x0"] - TEXT_GAP for v in same_line] or [page.width - PAGE_RIGHT_MARGIN])
        if x1 - x0 < MIN_BOX_W:
            return None
        return (x0, w["top"] - 2, x1, w["bottom"] + 2)
    return None


def _candidates(f: FormField, grids, used: set) -> list[tuple[Cell, int]]:
    """(셀, 일치 정도). 칸 이름 앞에 묶음 이름이 붙은 경우(예: "대리인 전화:")는 떼고도 찾는다"""
    out = []
    for grid in grids:
        for row in grid:
            for cell in row:
                if cell is None or cell.key in used or _SECTION_RE.match(cell.text):
                    continue  # "1. 신청인" 같은 구역 제목은 칸 이름이 아니다
                score = _match(f.label, cell.text)
                if not score:
                    words = f.label.split()
                    for k in range(1, len(words)):
                        prefix, rest = " ".join(words[:k]), " ".join(words[k:])
                        if _norm(prefix) in _norm(cell.group + cell.section):
                            score = _match(rest, cell.text)
                            break
                if score:
                    out.append((cell, score))
    return out


def _pick(f: FormField, cands: list[tuple[Cell, int]]) -> Cell | None:
    if not cands:
        return None
    key = _words(f.label) | _words(f.section)
    best = max(score for _, score in cands)
    pool = [(c, s) for c, s in cands if s >= min(best, 2)] or cands
    # 1) 묶음 이름(예: 대리인)이 칸 이름·구역에 들어 있으면 그 묶음
    by_group = [c for c, _ in pool if c.group and _words(c.group) & key]
    if by_group:
        return min(by_group, key=lambda c: c.order)
    # 2) 같은 번호 구역 (묶음 이름이 key 에 없으면 묶음 없는 칸 또는 첫 묶음)
    sec_words = _words(f.section)
    by_section = [c for c, _ in pool if sec_words and _words(c.section) & sec_words]
    pool_cells = by_section or [c for c, _ in pool]
    # 3) 먼저 나오는 칸
    return min(pool_cells, key=lambda c: c.order)


def locate(pdf_path: Path, fields: list[FormField]) -> dict[str, dict]:
    """field.id → {"page", "rect": [x0, y0, x1, y1], "how", "template_text", "options"?}. 못 찾은 칸은 빠진다."""
    out: dict[str, dict] = {}
    with pdfplumber.open(pdf_path) as pdf:
        grids = _cells(pdf)
        labels = {_norm(f.label) for f in fields}
        used: set[tuple[int, int, int]] = set()
        for f in fields:
            box = None
            if _norm(f.label) in DATE_LABELS:
                for pno, page in enumerate(pdf.pages):
                    rect = _date_line(page)
                    if rect:
                        box = {"page": pno, "rect": rect, "how": "date_line", "template_text": "년 월 일"}
                if box:
                    out[f.id] = _round(box)
                    continue

            cell = _pick(f, _candidates(f, grids, used))
            if cell is not None:
                grid = grids[cell.table]
                page = pdf.pages[cell.page]
                if f.input_type == "checkbox":
                    used.add(cell.key)
                    area = _checkbox_area(grid, cell, page)
                    if area:
                        box = {"page": cell.page, "rect": area["rect"], "how": "checkbox",
                               "template_text": area["template_text"], "options": area["options"],
                               "marks": area["marks"]}
                else:
                    core = _core_label(f, cell)
                    # "전화 :"처럼 ":"로 끝나는 작은 칸 이름이 셀 안에 여럿이면 셀 안에서 먼저 찾는다
                    inline_first = _norm(core).endswith(":") and _norm(core) != _norm(cell.text)
                    rect = _inline_box(page, cell, core) if inline_first else None
                    target, how = (None, "") if rect else _right_or_below(grid, cell, labels)
                    if target is not None:
                        used.update((cell.key, target.key))
                        box = {"page": target.page, "rect": target.bbox, "how": how,
                               "template_text": target.text or None}
                    else:
                        rect = rect or _inline_box(page, cell, core)
                        if rect:
                            # 셀 하나에 칸 이름이 여럿이라 셀을 다시 쓸 수 있게 used 에 넣지 않는다
                            box = {"page": cell.page, "rect": rect, "how": "cell_inline", "template_text": None}
                        else:
                            used.add(cell.key)
            elif f.input_type == "checkbox" and "/" in f.label:
                box = _checkbox_by_options(pdf, grids, [p.strip() for p in f.label.split("/") if p.strip()])
            elif f.input_type == "checkbox":
                box = _checkbox_in_text(pdf, f.label)
            elif f.input_type != "checkbox":
                for pno, page in enumerate(pdf.pages):
                    rect = _text_right(page, f.label)
                    if rect:
                        box = {"page": pno, "rect": rect, "how": "text_right", "template_text": None}
                        break
            if box:
                out[f.id] = _round(box)
    return out


def _core_label(f: FormField, cell: Cell) -> str:
    """"대리인 전화 :" → 셀 묶음이 대리인이면 "전화 :" """
    words = f.label.split()
    for k in range(1, len(words)):
        if _norm(" ".join(words[:k])) in _norm(cell.group + cell.section):
            return " ".join(words[k:])
    return f.label


def _checkbox_in_text(pdf, label: str) -> dict | None:
    """표 밖 문장 속 체크박스 (예: "것에 동의함. ([ ]동의함, [ ]동의하지 않음)")"""
    target = _norm(label)
    for pno, page in enumerate(pdf.pages):
        for ln in page.extract_text_lines():
            if target not in _norm(ln["text"]) or "[" not in ln["text"]:
                continue
            rect = (ln["x0"] - 1, ln["top"] - 2, ln["x1"] + 1, ln["bottom"] + 2)
            marks = _marks(page, rect)
            if marks:
                return {"page": pno, "rect": rect, "how": "checkbox", "options": list(marks), "marks": marks,
                        "template_text": " ".join(ln["text"].split())}
    return None


def _checkbox_by_options(pdf, grids, parts: list[str]) -> dict | None:
    """칸 이름 없이 선택지만 있는 체크박스 (예: 제목의 "□자립수당 지급 □의료비 지원") — label 은 선택지를 "/"로 이은 것"""
    want = {_norm(p) for p in parts}
    for grid in grids:
        for row in grid:
            for cell in row:
                if cell is None:
                    continue
                page = pdf.pages[cell.page]
                marks = _marks(page, cell.bbox)
                if marks and want <= {_norm(o) for o in marks}:
                    text = page.crop(cell.bbox).extract_text() or ""
                    return {"page": cell.page, "rect": cell.bbox, "how": "checkbox", "options": list(marks),
                            "marks": marks, "template_text": _SPACE_RE.sub(" ", text).strip()}
    return None


def label_hints(pdf_path: Path, limit: int = 120) -> list[str]:
    """LLM 에 같이 줄 칸 이름 후보: 표 셀 글자(묶음 > 칸 이름), 셀 안 작은 칸 이름("전화 :"), 체크박스 선택지, 날짜 줄.
    AI 가 칸을 빠뜨리거나 대리인·보호기관 칸을 섞지 않게 돕는다"""
    out: list[str] = []
    with pdfplumber.open(pdf_path) as pdf:
        for grid in _cells(pdf):
            for row in grid:
                for c in row:
                    if c is None or not c.text or _blank(c) or _SECTION_RE.match(c.text):
                        continue
                    page = pdf.pages[c.page]
                    where = " > ".join(x for x in (c.section.split("\n")[0], c.group) if x)
                    marks = _marks(page, c.bbox)
                    text = " ".join(c.text.split())
                    if marks:
                        out.append(f"{where} > 체크박스 [{text[:40]}] 선택지: {' / '.join(marks)}".lstrip(" >"))
                        continue
                    if len(c.text) > 40:
                        continue
                    subs = [w["text"] for w in page.crop(c.bbox).extract_words()]
                    joined = " ".join(subs)
                    if joined.count(":") >= 2:  # "전화 : 휴대전화 :" → 칸 두 개
                        for part in re.findall(r"[^:]+?\s*:", joined):
                            out.append(f"{where} > {part.strip()}".lstrip(" >"))
                    else:
                        out.append(f"{where} > {text}".lstrip(" >"))
        for page in pdf.pages:
            # 표 밖의 "신청인(대리신청인) 성명:" 같은 줄
            tables = [t.bbox for t in page.find_tables()]
            for ln in page.extract_text_lines():
                inside = any(t[0] - 1 <= ln["x0"] and ln["x1"] <= t[2] + 1 and t[1] - 1 <= ln["top"] and ln["bottom"] <= t[3] + 1
                             for t in tables)
                if not inside and ":" in ln["text"] and len(ln["text"]) <= 40:
                    out.append(f"표 밖 > {' '.join(ln['text'].split())}")
            if _date_line(page):
                out.append("날짜 줄 > 년 월 일")
    seen, uniq = set(), []
    for h in out:
        if h not in seen:
            seen.add(h)
            uniq.append(h)
    return uniq[:limit]


def _round(box: dict) -> dict:
    return {**box, "rect": [round(v, 1) for v in box["rect"]]}


def group_names(pdf_path: Path) -> set[str]:
    """표의 묶음 이름 (예: "대상아동", "대리인"), 공백 없이"""
    with pdfplumber.open(pdf_path) as pdf:
        return {_norm(c.group) for grid in _cells(pdf) for row in grid for c in row if c is not None and c.group}


def attach_boxes(spec: FormSpec, pdf_path: Path) -> FormSpec:
    boxes = locate(pdf_path, spec.fields)
    groups = group_names(pdf_path)
    warnings = [w for w in spec.warnings if not w.startswith("값 자리를 못 찾음")]
    fields = []
    for f in spec.fields:
        words = f.label.split()
        # "대리인 E-mail"처럼 묶음 이름을 붙였는데 그 묶음에 칸이 없으면 AI 가 지어낸 칸
        if f.id not in boxes and len(words) >= 2 and _norm(words[0]) in groups and f.value_source in ("profile", "user"):
            warnings.append(f"양식에 없는 칸이라 버림: '{f.label}' ({words[0]} 묶음에 없음)")
            continue
        fields.append(f.model_copy(update={"box": boxes.get(f.id)}))
    missing = [f.label for f in fields if f.box is None and f.value_source in ("profile", "user")]
    if missing:
        warnings.append(f"값 자리를 못 찾음 (별지에 적음): {', '.join(missing)}")
    return spec.model_copy(update={"fields": fields, "warnings": warnings})


def save_layout_pdf(pdf_path: Path, out_dir: Path, form_id: str, redraw: bool | None = None) -> Path:
    """원본 양식 PDF 를 data/forms/{form_id}.pdf 로 저장 (백엔드가 내보낼 때 바탕으로 씀).
    redraw: 깨진 글자를 다시 그릴지 (None 이면 글꼴이 잘못 들어간 PDF 일 때만, formguide/redraw.py)"""
    out = out_dir / f"{form_id}.pdf"
    if redraw is None:
        redraw = has_broken_fonts(pdf_path)
    if redraw:
        tmp = out.with_suffix(".redraw.pdf")
        redraw_text(pdf_path, tmp)
        tmp.replace(out)
    elif pdf_path.resolve() != out.resolve():
        shutil.copyfile(pdf_path, out)
    return out


def main() -> None:
    from formguide.build import FORMS_DIR

    parser = argparse.ArgumentParser(description="항목 JSON 에 값 자리(좌표)를 넣는다 (LLM 안 씀)")
    parser.add_argument("form_id")
    parser.add_argument("--pdf", type=Path, help="양식 PDF (없으면 data/forms/{form_id}.pdf)")
    parser.add_argument("--out", type=Path, default=FORMS_DIR)
    parser.add_argument("--redraw-text", action=argparse.BooleanOptionalAction, default=None,
                        help="양식 글자를 깨끗한 글꼴로 다시 그린다 (기본: 글꼴이 깨진 PDF 만)")
    args = parser.parse_args()

    spec_path = args.out / f"{args.form_id}.json"
    spec = FormSpec.model_validate_json(spec_path.read_text(encoding="utf-8"))
    src = args.pdf or args.out / f"{args.form_id}.pdf"
    redraw = has_broken_fonts(src) if args.redraw_text is None else args.redraw_text
    pdf = save_layout_pdf(src, args.out, args.form_id, redraw)
    if redraw:
        print("양식 글자를 깨끗한 글꼴로 다시 그렸습니다.")
    spec = attach_boxes(spec, pdf)
    spec_path.write_text(spec.model_dump_json(indent=2), encoding="utf-8")
    found = sum(1 for f in spec.fields if f.box)
    print(f"값 자리 {found}/{len(spec.fields)}칸 찾음 → {spec_path}")
    for f in spec.fields:
        if f.box:
            extra = f" 선택지: {', '.join(f.box['options'])}" if f.box.get("options") else ""
            print(f"  {f.label} → {f.box['how']} {f.box['rect']}{extra}")
    for w in spec.warnings:
        print(f"  ! {w}")


if __name__ == "__main__":
    main()
