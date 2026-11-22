"""올린 양식을 원본 규격으로 내보내기 위한 바탕 PDF 와 값 자리 찾기.

- PDF (글자가 있는 PDF): 올린 PDF 를 그대로 바탕으로 쓴다
- 한글 hwpx: 같은 표 구조로 다시 그린 PDF 를 바탕으로 쓴다 (app/hwpx_render.py)
- 사진 · 스캔 PDF · 예전 한글(hwp): 글자 위치를 알 수 없어 바탕이 없다 → 내보내면 칸 이름·내용 표
값 자리(box)는 정책 신청서와 같은 방법으로 찾는다 (formguide/layout.py, 표 칸 이름 오른쪽·아래 빈 칸 등).
값 자리를 하나도 못 찾으면 바탕을 쓰지 않는다. 못 찾은 칸은 내보낼 때 별지에 적는다 (doc_export.render_overlay).

바탕 PDF 는 문서(documents.form_pdf)에 암호화해 저장하고, 문서를 지우거나 탈퇴하면 함께 지워진다.
"""

import logging
import re
import tempfile
from pathlib import Path

import pymupdf

from app import ai_content  # noqa: F401  (src 를 import 경로에 넣는다: formguide)

logger = logging.getLogger(__name__)

MIN_TEXT = 40  # form_upload.MIN_PDF_TEXT 와 같은 기준: 이보다 글자가 적으면 스캔 PDF
MAX_LAYOUT_PAGES = 6  # 이보다 긴 PDF 는 바탕으로 쓰지 않는다 (값 자리 찾기가 느리고 양식이 아닐 가능성이 크다)
_INPUT_TYPES = {"text", "date", "phone", "address", "number", "checkbox", "account", "id_number", "signature"}


def base_pdf(data: bytes, kind: str) -> bytes | None:
    """원본 규격 바탕 PDF. 만들 수 없으면 None"""
    try:
        if kind == "pdf":
            with pymupdf.open(stream=data, filetype="pdf") as doc:
                if doc.page_count > MAX_LAYOUT_PAGES:
                    return None
                text = "".join(p.get_text() for p in doc)
            return data if len("".join(text.split())) >= MIN_TEXT else None
        if kind == "hwpx":
            from app import hwpx_render

            return hwpx_render.render(data)
    except Exception as e:  # 바탕을 못 만들어도 칸 읽기·표 내보내기는 된다
        logger.warning("양식 바탕 PDF 만들기 실패: %s", type(e).__name__)
    return None


def _form_field(spec: dict):
    from formguide.schema import FormField

    kind = spec.get("input_type")
    return FormField(
        id=spec["field_id"], label=spec["label"], section=spec.get("section"),
        input_type=kind if kind in _INPUT_TYPES else "text",
        value_source=spec.get("value_source") if spec.get("value_source") in ("profile", "user", "handwrite", "office") else "user",
        guide=spec.get("guide") or spec["label"],
    )


MIN_FREE_W = 28  # 글자 오른쪽 빈 자리가 이보다 좁으면 쓸 자리로 보지 않는다 (pt)
MIN_BOXED = 0.5  # 쓸 칸 중 이만큼은 자리를 찾아야 원본 위에 채운다. 아니면 칸 이름·내용 표가 더 읽기 쉽다


