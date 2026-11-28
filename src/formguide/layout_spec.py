"""사람이 원본과 대조해 적은 칸 배치(data/forms/layout/{form_id}.json)로 양식 칸 · 값 자리를 만든다.

formguide.build(AI 추출) + layout.py(표 칸 자동 매칭)는 칸을 빠뜨리거나 다른 칸 자리에 붙이는 일이 많아,
검수한 양식은 칸마다 '어디에 쓰는지'를 글자 기준으로 적어 두고 좌표는 여기서 PDF 선 · 글자 위치로 계산한다.

배치 파일 형식
{
  "form_id": "child-leave-care",
  "fields": [
    {"id": "applicant_name", "label": "성명", "section": "신청인", "input_type": "text", "guide": "…", "example": "…",
     "at": {"right_of": "성명#1"}},                     # 글자 오른쪽 ~ 다음 세로선(또는 "to")까지, 위아래는 가로선
    {"id": "period_start", "at": {"right_of": "재직기간", "to": "~"}},    # '~' 앞 (시작)
    {"id": "period_end", "at": {"right_of": "~#1"}},                    # '~' 뒤 (끝)
    {"id": "reason", "at": {"below": "퇴소 사유"}},       # 글자 아래 칸 (가로선 사이)
    {"id": "reason", "at": {"cell_right": "퇴소 사유"}},  # 글자가 든 칸의 오른쪽 칸 전체
    {"id": "kind", "input_type": "checkbox", "at": {"choices": "종료[", "line": true}},  # 그 줄(또는 칸)의 [ ] · □ · ① 선택지
    {"id": "date", "input_type": "date", "at": {"date": "년#2"}},  # "년 월 일" 줄
    {"id": "x", "at": {"rect": [x0, y0, x1, y1]}}       # 직접
  ]
}
글자는 띄어쓰기 없이 비교하고 "#n" 은 페이지에서 n 번째(위 → 아래, 왼 → 오른). "page" 로 쪽(0부터) 지정.

    cd src && python -m formguide.layout_spec child-leave-care          # data/forms/{id}.json 다시 만들기
    cd src && python -m formguide.layout_spec child-leave-care --inspect out/   # 쪽마다 선 · 좌표 눈금 그림 + 글자 목록
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import pymupdf

FORMS_DIR = Path(__file__).resolve().parents[2] / "data" / "forms"
LAYOUT_DIR = FORMS_DIR / "layout"
BOX_GLYPHS = "□❏☐▢"
CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮"


def _norm(s: str) -> str:
    return re.sub(r"\s", "", s or "")


class Page:
    def __init__(self, page: pymupdf.Page, index: int):
        self.i = index
        self.w, self.h = page.rect.width, page.rect.height
        self.chars = []  # 줄 순서 글자 (공백 없이) {c, x0, y0, x1, y1}
        raw = page.get_text("rawdict")
        lines = []
        for b in raw["blocks"]:
            for ln in b.get("lines", []):
                cs = [ch for sp in ln["spans"] for ch in sp["chars"] if ch["c"].strip()]
                if cs:
                    lines.append(cs)
        lines.sort(key=lambda cs: (round(cs[0]["bbox"][1] / 3), cs[0]["bbox"][0]))
        rows = []
        for cs in lines:
            row = [{"c": ch["c"], "x0": ch["bbox"][0], "y0": ch["bbox"][1], "x1": ch["bbox"][2], "y1": ch["bbox"][3]} for ch in cs]
            # 같은 높이에 따로 놓인 글자 묶음("□", "미", "정")은 한 줄로 (글자 사이 띄움이 넓은 양식)
            same = next((r for r in rows if abs(r[0]["y0"] - row[0]["y0"]) < 1.5 and abs(r[0]["y1"] - row[0]["y1"]) < 1.5
                         and not any(c["x0"] < row[-1]["x1"] and c["x1"] > row[0]["x0"] for c in r)), None)
            if same is not None:
                same.extend(row)
                same.sort(key=lambda c: c["x0"])
            else:
                rows.append(row)
        self.lines = rows
        self.H, self.V = [], []  # 가로선 (y, x0, x1) · 세로선 (x, y0, y1)
        for d in page.get_drawings():
            for it in d["items"]:
                if it[0] == "l":
                    a, b = it[1], it[2]
                    self._seg(a.x, a.y, b.x, b.y)
                elif it[0] == "re":
                    r = it[1]
                    if r.height < 2.5 and r.width > 4:
                        self.H.append(((r.y0 + r.y1) / 2, r.x0, r.x1))
                    elif r.width < 2.5 and r.height > 4:
                        self.V.append(((r.x0 + r.x1) / 2, r.y0, r.y1))
                    elif r.width > 4 and r.height > 4:
                        self._seg(r.x0, r.y0, r.x1, r.y0)
                        self._seg(r.x0, r.y1, r.x1, r.y1)
                        self._seg(r.x0, r.y0, r.x0, r.y1)
                        self._seg(r.x1, r.y0, r.x1, r.y1)

    def _seg(self, x0, y0, x1, y1):
        if abs(y0 - y1) < 1.5 and abs(x1 - x0) > 4:
            self.H.append(((y0 + y1) / 2, min(x0, x1), max(x0, x1)))
        elif abs(x0 - x1) < 1.5 and abs(y1 - y0) > 4:
            self.V.append(((x0 + x1) / 2, min(y0, y1), max(y0, y1)))

    # ---- 글자 찾기
    def find(self, ref: str) -> tuple[list[dict], dict]:
        """'성명#2' → (그 글자들, 그 줄)."""
        text, _, n = ref.partition("#")
        n = int(n or 1)
        needle = _norm(text)
        hits = []
        for row in self.lines:
            s = "".join(c["c"] for c in row)
            start = 0
            while (j := s.find(needle, start)) >= 0:
                hits.append((row[j: j + len(needle)], row))
                start = j + 1
        hits.sort(key=lambda h: (round(h[0][0]["y0"] / 3), h[0][0]["x0"]))
        if len(hits) < abs(n) or n == 0:
            raise KeyError(f"쪽 {self.i}: '{ref}' 를 찾지 못함 ({len(hits)}개)")
        return hits[n - 1] if n > 0 else hits[n]  # "#-1": 마지막

    # ---- 선
    def rule_above(self, x0, x1, y):
        ys = [h[0] for h in self.H if h[0] <= y + 0.5 and h[1] <= x0 + 2 and h[2] >= x1 - 2]
        return max(ys) if ys else None

    def rule_below(self, x0, x1, y):
        ys = [h[0] for h in self.H if h[0] >= y - 0.5 and h[1] <= x0 + 2 and h[2] >= x1 - 2]
        return min(ys) if ys else None

    def rule_right(self, x, y0, y1):
        xs = [v[0] for v in self.V if v[0] >= x - 0.5 and v[1] <= y0 + 2 and v[2] >= y1 - 2]
        return min(xs) if xs else None

    def rule_left(self, x, y0, y1):
        xs = [v[0] for v in self.V if v[0] <= x + 0.5 and v[1] <= y0 + 2 and v[2] >= y1 - 2]
        return max(xs) if xs else None

    def row_end(self, x, y):
        """y 근처 가로선 중 x 를 지나는 것의 오른쪽 끝 (표 오른쪽 테두리가 없는 양식)"""
        xs = [h[2] for h in self.H if abs(h[0] - y) < 3 and h[1] <= x <= h[2]]
        return max(xs) if xs else self.w - 40

    def words_in(self, rect):
        out = []
        for row in self.lines:
            for c in row:
                if c["x0"] >= rect[0] - 0.5 and c["x1"] <= rect[2] + 0.5 and c["y0"] >= rect[1] - 1 and c["y1"] <= rect[3] + 1:
                    out.append(c)
        return out


def _row_bounds(pg: Page, x0, x1, y0, y1, gap: float = 24, pad: float = 4):
    """글자 줄을 감싼 가로선 (없으면 글자 높이 ± 4). gap: 선을 찾는 거리 (높은 칸은 크게)"""
    top = pg.rule_above(x0, x1, y0)
    bot = pg.rule_below(x0, x1, y1)
    if top is None or y0 - top > gap:
        top = y0 - pad
    if bot is None or bot - y1 > gap:
        bot = y1 + pad
    return top, bot


def _rect(pg: Page, at: dict) -> tuple[list[float], dict]:
    extra: dict = {}
    if "rect" in at and "marks_xy" not in at:
        return list(at["rect"]), extra
    if "right_of" in at:
        seg, row = pg.find(at["right_of"])
        x = seg[-1]["x1"] + 2
        ly0, ly1 = min(c["y0"] for c in seg), max(c["y1"] for c in seg)
        top, bot = _row_bounds(pg, x, x + 10, ly0, ly1, at.get("gap", 24), at.get("pad", 4))
        if "rows" in at:
            top, bot = at["rows"]
        if "to_in_row" in at:  # 같은 줄에서 칸 이름 뒤 처음 나오는 글자 앞까지 ("월저축액: ____ 원")
            k = row.index(seg[-1])
            s_row = "".join(c["c"] for c in row)
            j = s_row.find(_norm(at["to_in_row"]), k + 1)
            if j < 0:
                raise KeyError(f"'{at['right_of']}' 줄에 '{at['to_in_row']}' 없음")
            pad = at.get("pad", 1.5)
            return [x, ly0 - pad, row[j]["x0"] - 2, ly1 + pad], extra
        if "to" in at:
            to = at["to"]
            if isinstance(to, (int, float)):
                right = to
            else:
                tseg, _ = pg.find(to)
                right = tseg[0]["x0"] - 2
        else:
            right = pg.rule_right(x + 1, top + 2, bot - 2)
            if right is None:
                right = pg.row_end(x, top)
            elif right - x < 40:  # 칸 이름만 든 칸 → 오른쪽 칸 전체
                x = right + 1
                nxt = pg.rule_right(x + 1, top + 2, bot - 2)
                right = nxt if nxt is not None else pg.row_end(x, top)
        if "x0" in at:
            x = at["x0"]
        return [x, top + 1, right - 1, bot - 1], extra
    if "cell_right" in at:
        seg, _ = pg.find(at["cell_right"])
        ly0, ly1 = min(c["y0"] for c in seg), max(c["y1"] for c in seg)
        top, bot = _row_bounds(pg, seg[0]["x0"], seg[-1]["x1"], ly0, ly1)
        if "rows" in at:
            top, bot = at["rows"]
        left = pg.rule_right(seg[-1]["x1"], top + 2, bot - 2)
        right = pg.rule_right(left + 2, top + 2, bot - 2) if left is not None else None
        if left is not None and right is None:
            right = pg.row_end(left + 2, top)
        if "to" in at:
            right = at["to"] if isinstance(at["to"], (int, float)) else pg.find(at["to"])[0][0]["x0"] - 2
        if left is None or right is None:
            raise KeyError(f"'{at['cell_right']}' 오른쪽 칸을 못 찾음")
        return [left + 1, top + 1, right - 1, bot - 1], extra
    if "under" in at:  # 같은 칸 안, 칸 이름 글자 아래 (이름이 칸 폭을 다 차지하는 칸)
        seg, _ = pg.find(at["under"])
        ly0, ly1 = min(c["y0"] for c in seg), max(c["y1"] for c in seg)
        cx0, cx1 = seg[0]["x0"], seg[-1]["x1"]
        bot = pg.rule_below(cx0, cx1, ly1) or ly1 + 16
        left = pg.rule_left(cx0, ly0, bot - 1)
        right = pg.rule_right(cx1, ly0, bot - 1)
        right = right if right is not None else pg.row_end(cx0, bot)
        if "to" in at:
            right = at["to"]
        return [(left if left is not None else cx0 - 3) + 1, ly1 + 0.5, right - 1, bot - 1], extra
    if "below" in at:
        seg, _ = pg.find(at["below"])
        ly0, ly1 = min(c["y0"] for c in seg), max(c["y1"] for c in seg)
        cx0, cx1 = seg[0]["x0"], seg[-1]["x1"]
        under = pg.rule_below(cx0, cx1, ly1)
        if under is None:
            under = ly1 + 2
        bot = at.get("bottom") or pg.rule_below(cx0, cx1, under + 3) or under + 20
        left = pg.rule_left(cx0, under + 2, bot - 2)
        right = pg.rule_right(cx1, under + 2, bot - 2)
        if "x" in at:
            left, right = at["x"]
        return [(left if left is not None else cx0 - 4) + 1, under + 1, (right if right is not None else cx1 + 4) - 1, bot - 1], extra
    if "date" in at:
        seg, row = pg.find(at["date"])  # "년" 글자 (또는 "20")
        y0, y1 = min(c["y0"] for c in row), max(c["y1"] for c in row)
        s = "".join(c["c"] for c in row)
        k = row.index(seg[0])
        # "년 … 일" 까지 (뒤쪽 첫 "일")
        end = next((c for c in row[k:] if c["c"] == "일"), row[-1])
        start_x = at.get("from") or (row[k - 1]["x1"] + 2 if k > 0 and row[k]["x0"] - row[k - 1]["x1"] < 40 and not s[k - 1].isdigit() else seg[0]["x0"] - 32)
        if k > 0 and s[k - 1].isdigit():  # "20 년" 처럼 앞에 숫자가 찍혀 있음
            j = k - 1
            while j > 0 and s[j - 1].isdigit():
                j -= 1
            start_x = row[j]["x0"] - 2
        extra["how"] = "date_line"
        pad = at.get("pad", 3)
        return [start_x, y0 - pad, end["x1"] + 2, y1 + pad], extra
    if "choices" in at:
        seg, row = pg.find(at["choices"])
        y0 = min(c["y0"] for c in seg)
        rows = [row] + [o for o in pg.lines if o is not row and abs(o[0]["y0"] - row[0]["y0"]) < 3]  # 같은 줄로 보이는 글자 묶음
        if at.get("rows_below"):
            for other in pg.lines:
                oy = other[0]["y0"]
                if 0 < oy - y0 <= at["rows_below"] and other is not row:
                    rows.append(other)
        x_lo = at.get("x", [0, pg.w])[0]
        x_hi = at.get("x", [0, pg.w])[1]
        options, marks, xs, ys = [], {}, [], []
        only = at.get("options")
        for r in rows:
            for i, c in enumerate(r):
                if not (x_lo <= c["x0"] <= x_hi):
                    continue
                mark = None
                if c["c"] in BOX_GLYPHS or c["c"] in CIRCLED:
                    mark, start = (c, c), i + 1
                elif c["c"] == "[":
                    close = next((j for j in range(i + 1, min(i + 6, len(r))) if r[j]["c"] == "]"), None)
                    if close is not None:
                        mark, start = (c, r[close]), close + 1
                if mark is None:
                    continue
                word, last = "", mark[1]
                for d in r[start:]:
                    if d["c"] in BOX_GLYPHS or d["c"] in CIRCLED or d["c"] == "[" or d["x0"] - last["x1"] > at.get("word_gap", 9):
                        break
                    word += (" " if word and d["x0"] - last["x1"] > 2.2 else "") + d["c"]
                    last = d
                word = re.sub(r"\(\s*\)?$", "", word.strip().strip(":")).strip()
                if word.endswith(")") and word.count(")") > word.count("("):
                    word = word[:-1]
                if not word or (only and word not in only):
                    continue
                if word in marks:
                    continue
                options.append(word)
                marks[word] = [round((mark[0]["x0"] + mark[1]["x1"]) / 2, 1), round((mark[0]["y0"] + mark[1]["y1"]) / 2, 1)]
                xs += [mark[0]["x0"], last["x1"]]
                ys += [mark[0]["y0"], mark[1]["y1"]]
        if not options:
            raise KeyError(f"'{at['choices']}' 줄에 선택지가 없음")
        if at.get("names"):  # 선택지 이름 바꾸기 (표시 글자가 없는 칸: "동의" 등)
            options = [at["names"].get(o, o) for o in options]
            marks = {at["names"].get(k, k): v for k, v in marks.items()}
        extra.update(how="checkbox", options=options, marks=marks)
        return [min(xs) - 2, min(ys) - 2, max(xs) + 2, max(ys) + 2], extra
    if "marks" in at:  # {"선택지": "종료["} — 참조 글자의 마지막 글자([ · □ · ①)가 표시 자리 (선택지 글자가 표시 앞에 있는 양식)
        marks, xs, ys = {}, [], []
        for opt, ref in at["marks"].items():
            seg, row = pg.find(ref)
            c = seg[-1]
            x1 = c["x1"]
            if c["c"] == "[":
                k = row.index(c)
                close = next((d for d in row[k + 1: k + 6] if d["c"] == "]"), None)
                if close is not None:
                    x1 = close["x1"]
            marks[opt] = [round((c["x0"] + x1) / 2, 1), round((c["y0"] + c["y1"]) / 2, 1)]
            xs += [seg[0]["x0"], x1]
            ys += [c["y0"], c["y1"]]
        extra.update(how="checkbox", options=list(marks), marks=marks)
        return [min(xs) - 2, min(ys) - 2, max(xs) + 2, max(ys) + 2], extra
    if "words" in at:  # 괄호 안 낱말에 ○ 표시: {"선택지": "참조"} (예: "(저축, 저축안함)")
        marks, circles, xs, ys = {}, {}, [], []
        for opt, ref in at["words"].items():
            seg, _ = pg.find(ref)
            body = [c for c in seg if c["c"] not in "(),:"] or seg
            x0, y0, x1, y1 = body[0]["x0"], min(c["y0"] for c in body), body[-1]["x1"], max(c["y1"] for c in body)
            marks[opt] = [round((x0 + x1) / 2, 1), round((y0 + y1) / 2, 1)]
            circles[opt] = [round(x0 - 2, 1), round(y0 - 1.5, 1), round(x1 + 2, 1), round(y1 + 1.5, 1)]
            xs += [x0, x1]
            ys += [y0, y1]
        extra.update(how="checkbox", options=list(marks), marks=marks, circles=circles)
        return [min(xs) - 3, min(ys) - 3, max(xs) + 3, max(ys) + 3], extra
    if "marks_xy" in at:  # 선택지 표시 자리를 좌표로 (글자 없는 네모 칸: "동의 [  ] 미동의 [  ]")
        marks = {k: [round(v[0], 1), round(v[1], 1)] for k, v in at["marks_xy"].items()}
        xs, ys = [v[0] for v in marks.values()], [v[1] for v in marks.values()]
        extra.update(how="checkbox", options=list(marks), marks=marks)
        return at.get("rect") or [min(xs) - 10, min(ys) - 8, max(xs) + 10, max(ys) + 8], extra
    if "around" in at:  # 글자 둘레 ("(인)" 자리 등)
        seg, _ = pg.find(at["around"])
        pad = at.get("pad", 6)
        return [seg[0]["x0"] - pad, min(c["y0"] for c in seg) - 4, seg[-1]["x1"] + pad, max(c["y1"] for c in seg) + 4], extra
    if "mark" in at:  # 표시 글자 없이 □ 하나 (동의 칸): 선택지는 at["option"]
        seg, _ = pg.find(at["mark"])
        c = seg[0]
        opt = at.get("option", "확인")
        extra.update(how="checkbox", options=[opt], marks={opt: [round((c["x0"] + c["x1"]) / 2, 1), round((c["y0"] + c["y1"]) / 2, 1)]})
        return [c["x0"] - 2, c["y0"] - 2, c["x1"] + 2, c["y1"] + 2], extra
    raise KeyError(f"모르는 at: {at}")


FIELD_KEYS = ("id", "label", "section", "input_type", "value_source", "profile_key", "required", "guide", "example",
              "evidence", "sensitive")


def build(form_id: str) -> dict:
    layout = json.loads((LAYOUT_DIR / f"{form_id}.json").read_text(encoding="utf-8"))
    spec_path = FORMS_DIR / f"{form_id}.json"
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    old = {f["id"]: f for f in spec["fields"]}
    doc = pymupdf.open(FORMS_DIR / f"{form_id}.pdf")
    pages = [Page(doc[i], i) for i in range(doc.page_count)]
    fields = []
    for lf in layout["fields"]:
        # 배치 파일이 기준: 적지 않은 값은 기본값 (예전 AI 추출 값은 예시만, 같은 종류 칸일 때 가져온다)
        base = old.get(lf.get("from_old", lf["id"]), {})
        f = {
            "id": lf["id"], "label": lf["label"], "section": lf.get("section") or None,
            "input_type": lf.get("input_type", "text"), "value_source": lf.get("value_source", "user"),
            "profile_key": lf.get("profile_key"),
            # 담당자 기재란은 신청인이 채우는 칸이 아니라 필수가 아니다
            "required": lf.get("required", lf.get("value_source", "user") != "office"),
            "guide": lf.get("guide") or lf["label"], "example": lf.get("example"),
            "evidence": lf.get("evidence") or lf["label"], "sensitive": bool(lf.get("sensitive")),
        }
        if f["example"] is None and base.get("input_type") == f["input_type"] and not f["sensitive"]:
            ex = base.get("example")
            f["example"] = ex if ex not in ("null", "") else None
        at = lf.get("at")
        if at is None:
            f["box"] = None
        else:
            pg = pages[at.get("page", lf.get("page", 0))]
            rect, extra = _rect(pg, at)
            rect = [round(v, 1) for v in rect]
            how = extra.pop("how", None) or at.get("how") or ("text_right" if "right_of" in at else "table_below" if "below" in at else "table_right")
            tmpl = "".join(c["c"] for c in pg.words_in(rect)).strip() or None
            box = {"page": pg.i, "rect": rect, "how": how, "template_text": tmpl if how != "checkbox" else None, **extra}
            if lf.get("sign_mark"):
                box["sign_mark"] = lf["sign_mark"]
            if lf.get("cover"):  # 칸 안의 예시 글자(000000-0000000 · ○○○○)를 지우고 쓴다
                box["cover"] = True
            if lf.get("align"):
                box["align"] = lf["align"]
            if box.get("options"):  # 선택지 이름에 쉼표가 있으면 여러 개 고른 값("가, 나")과 헷갈린다 → 가운뎃점
                fix = lambda o: re.sub(r",\s*", "·", o)  # noqa: E731
                box["options"] = [fix(o) for o in box["options"]]
                for k in ("marks", "circles"):
                    if box.get(k):
                        box[k] = {fix(o): v for o, v in box[k].items()}
            box["found_by"] = "layout_spec"
            f["box"] = box
        if f["input_type"] == "checkbox" and f["box"] and not f["box"].get("options"):
            raise KeyError(f"{lf['id']}: 체크박스인데 선택지가 없음")
        fields.append(f)
    ids = [f["id"] for f in fields]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        raise ValueError(f"칸 id 중복: {dup}")
    spec["fields"] = fields
    left = [f["label"] for f in fields if not f.get("box") and f["value_source"] in ("user", "profile")]
    spec["warnings"] = [w for w in spec.get("warnings", []) if not w.startswith("값 자리를 못 찾음")]
    if left:
        spec["warnings"].append("값 자리를 못 찾음 (별지에 적음): " + ", ".join(left))
    if layout.get("title"):
        spec["title"] = layout["title"]
    return spec


def inspect(form_id: str, out: Path, scale: float = 1.6) -> None:
    """선(파랑) · 50pt 눈금 · 글자 목록 (검수용)"""
    from PIL import ImageDraw

    doc = pymupdf.open(FORMS_DIR / f"{form_id}.pdf")
    out.mkdir(parents=True, exist_ok=True)
    for i in range(doc.page_count):
        pg = Page(doc[i], i)
        pix = doc[i].get_pixmap(matrix=pymupdf.Matrix(scale, scale))
        from PIL import Image

        im = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        d = ImageDraw.Draw(im)
        for x in range(0, int(pg.w), 50):
            d.line([(x * scale, 0), (x * scale, 8)], fill=(200, 0, 0))
            d.text((x * scale + 1, 8), str(x), fill=(200, 0, 0))
        for y in range(0, int(pg.h), 50):
            d.line([(0, y * scale), (8, y * scale)], fill=(200, 0, 0))
            d.text((9, y * scale - 5), str(y), fill=(200, 0, 0))
        im.save(out / f"{form_id}-{i}.png")
        with open(out / f"{form_id}-{i}.txt", "w", encoding="utf-8") as fp:
            for row in pg.lines:
                fp.write(f"{row[0]['x0']:6.1f} {row[0]['y0']:6.1f}  {''.join(c['c'] for c in row)}\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("form_ids", nargs="*")
    ap.add_argument("--inspect", type=Path)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    ids = args.form_ids or sorted(p.stem for p in LAYOUT_DIR.glob("*.json"))
    for fid in ids:
        if args.inspect:
            inspect(fid, args.inspect)
            continue
        spec = build(fid)
        n = sum(1 for f in spec["fields"] if f.get("box"))
        print(f"{fid}: 칸 {len(spec['fields'])}, 자리 {n}")
        if not args.dry_run:
            (FORMS_DIR / f"{fid}.json").write_text(json.dumps(spec, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
