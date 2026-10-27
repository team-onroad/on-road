"""양식 PDF 에서 칸마다 "값을 쓸 자리"(좌표)를 찾는다. 문서 AI 가 원본 양식 위에 작성 내용을 채워 내보낼 때 쓴다.

찾는 순서
1. 표 칸: 칸 이름이 적힌 셀의 오른쪽 빈 셀 → 없으면 바로 아래 셀 (칸 이름 위·내용 아래 양식)
2. 표가 아니면: 같은 줄에서 칸 이름 오른쪽 빈 공간
못 찾은 칸은 box 가 null 이고, 내보낼 때 별지(추가 쪽)에 적는다.

좌표는 PDF 포인트, 왼쪽 위 원점 [x0, y0, x1, y1] (pdfplumber·PyMuPDF 공통). page 는 0부터.

실행 (src 폴더에서, 이미 만든 항목 JSON 에 좌표만 다시 넣기):
    backend\\.venv\\Scripts\\python -m formguide.layout P001-apply
"""

import argparse
import re
import shutil
from dataclasses import dataclass
from pathlib import Path

import pdfplumber

from formguide.schema import FormField, FormSpec

_SPACE_RE = re.compile(r"\s+")
# 셀에 원래 적힌 안내 글자만 있으면 빈 셀로 본다 (예: "    년   월   일", "(   )")
_BLANKISH_RE = re.compile(r"^[\s년월일().:/~\-·]*$")
TEXT_GAP = 4
PAGE_RIGHT_MARGIN = 36
MIN_BOX_W = 20


def _norm(text: str | None) -> str:
    return _SPACE_RE.sub("", text or "")


@dataclass
class Cell:
    page: int
    row: int
    col: int
    bbox: tuple[float, float, float, float]
    text: str
    table: int


def _cells(pdf) -> list[list[list[Cell | None]]]:
    """페이지·표별 셀 격자. 병합된 자리는 None"""
    tables: list[list[list[Cell | None]]] = []
    for pno, page in enumerate(pdf.pages):
        for t in page.find_tables():
            grid = []
            for r, row in enumerate(t.rows):
                cells = []
                for c, bbox in enumerate(row.cells):
                    if bbox is None:
                        cells.append(None)
                        continue
                    text = page.crop(bbox).extract_text() or ""
                    cells.append(Cell(pno, r, c, tuple(bbox), text.strip(), len(tables)))
                grid.append(cells)
            tables.append(grid)
    return tables


def _match(label: str, cell_text: str) -> int:
    """0: 아님, 2: 같음, 1: 칸 이름으로 시작 (예: "성 명(한글)")"""
    a, b = _norm(label), _norm(cell_text)
    if not a or not b:
        return 0
    if a == b:
        return 2
    if b.startswith(a) and len(a) >= 2 and len(a) / len(b) >= 0.4:
        return 1
    return 0


def _blank(cell: Cell) -> bool:
    return bool(_BLANKISH_RE.match(cell.text))


def _right_or_below(grid: list[list[Cell | None]], cell: Cell, labels: set[str]) -> tuple[Cell | None, str]:
    row = grid[cell.row]
    for c in row[cell.col + 1:]:
        if c is None:
            continue
        if _blank(c) or _norm(c.text) not in labels:
            return (c, "table_right") if _blank(c) else (None, "")
        break
    if cell.row + 1 < len(grid):
        x0, _, x1, _ = cell.bbox
        for c in grid[cell.row + 1]:
            if c is not None and c.bbox[0] < x1 and c.bbox[2] > x0 and _blank(c):
                return c, "table_below"
    return None, ""


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


def locate(pdf_path: Path, fields: list[FormField]) -> dict[str, dict]:
    """field.id → {"page", "rect": [x0, y0, x1, y1], "how", "date_slots"}. 못 찾은 칸은 빠진다.
    같은 칸 이름이 여러 번 나오면 (신청인·대리인 성명 등) 나온 순서대로 짝짓는다."""
    out: dict[str, dict] = {}
    with pdfplumber.open(pdf_path) as pdf:
        grids = _cells(pdf)
        labels = {_norm(f.label) for f in fields}
        used: set[tuple[int, int, int]] = set()
        for f in fields:
            best: tuple[int, Cell] | None = None
            for grid in grids:
                for row in grid:
                    for cell in row:
                        if cell is None or (cell.table, cell.row, cell.col) in used:
                            continue
                        score = _match(f.label, cell.text)
                        if score and (best is None or score > best[0]):
                            best = (score, cell)
                if best and best[0] == 2:
                    break
            if best is not None:
                cell = best[1]
                used.add((cell.table, cell.row, cell.col))
                target, how = _right_or_below(grids[cell.table], cell, labels)
                if target is not None:
                    used.add((target.table, target.row, target.col))
                    out[f.id] = {"page": target.page, "rect": [round(v, 1) for v in target.bbox], "how": how,
                                 "template_text": target.text or None}
                continue
            for pno, page in enumerate(pdf.pages):
                rect = _text_right(page, f.label)
                if rect:
                    out[f.id] = {"page": pno, "rect": [round(v, 1) for v in rect], "how": "text_right",
                                 "template_text": None}
                    break
    return out


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

    parser = argparse.ArgumentParser(description="항목 JSON 에 값 자리(좌표)를 넣는다")
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
    for w in spec.warnings:
        print(f"  ! {w}")


if __name__ == "__main__":
    main()
