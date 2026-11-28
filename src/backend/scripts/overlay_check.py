"""양식 내보내기 검수: 칸마다 견본 값을 하나씩 채워 원본 위에 그려 보고, 값이 제자리에 들어갔는지 본다.

칸 하나씩 채워서 새로 생긴 글자만 그 칸의 값으로 본다.
- outside: 값 글자가 칸 자리(box)를 벗어남 (옆 칸 · 위아래 칸 침범)
- collide: 값 글자가 원래 찍힌 글자(칸 이름 · 안내)와 겹침
- spill: 칸에 안 들어가 별지로 넘어감 (긴 글 칸은 정상일 수 있음)
- missing: 아무것도 그려지지 않음

    cd src/backend && python scripts/overlay_check.py                # 전부
    cd src/backend && python scripts/overlay_check.py welfare-apply  # 하나만
    cd src/backend && python scripts/overlay_check.py --png out/     # 문제 칸이 있는 쪽을 그림으로 (값 빨강 테두리)
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pymupdf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from app import doc_export, doc_spec  # noqa: E402
from formguide.schema import FormSpec  # noqa: E402

FORMS = Path(__file__).resolve().parents[3] / "data" / "forms"
TOL = 1.5  # 칸 밖으로 이만큼은 봐준다 (pt)

SAMPLES = {
    "date": "2007.03.15",
    "phone": "010-1234-5678",
    "account": "110-123-456789",
    "address": "서울특별시 강남구 테헤란로 123, 101동 202호",
    "email": "name@example.com",
}


def sample(f: dict) -> str:
    if f.get("options"):
        return f["options"][0]
    if (f.get("box") or {}).get("how") == "confirm":
        return "확인"
    if f["input_type"] == "phone" and "유선" in f["label"]:
        return "02-123-4567"
    if f["input_type"] in SAMPLES:
        return SAMPLES[f["input_type"]]
    label = "".join(f["label"].split())
    if f.get("example") and not (f.get("essay") or f.get("multiline")) and "○" not in f["example"]:
        return f["example"]  # 양식에 적힌 예시가 가장 그럴듯한 값
    if label.endswith(("성명", "이름", "예금주")) or f.get("profile_key") == "name":
        return "홍길동"
    if f.get("essay") or f.get("multiline"):
        return "대학 재학 중이라 졸업할 때까지 생활비와 주거비 지원이 필요합니다."
    if label.endswith(("귀하", "귀중")):
        return "서울특별시"
    if "나이" in label:
        return "20"
    if "은행" in label:
        return "국민은행"
    if "관계" in label:
        return "본인"
    return "견본 내용"


def words(page: pymupdf.Page) -> list[tuple]:
    return [tuple(round(v, 1) for v in w[:4]) + (w[4],) for w in page.get_text("words")]


def overlap(a, b) -> float:
    x0, y0, x1, y1 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    if x1 <= x0 or y1 <= y0:
        return 0.0
    area = (a[2] - a[0]) * (a[3] - a[1]) or 1
    return (x1 - x0) * (y1 - y0) / area


def check(form_id: str, png: Path | None = None) -> list[tuple[str, str, str]]:
    spec = FormSpec.model_validate_json((FORMS / f"{form_id}.json").read_text(encoding="utf-8"))
    fields = doc_spec.from_form_spec(spec)
    pdf = FORMS / f"{form_id}.pdf"
    base = pymupdf.open(pdf)
    base_words = {i: words(base[i]) for i in range(base.page_count)}
    issues: list[tuple[str, str, str]] = []
    bad_pages: dict[int, list] = {}
    for f in fields:
        if f["value_source"] not in doc_spec.WRITABLE or not f.get("box"):
            continue
        value = sample(f)
        rows = [(g, value if g["field_id"] == f["field_id"] else "") for g in fields]
        out = pymupdf.open(stream=doc_export.render_overlay(pdf, spec.title, rows), filetype="pdf")
        box = f["box"]
        p = box["page"]
        name = f"{f['field_id']} ({f['label']})"
        if out.page_count > base.page_count and not (f.get("essay") or f.get("multiline")):
            issues.append((name, "spill", value))
        before = base_words[p]
        new = [w for w in words(out[p]) if w not in before]
        how = box.get("how")
        if not new and how not in ("checkbox", "confirm"):
            if not (out.page_count > base.page_count):
                issues.append((name, "missing", value))
            continue
        r = box["rect"]
        for w in new:
            if w[4] in ("○", "✓", "V", "√"):
                continue
            if w[0] < r[0] - TOL or w[2] > r[2] + TOL or w[1] < r[1] - TOL or w[3] > r[3] + TOL:
                issues.append((name, "outside", f"'{w[4]}' {w[:4]} 칸 {r}"))
                bad_pages.setdefault(p, []).append(w)
                break
        # 내보내기가 흰 상자로 덮은 예시 글자(000000-0000000, ○○, (예시)…)는 겹쳐도 안 보인다
        covers = [d["rect"] for d in out[p].get_drawings() if d.get("fill") == (1.0, 1.0, 1.0)]
        before_shown = [b for b in before if not any(c.contains(pymupdf.Rect(b[:4])) or overlap(b, tuple(c)) > 0.8 for c in covers)]
        for w in new:
            # 밑줄(____) 위에 쓰는 것은 정상
            def hidden(b):  # 겹친 부분이 흰 상자로 덮였으면 보이지 않는다
                cut = pymupdf.Rect(max(w[0], b[0]), max(w[1], b[1]), min(w[2], b[2]), min(w[3], b[3]))
                return any(pymupdf.Rect(c).contains(cut) for c in covers)
            hit = next((b for b in before_shown if overlap(w, b) > 0.25 and b[4].strip("_,.") != "" and not hidden(b)), None)
            if hit:
                issues.append((name, "collide", f"'{w[4]}' 와 원래 글자 '{hit[4]}'"))
                bad_pages.setdefault(p, []).append(w)
                break
    if png and bad_pages:
        png.mkdir(parents=True, exist_ok=True)
        allrows = [(g, sample(g)) for g in fields]
        out = pymupdf.open(stream=doc_export.render_overlay(pdf, spec.title, allrows), filetype="pdf")
        for p, ws in bad_pages.items():
            page = out[p]
            for w in ws:
                page.draw_rect(pymupdf.Rect(w[:4]), color=(1, 0, 0), width=0.8)
            page.get_pixmap(dpi=110).save(png / f"{form_id}-{p}.png")
    return issues


def form_ids() -> list[str]:
    return sorted(p.stem for p in FORMS.glob("*.pdf") if (FORMS / f"{p.stem}.json").exists())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("form_ids", nargs="*")
    ap.add_argument("--png", type=Path)
    args = ap.parse_args()
    total = 0
    for fid in args.form_ids or form_ids():
        issues = check(fid, args.png)
        total += len(issues)
        print(f"{fid:28s} 문제 {len(issues)}")
        for name, kind, detail in issues:
            print(f"   {kind:8s} {name}: {detail}")
    print(f"합계 {total}")


if __name__ == "__main__":
    main()
