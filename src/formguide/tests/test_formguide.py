"""신청서 작성 도우미. OpenAI 대신 가짜 LLM, 신청서는 테스트에서 만든 가상 PDF 를 쓴다.

실행 (src 폴더에서): backend\\.venv\\Scripts\\python -m pytest formguide/tests
"""

import json
from pathlib import Path

import pytest

from formguide.build import build, build_spec
from formguide.convert import ConvertError, to_pdf
from formguide.extract import ExtractError, extract
from formguide.fetch import FetchError, fetch
from formguide.guide import DEMO_PROFILE, NOT_CHECKED_NOTICE, make_guide, to_text
from formguide.schema import FormSpec
from formguide.validate import check_fields

fpdf = pytest.importorskip("fpdf")
FONT = Path(r"C:\Windows\Fonts\malgun.ttf")

ROWS = [
    ("성 명", ""),
    ("주민등록번호", ""),
    ("생년월일", ""),
    ("주 소", ""),
    ("연락처", ""),
    ("수급 계좌", "은행명 / 계좌번호"),
    ("보호종료일", ""),
    ("접수번호", "(담당자 기재)"),
]


def make_pdf(path: Path, rows=ROWS, with_text=True) -> Path:
    pdf = fpdf.FPDF()
    pdf.add_page()
    if with_text:
        pdf.add_font("malgun", fname=str(FONT))
        pdf.set_font("malgun", size=14)
        pdf.cell(0, 10, "자립준비청년 자립수당 지급 신청서 (가상 양식)", new_x="LMARGIN", new_y="NEXT")
        pdf.set_font("malgun", size=10)
        with pdf.table(col_widths=(50, 130), first_row_as_headings=False) as table:
            for label, value in rows:
                row = table.row()
                row.cell(label)
                row.cell(value)
        pdf.cell(0, 10, "※ 신청서는 주소지 읍·면·동 행정복지센터에 제출합니다.", new_x="LMARGIN", new_y="NEXT")
        pdf.cell(0, 8, "작성요령: 보호종료일은 보호종료 확인서에 적힌 날짜를 적습니다.", new_x="LMARGIN", new_y="NEXT")
    pdf.output(str(path))
    return path


def field(label, **kw):
    base = {"id": label, "label": label, "guide": f"{label}을 적어요.", "example": "예시", "evidence": label}
    return base | kw


class FakeDraft:
    def __init__(self, fields=None, notes=None, title="자립수당 지급 신청서"):
        self.out = {
            "title": title,
            "fields": fields if fields is not None else [
                field("성명", profile_key="name", value_source="profile", example="홍길동"),
                field("주민등록번호", example="000000-0000000"),  # 민감: 예시 지워져야 함
                field("생년월일", input_type="date", value_source="profile", profile_key="birth_date"),
                field("수급 계좌", value_source="user", example="국민 123-456"),
                field("보호종료일", input_type="date", example="2026-12-31",
                      evidence="보호종료일은 보호종료 확인서에 적힌 날짜를 적습니다."),
                field("접수번호"),
                field("가족관계", example="없음"),  # 양식에 없는 칸
            ],
            "notes": notes if notes is not None else ["주소지 행정복지센터에 제출", "30일 이내 제출"],
        }
        self.calls = 0

    def __call__(self, form):
        self.calls += 1
        return self.out


@pytest.fixture
def form_pdf(tmp_path):
    if not FONT.exists():
        pytest.skip("한글 폰트(malgun.ttf)가 없음")
    return make_pdf(tmp_path / "form.pdf")


# --- 가져오기·변환·추출 ---


def test_fetch_local_and_reject_types(tmp_path, form_pdf):
    assert fetch(str(form_pdf), tmp_path) == form_pdf
    bad = tmp_path / "x.docx"
    bad.write_text("x")
    with pytest.raises(FetchError):
        fetch(str(bad), tmp_path)
    with pytest.raises(FetchError):
        fetch(str(tmp_path / "none.pdf"), tmp_path)


def test_pdf_is_used_as_is(tmp_path, form_pdf):
    assert to_pdf(form_pdf, tmp_path / "out") == form_pdf


def test_unknown_type_cannot_convert(tmp_path):
    with pytest.raises(ConvertError):
        to_pdf(tmp_path / "a.txt", tmp_path)


