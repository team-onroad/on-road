"""색 · 테두리가 다양한 hwpx 이력서와 직접 그린 PDF 장학금 신청서 (항목 칸 · 값 칸 나누기, 단위 칸 확인용)"""
import io
import zipfile

import pymupdf

NS = 'xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph" xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"'


def _bf(i, fill=None, l="SOLID", r="SOLID", t="SOLID", b="SOLID", w="0.12 mm", tw=None, bw=None, color="#000000"):
    side = lambda tag, typ, wd: f'<hh:{tag} type="{typ}" width="{wd}" color="{color}"/>'
    brush = f'<hc:fillBrush><hc:winBrush faceColor="{fill}" hatchColor="#999999" alpha="0"/></hc:fillBrush>' if fill else ""
    return (f'<hh:borderFill id="{i}">' + side("leftBorder", l, w) + side("rightBorder", r, w)
            + side("topBorder", t, tw or w) + side("bottomBorder", b, bw or w) + brush + '</hh:borderFill>')


BFS = "".join([
    _bf(1, "#DCE6F2", tw="0.5 mm"),                       # 항목 칸: 연파랑, 위 굵게
    _bf(2, None, tw="0.5 mm"),                            # 값 칸: 흰색
    _bf(3, "#F2F2F2", l="DOT", r="DOT", t="DOT", b="DOT"),  # 사진 칸: 점선
    _bf(4, "#1F3864", tw="0.5 mm"),                       # 섹션 제목 줄: 남색
    _bf(5, "#DCE6F2", b="DOUBLE_SLIM", bw="0.5 mm"),      # 열 제목: 아래 이중선
    _bf(6, None, t="DASH", b="DASH"),                     # 표 안 데이터 줄: 점선 구분
    _bf(7, "#FDE9D9", tw="0.5 mm"),                       # 경력 제목 줄: 연주황
    _bf(8, "#FFF2CC"),                                    # 노란 값 칸
])
HEADER = (
    '<?xml version="1.0" encoding="UTF-8"?><hh:head xmlns:hh="http://www.hancom.co.kr/hwpml/2011/head" '
    'xmlns:hc="http://www.hancom.co.kr/hwpml/2011/core"><hh:refList>'
    f'<hh:borderFills itemCnt="8">{BFS}</hh:borderFills><hh:charProperties itemCnt="4">'
    '<hh:charPr id="0" height="1000" textColor="#000000"/>'
    '<hh:charPr id="1" height="1800" textColor="#1F3864"><hh:bold/></hh:charPr>'
    '<hh:charPr id="2" height="1000" textColor="#FFFFFF"><hh:bold/></hh:charPr>'
    '<hh:charPr id="3" height="1000" textColor="#000000"><hh:bold/></hh:charPr>'
    '</hh:charProperties><hh:paraProperties itemCnt="2">'
    '<hh:paraPr id="0"><hh:align horizontal="LEFT" vertical="BASELINE"/></hh:paraPr>'
    '<hh:paraPr id="1"><hh:align horizontal="CENTER" vertical="BASELINE"/></hh:paraPr>'
    '</hh:paraProperties></hh:refList></hh:head>'
)


def p(text, char=0, para=0):
    return f'<hp:p paraPrIDRef="{para}"><hp:run charPrIDRef="{char}"><hp:t>{text}</hp:t></hp:run></hp:p>'


def tc(col, row, w, h, text="", bf=2, cs=1, rs=1, char=0, para=0):
    body = p(text, char, para) if text else '<hp:p><hp:run charPrIDRef="0"/></hp:p>'
    return (f'<hp:tc borderFillIDRef="{bf}"><hp:subList vertAlign="CENTER">{body}</hp:subList>'
            f'<hp:cellAddr colAddr="{col}" rowAddr="{row}"/><hp:cellSpan colSpan="{cs}" rowSpan="{rs}"/>'
            f'<hp:cellSz width="{w}" height="{h}"/></hp:tc>')


def L(col, row, w, h, text, **kw):  # 항목 칸
    return tc(col, row, w, h, text, bf=kw.pop("bf", 1), char=3, para=1, **kw)


