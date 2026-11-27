"""fill_missing: 표 칸으로 못 찾은 값 자리를 글자 위치로 찾기 (data/forms 의 실제 양식으로)"""

import json

import pytest

from formguide.fill_missing import FORMS_DIR, _clean_option, locate_missing


def _spec_without_new_boxes(form_id: str) -> dict:
    spec = json.loads((FORMS_DIR / f"{form_id}.json").read_text(encoding="utf-8"))
    for f in spec["fields"]:
        if (f.get("box") or {}).get("found_by"):
            f["box"] = None
    return spec


@pytest.mark.skipif(not (FORMS_DIR / "jindo-proxy.pdf").exists(), reason="양식 PDF 없음")
def test_colon_labels_get_space_to_the_right():
    spec, _ = locate_missing(_spec_without_new_boxes("jindo-proxy"), FORMS_DIR / "jindo-proxy.pdf")
    boxes = {f["id"]: f["box"] for f in spec["fields"]}
    # 위임자 · 수임자 같은 이름 칸은 위에서부터 차례로
    assert boxes["delegate_name"]["rect"][1] < boxes["agent_name"]["rect"][1]
    # "성 명 : ___ (인)  연락처) ___" → 성명은 연락처 앞에서 끝난다
    assert boxes["delegate_name"]["rect"][2] < boxes["delegate_phone"]["rect"][0]


@pytest.mark.skipif(not (FORMS_DIR / "youth-counsel-survey.pdf").exists(), reason="양식 PDF 없음")
def test_survey_choices_in_answer_column():
    spec, _ = locate_missing(_spec_without_new_boxes("youth-counsel-survey"), FORMS_DIR / "youth-counsel-survey.pdf")
    box = next(f["box"] for f in spec["fields"] if f["id"] == "university_preparation")
    assert box["how"] == "checkbox" and box["options"] == ["아니오", "예"]
    assert set(box["marks"]) == {"아니오", "예"}


def test_only_reviewed_boxes_are_kept():
    spec = _spec_without_new_boxes("jindo-proxy")
    out, log = locate_missing(spec, FORMS_DIR / "jindo-proxy.pdf", accept={"delegate_name"})
    found = [f["id"] for f in out["fields"] if (f.get("box") or {}).get("found_by")]
    assert found == ["delegate_name"]
    assert any(line.startswith("확인 안 됨") for line in log)


def test_clean_option():
    assert _clean_option("기타( )") == "기타"
    assert _clean_option("대학장학금(각급대학") == "대학장학금(각급대학)"
    assert _clean_option("동의함:") == "동의함"
