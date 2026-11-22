"""올린 양식을 원본 규격으로 내보내기 위한 바탕 PDF 와 값 자리 찾기.

- PDF (글자가 있는 PDF): 올린 PDF 를 그대로 바탕으로 쓴다
- 한글 hwpx: 같은 표 구조로 다시 그린 PDF 를 바탕으로 쓴다 (app/hwpx_render.py)
- 사진 · 스캔 PDF · 예전 한글(hwp): 글자 위치를 알 수 없어 바탕이 없다 → 내보내면 칸 이름·내용 표
값 자리(box)는 정책 신청서와 같은 방법으로 찾는다 (formguide/layout.py, 표 칸 이름 오른쪽·아래 빈 칸 등).
값 자리를 하나도 못 찾으면 바탕을 쓰지 않는다. 못 찾은 칸은 내보낼 때 별지에 적는다 (doc_export.render_overlay).

바탕 PDF 는 문서(documents.form_pdf)에 암호화해 저장하고, 문서를 지우거나 탈퇴하면 함께 지워진다.
"""

import logging
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


def attach_boxes(fields: list[dict], pdf: bytes) -> list[dict] | None:
    """칸마다 바탕 PDF 위의 값 자리(box)를 붙인 새 칸 목록.
    쓸 칸 중 자리를 찾은 칸이 너무 적으면 None (칸 이름·내용 표로 내보낸다)"""
    from formguide.layout import locate

    try:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "form.pdf"
            path.write_bytes(pdf)
            boxes = _clean_boxes(pdf, locate(path, [_form_field(f) for f in fields]))
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
    if not writable or sum(1 for f in writable if f["box"]) < len(writable) * MIN_BOXED:
        return None
    return out
