"""양식 PDF 글자 다시 그리기.

HWP 를 PDF 로 바꾸는 과정에서 글꼴이 잘못 들어가면 글자 정보(텍스트)는 맞는데 화면에는 다른 글자로 보인다
(예: 실제 자립수당 신청서의 "아니오" → "✔니오", "전자우편" → "ᅥ자우편").
글자를 모두 지우고 같은 자리에 글자 하나씩 PyMuPDF 내장 한글 글꼴로 다시 쓴다. 선·표는 그대로 둔다.

- 같은 줄에서 다른 글자 묶음과 겹치는 글자는 겹치지 않게 오른쪽으로 민다 (예: "신청" + "합니다")
- 빈 양식에 미리 그려진 체크 표시(작은 네모 안의 짧은 선)는 지운다. 사용자가 고른 칸은 내보낼 때 다시 그린다
"""

from pathlib import Path

import pymupdf

FONT_NAME = "fgredraw"  # 내보낼 때 쓰는 글꼴 이름(onroadkr)과 겹치지 않게
BOLD_STROKE = 0.035  # 굵은 글자는 테두리를 같이 그려 굵게 보이게 (글자 크기 대비)
OVERLAP = 0.3  # 앞 글자와 이 비율 넘게 겹치면 민다
CHECK_MAX = 8.0  # 미리 그려진 체크 표시 크기 (pt)
BOX_MAX = 14.0  # 체크 표시를 감싼 네모 크기 (pt)


def _font() -> pymupdf.Font:
    return pymupdf.Font("cjk")


def has_broken_fonts(pdf_path: Path) -> bool:
    """글꼴 파일이 선언(TrueType)과 다른 PDF (CFF 가 들어 있음). 모든 깨짐을 잡지는 못하니 눈으로도 확인한다"""
    with pymupdf.open(pdf_path) as doc:
        for pno in range(doc.page_count):
            for xref, ext, *_ in doc.get_page_fonts(pno):
                try:
                    _, _, _, buf = doc.extract_font(xref)
                except Exception:  # noqa: BLE001, S112  (읽을 수 없는 글꼴은 건너뜀)
                    continue
                if ext == "ttf" and buf[:1] == b"\x01":
                    return True
    return False


def _chars(page: pymupdf.Page) -> tuple[list[dict], list[pymupdf.Rect]]:
    """(다시 쓸 글자, 지울 영역). 깨진 글자는 공백으로 뽑히기도 해서 지울 때는 글자 묶음(span) 전체를 지운다"""
    out, areas = [], []
    span_no = 0
    for block in page.get_text("rawdict")["blocks"]:
        if block.get("type") != 0:
            continue
        for line in block["lines"]:
            for span in line["spans"]:
                areas.append(pymupdf.Rect(span["bbox"]))
                bold = bool(span["flags"] & 16) or "bold" in span["font"].lower()
                for ch in span["chars"]:
                    # 띄어쓰기도 글자로 남겨야 다시 뽑을 때 "동의하지 않음"이 "동의하지않음"으로 붙지 않는다
                    if ch["c"].strip() or ch["c"] == " ":
                        out.append({
                            "c": ch["c"], "x": ch["origin"][0], "y": ch["origin"][1], "bbox": ch["bbox"],
                            "size": span["size"], "bold": bold, "color": span["color"], "span": span_no,
                        })
                span_no += 1
    return out, areas


def _unoverlap(chars: list[dict], font: pymupdf.Font) -> None:
    """같은 줄에서 다른 묶음 글자와 겹치면 그 묶음의 나머지 글자를 함께 오른쪽으로 민다"""
    lines: list[list[dict]] = []
    for ch in sorted(chars, key=lambda c: (round(c["y"]), c["x"])):
        if lines and abs(lines[-1][0]["y"] - ch["y"]) < 1.5:
            lines[-1].append(ch)
        else:
            lines.append([ch])
    for line in lines:
        shift: dict[int, float] = {}
        prev = None
        for ch in sorted(line, key=lambda c: c["x"]):
            ch["x"] += shift.get(ch["span"], 0.0)
            w = font.text_length(ch["c"], fontsize=ch["size"])
            if prev is not None and prev["span"] != ch["span"]:
                prev_end = prev["x"] + font.text_length(prev["c"], fontsize=prev["size"])
                if prev_end - ch["x"] > w * OVERLAP:
                    delta = prev_end - ch["x"] + 0.5
                    shift[ch["span"]] = shift.get(ch["span"], 0.0) + delta
                    ch["x"] += delta
            prev = ch


def _preset_checks(page: pymupdf.Page) -> list[pymupdf.Rect]:
    """작은 네모 안에 그려진 짧은 선(체크 표시) 영역"""
    drawings = page.get_drawings()
    boxes = [
        d["rect"] for d in drawings
        if d.get("fill") is not None and 4 <= d["rect"].width <= BOX_MAX and 4 <= d["rect"].height <= BOX_MAX
    ]
    out = []
    for d in drawings:
        r = d["rect"]
        if (
            d["type"] == "s" and r.width <= CHECK_MAX and r.height <= CHECK_MAX
            and all(item[0] == "l" for item in d["items"]) and 2 <= len(d["items"]) <= 3
            and any(b.contains(r) for b in boxes)
        ):
            out.append(r + (-1, -1, 1, 1))
    return out


def redraw_text(src: Path, dst: Path) -> int:
    """다시 쓴 글자 수. dst 에 저장"""
    doc = pymupdf.open(src)
    font = _font()
    total = 0
    for page in doc:
        checks = _preset_checks(page)
        if checks:
            for r in checks:
                page.add_redact_annot(r)
            page.apply_redactions(
                images=pymupdf.PDF_REDACT_IMAGE_NONE,
                graphics=pymupdf.PDF_REDACT_LINE_ART_REMOVE_IF_TOUCHED,
                text=pymupdf.PDF_REDACT_TEXT_NONE,
            )
        chars, areas = _chars(page)
        if not chars:
            continue
        for area in areas:
            page.add_redact_annot(area)
        page.apply_redactions(
            images=pymupdf.PDF_REDACT_IMAGE_NONE,
            graphics=pymupdf.PDF_REDACT_LINE_ART_NONE,
            text=pymupdf.PDF_REDACT_TEXT_REMOVE,
        )
        _unoverlap(chars, font)
        page.insert_font(fontname=FONT_NAME, fontbuffer=font.buffer)
        for ch in chars:
            size = ch["size"]
            width = ch["bbox"][2] - ch["bbox"][0]
            natural = font.text_length(ch["c"], fontsize=size)
            if width > 0 and natural > width * 1.15:  # 원래 글자 폭보다 많이 넓으면 살짝 줄인다
                size *= max(0.8, width * 1.15 / natural)
            c = ch["color"]
            kwargs = {
                "fontname": FONT_NAME, "fontsize": size,
                "color": ((c >> 16 & 255) / 255, (c >> 8 & 255) / 255, (c & 255) / 255),
            }
            if ch["bold"]:
                kwargs.update(render_mode=2, border_width=BOLD_STROKE * size / 10)
            page.insert_text((ch["x"], ch["y"]), ch["c"], **kwargs)
            total += 1
    doc.subset_fonts()
    doc.save(dst, garbage=3, deflate=True)
    doc.close()
    return total
