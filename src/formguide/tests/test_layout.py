"""값 자리(좌표) 찾기 (formguide.layout). 가상 표 양식 PDF 를 쓴다."""

import pytest

from formguide.layout import attach_boxes, locate, save_layout_pdf
from formguide.schema import FormField, FormSpec
from formguide.tests.sample_form import make_form


def f(fid, label, **kw):
    return FormField(id=fid, label=label, guide="안내", **kw)


FIELDS = [
    f("name", "성명", value_source="profile", profile_key="name"),
    f("birth", "생년월일", input_type="date"),
    f("rrn", "주민등록번호", value_source="handwrite", sensitive=True),
    f("addr", "주소"),
    f("phone", "연락처"),
    f("end", "보호종료일", input_type="date"),
    f("reason", "신청 사유"),
    f("acct", "수급 계좌", value_source="handwrite", sensitive=True),
    f("rcpt", "접수번호", value_source="office"),
    f("appl", "신청인", value_source="handwrite"),
]


@pytest.fixture
def form(tmp_path):
    return make_form(tmp_path / "form.pdf")


def test_finds_value_cells(form):
    boxes = locate(form, FIELDS)

    assert boxes["name"] == {"page": 0, "rect": [130.0, 100.0, 300.0, 128.0], "how": "table_right", "template_text": None}
    assert boxes["birth"]["rect"] == [380.0, 100.0, 545.0, 128.0]  # 한 줄에 칸 두 개
    assert boxes["addr"]["rect"] == [130.0, 156.0, 545.0, 184.0]  # 병합된 넓은 칸
    assert boxes["end"]["template_text"] == "년 월 일"  # 날짜 칸은 안내 글자가 있어도 빈 칸으로 본다
    assert boxes["reason"]["how"] == "table_below"  # 칸 이름 아래 큰 칸
    assert boxes["reason"]["rect"] == [50.0, 236.0, 545.0, 326.0]
    assert boxes["appl"]["how"] == "text_right"  # 표 밖 "신청인 ____"
    assert "acct" not in boxes  # 안내 글자("은행명 / 계좌번호")가 있는 칸에는 쓰지 않는다


def test_attach_boxes_warns_missing(form, tmp_path):
    spec = FormSpec(form_id="X", policy_key="P", title="t", source="s",
                    fields=FIELDS + [f("memo", "추가 메모")])
    out = attach_boxes(spec, form)
    assert next(x for x in out.fields if x.id == "name").box["how"] == "table_right"
    assert out.warnings == ["값 자리를 못 찾음 (별지에 적음): 추가 메모"]
    assert attach_boxes(out, form).warnings == out.warnings  # 다시 돌려도 경고가 쌓이지 않음



def test_save_layout_pdf(form, tmp_path):
    out_dir = tmp_path / "forms"
    out_dir.mkdir()
    copied = save_layout_pdf(form, out_dir, "X")
    assert copied == out_dir / "X.pdf"
    assert copied.read_bytes() == form.read_bytes()
