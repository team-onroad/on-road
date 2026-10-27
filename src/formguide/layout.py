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

_SPACE_RE = re.compile(r"[\s\x00]+")  # HWP 에서 바꾼 PDF 는 띄어쓰기 자리에 \x00 이 들어 있다
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
ACCOUNT_LINE_H = 10
OPTION_GAP = 20  # 선택지 글자 사이가 이보다 벌어지면 다른 셀 글자  # 계좌번호를 "은행" 줄 위·아래에 쓸 최소 높이
CONFIRM_OPTION = "확인"
CONFIRM_FIELD_ID = "notice_confirm"
SIGN_MARK = "(서명 또는 인)"
_SIGN_RE = re.compile(r"\(\s*(서명\s*또는\s*인|서명|인)\s*\)")


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
        text, prev_x1 = "", None
        for c in sorted(same, key=lambda c: c["x0"]):
            if c["x0"] >= nxt - 6 or c["text"] in "[]()□☐" or (
                text.strip() and prev_x1 is not None and c["x0"] - prev_x1 > OPTION_GAP
            ):  # 다음 선택지·괄호, 또는 멀리 떨어진 옆 셀 글자 (예: "처리기간")
                if text.strip():
                    break
                continue
            text += c["text"]
            prev_x1 = c["x1"]
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


def _line_words(page, word: dict) -> list[dict]:
    return sorted((v for v in page.extract_words() if abs(v["top"] - word["top"]) < 3), key=lambda v: v["x0"])


def _bank_slots(pdf, grids) -> dict[str, dict]:
    """계좌 줄 "____ 은행 (예금주: ____ )": 은행 이름은 "은행" 글자 앞, 예금주는 "예금주:" 뒤,
    계좌번호는 따로 칸이 없으면 같은 셀의 그 줄 위(또는 아래) 빈 자리. {"bank"|"holder"|"account": box}"""
    for grid in grids:
        for row in grid:
            for c in row:
                if c is None or "은행" not in _norm(c.text) or len(_norm(c.text)) > 40:
                    continue
                page = pdf.pages[c.page]
                inside = [
                    w for w in page.extract_words()
                    if "은행" in w["text"] and c.bbox[0] - 1 <= (w["x0"] + w["x1"]) / 2 <= c.bbox[2] + 1
                    and c.bbox[1] - 1 <= (w["top"] + w["bottom"]) / 2 <= c.bbox[3] + 1
                ]
                if not inside:
                    continue
                w = inside[0]
                line = _line_words(page, w)
                if not any("예금주" in v["text"] for v in line):
                    continue
                h = c.bbox[3] - c.bbox[1]
                parts = [x for x in row if x is not None and _overlaps_v(x.bbox, c.bbox) and x.bbox[3] - x.bbox[1] <= h + 1]
                rx0, rx1 = min(x.bbox[0] for x in parts), max(x.bbox[2] for x in parts)
                ry0, ry1 = c.bbox[1], c.bbox[3]
                ly0, ly1 = max(ry0, w["top"] - 2), min(ry1, w["bottom"] + 2)
                out: dict[str, dict] = {}
                left = [v for v in line if v["x1"] <= w["x0"] + 0.5 and v["x0"] >= rx0]
                bx0 = max([v["x1"] + TEXT_GAP for v in left] or [rx0 + 2])
                if w["x0"] - 1.5 - bx0 >= MIN_BOX_W:
                    out["bank"] = {"page": c.page, "rect": (bx0, ly0, w["x0"] - 1.5, ly1), "how": "before_word",
                                   "template_text": "은행", "suffix": "은행", "align": "right"}
                i = next(k for k, v in enumerate(line) if "예금주" in v["text"])
                j = i + 1 if i + 1 < len(line) and line[i + 1]["text"] == ":" else i
                hx0 = line[j]["x1"] + TEXT_GAP
                hx1 = min([v["x0"] - TEXT_GAP for v in line[j + 1:]] or [rx1 - 2])
                if hx1 - hx0 >= MIN_BOX_W:
                    out["holder"] = {"page": c.page, "rect": (hx0, ly0, hx1, ly1), "how": "cell_inline", "template_text": None}
                # 계좌번호 칸이 따로 없으면 "은행" 줄 위(없으면 아래) 빈 자리에 쓴다
                if w["top"] - ry0 >= ACCOUNT_LINE_H:
                    out["account"] = {"page": c.page, "rect": (rx0 + 2, ry0 + 1, rx1 - 2, w["top"] - 1),
                                      "how": "above_line", "template_text": None}
                elif ry1 - w["bottom"] >= ACCOUNT_LINE_H:
                    out["account"] = {"page": c.page, "rect": (rx0 + 2, w["bottom"] + 1, rx1 - 2, ry1 - 1),
                                      "how": "below_line", "template_text": None}
                return out
    return {}


