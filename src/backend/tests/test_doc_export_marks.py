"""원본 양식 위에 채우기: 계좌 줄 은행 이름, 서명란 "(서명 또는 인)", 유의사항 확인 열 (app/doc_export.py)."""

import pymupdf

from app import doc_export


def _form(path):
    doc = pymupdf.open()
    page = doc.new_page()
    page.insert_font(fontname="kr", fontbuffer=pymupdf.Font("cjk").buffer)
    page.insert_text((220, 100), "은행 (예금주:", fontname="kr", fontsize=10)
    page.insert_text((200, 300), "신청인 성명:", fontname="kr", fontsize=10)
    doc.save(str(path))
    return path


def _spec(fid, box, **kw):
    return {"field_id": fid, "label": fid, "value_source": "user", "box": {"page": 0, **box}, **kw}


def test_bank_sign_and_confirm(tmp_path):
    rows = [
        (_spec("bank", {"rect": [140, 88, 218, 104], "how": "before_word", "suffix": "은행", "align": "right"}), "국민 은행"),
        (_spec("sign", {"rect": [262, 288, 450, 306], "how": "text_right", "sign_mark": "(서명 또는 인)"}), "김민지"),
        (_spec("confirm", {"rect": [480, 400, 540, 500], "how": "confirm", "spots": [[500, 420], [500, 460]],
                           "options": ["확인"]}, options=["확인"]), "확인"),
    ]
    page = pymupdf.open(stream=doc_export.render_overlay(_form(tmp_path / "f.pdf"), "t", rows))[0]
    words = {w[4]: w for w in page.get_text("words")}

    assert "국민" in words and "국민은행" not in words  # 양식에 이미 있는 "은행"은 빼고
    assert 205 < words["국민"][2] <= 218  # "은행" 글자 바로 앞 (오른쪽 맞춤)
    assert "김민지" in words and "(서명" in words
    assert words["김민지"][2] < words["(서명"][0]  # 이름 → (서명 또는 인)
    marks = [d for d in page.get_drawings() if d["rect"].x0 > 490 and d["rect"].x1 < 510]
    assert len(marks) == 2  # 유의사항 항목마다 체크
