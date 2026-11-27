"""양식 입력칸 전수 검사: 원본 PDF 에서 값을 쓸 수 있는 자리를 모두 찾아 양식 JSON 의 칸(box)과 대조한다.

찾는 자리 (후보)
- 표 칸: 빈 칸, 안내 글자만 있는 칸("년 월 일", "( )", "~", "원", "-"), 칸 이름 오른쪽이 넓게 빈 칸
- 줄글: 밑줄(선 · "____"), "성 명 :" 처럼 ':' 뒤 빈자리, "( )" 괄호 안
- 날짜 · 숫자: "20 . . .", "년 월 일"
- 선택: □ · ❏ · [ ] · ① ② · O/X 와 그 뒤 선택지 글자
- '~' 로 나뉜 자리(재직기간 등)는 시작 · 끝 두 자리

대조
- uncovered: 어느 칸 box 와도 안 겹치는 후보 (누락 의심)
- suspicious: 칸 box 안에 안내가 아닌 글자(제목 · 설명)가 많음 (오검출 의심), 다른 칸 box 와 겹침 (침범),
  같은 자리를 두 칸이 씀 (중복)

    cd src && python -m formguide.audit                    # 전부, 요약
    cd src && python -m formguide.audit welfare-apply --png out/   # 겹쳐 그린 그림 (초록: 칸, 빨강: 누락 후보, 주황: 의심)
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import pdfplumber

FORMS_DIR = Path(__file__).resolve().parents[2] / "data" / "forms"

BOX_GLYPHS = "□❏☐■▢"
CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮"
# 값 자리 안에 원래 찍혀 있는 안내 글자 (이것만 있으면 빈 칸으로 본다)
TEMPLATE_TOKENS = re.compile(r"^(년|월|일|시|분|세|원|만원|천원|개월|명|호|층|동|번지|[~\-–.·:/()（）,\s]|20|19|\d{1,2}|만|\(인\)|\(서명\)|\(서명또는인\)|서명또는인|인|%|평|㎡|m2|부)+$")
SKIP_PAGE_TEXT = ("유의사항", "작성방법", "처리절차", "개인정보", "안내")


@dataclass
class Cand:
    page: int
    rect: list[float]
    kind: str  # cell · cell_right · underline · colon · paren · date · choice
    note: str = ""
    options: list[str] = field(default_factory=list)
    marks: dict = field(default_factory=dict)
    id: str = ""

    def area(self) -> float:
        return max(1.0, (self.rect[2] - self.rect[0]) * (self.rect[3] - self.rect[1]))


def _r(v: float) -> float:
    return round(float(v), 1)


def _overlap(a, b) -> float:
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    return w * h if w > 0 and h > 0 else 0.0


def _words_in(words, rect, pad=0.5):
    return [w for w in words if w["x0"] >= rect[0] - pad and w["x1"] <= rect[2] + pad and w["top"] >= rect[1] - pad and w["bottom"] <= rect[3] + pad]


def _is_template(text: str) -> bool:
    t = text.replace(" ", "")
    return not t or bool(TEMPLATE_TOKENS.match(t))


def _choice_runs(chars):
    """한 줄 글자에서 □/①/[ ] 표시와 그 뒤 선택지 글자"""
    out = []
    for i, c in enumerate(chars):
        mark = None
        if c["text"] in BOX_GLYPHS or c["text"] in CIRCLED:
            mark, start = c, i + 1
        elif c["text"] == "[":
            close = next((j for j in range(i + 1, min(i + 6, len(chars))) if chars[j]["text"] == "]"), None)
            if close is not None and chars[close]["x0"] - c["x1"] < 20:
                mark, start = c, close + 1
                mark = {**c, "x1": chars[close]["x1"]}
        if mark is None:
            continue
        word, last = "", mark
        for d in chars[start:]:
            if d["text"] in BOX_GLYPHS or d["text"] in CIRCLED or d["text"] == "[" or d["x0"] - last["x1"] > 8:
                break
            word += (" " if word and d["x0"] - last["x1"] > 2.0 else "") + d["text"]
            last = d
        word = word.strip().strip(":").strip()
        out.append((word, mark, last))
    return out


def _lines(page):
    out = []
    for ln in page.extract_text_lines(return_chars=True):
        chars = [c for c in ln["chars"] if c["text"].strip()]
        if chars:
            out.append({"chars": chars, "s": "".join(c["text"] for c in chars), "top": ln["top"], "bottom": ln["bottom"],
                        "x0": chars[0]["x0"], "x1": chars[-1]["x1"]})
    return out


def candidates(pdf_path: Path) -> list[Cand]:
    out: list[Cand] = []
    with pdfplumber.open(pdf_path) as pdf:
        for pi, page in enumerate(pdf.pages):
            words = page.extract_words(keep_blank_chars=False, use_text_flow=False)
            lines = _lines(page)
            cells = []
            for t in page.find_tables():
                for c in t.cells:
                    if c and c[2] - c[0] > 6 and c[3] - c[1] > 6:
                        cells.append(tuple(c))
            # 다른 칸을 품은 큰 칸(표 바깥 테두리)은 뺀다
            cells = [c for c in cells if not any(o != c and _overlap(o, c) >= 0.9 * ((o[2] - o[0]) * (o[3] - o[1])) for o in cells)]
            in_cell = lambda x, y: next((c for c in cells if c[0] - 1 <= x <= c[2] + 1 and c[1] - 1 <= y <= c[3] + 1), None)  # noqa: E731

            # 1) 표 칸
            for c in cells:
                ws = _words_in(words, c, 1)
                text = "".join(w["text"] for w in ws)
                rect = [_r(c[0] + 1), _r(c[1] + 1), _r(c[2] - 1), _r(c[3] - 1)]
                if any(g in text for g in BOX_GLYPHS + CIRCLED) or "[" in text:
                    continue  # 선택 칸은 아래 3) 에서
                if _is_template(text):
                    if "~" in text:  # 시작 ~ 끝
                        tilde = next(w for w in ws if "~" in w["text"])
                        mid = (tilde["x0"] + tilde["x1"]) / 2
                        out.append(Cand(pi, [rect[0], rect[1], _r(mid - 3), rect[3]], "cell", "~ 앞 (시작)"))
                        out.append(Cand(pi, [_r(mid + 3), rect[1], rect[2], rect[3]], "cell", "~ 뒤 (끝)"))
                    else:
                        out.append(Cand(pi, rect, "cell", text[:20]))
                    continue
                # 칸 이름 + 오른쪽 빈자리 (이름이 칸 왼쪽 45% 안, 오른쪽 40pt 넘게 빔)
                right = max(w["x1"] for w in ws)
                tail = [w for w in ws if w["x0"] > (c[0] + c[2]) / 2]
                if not tail and c[2] - right > 40 and right - c[0] < 0.5 * (c[2] - c[0]) and len(text) <= 16:
                    out.append(Cand(pi, [_r(right + 3), rect[1], rect[2], rect[3]], "cell_right", text[:16]))

            # 2) 줄글: 밑줄 선 · "____"
            for ln in page.lines + [r for r in page.rects if r["height"] < 1.6]:
                if abs(ln["top"] - ln["bottom"]) > 1.6 or ln["x1"] - ln["x0"] < 25:
                    continue
                y = ln["top"]
                if any(abs(y - c[1]) < 1.5 or abs(y - c[3]) < 1.5 for c in cells if c[0] - 2 <= ln["x0"] and ln["x1"] <= c[2] + 2):
                    continue  # 표 테두리
                if in_cell((ln["x0"] + ln["x1"]) / 2, y - 3):
                    pass
                above = [w for w in words if w["bottom"] <= y + 1 and w["bottom"] > y - 14 and w["x0"] >= ln["x0"] - 1 and w["x1"] <= ln["x1"] + 1]
                if above and not _is_template("".join(w["text"] for w in above)):
                    continue  # 글자에 그은 밑줄 (강조)
                out.append(Cand(pi, [_r(ln["x0"]), _r(y - 14), _r(ln["x1"]), _r(y + 1)], "underline"))
            for ln in lines:
                s = ln["s"]
                for m in re.finditer(r"_{3,}", s):
                    a, b = ln["chars"][m.start()], ln["chars"][m.end() - 1]
                    out.append(Cand(pi, [_r(a["x0"]), _r(ln["top"] - 3), _r(b["x1"]), _r(ln["bottom"] + 2)], "underline", "____"))

            # 3) 선택 (□ ① [ ] O/X): 같은 줄 · 같은 칸의 선택지를 한 묶음
            for ln in lines:
                runs = _choice_runs(ln["chars"])
                if not runs:
                    continue
                groups: dict = {}
                for word, mark, last in runs:
                    cell = in_cell((mark["x0"] + mark["x1"]) / 2, (mark["top"] + mark["bottom"]) / 2)
                    groups.setdefault(cell, []).append((word, mark, last))
                for cell, rs in groups.items():
                    # 문항 앞 번호(① 향후 1년 …)는 선택지가 아니다: 선택지 글자가 길면 뺀다
                    rs = [r for r in rs if 0 < len(r[0]) <= 24 or r[1]["text"] in BOX_GLYPHS + "["]
                    if not rs:
                        continue
                    xs = [r[1]["x0"] for r in rs] + [r[2]["x1"] for r in rs]
                    rect = [_r(min(xs) - 1.5), _r(ln["top"] - 2), _r(max(xs) + 1.5), _r(ln["bottom"] + 2)]
                    marks = {r[0] or f"#{k}": [_r((r[1]["x0"] + r[1]["x1"]) / 2), _r((r[1]["top"] + r[1]["bottom"]) / 2)] for k, r in enumerate(rs)}
                    out.append(Cand(pi, rect, "choice", options=[r[0] or f"#{k}" for k, r in enumerate(rs)], marks=marks))
                # O/X
                for m in re.finditer(r"\(?O\s*/\s*X\)?|O,X", ln["s"]):
                    a, b = ln["chars"][m.start()], ln["chars"][m.end() - 1]
                    out.append(Cand(pi, [_r(a["x0"] - 2), _r(ln["top"] - 2), _r(b["x1"] + 2), _r(ln["bottom"] + 2)], "choice", "O/X", ["O", "X"]))

            # 4) 날짜 · 숫자: "20 . . ." · "년 월 일" (표 밖)
            for ln in lines:
                s = ln["s"]
                for pat in (r"(?:20|19)?\s*\.?\s*\.\s*\.", r"년\s*월\s*일"):
                    for m in re.finditer(pat, s):
                        seg = ln["chars"][m.start(): m.end()]
                        if len(seg) < 2 or seg[-1]["x1"] - seg[0]["x0"] < 30:
                            continue
                        first = seg[0]
                        # "년" 앞 숫자 자리까지
                        x0 = first["x0"] - (30 if s[m.start()] == "년" else 2)
                        if in_cell(first["x0"], (ln["top"] + ln["bottom"]) / 2):
                            continue  # 표 칸은 1) 에서
                        out.append(Cand(pi, [_r(x0), _r(ln["top"] - 3), _r(seg[-1]["x1"] + 2), _r(ln["bottom"] + 3)], "date",
                                        "".join(c["text"] for c in seg)))

            # 5) ':' 뒤 빈자리 · "( )" 괄호 안
            for ln in lines:
                chars = ln["chars"]
                for i, c in enumerate(chars):
                    nxt = chars[i + 1] if i + 1 < len(chars) else None
                    gap_end = nxt["x0"] if nxt else min(page.width - 30, (in_cell(c["x1"], c["top"] + 2) or (0, 0, page.width - 30))[2])
                    if c["text"] in ":：" and gap_end - c["x1"] > 40:
                        out.append(Cand(pi, [_r(c["x1"] + 3), _r(ln["top"] - 3), _r(gap_end - 3), _r(ln["bottom"] + 3)], "colon"))
                    if c["text"] in "(（" and nxt and nxt["text"] in ")）" and nxt["x0"] - c["x1"] > 15:
                        out.append(Cand(pi, [_r(c["x1"] + 1), _r(ln["top"] - 2), _r(nxt["x0"] - 1), _r(ln["bottom"] + 2)], "paren"))
    # 같은 자리 후보 하나로
    uniq: list[Cand] = []
    for c in out:
        if any(u.page == c.page and _overlap(u.rect, c.rect) > 0.7 * min(u.area(), c.area()) for u in uniq):
            continue
        uniq.append(c)
    for i, c in enumerate(uniq):
        c.id = f"c{i}"
    return uniq


def audit(form_id: str) -> dict:
    spec = json.loads((FORMS_DIR / f"{form_id}.json").read_text(encoding="utf-8"))
    pdf_path = FORMS_DIR / f"{form_id}.pdf"
    cands = candidates(pdf_path)
    fields = spec["fields"]
    boxes = [(f, f["box"]) for f in fields if f.get("box")]
    report = {"form_id": form_id, "fields": len(fields), "no_box": [f["id"] for f in fields if not f.get("box")],
              "uncovered": [], "suspicious": [], "overlap": [], "duplicate": []}
    for c in cands:
        hit = False
        for f, b in boxes:
            if b["page"] != c.page:
                continue
            if _overlap(b["rect"], c.rect) >= 0.35 * c.area():
                hit = True
                break
            if b.get("marks") and any(c.rect[0] - 3 <= x <= c.rect[2] + 3 and c.rect[1] - 3 <= y <= c.rect[3] + 3 for x, y in b["marks"].values()):
                hit = True
                break
        if not hit:
            report["uncovered"].append(c)
    with pdfplumber.open(pdf_path) as pdf:
        words_by_page = [p.extract_words() for p in pdf.pages]
    for f, b in boxes:
        if b.get("how") in ("checkbox", "confirm"):
            continue
        ws = _words_in(words_by_page[b["page"]], b["rect"], 0.5)
        foreign = "".join(w["text"] for w in ws if not _is_template(w["text"]))
        label = re.sub(r"\s", "", f["label"])
        if len(foreign) > 8 and foreign not in label:
            report["suspicious"].append((f["id"], foreign[:30]))
    for i, (f, b) in enumerate(boxes):
        for g, c in boxes[i + 1:]:
            if b["page"] == c["page"] and b.get("how") != "checkbox" and c.get("how") != "checkbox":
                ov = _overlap(b["rect"], c["rect"])
                if ov > 0.3 * min(_area(b["rect"]), _area(c["rect"])):
                    (report["duplicate"] if ov > 0.85 * min(_area(b["rect"]), _area(c["rect"])) else report["overlap"]).append((f["id"], g["id"]))
    return report


def _area(r) -> float:
    return max(1.0, (r[2] - r[0]) * (r[3] - r[1]))


def draw(form_id: str, report: dict, out_dir: Path, scale: float = 1.4) -> list[Path]:
    import pypdfium2 as pdfium
    from PIL import ImageDraw

    spec = json.loads((FORMS_DIR / f"{form_id}.json").read_text(encoding="utf-8"))
    pdf = pdfium.PdfDocument(str(FORMS_DIR / f"{form_id}.pdf"))
    imgs = [pdf[i].render(scale=scale).to_pil().convert("RGB") for i in range(len(pdf))]
    bad = {fid for fid, _ in report["suspicious"]} | {x for pair in report["overlap"] + report["duplicate"] for x in pair}
    for n, f in enumerate(spec["fields"]):
        b = f.get("box")
        if not b:
            continue
        d = ImageDraw.Draw(imgs[b["page"]])
        col = (230, 120, 0) if f["id"] in bad else (0, 150, 60)
        r = [v * scale for v in b["rect"]]
        d.rectangle(r, outline=col, width=2)
        d.text((r[0] + 2, r[1] + 1), f"f{n}", fill=col)
        for x, y in (b.get("marks") or {}).values():
            d.ellipse([x * scale - 4, y * scale - 4, x * scale + 4, y * scale + 4], outline=col, width=2)
    for c in report["uncovered"]:
        d = ImageDraw.Draw(imgs[c.page])
        r = [v * scale for v in c.rect]
        d.rectangle(r, outline=(220, 0, 0), width=2)
        d.text((r[0] + 2, r[3] - 11), c.id, fill=(220, 0, 0))
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    for i, im in enumerate(imgs):
        p = out_dir / f"{form_id}-{i}.png"
        im.save(p)
        paths.append(p)
    return paths


def form_ids() -> list[str]:
    return sorted(p.stem for p in FORMS_DIR.glob("*.json")
                  if not p.stem.endswith(".extracted") and p.stem not in ("index", "sources", "fill_missing_review", "fixes"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("form_ids", nargs="*")
    ap.add_argument("--png", type=Path)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    for fid in args.form_ids or form_ids():
        rep = audit(fid)
        if args.png:
            draw(fid, rep, args.png)
        if args.json:
            print(json.dumps({**rep, "uncovered": [vars(c) for c in rep["uncovered"]]}, ensure_ascii=False))
        else:
            print(f"{fid:28} 칸 {rep['fields']:3}  자리없음 {len(rep['no_box']):2}  누락후보 {len(rep['uncovered']):3}  "
                  f"의심 {len(rep['suspicious']):2}  겹침 {len(rep['overlap']):2}  중복 {len(rep['duplicate']):2}")


if __name__ == "__main__":
    main()