def _clean_boxes(pdf: bytes, boxes: dict[str, dict]) -> dict[str, dict]:
    """찾은 자리 다듬기
    - 표가 아닌 줄에서 찾은 자리(칸 이름 오른쪽)는 같은 줄 다음 글자 앞까지로 줄인다. 너무 좁으면 버린다
    - 두 칸이 같은 자리를 받으면 먼저 칸만 남긴다 (나머지는 별지에 적는다)"""
    out: dict[str, dict] = {}
    taken: set[tuple] = set()
    with pymupdf.open(stream=pdf, filetype="pdf") as doc:
        words = {i: doc[i].get_text("words") for i in range(doc.page_count)}
        for fid, box in boxes.items():
            box = dict(box)
            if box.get("how") in ("text_right", "cell_inline"):
                x0, y0, x1, y1 = box["rect"]
                mid = (y0 + y1) / 2
                line = [w for w in words.get(box["page"], []) if w[1] <= mid <= w[3] and w[2] > x0 + 1 and w[0] < x1]
                if any(w[0] <= x0 + 2 for w in line):
                    continue  # 자리 시작이 이미 글자 위
                if line:
                    x1 = min(x1, min(w[0] for w in line) - 3)
                if x1 - x0 < MIN_FREE_W:
                    continue
                box["rect"] = [x0, y0, round(x1, 1), y1]
            key = (box["page"], tuple(round(v) for v in box["rect"]))
            if key in taken:
                continue
            taken.add(key)
            out[fid] = box
    return out


def _flat(text: str | None) -> str:
    return "".join((text or "").split()).rstrip(":")


def _cells(path: Path) -> dict[int, list[tuple[pymupdf.Rect, str]]]:
    """쪽마다 표 칸 (위치, 글자). 값 자리 찾기와 같은 표 읽기(formguide/layout.py)를 쓴다"""
    import pdfplumber
    from formguide.layout import _cells as grids

    out: dict[int, list[tuple[pymupdf.Rect, str]]] = {}
    with pdfplumber.open(path) as pdf:
        for grid in grids(pdf):
            for row in grid:
                for c in row:
                    if c is not None:
                        out.setdefault(c.page, []).append((pymupdf.Rect(c.bbox), _flat(c.text)))
    return out


def _fix_rows(path: Path, fields: list[dict], boxes: dict[str, dict]) -> dict[str, dict]:
    """표 칸 다듬기
    - 반복 줄 (학력 1 · 학력 2 의 "기간" · "학교명"): 앞 줄 칸 바로 아래 칸을 다음 줄 자리로
    - 칸 이름만 적힌 칸 안에 잡힌 자리는 버린다 (항목 칸을 침범하지 않게, 별지에 적는다)"""
    cells = _cells(path)
    out = dict(boxes)
    last_below: dict[str, dict] = {}
    for f in fields:
        fid, label = f["field_id"], _flat(f["label"])
        box = out.get(fid)
        prev = last_below.get(label)
        if prev is not None and (box is None or box.get("how") in ("text_right", "cell_inline", "table_below")):
            px0, _, px1, py1 = prev["rect"]
            under = [
                (r, t) for r, t in cells.get(prev["page"], [])
                if abs(r.y0 - py1) < 3 and r.x0 < (px0 + px1) / 2 < r.x1
            ]
            if under and (box is None or box.get("how") != "table_below" or box["rect"] == prev["rect"]):
                r, t = under[0]
                box = {"page": prev["page"], "rect": [round(v, 1) for v in r], "how": "table_below",
                       "template_text": t or None}
                out[fid] = box
        if box and box.get("how") in ("text_right", "cell_inline"):
            cx = (box["rect"][0] + box["rect"][2]) / 2
            cy = (box["rect"][1] + box["rect"][3]) / 2
            home = [(r, t) for r, t in cells.get(box["page"], []) if r.x0 <= cx <= r.x1 and r.y0 <= cy <= r.y1]
            if home and label and home[0][1] in (label, label + ":"):
                # 칸 이름 칸 안이면 쓰지 않는다. 바로 아래 빈 칸이 있으면 그 칸 (머리줄 아래에 쓰는 표)
                hr = home[0][0]
                below = [
                    (r, t) for r, t in cells.get(box["page"], [])
                    if abs(r.y0 - hr.y1) < 3 and r.x0 < (hr.x0 + hr.x1) / 2 < r.x1 and not t.strip("년월일세만~-.()/:")
                ]
                taken = {(b["page"], tuple(b["rect"])) for k, b in out.items() if k != fid and b}
                below = [(r, t) for r, t in below if (box["page"], tuple(round(v, 1) for v in r)) not in taken]
                if not below:
                    out.pop(fid, None)
                    continue
                r, t = below[0]
                box = {"page": box["page"], "rect": [round(v, 1) for v in r], "how": "table_below", "template_text": t or None}
                out[fid] = box
        if box and box.get("how") == "table_below":
            last_below[label] = box
    return out


