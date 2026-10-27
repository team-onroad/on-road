"""계좌 줄("____ 은행 (예금주: ____)"), 유의사항 "확인(√ 체크)" 열, 서명란 (formguide.layout)."""

from pathlib import Path

import pymupdf

from formguide.layout import CONFIRM_FIELD_ID, SIGN_MARK, attach_boxes
from formguide.schema import FormField, FormSpec


def make_form(path: Path, tall_bank_row: bool = True, sign_text: bool = False) -> Path:
    doc = pymupdf.open()
    p = doc.new_page()
    p.insert_font(fontname="kr", fontbuffer=pymupdf.Font("cjk").buffer)

    def text(x, y, s, size=10):
        p.insert_text((x, y), s, fontname="kr", fontsize=size)

    def cell(x0, y0, x1, y1):
        p.draw_rect(pymupdf.Rect(x0, y0, x1, y1), color=(0, 0, 0), width=0.7)

    # 1쪽 위: 계좌 줄. 셀이 높으면 "은행" 줄 위에 계좌번호 쓸 자리가 있다
    y0, y1 = 80, 80 + (36 if tall_bank_row else 20)
    cell(50, y0, 130, y1)
    text(56, (y0 + y1) / 2 + 4, "계좌번호")
    cell(130, y0, 545, y1)
    base = y1 - 6
    text(220, base, "은행 (예금주:")
    text(370, base, ")  * 본인 명의 계좌만 가능")

    # 유의사항 표: 항목 | 확인 열
    top = 150
    cell(50, top, 480, top + 30)
    text(60, top + 19, "유의사항")
    cell(480, top, 545, top + 30)
    text(490, top + 13, "확 인")
    text(486, top + 26, "(√ 체크)", 8)
    items = ["1. 자립수당 지원 정보를 열람할 수 있습니다.", "2. 정지 사유가 생기면 알려야 합니다.", "3. 서류는 돌려주지 않습니다."]
    for i, item in enumerate(items):
        y = top + 30 + i * 30
        cell(50, y, 480, y + 30)
        text(60, y + 19, item)
        cell(480, y, 545, y + 30)
        text(500, y + 19, "[     ]")

    text(200, 330, "신청인(대리신청인) 성명:")
    if sign_text:
        text(450, 330, "(서명 또는 인)", 8)
    doc.save(str(path))
    return path


def f(fid, label, **kw):
    return FormField(id=fid, label=label, guide="안내", **kw)


def spec(*fields):
    return FormSpec(form_id="X", policy_key="P", title="t", source="s", fields=list(fields))


def by_id(out):
    return {x.id: x for x in out.fields}


def test_compound_bank_label_is_split_and_placed(tmp_path):
    pdf = make_form(tmp_path / "f.pdf")
    out = by_id(attach_boxes(spec(
        f("acc", "계좌번호", input_type="account", value_source="handwrite", sensitive=True),
        f("bank", "은행 (예금주: )", required=True),
    ), pdf))
    bank, holder, acc = out["bank"], out["bank_holder"], out["acc"]
    assert bank.label == "은행" and bank.box["how"] == "before_word"
    assert bank.box["suffix"] == "은행" and bank.box["align"] == "right"
    assert bank.box["rect"][2] < 220  # "은행" 글자 앞
    assert (holder.label, holder.value_source, holder.profile_key) == ("예금주", "profile", "name")
    assert holder.box["rect"][0] > 280 and holder.box["rect"][2] < 370  # "예금주:" 뒤 ~ ")" 앞
    assert acc.box["how"] == "above_line"  # 계좌번호 칸이 따로 없으면 "은행" 줄 위
    assert acc.box["rect"][3] < bank.box["rect"][1] + 3


def test_short_bank_row_has_no_account_slot(tmp_path):
    pdf = make_form(tmp_path / "f.pdf", tall_bank_row=False)
    out = by_id(attach_boxes(spec(
        f("acc", "계좌번호", input_type="account", value_source="handwrite", sensitive=True),
        f("bank", "은행"), f("holder", "예금주"),
    ), pdf))
    assert out["acc"].box is None  # 옆 빈칸은 은행 이름 자리라 계좌번호를 넣지 않는다
    assert out["bank"].box["how"] == "before_word" and "bank_holder" not in out


def test_confirm_column_added_once(tmp_path):
    pdf = make_form(tmp_path / "f.pdf")
    out = attach_boxes(spec(f("x", "확인 (√ 체크)", input_type="checkbox")), pdf)
    fields = by_id(out)
    assert "x" not in fields  # AI 가 뽑은 머리글 칸 대신 열 전체를 칸 하나로
    confirm = fields[CONFIRM_FIELD_ID]
    assert (confirm.input_type, confirm.required, confirm.box["how"]) == ("checkbox", True, "confirm")
    assert confirm.box["options"] == ["확인"] and len(confirm.box["spots"]) == 3
    assert confirm.box["items"][1].startswith("2. 정지 사유가")
    again = attach_boxes(out, pdf)
    assert [x.id for x in again.fields].count(CONFIRM_FIELD_ID) == 1


def test_signature_line_gets_name_and_mark(tmp_path):
    pdf = make_form(tmp_path / "f.pdf")
    sign = f("sign", "신청인(대리신청인) 성명:", input_type="signature", value_source="handwrite", sensitive=True)
    out = by_id(attach_boxes(spec(sign, f("name", "성명", value_source="profile", profile_key="name")), pdf))
    s = out["sign"]
    assert (s.value_source, s.profile_key, s.sensitive, s.input_type) == ("profile", "name", False, "text")
    assert s.box["how"] == "text_right" and s.box["sign_mark"] == SIGN_MARK  # 양식에 "(서명 또는 인)"이 없음
    assert "sign_mark" not in (out["name"].box or {})  # 서명란이 아닌 성명 칸에는 붙이지 않는다

    printed = make_form(tmp_path / "g.pdf", sign_text=True)
    s2 = by_id(attach_boxes(spec(sign), printed))["sign"]
    assert "sign_mark" not in s2.box and s2.box["rect"][2] < 450  # 양식에 있으면 그 글자 앞까지만