def _tbl(rows, cols):
    trs = "".join("<hp:tr>" + "".join(r) + "</hp:tr>" for r in rows)
    return f'<hp:p><hp:run><hp:tbl rowCnt="{len(rows)}" colCnt="{cols}">{trs}</hp:tbl></hp:run></hp:p>'


def resume_hwpx() -> bytes:
    H = 2300
    info = [
        [tc(0, 0, 9000, H * 3, "사진", bf=3, rs=3, para=1), L(1, 0, 7000, H, "성명"), tc(2, 0, 11000, H),
         L(3, 0, 7000, H, "생년월일"), tc(4, 0, 14000, H, "        년     월     일")],
        [L(1, 1, 7000, H, "연락처"), tc(2, 1, 11000, H), L(3, 1, 7000, H, "이메일"), tc(4, 1, 14000, H)],
        [L(1, 2, 7000, H, "주소"), tc(2, 2, 32000, H, cs=3)],
        [L(0, 3, 9000, H, "희망 직무"), tc(1, 3, 18000, H, "(                         )", cs=2),
         L(3, 3, 7000, H, "희망 연봉"), tc(4, 3, 14000, H, "만원", bf=8)],
    ]
    edu = [[tc(0, 0, 48000, H, "학력사항", bf=4, cs=4, char=2)],
           [L(0, 1, 14000, H, "기간", bf=5), L(1, 1, 14000, H, "학교명", bf=5), L(2, 1, 12000, H, "전공", bf=5),
            L(3, 1, 8000, H, "졸업 구분", bf=5)]]
    for r in (2, 3):
        edu.append([tc(0, r, 14000, H, "     년    월 ~     년    월", bf=6), tc(1, r, 14000, H, bf=6),
                    tc(2, r, 12000, H, bf=6), tc(3, r, 8000, H, bf=6)])
    car = [[tc(0, 0, 48000, H, "경력사항", bf=7, cs=3, char=3)],
           [L(0, 1, 16000, H, "회사명"), L(1, 1, 16000, H, "담당 업무"), L(2, 1, 16000, H, "근무 기간")]]
    for r in (2, 3):
        car.append([tc(0, r, 16000, H, bf=6), tc(1, r, 16000, H, bf=6), tc(2, r, 16000, H, bf=6)])
    intro = [[L(0, 0, 9000, H * 4, "자기소개"), tc(1, 0, 39000, H * 4)]]
    sec = (f'<?xml version="1.0" encoding="UTF-8"?><hs:sec {NS}>'
           '<hp:p><hp:run><hp:secPr><hp:pagePr landscape="WIDELY" width="59528" height="84186"><hp:margin header="1417" footer="1417" gutter="0" left="5669" right="5669" top="4251" bottom="4251"/></hp:pagePr></hp:secPr></hp:run></hp:p>'
           + p("이 력 서", 1, 1) + _tbl(info, 5) + p("") + _tbl(edu, 4) + p("") + _tbl(car, 3) + p("") + _tbl(intro, 2)
           + p("위 기재 사항은 사실과 다름없음을 확인합니다.", 0, 1) + p("        년      월      일", 0, 1)
           + p("작성자                    (서명 또는 인)", 0, 1) + '</hs:sec>')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("mimetype", "application/hwp+zip")
        z.writestr("Contents/section0.xml", sec)
        z.writestr("Contents/header.xml", HEADER)
    return buf.getvalue()


RESUME_LLM = {"title": "이력서", "fields": [
    {"label": "이름", "type": "text"}, {"label": "생년월일", "type": "date"}, {"label": "연락처", "type": "phone"},
    {"label": "이메일", "type": "text"}, {"label": "주소", "type": "address"}, {"label": "희망 직무", "type": "text"},
    {"label": "희망 연봉", "type": "text"}, {"label": "기간", "section": "학력사항", "type": "text"},
    {"label": "학교명", "section": "학력사항", "type": "text"}, {"label": "자기소개", "type": "essay"},
    {"label": "작성일", "type": "date"}, {"label": "작성자 서명", "type": "text"},
]}