def _bank_role(f: FormField) -> str | None:
    label = _norm(f.label)
    if "은행" in label and "예금주" not in label:
        return "bank"
    if "예금주" in label and "은행" not in label:
        return "holder"
    if "계좌" in label and "번호" in label or f.input_type == "account":
        return "account"
    return None


def _bracket_spots(page, rect: tuple) -> list[list[float]]:
    """영역 안 "[ ]"·□ 가운데 좌표 (선택지 글자가 없어도)"""
    x0, y0, x1, y1 = rect
    chars = [c for c in page.chars if x0 - 1 <= c["x0"] and c["x1"] <= x1 + 1 and y0 - 1 <= c["top"] and c["bottom"] <= y1 + 1]
    spots = []
    for c in chars:
        if c["text"] != "[":
            continue
        close = next((d for d in sorted(chars, key=lambda d: d["x0"]) if d["text"] == "]" and abs(d["top"] - c["top"]) < 3
                      and 0 <= d["x0"] - c["x1"] <= MARK_BRACKET_GAP), None)
        if close:
            spots.append([round((c["x1"] + close["x0"]) / 2, 1), round((c["top"] + c["bottom"]) / 2, 1)])
    for r in [*page.rects, *page.curves]:
        if (r.get("stroke") and 4 <= r["width"] <= MARK_BOX_MAX and abs(r["width"] - r["height"]) <= 3
                and x0 - 1 <= r["x0"] and r["x1"] <= x1 + 1 and y0 - 1 <= r["top"] and r["bottom"] <= y1 + 1):
            spot = [round((r["x0"] + r["x1"]) / 2, 1), round((r["top"] + r["bottom"]) / 2, 1)]
            if not any(abs(spot[0] - s[0]) < 3 and abs(spot[1] - s[1]) < 3 for s in spots):
                spots.append(spot)
    return spots


def _confirm_column(pdf, grids) -> dict | None:
    """유의사항 표의 "확인(√ 체크)" 열: 항목마다 "[ ]" 가 있는 열. 신청인이 읽고 모두 체크해야 한다.
    → {"page", "rect", "how": "confirm", "spots": [[x, y], …], "items": [항목 글, …]}"""
    for grid in grids:
        cells = [c for row in grid for c in row if c is not None]
        for head in cells:
            if not _norm(head.text).startswith("확인") or len(_norm(head.text)) > 10:
                continue
            page = pdf.pages[head.page]
            hx0, _, hx1, _ = head.bbox
            below = sorted(
                (c for c in cells if c.bbox[1] >= head.bbox[3] - 0.5 and abs(c.bbox[0] - hx0) < 2 and abs(c.bbox[2] - hx1) < 2),
                key=lambda c: c.bbox[1],
            )
            column = []
            for c in below:
                if _norm(c.text) in ("", "()", "(√체크)", "√체크"):
                    if _norm(c.text):
                        continue  # 둘로 나뉜 머리글의 아랫줄
                    break
                if _norm(c.text) != "[]":
                    break
                column.append(c)
            if len(column) < 2:
                continue
            table_x0 = min(c.bbox[0] for c in cells)
            spots, items = [], []
            for c in column:
                found = _bracket_spots(page, c.bbox)
                if not found:
                    continue
                spots.append(found[0])
                text = page.crop((table_x0, c.bbox[1], c.bbox[0], c.bbox[3])).extract_text() or ""
                items.append(_SPACE_RE.sub(" ", text).strip())
            if len(spots) < 2:
                continue
            rect = (hx0, column[0].bbox[1], hx1, column[-1].bbox[3])
            return {"page": head.page, "rect": rect, "how": "confirm", "spots": spots, "items": items,
                    "options": [CONFIRM_OPTION], "template_text": _SPACE_RE.sub(" ", head.text).strip()}
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
        bank = _bank_slots(pdf, grids)
        for f in fields:
            box = None
            role = _bank_role(f)
            if role in bank:
                out[f.id] = _round(bank[role])
                continue
            if role == "account" and bank:
                continue  # 계좌 줄에 계좌번호 쓸 자리가 따로 없음 (옆 빈칸은 은행 이름 자리)
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


