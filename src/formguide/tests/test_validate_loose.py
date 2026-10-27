"""validate.check_fields: 구역 이름을 붙인 칸 이름, 표 구분자가 섞인 근거 (실제 자립수당 신청서에서 버려지던 경우)."""

from formguide.extract import FormText, Page
from formguide.validate import check_fields

FORM = FormText([Page(1, "대리인\n성명 주민등록번호\n전화:", [[["대리인", "", "성명", "", "전화:", ""]]])])


def test_label_with_group_prefix_is_kept():
    fields, warnings = check_fields(
        [{"id": "g_name", "label": "대리인 성명", "guide": "g"}, {"id": "x", "label": "보호자 성명", "guide": "g"}], FORM,
    )
    assert [f.label for f in fields] == ["대리인 성명"]
    assert warnings == ["'보호자 성명': 양식 원문에 없는 칸 이름이라 버림"]


def test_evidence_with_table_separators_is_kept():
    fields, warnings = check_fields(
        [{"id": "a", "label": "성명", "guide": "g", "evidence": "대리인 | 성명"},
         {"id": "b", "label": "전화:", "guide": "g", "evidence": "대리인 > 전화:"},
         {"id": "c", "label": "주민등록번호", "guide": "g", "evidence": "없는 문장"}], FORM,
    )
    assert [f.evidence for f in fields] == ["대리인 | 성명", "대리인 > 전화:", None]
    assert warnings == ["'주민등록번호': 근거 문장이 원문과 달라 근거를 지움 (근거: '없는 문장')"]


def test_evidence_joined_from_table_cells_is_kept():
    fields, _ = check_fields([{"id": "a", "label": "성명", "guide": "g", "evidence": "대리인 성명 전화:"}], FORM)
    assert fields[0].evidence == "대리인 성명 전화:"
