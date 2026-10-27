"""테스트용 가상 신청서 PDF (표 양식). 한글 글꼴은 PyMuPDF 내장 글꼴이라 Windows·Linux 모두 된다."""

from pathlib import Path

import pymupdf

X = [50, 130, 300, 380, 545]


def make_form(path: Path) -> Path:
    doc = pymupdf.open()
    p = doc.new_page()
    p.insert_font(fontname="kr", fontbuffer=pymupdf.Font("cjk").buffer)

    def text(x, y, s, size=10):
        p.insert_text((x, y), s, fontname="kr", fontsize=size)

    def cell(x0, y0, x1, y1, label=None):
        p.draw_rect(pymupdf.Rect(x0, y0, x1, y1), color=(0, 0, 0), width=0.7)
        if label:
            text(x0 + 6, (y0 + y1) / 2 + 4, label)

    text(200, 70, "자립수당 지급 신청서 (가상 양식)", 15)
    y = 100
    cell(X[0], y, X[1], y + 28, "성 명"); cell(X[1], y, X[2], y + 28)  # noqa: E702
    cell(X[2], y, X[3], y + 28, "생년월일"); cell(X[3], y, X[4], y + 28)  # noqa: E702
    y += 28
    cell(X[0], y, X[1], y + 28, "주민등록번호"); cell(X[1], y, X[4], y + 28)  # noqa: E702
    y += 28
    cell(X[0], y, X[1], y + 28, "주 소"); cell(X[1], y, X[4], y + 28)  # noqa: E702
    y += 28
    cell(X[0], y, X[1], y + 28, "연락처"); cell(X[1], y, X[2], y + 28)  # noqa: E702
    cell(X[2], y, X[3], y + 28, "보호종료일"); cell(X[3], y, X[4], y + 28)  # noqa: E702
    for unit, x in (("년", 450), ("월", 490), ("일", 530)):
        text(x, y + 18, unit)
    y += 28
    cell(X[0], y, X[4], y + 24, "신청 사유")
    y += 24
    cell(X[0], y, X[4], y + 90)
    y += 90
    cell(X[0], y, X[1], y + 28, "수급 계좌"); cell(X[1], y, X[4], y + 28, "은행명 /           계좌번호")  # noqa: E702
    y += 28
    cell(X[0], y, X[1], y + 28, "접수번호"); cell(X[1], y, X[4], y + 28)  # noqa: E702
    y += 28
    text(50, y + 40, "위와 같이 자립수당 지급을 신청합니다.")
    text(330, y + 75, "신청인                     (서명 또는 인)")
    doc.save(str(path))
    doc.close()
    return path
