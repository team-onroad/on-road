"""테스트용 이력서 hwpx: 단위 글자 칸("년 월 일", "만 세", "년 월 ~ 년 월"), 괄호 칸, 반복 줄(학력 1 · 2)"""
import io
import zipfile

from tests.hwpx_sample import HEADER, NS, p, tc
def build():
    H=2200
    rows=[
     [tc(0,0,8000,H,"성명"),tc(1,0,14000,H),tc(2,0,8000,H,"생년월일"),tc(3,0,15000,H,"     년     월     일")],
     [tc(0,1,8000,H,"나이"),tc(1,1,14000,H,"만        세"),tc(2,1,8000,H,"연락처"),tc(3,1,15000,H)],
     [tc(0,2,8000,H,"주소"),tc(1,2,37000,H,cs=3)],
     [tc(0,3,8000,H,"이메일"),tc(1,3,14000,H),tc(2,3,8000,H,"희망 직무"),tc(3,3,15000,H,"(                    )")],
    ]
    edu=[[tc(0,0,15000,H,"기간"),tc(1,0,15000,H,"학교명"),tc(2,0,15000,H,"전공")]]
    for r in (1,2):
        edu.append([tc(0,r,15000,H,"     년    월 ~     년    월"),tc(1,r,15000,H),tc(2,r,15000,H)])
    t1="".join("<hp:tr>"+"".join(r)+"</hp:tr>" for r in rows)
    t2="".join("<hp:tr>"+"".join(r)+"</hp:tr>" for r in edu)
    sec=(f'<?xml version="1.0" encoding="UTF-8"?><hs:sec {NS}>'
         '<hp:p><hp:run><hp:secPr><hp:pagePr landscape="WIDELY" width="59528" height="84186"><hp:margin header="1417" footer="1417" gutter="0" left="5669" right="5669" top="4251" bottom="4251"/></hp:pagePr></hp:secPr></hp:run></hp:p>'
         '<hp:p paraPrIDRef="1"><hp:run charPrIDRef="1"><hp:t>이 력 서</hp:t></hp:run></hp:p>'
         f'<hp:p><hp:run><hp:tbl rowCnt="4" colCnt="4">{t1}</hp:tbl></hp:run></hp:p>'
         + p("학력사항") + f'<hp:p><hp:run><hp:tbl rowCnt="3" colCnt="3">{t2}</hp:tbl></hp:run></hp:p></hs:sec>')
    buf=io.BytesIO()
    with zipfile.ZipFile(buf,"w") as z:
        z.writestr("mimetype","application/hwp+zip"); z.writestr("Contents/section0.xml",sec); z.writestr("Contents/header.xml",HEADER)
    return buf.getvalue()