# ---------------- PDF: 직접 그린 장학금 신청서 ----------------
GREEN, BAR, GREY = (0.886, 0.937, 0.855), (0.2, 0.4, 0.3), (0.85, 0.85, 0.85)


def scholarship_pdf() -> bytes:
    doc = pymupdf.open()
    page = doc.new_page(width=595, height=842)
    page.insert_font(fontname="kr", fontbuffer=pymupdf.Font("cjk").buffer)

    def cell(x0, y0, x1, y1, text="", fill=None, color=(0, 0, 0), width=0.6, center=False, size=10, dash=None):
        page.draw_rect((x0, y0, x1, y1), color=(0, 0, 0), fill=fill, width=width, dashes=dash)
        if text:
            w = pymupdf.Font("cjk").text_length(text, fontsize=size)
            x = (x0 + x1 - w) / 2 if center else x0 + 4
            page.insert_text((x, (y0 + y1) / 2 + size * 0.35), text, fontname="kr", fontsize=size, color=color)

    page.insert_text((215, 70), "장학금 신청서", fontname="kr", fontsize=20, color=BAR)
    X = [50, 130, 300, 380, 545]
    y = 95
    cell(50, y, 300, y + 24, "접수번호", fill=GREY, center=True)
    cell(300, y, 545, y + 24, "※ 기관 기재", fill=GREY, color=(0.4, 0.4, 0.4), size=8)
    y += 34
    cell(50, y, 545, y + 22, "1. 신청인", fill=BAR, color=(1, 1, 1))
    y += 22
    for (a, b) in [("성명", "학번"), ("학과", "학년")]:
        cell(X[0], y, X[1], y + 26, a, fill=GREEN, center=True); cell(X[1], y, X[2], y + 26)
        cell(X[2], y, X[3], y + 26, b, fill=GREEN, center=True); cell(X[3], y, X[4], y + 26)
        y += 26
    cell(X[0], y, X[1], y + 26, "연락처", fill=GREEN, center=True); cell(X[1], y, X[4], y + 26)
    y += 26
    cell(X[0], y, X[1], y + 26, "직전 학기 성적", fill=GREEN, center=True, size=9)
    cell(X[1], y, X[2], y + 26, "         / 4.5")
    cell(X[2], y, X[3], y + 26, "이수 학점", fill=GREEN, center=True); cell(X[3], y, X[4], y + 26, "          학점")
    y += 36
    cell(50, y, 545, y + 22, "2. 신청 사유", fill=BAR, color=(1, 1, 1))
    y += 22
    cell(50, y, 130, y + 26, "장학 구분", fill=GREEN, center=True)
    cell(130, y, 545, y + 26, "[ ]성적우수   [ ]생활지원   [ ]근로")
    y += 26
    cell(50, y, 130, y + 110, "신청 사유", fill=GREEN, center=True); cell(130, y, 545, y + 110)
    y += 120
    page.draw_rect((50, 95, 545, y - 10), color=(0, 0, 0), width=1.6)  # 바깥 굵은 테두리 (아랫부분만)
    page.insert_text((140, y + 20), "위와 같이 장학금을 신청합니다.", fontname="kr", fontsize=11)
    page.insert_text((300, y + 50), "년      월      일", fontname="kr", fontsize=11)
    page.insert_text((300, y + 80), "신청인                 (서명)", fontname="kr", fontsize=11)
    return doc.tobytes()


SCHOLAR_LLM = {"title": "장학금 신청서", "fields": [
    {"label": "성명", "section": "신청인", "type": "text"}, {"label": "학번", "type": "text"},
    {"label": "학과", "type": "text"}, {"label": "학년", "type": "text"}, {"label": "연락처", "type": "phone"},
    {"label": "직전 학기 성적", "type": "text"}, {"label": "이수 학점", "type": "text"},
    {"label": "장학 구분", "type": "checkbox", "options": ["성적우수", "생활지원", "근로"]},
    {"label": "신청 사유", "type": "essay"}, {"label": "신청일", "type": "date"},
]}