def _split_bank(fields: list[FormField]) -> list[FormField]:
    """"은행 (예금주: )"처럼 한 칸으로 뽑힌 계좌 줄 → 은행 이름 칸 + 예금주 칸 (예금주는 프로필 이름)"""
    has_holder = any(_bank_role(f) == "holder" for f in fields)
    out = []
    for f in fields:
        label = _norm(f.label)
        if "은행" in label and "예금주" in label:
            out.append(f.model_copy(update={"label": "은행", "input_type": "text", "guide": "돈을 받을 통장의 은행 이름 (예: 국민)"}))
            if not has_holder:
                out.append(FormField(
                    id=f"{f.id}_holder", label="예금주", section=f.section, input_type="text", value_source="profile",
                    profile_key="name", required=f.required, guide="통장 주인 이름. 본인 명의 통장만 됩니다.",
                ))
            continue
        out.append(f)
    return out


def _signature(f: FormField) -> FormField:
    """"신청인 성명: ____ (서명 또는 인)" 줄: 이름은 프로필로 채우고, 서명·도장만 손으로 (서명 자리는 비워 둔다)"""
    label = _SIGN_RE.sub("", f.label).strip()
    sign_like = f.input_type == "signature" or f.sensitive or f.value_source == "handwrite" or label != f.label.strip()
    if "성명" not in _norm(label) or not sign_like:
        return f
    return f.model_copy(update={"label": label,
        "input_type": "text", "value_source": "profile", "profile_key": "name", "sensitive": False, "example": None,
        "guide": "신청인 이름을 적어요. 출력한 뒤 옆에 직접 서명하거나 도장을 찍어요.",
    })


def _sign_mark(pdf_path: Path, box: dict) -> dict:
    """이름 칸 오른쪽에 "(서명 또는 인)" 글자가 없으면 내보낼 때 그려 넣도록 표시"""
    with pdfplumber.open(pdf_path) as pdf:
        page = pdf.pages[box["page"]]
        x0, y0, _, y1 = box["rect"]
        line = page.crop((x0, max(0, y0 - 4), page.width, min(page.height, y1 + 4))).extract_text() or ""
        # 칸 경계가 "성명:" 글자에 걸쳐 있으면 글자 뒤부터 쓴다
        label_end = max([w["x1"] for w in page.extract_words()
                         if w["x0"] < x0 < w["x1"] + TEXT_GAP and w["top"] >= y0 - 1 and w["bottom"] <= y1 + 1] or [x0])
    if label_end > x0:
        box = {**box, "rect": [round(label_end + TEXT_GAP, 1), *box["rect"][1:]]}
    if any(w in _norm(line) for w in ("서명", "(인)", "날인")):
        return box
    return {**box, "sign_mark": SIGN_MARK}


def _first_group(pdf_path: Path) -> str:
    """양식에서 먼저 나오는 묶음 이름 (신청인 본인, 예: "대상아동")"""
    with pdfplumber.open(pdf_path) as pdf:
        cells = [c for grid in _cells(pdf) for row in grid for c in row if c is not None and c.group]
    return _norm(min(cells, key=lambda c: c.order).group) if cells else ""


