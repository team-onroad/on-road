"""테스트용 가상 신청서 PDF (표 양식). 한글 글꼴은 PyMuPDF 내장 글꼴이라 Windows·Linux 모두 된다.

실제 자립수당 신청서처럼 대상아동·대리인 묶음에 같은 칸 이름("성명", "전화:")이 반복되고,
체크박스 줄, 칸 이름 아래 큰 칸, 구분 셀("예금주 : ____"), 맨 아래 "년 월 일" 줄이 있다.
"""

from pathlib import Path

import pymupdf

X = [50, 80, 130, 300, 380, 545]


def make_form(path: Path) -> Path:
    doc = pymupdf.open()
    p = doc.new_page()
    p.insert_font(fontname="kr", fontbuffer=pymupdf.Font("cjk").buffer)

    def text(x, y, s, size=10):
        p.insert_text((x, y), s, fontname="kr", fontsize=size)

    def cell(x0, y0, x1, y1, label=None):
        p.draw_rect(pymupdf.Rect(x0, y0, x1, y1), color=(0, 0, 0), width=0.7)
        if label:
            text(x0 + 4, (y0 + y1) / 2 + 4, label)

    text(200, 60, "자립수당 지급 신청서 (가상 양식)", 15)
    cell(X[0], 75, X[5], 95, "1. 신청인")

    def group(y, name):
        """묶음 이름 셀(세로 2줄) + 성명/생년월일, 주소/전화 두 줄"""
        cell(X[0], y, X[1], y + 56, name)
        cell(X[1], y, X[2], y + 28, "성 명")
        cell(X[2], y, X[3], y + 28)
        cell(X[3], y, X[4], y + 28, "생년월일")
        cell(X[4], y, X[5], y + 28)
        cell(X[1], y + 28, X[2], y + 56, "주 소")
        cell(X[2], y + 28, X[3], y + 56)
        cell(X[3], y + 28, X[4], y + 56, "전화:")
        cell(X[4], y + 28, X[5], y + 56)

    group(95, "대상아동")
    y = 151
    cell(X[0], y, X[1], y + 24, "계좌")
    cell(X[1], y, X[2], y + 24, "예금주")
    cell(X[2], y, X[2] + 8, y + 24, ":")
    cell(X[2] + 8, y, X[3], y + 24)
    cell(X[3], y, X[5], y + 24, "* 본인 명의만")
    y += 24
    cell(X[0], y, X[5], y + 24, "압류방지통장 여부 [ ]네 [ ]아니오")
    y += 24
    group(y, "대리인")
    y += 56
    cell(X[0], y, X[2], y + 26, "통지방법")
    cell(X[2], y, X[5], y + 26, "[ ]서면 [ ]전자우편 [ ]문자메시지")
    y += 26
    cell(X[0], y, X[5], y + 22, "2. 보호기관")
    y += 22
    cell(X[0], y, X[2], y + 28, "전화:")
    cell(X[2], y, X[5], y + 28)
    y += 28
    cell(X[0], y, X[5], y + 22, "신청 사유")
    y += 22
    cell(X[0], y, X[5], y + 80)
    y += 80
    text(380, y + 40, "년          월          일")
    text(330, y + 75, "신청인                     (서명 또는 인)")
    doc.save(str(path))
    doc.close()
    return path
