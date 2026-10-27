"""값 자리(좌표) 찾기 (formguide.layout). 가상 표 양식 PDF 를 쓴다."""

import pytest

from formguide.layout import attach_boxes, label_hints, locate, save_layout_pdf
from formguide.schema import FormField, FormSpec
from formguide.tests.sample_form import make_form


def f(fid, label, section="1. 신청인", **kw):
    return FormField(id=fid, label=label, section=section, guide="안내", **kw)


FIELDS = [
    f("name", "성명", value_source="profile", profile_key="name"),
    f("birth", "생년월일", input_type="date"),
    f("addr", "주소"),
    f("phone", "전화:"),
    f("holder", "예금주"),
    f("seizure", "압류방지통장 여부", input_type="checkbox"),
    f("g_name", "대리인 성명"),
    f("g_phone", "대리인 전화:"),
    f("notice", "통지방법", input_type="checkbox"),
    f("agency_phone", "전화:", section="2. 보호기관"),
    f("reason", "신청 사유", section=None),
    f("tel", "전화 :", section="2. 보호기관"),
    f("mob", "휴대전화 :", section="2. 보호기관"),
    f("kind", "자립수당 지급/의료비 지원", section=None, input_type="checkbox"),
    f("agree", "동의함", section=None, input_type="checkbox"),
    f("date", "년 월 일", section=None, input_type="date"),
    f("sign", "신청인", section=None, value_source="handwrite"),
]


@pytest.fixture
def boxes(tmp_path):
    return locate(make_form(tmp_path / "form.pdf"), FIELDS)


def test_value_cells(boxes):
    assert boxes["name"] == {"page": 0, "rect": [130.0, 95.0, 300.0, 123.0], "how": "table_right", "template_text": None}
    assert boxes["birth"]["rect"] == [380.0, 95.0, 545.0, 123.0]  # 한 줄에 칸 두 개
    assert boxes["reason"]["how"] == "table_below"  # 칸 이름 아래 큰 칸
    assert boxes["holder"]["rect"][0] == 138.0  # ":" 구분 셀은 건너뛴다
    assert boxes["sign"]["how"] == "text_right"  # 표 밖 "신청인 ____"


def test_repeated_labels_follow_group_and_section(boxes):
    assert boxes["phone"]["rect"][1] == 123.0  # 묶음 이름이 없으면 먼저 나오는 대상아동 칸
    assert boxes["g_name"]["rect"][1] == 199.0  # "대리인 성명" → 대리인 묶음의 성명
    assert boxes["g_phone"]["rect"][1] == 227.0
    assert boxes["agency_phone"]["rect"][1] == 303.0  # 구역 "2. 보호기관"의 전화


def test_checkbox_options(boxes):
    assert boxes["seizure"]["how"] == "checkbox"
    assert boxes["seizure"]["options"] == ["네", "아니오"]
    assert boxes["notice"]["options"] == ["서면", "전자우편", "문자메시지"]
    assert boxes["notice"]["rect"][0] == 130.0  # 칸 이름 오른쪽 영역


def test_inline_labels_in_one_cell(boxes):
    tel, mob = boxes["tel"], boxes["mob"]
    assert tel["how"] == mob["how"] == "cell_inline"  # 같은 셀을 두 칸이 나눠 쓴다
    assert tel["rect"][1] < mob["rect"][1]
    assert tel["rect"][2] == mob["rect"][2] == 543.0  # 셀 오른쪽 끝까지


def test_square_and_text_checkboxes(boxes):
    assert boxes["kind"]["options"] == ["자립수당 지급", "의료비 지원"]  # □ 네모 선택지
    assert set(boxes["kind"]["marks"]) == {"자립수당 지급", "의료비 지원"}
    assert boxes["agree"]["options"] == ["동의함", "동의하지 않음"]  # 표 밖 문장 속 [ ]
    assert boxes["seizure"]["marks"]["네"][0] < boxes["seizure"]["marks"]["아니오"][0]


def test_hints(tmp_path):
    hints = label_hints(make_form(tmp_path / "form.pdf"))
    assert "1. 신청인 > 대리인 > 성 명" in hints
    assert "2. 보호기관 > 전화 :" in hints and "2. 보호기관 > 휴대전화 :" in hints
    assert any(h.endswith("선택지: 자립수당 지급 / 의료비 지원") for h in hints)
    assert "날짜 줄 > 년 월 일" in hints


def test_date_line(boxes):
    assert boxes["date"]["how"] == "date_line"
    assert boxes["date"]["template_text"] == "년 월 일"


def test_attach_boxes_warns_missing(tmp_path):
    form = make_form(tmp_path / "form.pdf")
    spec = FormSpec(form_id="X", policy_key="P", title="t", source="s",
                    fields=FIELDS + [f("memo", "추가 메모"), f("g_mail", "대리인 E-mail")])
    out = attach_boxes(spec, form)
    assert next(x for x in out.fields if x.id == "name").box["how"] == "table_right"
    assert "g_mail" not in {x.id for x in out.fields}  # 대리인 묶음에 없는 칸 → AI 가 지어낸 칸
    assert out.warnings == [
        "양식에 없는 칸이라 버림: '대리인 E-mail' (대리인 묶음에 없음)", "값 자리를 못 찾음 (별지에 적음): 추가 메모",
    ]
    assert attach_boxes(out, form).warnings == out.warnings  # 다시 돌려도 경고가 쌓이지 않음


def test_save_layout_pdf(tmp_path):
    form = make_form(tmp_path / "form.pdf")
    out_dir = tmp_path / "forms"
    out_dir.mkdir()
    copied = save_layout_pdf(form, out_dir, "X")
    assert copied == out_dir / "X.pdf"
    assert copied.read_bytes() == form.read_bytes()
