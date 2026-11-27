"""layout_spec: 검수한 칸 배치(data/forms/layout)와 양식 JSON 이 맞는지, 칸끼리 겹치지 않는지"""

import json

import pytest

from formguide.layout_spec import FORMS_DIR, LAYOUT_DIR, build

LAYOUTS = sorted(p.stem for p in LAYOUT_DIR.glob("*.json"))


def _area(r):
    return max(1e-6, (r[2] - r[0]) * (r[3] - r[1]))


def _overlap(a, b):
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    return w * h if w > 0 and h > 0 else 0.0


@pytest.mark.parametrize("form_id", LAYOUTS)
def test_saved_form_matches_layout(form_id):
    """배치 파일을 고치고 양식 JSON 을 다시 만들지 않은 채 두지 않게 (python -m formguide.layout_spec)"""
    saved = json.loads((FORMS_DIR / f"{form_id}.json").read_text(encoding="utf-8"))
    built = build(form_id)
    assert [f["id"] for f in saved["fields"]] == [f["id"] for f in built["fields"]]
    for a, b in zip(saved["fields"], built["fields"], strict=True):
        assert (a["box"] or {}).get("rect") == (b["box"] or {}).get("rect"), a["id"]


@pytest.mark.parametrize("form_id", LAYOUTS)
def test_every_field_has_a_place_and_no_two_overlap(form_id):
    spec = json.loads((FORMS_DIR / f"{form_id}.json").read_text(encoding="utf-8"))
    ids = [f["id"] for f in spec["fields"]]
    assert len(ids) == len(set(ids))
    boxes = []
    for f in spec["fields"]:
        b = f["box"]
        assert b, f"{f['id']}: 값 자리 없음"
        r = b["rect"]
        assert r[2] - r[0] >= 4 and r[3] - r[1] >= 4, f["id"]
        if f["input_type"] == "checkbox":
            assert b["options"] and set(b["options"]) == set(b["marks"]), f["id"]
            assert not any("," in o for o in b["options"]), f["id"]  # 여러 개 고른 값 "가, 나" 와 헷갈림
        else:
            boxes.append((f["id"], b["page"], r))
    for i, (fa, pa, ra) in enumerate(boxes):
        for fb, pb, rb in boxes[i + 1:]:
            if pa == pb:
                assert _overlap(ra, rb) <= 0.15 * min(_area(ra), _area(rb)), f"{fa} ↔ {fb} 겹침"
