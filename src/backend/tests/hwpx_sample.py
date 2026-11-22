"""테스트용 hwpx 양식 만들기 (실제 한글 파일과 같은 XML 모양)"""
import io
import zipfile

NS='xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph" xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"'
def p(text): return f'<hp:p><hp:run charPrIDRef="0"><hp:t>{text}</hp:t></hp:run></hp:p>'
def tc(col,row,w,h,text="",cs=1,rs=1):
    body = p(text) if text else '<hp:p><hp:run charPrIDRef="0"/></hp:p>'
    fill = "7" if text else "8"
    return (f'<hp:tc borderFillIDRef="{fill}"><hp:subList vertAlign="CENTER">{body}</hp:subList><hp:cellAddr colAddr="{col}" rowAddr="{row}"/>'
            f'<hp:cellSpan colSpan="{cs}" rowSpan="{rs}"/><hp:cellSz width="{w}" height="{h}"/></hp:tc>')
def build(styled: bool = True):
    H=2400
    rows=[
      [tc(0,0,7000,H*2,"신청인",rs=2),tc(1,0,7000,H,"성명"),tc(2,0,12000,H),tc(3,0,7000,H,"생년월일"),tc(4,0,12000,H,"    년    월    일")],
      [tc(1,1,7000,H,"주소"),tc(2,1,31000,H,cs=3)],
      [tc(0,2,7000,H,"연락처"),tc(1,2,7000,H,"휴대전화"),tc(2,2,12000,H),tc(3,2,7000,H,"이메일"),tc(4,2,12000,H)],
      [tc(0,3,7000,H,"신청 방법"),tc(1,3,38000,H,"[ ]방문   [ ]온라인",cs=4)],
    ]
    trs="".join("<hp:tr>"+"".join(r)+"</hp:tr>" for r in rows)
    tbl=f'<hp:tbl rowCnt="4" colCnt="5">{trs}</hp:tbl>'
    sec=(f'<?xml version="1.0" encoding="UTF-8"?><hs:sec {NS}>'
         '<hp:p><hp:run><hp:secPr><hp:pagePr landscape="WIDELY" width="59528" height="84186"><hp:margin header="1417" footer="1417" gutter="0" left="5669" right="5669" top="4251" bottom="4251"/></hp:pagePr></hp:secPr></hp:run></hp:p>'
         + '<hp:p paraPrIDRef="1"><hp:run charPrIDRef="1"><hp:t>자립 지원 신청서</hp:t></hp:run></hp:p>' + f'<hp:p><hp:run>{tbl}</hp:run></hp:p>'
         + p("위와 같이 신청합니다.") + p("          년      월      일") + p("신청인                    (서명 또는 인)")
         + '</hs:sec>')
    buf=io.BytesIO()
    with zipfile.ZipFile(buf,"w") as z:
        z.writestr("mimetype","application/hwp+zip")
        z.writestr("Contents/section0.xml",sec)
        if styled:
            z.writestr("Contents/header.xml", HEADER)
    return buf.getvalue()


# 칸 이름 칸: 회색 배경 · 왼쪽 테두리 없음 / 글자: 굵게 · 가운데 (실제 한글 header.xml 과 같은 모양)
HEADER = (
    '<?xml version="1.0" encoding="UTF-8"?><hh:head xmlns:hh="http://www.hancom.co.kr/hwpml/2011/head" '
    'xmlns:hc="http://www.hancom.co.kr/hwpml/2011/core"><hh:refList>'
    '<hh:borderFills itemCnt="2">'
    '<hh:borderFill id="7"><hh:leftBorder type="NONE" width="0.15 mm" color="#000000"/>'
    '<hh:rightBorder type="SOLID" width="0.15 mm" color="#000000"/><hh:topBorder type="SOLID" width="0.4 mm" color="#000000"/>'
    '<hh:bottomBorder type="DASH" width="0.15 mm" color="#000000"/>'
    '<hc:fillBrush><hc:winBrush faceColor="#E5E5E5" hatchColor="#999999" alpha="0"/></hc:fillBrush></hh:borderFill>'
    '<hh:borderFill id="8"><hh:leftBorder type="SOLID" width="0.15 mm" color="#000000"/>'
    '<hh:rightBorder type="NONE" width="0.15 mm" color="#000000"/><hh:topBorder type="SOLID" width="0.4 mm" color="#000000"/>'
    '<hh:bottomBorder type="DASH" width="0.15 mm" color="#000000"/></hh:borderFill>'
    '</hh:borderFills><hh:charProperties itemCnt="2">'
    '<hh:charPr id="0" height="1000" textColor="#000000"/><hh:charPr id="1" height="1600" textColor="#1F3864"><hh:bold/></hh:charPr>'
    '</hh:charProperties><hh:paraProperties itemCnt="2">'
    '<hh:paraPr id="0"><hh:align horizontal="LEFT" vertical="BASELINE"/></hh:paraPr>'
    '<hh:paraPr id="1"><hh:align horizontal="CENTER" vertical="BASELINE"/></hh:paraPr>'
    '</hh:paraProperties></hh:refList></hh:head>'
)
if __name__=="__main__":
    pass
