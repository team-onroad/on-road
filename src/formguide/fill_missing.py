"""값 자리(box)를 못 찾은 칸을 글자 위치로 다시 찾는다 (layout.py 가 표 칸으로 못 찾은 것 보완).

layout.attach_boxes 는 표 칸(셀) 기준이라, 줄글 양식("성 명 :  ____")·□ 체크 줄·문답표처럼
표가 아니거나 칸 이름과 값 자리가 같은 셀에 있는 양식에서 많이 놓친다. 여기서는
  1) 칸 이름 글자를 페이지에서 찾고 (띄어쓰기 · ':' · 괄호 무시, 같은 이름은 위에서부터 차례로)
  2) 글자 칸이면 이름 오른쪽 빈 자리, □ · ① 선택이면 선택지마다 표시 자리(marks), 서명이면 "(인)" 앞
을 box 로 둔다. 이미 box 가 있는 칸은 건드리지 않고, 값을 쓰지 않는 칸(처리기간 · 연번 · 머리글)은 찾지 않는다.
자동으로 찾은 자리는 틀릴 수 있어서, 원본 위에 겹쳐 보고 맞다고 확인한 칸(data/forms/fill_missing_review.json)만 저장한다.
틀린 자리에 쓰는 것보다 별지에 적는 편이 낫다.

    cd src && python -m formguide.fill_missing            # data/forms/*.json 전부
    cd src && python -m formguide.fill_missing jindo-proxy --dry-run
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import pdfplumber

FORMS_DIR = Path(__file__).resolve().parents[2] / "data" / "forms"

GLYPHS = "□❏☐○◯"
CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩"
# 값을 쓰는 칸이 아닌 것 (양식이 미리 적어 둔 글자 · 처리기관이 쓰는 칸 · 표 머리글)
NOT_INPUT = re.compile(r"^(처리\s*기간.*|.*심의\s*후\s*\d+일|연\s*번|현\s*행|변\s*경|.*귀하\s*\d*|사업종료일로부터.*|중앙부처.*)$")


def _norm(s: str) -> str:
    return re.sub(r"[\s:：·․ㆍ・\-]", "", s or "")


def _core(label: str) -> list[str]:
    """찾아볼 이름 후보: 전체 → 괄호 뺀 것 → 마지막 낱말 묶음 (섹션 이름이 앞에 붙은 칸)"""
    out = [label]
    no_paren = re.sub(r"\([^)]*\)?|\)", "", label)
    out.append(no_paren)
    for g in GLYPHS + CIRCLED:
        no_paren = no_paren.replace(g, "")
    out.append(no_paren)
    parts = [p for p in re.split(r"\s{2,}|(?<=\))\s", label) if p.strip()]
    if len(parts) > 1:
        out.append(parts[-1])
    words = no_paren.split()
    for k in (3, 2, 1):
        if len(words) > k:
            out.append(" ".join(words[-k:]))
    # 두 줄로 나뉜 긴 이름 (문답표 문항): 앞부분만
    full = _norm(no_paren)
    if len(full) > 16:
        out += [full[:14], full[:10]]
    seen, res = set(), []
    for c in out:
        n = _norm(c)
        if len(n) >= 2 and n not in seen:
            seen.add(n)
            res.append(n)
    return res


class Page:
    def __init__(self, page, index: int):
        self.p = page
        self.i = index
        self.w, self.h = float(page.width), float(page.height)
        self.lines = []  # (chars without spaces, text)
        for ln in page.extract_text_lines(return_chars=True):
            chars = [c for c in ln["chars"] if c["text"].strip()]
            if chars:
                self.lines.append({"chars": chars, "s": "".join(c["text"] for c in chars), "top": ln["top"], "bottom": ln["bottom"]})
        self.cells = []
        for t in page.find_tables():
            for row in t.rows:
                for c in row.cells:
                    if c:
                        self.cells.append(c)
        self.words = page.extract_words()

    def cell_of(self, x: float, y: float):
        best = None
        for c in self.cells:
            if c[0] - 1 <= x <= c[2] + 1 and c[1] - 1 <= y <= c[3] + 1:
                if best is None or (c[2] - c[0]) * (c[3] - c[1]) < (best[2] - best[0]) * (best[3] - best[1]):
                    best = c
        return best


def _find_all(pages: list[Page], needle: str):
    hits = []
    for pg in pages:
        for li, ln in enumerate(pg.lines):
            s = ln["s"]
            norm_map = [k for k, ch in enumerate(s) if _norm(ch)]
            ns = "".join(s[k] for k in norm_map)
            start = 0
            while True:
                j = ns.find(needle, start)
                if j < 0:
                    break
                a, b = norm_map[j], norm_map[j + len(needle) - 1]
                hits.append((pg, li, a, b))
                start = j + 1
    hits.sort(key=lambda h: (h[0].i, h[0].lines[h[1]]["top"], h[0].lines[h[1]]["chars"][h[2]]["x0"]))
    return hits


def _right_space(pg: Page, ln: dict, end_char: int) -> list[float] | None:
    """칸 이름 끝 오른쪽 빈 자리: 같은 줄 다음 글자(15pt 넘게 떨어진 것) 또는 표 칸 오른쪽 끝까지"""
    chars = ln["chars"]
    x0 = chars[end_char]["x1"] + 3
    y_mid = (ln["top"] + ln["bottom"]) / 2
    cell = pg.cell_of(chars[end_char]["x1"] - 1, y_mid)
    limit = cell[2] - 2 if cell else pg.w - 40
    nxt = [c for c in chars[end_char + 1:] if c["x0"] > x0]
    for k, c in enumerate(nxt):
        prev_x = chars[end_char]["x1"] if k == 0 else nxt[k - 1]["x1"]
        if c["x0"] - prev_x > 12 or k == 0 and c["x0"] - x0 > 12:
            limit = min(limit, c["x0"] - 3)
            break
        if k == 0 and c["x0"] - x0 <= 12:
            # 이름 바로 뒤에 글자가 붙어 있음 (예: "연락처)") → 그 글자 뒤부터
            x0 = c["x1"] + 3
    top, bottom = ln["top"] - 3, ln["bottom"] + 3
    if cell:
        top, bottom = max(cell[1] + 1, top), min(cell[3] - 1, bottom)
        if bottom - top < 12:
            top, bottom = cell[1] + 1, cell[3] - 1
    if limit - x0 >= 30:
        return [round(x0, 1), round(top, 1), round(limit, 1), round(bottom, 1)]
    # 이름이 칸을 다 차지 → 오른쪽 빈 칸
    if cell:
        right = [c for c in pg.cells if abs(c[1] - cell[1]) < 3 and c[0] >= cell[2] - 2]
        right.sort(key=lambda c: c[0])
        for c in right[:1]:
            inside = [w for w in pg.words if c[0] <= w["x0"] <= c[2] and c[1] <= w["top"] <= c[3]]
            if not inside and c[2] - c[0] > 25:
                return [round(c[0] + 2, 1), round(c[1] + 1, 1), round(c[2] - 2, 1), round(c[3] - 1, 1)]
    return None


def _clean_option(word: str) -> str:
    """선택지 글자 정리: 앞뒤 ':' · 짝 없는 괄호"""
    word = word.strip().strip(":").strip()
    if word.endswith(")") and "(" not in word:
        word = word[:-1]
    if word.startswith("(") and ")" not in word:
        word = word[1:]
    if word.count("(") > word.count(")"):
        word += ")"
    return re.sub(r"\(\s*\)$", "", word).strip()  # "기타( )" → "기타"



def _option_runs(chars: list[dict]) -> list[tuple[str, dict, dict]]:
    """한 줄 글자에서 (선택지 글자, 표시 글자, 마지막 글자)"""
    out = []
    for idx, c in enumerate(chars):
        if c["text"] in GLYPHS or c["text"] in CIRCLED:
            word, last = "", c
            for d in chars[idx + 1:]:
                if d["text"] in GLYPHS or d["text"] in CIRCLED or d["x0"] - last["x1"] > 8:
                    break
                # 글자 사이가 벌어져 있으면 띄어쓰기 ("동의하지 않음")
                word += (" " if word and d["x0"] - last["x1"] > 2.0 else "") + d["text"]
                last = d
            word = _clean_option(word)
            if word and len(word) <= 20:
                out.append((word, c, last))
        elif c["text"] == "[":
            rest = chars[idx + 1:]
            close = next((j for j, d in enumerate(rest) if d["text"] == "]"), None)
            if close is not None:
                word, last = "", rest[close]
                for d in rest[close + 1:]:
                    if d["text"] in "[□" or d["x0"] - last["x1"] > 8:
                        break
                    word += (" " if word and d["x0"] - last["x1"] > 2.0 else "") + d["text"]
                    last = d
                word = _clean_option(word)
                if word:
                    out.append((word, c, last))
    return out


def _choices(pg: Page, li: int, end_char: int, max_lines: int = 3) -> tuple[list[float], list[str], dict] | None:
    """칸 이름 뒤 □ · ① 선택지와 표시 자리.
    표 안이면 같은 행의 오른쪽 칸들(문답표의 '응답선택지' 열), 아니면 같은 줄 · 아래 몇 줄"""
    ln = pg.lines[li]
    y_mid = (ln["top"] + ln["bottom"]) / 2
    end_x = ln["chars"][end_char]["x1"]
    cell = pg.cell_of(end_x - 1, y_mid)
    cands = []
    if cell:
        band_top, band_bottom = cell[1] - 1, cell[3] + 1
        right = [L for L in pg.lines if band_top <= (L["top"] + L["bottom"]) / 2 <= band_bottom]
        for L in right:
            chars = [c for c in L["chars"] if c["x0"] >= cell[2] - 2]
            cands += [(w, g, last, L) for w, g, last in _option_runs(chars)]
        if not cands:  # 같은 칸 안, 이름 뒤
            for L in right:
                chars = [c for c in L["chars"] if cell[0] <= c["x0"] <= cell[2] and (L is not ln or c["x0"] > end_x)]
                cands += [(w, g, last, L) for w, g, last in _option_runs(chars)]
    else:
        for k in range(li, min(len(pg.lines), li + max_lines + 1)):
            L = pg.lines[k]
            if k > li and L["top"] - pg.lines[k - 1]["bottom"] > 14:
                break
            chars = L["chars"][end_char + 1:] if k == li else L["chars"]
            cands += [(w, g, last, L) for w, g, last in _option_runs(chars)]
    if not cands:
        return None
    marks, options = {}, []
    for word, g, last, L in cands:
        if word in marks:
            continue
        options.append(word)
        marks[word] = [round((g["x0"] + g["x1"]) / 2, 1), round((g["top"] + g["bottom"]) / 2, 1)]
    xs = [g["x0"] for _, g, _, _ in cands] + [last["x1"] for _, _, last, _ in cands]
    ys = [g["top"] for _, g, _, _ in cands] + [g["bottom"] for _, g, _, _ in cands]
    rect = [round(min(xs) - 2, 1), round(min(ys) - 2, 1), round(max(xs) + 2, 1), round(max(ys) + 2, 1)]
    return rect, options, marks


def _sign(pg: Page, li: int, end_char: int) -> list[float] | None:
    for k in range(li, min(len(pg.lines), li + 5)):
        L = pg.lines[k]
        s = L["s"]
        for pat in ("(서명또는인)", "(인)", "(서명)", "서명"):
            j = s.find(pat, end_char + 1 if k == li else 0)
            if j >= 0:
                c = L["chars"][j]
                return [round(c["x0"] - 60, 1), round(L["top"] - 3, 1), round(c["x0"] - 2, 1), round(L["bottom"] + 3, 1)]
    return None


def _clash(box: dict, others: list[dict]) -> bool:
    """다른 칸 자리와 절반 넘게 겹치면 잘못 찾은 것으로 본다 (틀린 자리에 쓰는 것보다 별지가 낫다)"""
    x0, y0, x1, y1 = box["rect"]
    area = max(1.0, (x1 - x0) * (y1 - y0))
    for o in others:
        if o.get("page") != box["page"]:
            continue
        a0, b0, a1, b1 = o["rect"]
        w, h = min(x1, a1) - max(x0, a0), min(y1, b1) - max(y0, b0)
        if w > 0 and h > 0 and w * h > 0.5 * min(area, max(1.0, (a1 - a0) * (b1 - b0))):
            return True
    return False


def locate_missing(spec: dict, pdf_path: Path, accept: set[str] | None = None) -> tuple[dict, list[str]]:
    log = []
    fields = spec["fields"]
    with pdfplumber.open(pdf_path) as pdf:
        pages = [Page(p, i) for i, p in enumerate(pdf.pages)]
        # 같은 이름 칸은 위에서부터 차례로: 이미 쓴 자리 기억
        used: dict[str, int] = {}
        taken = [tuple(f["box"]["rect"]) for f in fields if f.get("box")]
        taken_boxes = [f["box"] for f in fields if f.get("box")]
        for f in fields:
            if f.get("box"):
                continue
            if NOT_INPUT.match(f["label"].strip()) or f["input_type"] == "other":
                continue  # 값을 쓰는 칸이 아님 (처리기간 · 연번 · 머리글)
            box = None
            for needle in _core(f["label"]):
                hits = _find_all(pages, needle)
                n = used.get(needle, 0)
                # 이미 다른 칸 box 안에 있는 글자는 건너뛴다
                for pg, li, a, b in hits[n:]:
                    ln = pg.lines[li]
                    used[needle] = used.get(needle, 0) + 1
                    t = f["input_type"]
                    if t == "checkbox":
                        r = _choices(pg, li, b)
                        if r:
                            rect, options, marks = r
                            box = {"page": pg.i, "rect": rect, "how": "checkbox", "template_text": None,
                                   "options": options, "marks": marks, "found_by": "fill_missing"}
                    elif t == "signature":
                        r = _sign(pg, li, b)
                        if r:
                            box = {"page": pg.i, "rect": r, "how": "text_right", "template_text": None, "found_by": "fill_missing"}
                    else:
                        r = _right_space(pg, ln, b)
                        if r and not any(abs(r[0] - q[0]) < 2 and abs(r[1] - q[1]) < 2 for q in taken):
                            box = {"page": pg.i, "rect": r, "how": "text_right", "template_text": None, "found_by": "fill_missing"}
                    if box:
                        break
                if box:
                    break
            if box and accept is not None and f["id"] not in accept:
                log.append(f"확인 안 됨 (안 씀) {f['label']} → p{box['page']} {box['rect']}")
                box = None
            if box and _clash(box, taken_boxes):
                log.append(f"겹쳐서 버림 {f['label']} → p{box['page']} {box['rect']}")
                box = None
            if box:
                f["box"] = box
                taken_boxes.append(box)
                taken.append(tuple(box["rect"]))
                log.append(f"찾음 {f['label']} → p{box['page']} {box['rect']}" + (f" {box.get('options')}" if box.get("options") else ""))
            else:
                log.append(f"못 찾음 {f['label']} [{f['input_type']}]")
    spec = {**spec, "fields": fields}
    left = [f["label"] for f in fields if not f.get("box") and not (NOT_INPUT.match(f["label"].strip()) or f["input_type"] == "other")]
    spec["warnings"] = [w for w in spec.get("warnings", []) if not w.startswith("값 자리를 못 찾음")]
    if left:
        spec["warnings"].append("값 자리를 못 찾음 (별지에 적음): " + ", ".join(left))
    return spec, log


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("form_ids", nargs="*")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--all", action="store_true", help="확인 목록(fill_missing_review.json)과 상관없이 찾은 자리를 다 쓴다 (검토용)")
    args = ap.parse_args()
    ids = args.form_ids or sorted(
        p.stem for p in FORMS_DIR.glob("*.json")
        if not p.stem.endswith(".extracted") and p.stem not in ("index", "sources", "fill_missing_review")
    )
    review = json.loads((FORMS_DIR / "fill_missing_review.json").read_text(encoding="utf-8"))
    for fid in ids:
        accept = None if args.all else set(review.get(fid, []))
        path = FORMS_DIR / f"{fid}.json"
        spec = json.loads(path.read_text(encoding="utf-8"))
        if all(f.get("box") for f in spec["fields"]):
            continue
        new, log = locate_missing(spec, FORMS_DIR / f"{fid}.pdf", accept)
        print(f"== {fid}")
        for line in log:
            print("  ", line)
        added = [f for f in new["fields"] if (f.get("box") or {}).get("found_by")]
        if added and not args.dry_run:
            path.write_text(json.dumps(new, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