# ---------------- 표 구조로 항목 칸 · 값 칸 나누기 ----------------

_SKIP_SLOT = ("사진", "접수", "담당자", "담당공무원", "결재", "처리", "확인자", "비고")
_OPTION_MARK = re.compile(r"\[\s*\]|□|☐|○")
_PHOTO_WORDS = ("사진", "증명사진", "사진첨부", "사진붙이는곳")
_TYPE_WORDS = (
    (("생년월일", "일자", "날짜", "년월일", "입사일", "졸업일", "취득일"), "date"),
    (("연락처", "전화", "휴대", "핸드폰"), "phone"),
    (("주소", "거주지"), "address"),
    (("계좌",), "account"),
)


def _caption(page, top: float) -> str:
    """표 바로 위의 짧은 제목 줄 ("학력사항", "2. 보호기관")"""
    lines = [ln for ln in page.extract_text_lines() if ln["bottom"] <= top + 2 and len(ln["text"].strip()) <= 20]
    return max(lines, key=lambda ln: ln["bottom"])["text"].strip() if lines else ""


def slots(pdf: bytes) -> list[dict]:
    """표마다 값 칸(빈 칸 · "년 월 일" 같은 안내만 있는 칸)과 그 칸의 항목 이름.
    - 왼쪽에 항목 칸이 있으면 그 이름 ("성명 | ____")
    - 왼쪽이 없고 위에 머리줄이 있으면 머리줄 이름 ("기간 | 학교명" 아래 빈 줄). 여러 줄이면 "학력사항 2" 처럼 줄 번호
    - 왼쪽이 첫 열의 줄 이름이고 위에 머리줄도 있으면 머리줄이 항목, 줄 이름이 묶음 ("1지망" 줄의 "학교명")
    돌려주는 것: [{"label", "section", "page", "rect", "template"}] (양식 읽는 순서)"""
    import pdfplumber
    from formguide.layout import MIN_BOX_W, _blank
    from formguide.layout import _cells as grids

    out: list[dict] = []
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "form.pdf"
        path.write_bytes(pdf)
        with pdfplumber.open(path) as doc:
            for grid in grids(doc):
                cells = [c for row in grid for c in row if c is not None]
                if not cells:
                    continue
                page = doc.pages[cells[0].page]
                top = min(c.bbox[1] for c in cells)
                caption = _caption(page, top)
                is_value = {id(c): _blank(c) and c.width >= MIN_BOX_W for c in cells}
                table_w = max(c.bbox[2] for c in cells) - min(c.bbox[0] for c in cells)
                # 표 폭 전체를 한 칸이 차지하는 글자 줄 ("학력사항", "1. 신청인"): 열 머리줄이 아니라 그 아래 줄들의 제목
                titles = {
                    r: " ".join(row_cells[0].text.split()) for r, row in enumerate(grid)
                    if len(row_cells := [c for c in row if c is not None]) == 1
                    and not is_value[id(row_cells[0])] and row_cells[0].width >= table_w * 0.9
                }
                header_rows = [
                    r for r, row in enumerate(grid)
                    if r not in titles and any(c is not None for c in row) and all(c is None or not is_value[id(c)] for c in row)
                    # 선택지 줄 ("[ ]방문 [ ]온라인")이나 긴 안내 글이 있는 줄은 열 머리줄이 아니다
                    and not any(c is not None and (_OPTION_MARK.search(c.text) or len(c.text.strip()) > 20) for c in row)
                    and r + 1 < len(grid) and any(c is not None and is_value[id(c)] for c in grid[r + 1])
                ]
                data_rows: dict[int, int] = {}  # 머리줄 아래 값 줄 번호 (1부터)
                for r, row in enumerate(grid):
                    for c in row:
                        if c is None or not is_value[id(c)]:
                            continue
                        left = next((x for x in reversed(row[: c.col]) if x is not None and not is_value[id(x)]), None)
                        last_title = max((t for t in titles if t < r), default=-1)
                        heads = [h for h in header_rows if last_title < h < r]  # 제목 줄 위 머리줄은 다른 묶음
                        header = None
                        if heads:
                            hrow = grid[heads[-1]]
                            header = next(
                                (x for x in hrow if x is not None and x.bbox[0] < (c.bbox[0] + c.bbox[2]) / 2 < x.bbox[2]),
                                None,
                            )
                        group = c.group or (left.group if left else "")
                        if "".join(group.split()) in _PHOTO_WORDS:  # 왼쪽 "사진" 칸은 묶음 이름이 아니다
                            group = ""
                        title = next((titles[t] for t in sorted(titles, reverse=True) if t < r), "")
                        if header is not None and (left is None or (left.col == 0 and left.text != header.text)):
                            label = header.text
                            if left is not None:
                                section = left.text
                            else:
                                n = data_rows.setdefault(r, len(data_rows) + 1)
                                rows_below = sum(1 for rr in range(heads[-1] + 1, len(grid))
                                                 if any(x is not None and is_value[id(x)] for x in grid[rr]))
                                base = title or caption or group or "줄"
                                section = f"{base} {n}" if rows_below > 1 else (title or caption or group or None)
                        elif left is not None:
                            label, section = left.text, (group or c.section or title or None)
                        else:
                            continue
                        label = " ".join(label.split())
                        if not label or any(w in label.replace(" ", "") for w in _SKIP_SLOT):
                            continue
                        out.append({
                            "label": label, "section": section, "page": c.page,
                            "rect": [round(v, 1) for v in c.bbox], "template": " ".join(c.text.split()) or None,
                        })
    return out


