"""공고 페이지 첨부파일 찾기·내려받기. 네트워크 없이 HTML 문자열과 가짜 urlopen 으로 확인한다."""

import io

import pytest

from formguide import fetch as fetch_mod
from formguide.fetch import FetchError, _filename_from, fetch
from formguide.notice import Attachment, NoticeError, _decode, download, find_attachments, pick_form, score_name

PAGE = "https://www.example.go.kr/board/view.do?id=1"

HTML = """
<dl><dt><span>첨부파일</span></dt><dd><ul>
  <li><a href="/temp/dnd.do?fileKey=aaa">2026년 자립정보북.pdf&nbsp;</a></li>
  <li><a href="/temp/dnd.do?fileKey=bbb"><img src="hwp.gif"> [붙임1] 자립수당 지급 신청서.hwp </a></li>
  <li><a href="files/%EC%95%88%EB%82%B4%EB%AC%B8.pdf">다운로드</a></li>
  <li><a href="#" onclick="fn_fileDown('FILE_01')">[별지 제1호] 서식.hwpx</a></li>
  <li><a href="/temp/dnd.do?fileKey=bbb">[붙임1] 자립수당 지급 신청서.hwp</a></li>
  <li><a href="/board/list.do">목록</a></li>
  <li><a href="mailto:a@b.kr">문의.pdf</a></li>
</ul></dd></dl>
"""


def test_find_attachments_and_rank():
    atts = find_attachments(HTML, PAGE)
    names = [a.name for a in atts]

    assert names[0] == "[붙임1] 자립수당 지급 신청서.hwp"  # 신청서 점수 최고
    assert atts[0].url == "https://www.example.go.kr/temp/dnd.do?fileKey=bbb"
    assert names.count("[붙임1] 자립수당 지급 신청서.hwp") == 1  # 같은 주소는 한 번만
    assert "2026년 자립정보북.pdf" in names
    assert "안내문.pdf" in names  # 링크 글자에 이름이 없으면 주소에서
    js = next(a for a in atts if a.name.endswith(".hwpx"))
    assert js.url is None and not js.downloadable  # 자바스크립트 다운로드
    mail = next(a for a in atts if a.name == "문의.pdf")
    assert mail.url is None  # http(s) 가 아닌 주소는 받지 않음
    assert all("목록" not in n for n in names)


def test_pick_form_skips_js_only():
    atts = [Attachment("서식.hwp", None, 10), Attachment("안내.pdf", "https://x/a.pdf", -2)]
    assert pick_form(atts).name == "안내.pdf"
    assert pick_form([Attachment("서식.hwp", None, 10)]) is None


def test_score_order():
    assert score_name("자립수당 신청서.hwp") > score_name("자립수당 신청 안내.pdf") > score_name("업무매뉴얼.pdf")


def test_decode_cp949_page():
    data = "<html><meta charset='euc-kr'>신청서.hwp</html>".encode("cp949")
    assert "신청서.hwp" in _decode(data, None)
    assert "신청서" in _decode("신청서".encode("utf-8"), "text/html; charset=UTF-8")


def test_filename_from_header():
    raw_utf8 = "2026년 자립정보북.pdf".encode("utf-8").decode("latin-1")  # http.client 가 읽은 모양
    assert _filename_from("https://x/dnd.do", f'attachment; filename="{raw_utf8}"') == "2026년 자립정보북.pdf"
    assert _filename_from("https://x/dnd.do", "attachment; filename*=UTF-8''%EC%8B%A0%EC%B2%AD%EC%84%9C.hwp") == "신청서.hwp"
    assert _filename_from("https://x/a/form.pdf", None) == "form.pdf"
    assert _filename_from("https://x/dnd.do", None) is None


class FakeResp(io.BytesIO):
    def __init__(self, data, headers):
        super().__init__(data)
        self.headers = headers

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def test_download_uses_link_name_when_server_gives_none(tmp_path, monkeypatch):
    monkeypatch.setattr(fetch_mod.urllib.request, "urlopen", lambda req, timeout: FakeResp(b"%PDF-1.4", {}))
    path = download(Attachment("../../evil/신청서.pdf", "https://x/dnd.do?k=1", 10), tmp_path)
    assert path == tmp_path / "신청서.pdf"  # 경로 조작 방지
    assert path.read_bytes() == b"%PDF-1.4"


def test_download_rejects_other_types_and_js(tmp_path, monkeypatch):
    monkeypatch.setattr(
        fetch_mod.urllib.request, "urlopen",
        lambda req, timeout: FakeResp(b"x", {"Content-Disposition": 'attachment; filename="a.zip"'}),
    )
    with pytest.raises(FetchError):
        fetch("https://x/dnd.do", tmp_path, suggested_name="신청서.hwp")  # 서버가 알려준 이름이 우선
    with pytest.raises(NoticeError):
        download(Attachment("서식.hwp", None, 10), tmp_path)
