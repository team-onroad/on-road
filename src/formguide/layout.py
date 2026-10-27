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
            tables.append(grid)
    return tables


def _annotate(grid: list[list[Cell | None]]) -> None:
    flat = [c for row in grid for c in row if c is not None]
    section = ""
    for row in grid:
        for c in row:
            if c is not None and _SECTION_RE.match(c.text):
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


def _match(label: str, cell_text: str) -> int:
    """0: 아님, 3: 같음, 2: 칸 이름으로 시작 (예: "성 명(한글)"), 1: 칸 이름으로 끝남 (예: "보호구분 기타사유")"""
    a, b = _norm(label), _norm(cell_text)
    if not a or not b:
        return 0
    if a == b:
        return 3
    if b.startswith(a) and len(a) >= 2 and len(a) / len(b) >= 0.4:
        return 2
    if len(a) >= 3 and b.endswith(a):
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


def _checkbox_area(grid: list[list[Cell | None]], cell: Cell, page) -> dict | None:
    """체크박스 칸: 칸 이름 셀에 "[ ]"가 같이 있으면 그 줄, 아니면 칸 이름 오른쪽에서 같은 높이에 걸친 셀 전부"""
    if "[" in cell.text:
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
    text = page.crop(rect).extract_text() or ""
    options = _options(text)
    if not options:
        return None
    return {"rect": rect, "options": options, "template_text": _SPACE_RE.sub(" ", text).strip()}


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
                used.add(cell.key)
                grid = grids[cell.table]
                if f.input_type == "checkbox":
                    area = _checkbox_area(grid, cell, pdf.pages[cell.page])
                    if area:
                        box = {"page": cell.page, "rect": area["rect"], "how": "checkbox",
                               "template_text": area["template_text"], "options": area["options"]}
                else:
                    target, how = _right_or_below(grid, cell, labels)
                    if target is not None:
                        used.add(target.key)
                        box = {"page": target.page, "rect": target.bbox, "how": how,
                               "template_text": target.text or None}
            elif f.input_type != "checkbox":
                for pno, page in enumerate(pdf.pages):
                    rect = _text_right(page, f.label)
                    if rect:
                        box = {"page": pno, "rect": rect, "how": "text_right", "template_text": None}
                        break
            if box:
                out[f.id] = _round(box)
    return out


def _round(box: dict) -> dict:
    return {**box, "rect": [round(v, 1) for v in box["rect"]]}


def attach_boxes(spec: FormSpec, pdf_path: Path) -> FormSpec:
    boxes = locate(pdf_path, spec.fields)
    fields = [f.model_copy(update={"box": boxes.get(f.id)}) for f in spec.fields]
    missing = [f.label for f in fields if f.box is None and f.value_source in ("profile", "user")]
    warnings = [w for w in spec.warnings if not w.startswith("값 자리를 못 찾음")]
    if missing:
        warnings.append(f"값 자리를 못 찾음 (별지에 적음): {', '.join(missing)}")
    return spec.model_copy(update={"fields": fields, "warnings": warnings})


def save_layout_pdf(pdf_path: Path, out_dir: Path, form_id: str) -> Path:
    """원본 양식 PDF 를 data/forms/{form_id}.pdf 로 복사 (백엔드가 내보낼 때 바탕으로 씀)"""
    out = out_dir / f"{form_id}.pdf"
    if pdf_path.resolve() != out.resolve():
        shutil.copyfile(pdf_path, out)
    return out


def main() -> None:
    from formguide.build import FORMS_DIR

    parser = argparse.ArgumentParser(description="항목 JSON 에 값 자리(좌표)를 넣는다 (LLM 안 씀)")
    parser.add_argument("form_id")
    parser.add_argument("--pdf", type=Path, help="양식 PDF (없으면 data/forms/{form_id}.pdf)")
    parser.add_argument("--out", type=Path, default=FORMS_DIR)
    args = parser.parse_args()

    spec_path = args.out / f"{args.form_id}.json"
    spec = FormSpec.model_validate_json(spec_path.read_text(encoding="utf-8"))
    pdf = save_layout_pdf(args.pdf, args.out, args.form_id) if args.pdf else args.out / f"{args.form_id}.pdf"
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