def _flat_label(text: str | None) -> str:
    return "".join((text or "").split()).rstrip(":*")


def from_structure(llm_fields: list[dict], pdf: bytes) -> list[dict] | None:
    """표 구조로 칸 목록을 다시 만든다: 값 칸마다 칸 하나, 자리(box)는 그 값 칸 그대로.
    AI 가 읽은 같은 이름 칸의 종류 · 안내 · 예시 · 선택지는 가져온다. 표 밖 칸(신청일 줄 · 서명)은 AI 칸을 그대로 두고 자리를 찾는다.
    표가 없으면 None"""
    from app import form_upload

    try:
        found = slots(pdf)
    except Exception as e:  # 표를 못 읽는 PDF 여도 올리기는 된다: 칸 이름으로 자리를 찾는 방식으로
        logger.warning("양식 표 구조 읽기 실패: %s", type(e).__name__)
        return None
    if not found:
        return None
    pool = list(llm_fields)

    def take(label: str, section: str | None) -> dict | None:
        flat = _flat_label(label)
        same = [f for f in pool if _flat_label(f["label"]) == flat]
        if not same:
            same = [f for f in pool if flat and (flat in _flat_label(f["label"]) or _flat_label(f["label"]) in flat)]
        if not same:
            return None
        best = next((f for f in same if section and f.get("section") and _flat_label(section) == _flat_label(f["section"])), same[0])
        pool.remove(best)
        return best

    raw: list[dict] = []
    for slot in found:
        hint = take(slot["label"], slot["section"])
        kind = hint.get("input_type") if hint else None
        if not kind:
            flat = _flat_label(slot["label"])
            kind = next((t for words, t in _TYPE_WORDS if any(w in flat for w in words)), "text")
        # 칸 이름은 AI 가 읽은 이름이 같은 칸이면 그 이름 ("성 명" 처럼 칸 글자 사이 띄어쓰기 대신 "성명")
        label = hint["label"] if hint and _flat_label(hint["label"]) == _flat_label(slot["label"]) else slot["label"]
        slot["label"] = label
        raw.append({
            "label": label, "section": slot["section"], "type": kind,
            "guide": hint.get("guide") if hint else None, "example": hint.get("example") if hint else None,
            "options": hint.get("options") if hint else None, "required": hint.get("required") if hint else None,
        })
    fields = form_upload.to_fields(raw, None)
    by_key = {(_flat_label(s["section"]), _flat_label(s["label"])): s for s in found}
    for f in fields:
        slot = by_key.get((_flat_label(f.get("section")), _flat_label(f["label"])))
        if slot:
            f["box"] = {"page": slot["page"], "rect": slot["rect"], "how": "table_slot", "template_text": slot["template"]}
    # 표 밖 칸 · 표에서 자리를 못 찾은 칸 (AI 가 읽은 것 중 남은 것): 신청일 줄 · 서명 · 선택 칸 등.
    # 자리를 못 찾아도 버리지 않는다 (내보낼 때 별지에 적는다). 위에서 만든 표 칸과 같은 칸이면 빼서 두 번 묻지 않는다
    def same_as_slot(f: dict) -> bool:
        flat, sec = _flat_label(f["label"]), _flat_label(f.get("section"))
        for s in found:
            s_label, s_sec = _flat_label(s["label"]), _flat_label(s["section"])
            if flat in (s_label, s_sec + s_label):
                return True
            # "대리인 성명" ↔ 대리인 묶음의 "성명"
            if s_label and flat.endswith(s_label) and s_sec and (s_sec in flat or s_sec == sec):
                return True
        return False

    rest = [f for f in pool if f.get("value_source") in ("profile", "user", "handwrite")]
    if rest:
        located = attach_boxes(rest, pdf, structure=False, gate=False) or [{**f, "box": None} for f in rest]
        used = [pymupdf.Rect(s["rect"]) for s in found]
        for f in located:
            box = f.get("box")
            # 표 값 칸과 겹치는 자리는 이미 위에서 쓴 칸 (같은 칸을 두 번 쓰지 않게)
            if box and any(pymupdf.Rect(box["rect"]).intersects(u) for u in used):
                continue
            if not box and same_as_slot(f):
                continue
            fields.append(f)
    for i, f in enumerate(fields, 1):
        f["field_id"] = f"f{i}"
    return fields


def attach_boxes(fields: list[dict], pdf: bytes, structure: bool = False, gate: bool = True) -> list[dict] | None:
    """칸마다 바탕 PDF 위의 값 자리(box)를 붙인 새 칸 목록.
    쓸 칸 중 자리를 찾은 칸이 너무 적으면 None (칸 이름·내용 표로 내보낸다)"""
    from formguide.layout import locate

    try:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "form.pdf"
            path.write_bytes(pdf)
            boxes = _fix_rows(path, fields, _clean_boxes(pdf, locate(path, [_form_field(f) for f in fields])))
    except Exception as e:
        logger.warning("양식 값 자리 찾기 실패: %s", type(e).__name__)
        return None
    out = []
    for f in fields:
        box = boxes.get(f["field_id"])
        # 체크박스는 양식에서 찾은 선택지 글자로 맞춘다 (LLM 이 준 선택지와 글자가 조금 다를 수 있다)
        if box and f.get("input_type") == "checkbox" and box.get("options"):
            f = {**f, "options": box["options"]}
        out.append({**f, "box": box})
    writable = [f for f in out if f.get("value_source") in ("profile", "user")]
    if gate and (not writable or sum(1 for f in writable if f["box"]) < len(writable) * MIN_BOXED):
        return None
    return out