def _unhint(f: FormField, first_group: str) -> FormField:
    """AI 가 칸 후보 형식("대리인 > 성명")을 그대로 label 에 쓴 경우.
    신청인 본인 묶음·구역 이름은 떼고, 다른 묶음은 앞에 띄어 붙인다 ("대리인 성명", "보호기관 전화 :")"""
    if ">" not in f.label:
        return f
    parts = [p.strip() for p in f.label.split(">") if p.strip()]
    core, prefix = parts[-1], [p for p in parts[:-1] if not _SECTION_RE.match(p)]
    if prefix and _norm(prefix[-1]) != first_group and not _norm(core).startswith(_norm(prefix[-1])):
        label = f"{prefix[-1]} {core}"
    else:
        label = core
    return f.model_copy(update={"label": label, "section": f.section or (" ".join(parts[:-1]) or None)})


def _is_group_head(f: FormField, fields: list[FormField]) -> bool:
    """"연락처"처럼 작은 칸(전화 :, 휴대전화 :)을 묶는 이름: 같은 묶음에 전화 칸이 따로 있으면 버린다"""
    words = f.label.split()
    if _norm(words[-1] if words else "") != "연락처":
        return False
    prefix = _norm(" ".join(words[:-1]))
    return any(
        g is not f and "전화" in _norm(g.label) and _norm(g.label).startswith(prefix) for g in fields
    )


def attach_boxes(spec: FormSpec, pdf_path: Path) -> FormSpec:
    first_group = _first_group(pdf_path)
    kept = [_unhint(f, first_group) for f in spec.fields if f.id != CONFIRM_FIELD_ID]
    heads = [f for f in kept if _is_group_head(f, kept)]
    kept = [f for f in kept if f not in heads]
    sign_ids = {f.id for f in kept if _signature(f) is not f or (f.box or {}).get("sign_mark")}
    source = _split_bank([_signature(f) for f in kept])
    boxes = locate(pdf_path, source)
    groups = group_names(pdf_path)
    warnings = [w for w in spec.warnings if not w.startswith("값 자리를 못 찾음")]
    warnings += [w for w in (f"작은 칸을 묶는 이름이라 버림: '{f.label}'" for f in heads) if w not in warnings]
    fields = []
    for f in source:
        words = f.label.split()
        # "대리인 E-mail"처럼 묶음 이름을 붙였는데 그 묶음에 칸이 없으면 AI 가 지어낸 칸
        if f.id not in boxes and len(words) >= 2 and _norm(words[0]) in groups and f.value_source in ("profile", "user"):
            w = f"양식에 없는 칸이라 버림: '{f.label}' ({words[0]} 묶음에 없음)"
            if w not in warnings:
                warnings.append(w)
            continue
        box = boxes.get(f.id)
        if box and f.id in sign_ids and box["how"] in ("table_right", "text_right"):
            box = _sign_mark(pdf_path, box)
        update = {"box": box}
        if box and box.get("options") and "[" in f.label:
            head = f.label.split("[")[0].strip()
            # 선택지만 있는 체크박스(제목 속 "□자립수당 지급 □의료비 지원")는 선택지를 "/"로 잇는다
            mixed = not head or any(_norm(o) in _norm(head) for o in box["options"])
            update["label"] = "/".join(box["options"]) if mixed else head
        fields.append(f.model_copy(update=update))
    # 유의사항 "확인(√ 체크)" 열: AI 가 뽑지 않아도 양식에서 찾아 칸 하나로 넣는다 (모두 체크)
    with pdfplumber.open(pdf_path) as pdf:
        confirm = _confirm_column(pdf, _cells(pdf))
    if confirm:
        fields = [f for f in fields if not ("확인" in _norm(f.label) and "체크" in _norm(f.label))]
        fields.append(FormField(
            id=CONFIRM_FIELD_ID, label="유의사항 확인", section="유의사항", input_type="checkbox", value_source="user",
            required=True, guide="뒤쪽 유의사항을 읽고 항목마다 확인 칸에 체크해요.", box=_round(confirm),
        ))
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