def test_extract_text_and_table(form_pdf):
    form = extract(form_pdf)
    text = form.as_prompt_text()
    assert "자립수당 지급 신청서" in text
    assert form.pages[0].tables, "표를 뽑아야 함"
    assert "수급 계좌 | 은행명 / 계좌번호" in text
    assert "보호종료일" in form.normalized()


def test_scanned_like_pdf_is_rejected(tmp_path):
    with pytest.raises(ExtractError):
        extract(make_pdf(tmp_path / "empty.pdf", with_text=False))


# --- 항목 검사 ---


def test_check_fields_rules(form_pdf):
    fields, warnings = check_fields(FakeDraft().out["fields"], extract(form_pdf))
    by_label = {f.label: f for f in fields}

    assert "가족관계" not in by_label  # 양식에 없는 칸은 버림
    assert any("가족관계" in w for w in warnings)

    rrn = by_label["주민등록번호"]
    assert rrn.sensitive and rrn.example is None and rrn.value_source == "handwrite"
    assert rrn.input_type == "id_number"

    account = by_label["수급 계좌"]
    assert account.sensitive and account.example is None and account.value_source == "handwrite"

    assert by_label["접수번호"].value_source == "office"
    assert by_label["접수번호"].example is None
    assert by_label["성명"].value_source == "profile" and by_label["성명"].profile_key == "name"
    assert by_label["보호종료일"].evidence  # 원문에 있는 근거는 유지


def test_wrong_evidence_and_profile_without_key(form_pdf):
    raw = [field("성명", value_source="profile", evidence="원문에 없는 문장")]
    fields, warnings = check_fields(raw, extract(form_pdf))
    assert fields[0].evidence is None
    assert fields[0].value_source == "user"
    assert any("근거" in w for w in warnings)


@pytest.mark.parametrize("raw", [None, "x", [None, {"label": 1}], []])
def test_broken_draft(form_pdf, raw):
    fields, warnings = check_fields(raw, extract(form_pdf))
    assert fields == []
    assert warnings


def test_build_spec_notes_keep_only_grounded_numbers(form_pdf):
    spec = build_spec(extract(form_pdf), "P001-apply", "P001", "form.pdf", FakeDraft())
    assert spec.notes == ["주소지 행정복지센터에 제출"]  # "30일" 은 원문에 없음
    assert spec.checked is False
    assert spec.title == "자립수당 지급 신청서"


def test_build_writes_files(tmp_path, form_pdf):
    draft = FakeDraft()
    out = build(str(form_pdf), "P001-apply", "P001", out_dir=tmp_path / "forms", draft=draft)
    spec = FormSpec.model_validate_json(out.read_text(encoding="utf-8"))
    assert draft.calls == 1
    assert (tmp_path / "forms" / "P001-apply.source.txt").exists()
    assert len({f.id for f in spec.fields}) == len(spec.fields)


# --- 안내 ---


def test_guide_prefills_profile_and_hides_sensitive(form_pdf):
    spec = build_spec(extract(form_pdf), "P001-apply", "P001", "form.pdf", FakeDraft())
    guide = make_guide(spec, DEMO_PROFILE)
    items = {i["label"]: i for i in guide["items"]}

    assert items["성명"]["prefill"] == "김민지"
    assert items["생년월일"]["prefill"] == "2007년 3월 15일"
    assert items["주민등록번호"]["prefill"] is None and items["주민등록번호"]["example"] is None
    assert "손으로" in items["주민등록번호"]["how"]
    assert items["보호종료일"]["example"] == "2026-12-31"
    assert NOT_CHECKED_NOTICE in guide["notices"]
    json.dumps(guide, ensure_ascii=False)  # 앱 응답으로 보낼 수 있는 형식


def test_guide_without_profile_and_text(form_pdf):
    spec = build_spec(extract(form_pdf), "P001-apply", "P001", "form.pdf", FakeDraft())
    spec = spec.model_copy(update={"checked": True})
    guide = make_guide(spec)
    assert all(i["prefill"] is None for i in guide["items"])
    assert NOT_CHECKED_NOTICE not in guide["notices"]
    text = to_text(guide)
    assert "▶ 주민등록번호" in text and "000000-0000000" not in text
