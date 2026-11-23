"""믿을 만한 기관 웹 수집 (collector.web). 실제 검색 · 페이지 · AI 대신 가짜를 쓴다."""

import json
from datetime import date

import pytest

from collector import web
from collector.web import Source, ended, grounded, html_to_text, load_sources, trusted_source

TODAY = date(2026, 11, 22)
URL = "https://www.kosaf.go.kr/ko/scholar.do?pg=scholarship05_11"

HTML = """<html><head><title>장학금</title><script>var x = "지원대상 아무나";</script></head><body>
<h2>자립준비청년 꿈 장학금</h2>
<table><tr><th>지원대상</th><td>보호종료 5년 이내 자립준비청년 대학생</td></tr>
<tr><th>지원내용</th><td>학기당 등록금 최대 200만 원 지원</td></tr>
<tr><th>신청방법</th><td>한국장학재단 누리집에서 온라인 신청</td></tr>
<tr><th>신청기간</th><td>2026. 11. 1. ~ 2026. 12. 31.</td></tr>
<tr><th>제출서류</th><td>보호종료확인서</td></tr>
<tr><th>문의</th><td>한국장학재단 1599-2000</td></tr></table>
<p>""" + "장학금 안내 글입니다. " * 20 + "</p></body></html>"

PROGRAM = {
    "name": "자립준비청년 꿈 장학금",
    "agency": None,
    "contact": "한국장학재단 1599-2000",
    "target_description": "보호종료 5년 이내 자립준비청년 대학생",
    "support_content": "학기당 등록금 최대 200만 원 지원",
    "support_amount": "최대 200만 원",
    "apply_method": "한국장학재단 누리집에서 온라인 신청",
    "deadline": "2026. 11. 1. ~ 2026. 12. 31.",
    "required_docs": [{"name": "보호종료확인서", "condition": None}, {"name": "가족관계증명서", "condition": None}],
    "is_open": True,
    "quotes": {
        "name": "자립준비청년 꿈 장학금",
        "contact": "한국장학재단 1599-2000",
        "target_description": "보호종료 5년 이내 자립준비청년",
        "support_content": "등록금 최대 200만 원 지원",
        "support_amount": "최대 200만 원",
        "apply_method": "누리집에서 온라인 신청",
        "deadline": "2026. 11. 1. ~ 2026. 12. 31.",
    },
}

SOURCES = load_sources()


def _seed_ok():
    pytest.importorskip("sqlalchemy")


# ---------------- 믿을 만한 기관 ----------------


@pytest.mark.parametrize("url, name", [
    ("https://www.kosaf.go.kr/a", "한국장학재단"),  # 가장 길게 맞는 기관
    ("https://www.busan.go.kr/x", None),  # go.kr 일반 (기관명은 페이지에서)
    ("https://jaripon.ncrc.or.kr/home", "아동권리보장원"),
    ("https://www.childfund.or.kr/a", "초록우산"),
])
def test_trusted_domains(url, name):
    s = trusted_source(url, SOURCES)
    assert s is not None and s.name == name


@pytest.mark.parametrize("url", [
    "https://blog.naver.com/a",
    "https://kosaf.go.kr.evil.com/a",  # 끝이 같지 않으면 안 됨
    "https://fakego.kr/a",
    "ftp://www.kosaf.go.kr/a",
    "javascript:alert(1)",
])
def test_untrusted_domains(url):
    assert trusted_source(url, SOURCES) is None


def test_sources_file_is_valid():
    assert all(s.domain and "/" not in s.domain for s in SOURCES)
    assert json.loads(web.TRUSTED_PATH.read_text(encoding="utf-8"))["sources"]


# ---------------- 원문 대조 ----------------


def test_html_to_text_drops_script_and_keeps_rows():
    text = html_to_text(HTML)
    assert "var x" not in text
    assert "지원대상\n보호종료 5년 이내 자립준비청년 대학생" in text


def test_grounded_rules():
    page = html_to_text(HTML)
    assert grounded("보호종료 5년 이내 자립준비청년 대학생", "보호종료 5년 이내", page) is None
    assert "인용" in grounded("보호종료 5년 이내", "보호종료 3년 이내", page)
    assert "숫자" in grounded("학기당 최대 300만 원", "최대 200만 원", page)
    assert "낱말" in grounded("누구나 쉽게 받을 수 있는 큰 혜택", "등록금 최대 200만 원 지원", page)


def test_ended():
    assert ended("2026. 1. 1. ~ 2026. 3. 31.", TODAY)
    assert not ended("2026. 11. 1. ~ 2026. 12. 31.", TODAY)
    assert not ended("상시 신청 (예산 소진 시 마감)", TODAY)
    assert not ended(None, TODAY)


# ---------------- 한 제도 → 정책 ----------------


def _convert(program=PROGRAM, url=URL):
    page = html_to_text(HTML)
    return web.to_policy(program, page, url, trusted_source(url, SOURCES), TODAY)


def test_program_becomes_crawler_policy():
    conv = _convert()
    p = conv.policy
    assert p["source_type"] == "crawler" and p["easy_text_verified"] is False and p["easy_text"] is None
    assert p["agency"] == "한국장학재단"  # 페이지에 기관명이 없으면 목록의 기관명
    assert p["category"] == "education" and p["region"] == "전국"
    assert p["required_docs"] == [{"name": "보호종료확인서", "condition": None}]  # 원문에 없는 서류는 버림
    assert "신청기한: 2026. 11. 1. ~ 2026. 12. 31." in p["apply_method"]
    assert p["original_text"].startswith(f"[출처 A: 한국장학재단]\n{URL}\n[정책명]")
    assert any("가족관계증명서" in n for n in conv.notes)


def test_made_up_required_field_drops_program():
    conv = _convert({**PROGRAM, "support_content": "매월 100만 원 생활비 지원",
                     "quotes": {**PROGRAM["quotes"], "support_content": "매월 100만 원"}})
    assert conv.policy is None and "support_content" in conv.skip_reason


def test_made_up_optional_field_is_dropped_only():
    conv = _convert({**PROGRAM, "support_period": "4년", "quotes": {**PROGRAM["quotes"], "support_period": "4년간"}})
    assert conv.policy is not None and conv.policy["support_period"] is None
    assert any("support_period 버림" in n for n in conv.notes)


def test_ended_or_closed_program_is_skipped():
    past = {**PROGRAM, "deadline": "2026. 11. 1. ~ 2026. 12. 31.".replace("12. 31", "11. 10")}
    assert _convert(past).policy is not None  # 원문에 없는 날짜라 deadline 칸만 버려짐
    page = html_to_text(HTML.replace("2026. 12. 31.", "2026. 11. 10."))
    old = {**PROGRAM, "deadline": "2026. 11. 1. ~ 2026. 11. 10.",
           "quotes": {**PROGRAM["quotes"], "deadline": "2026. 11. 1. ~ 2026. 11. 10."}}
    conv = web.to_policy(old, page, URL, trusted_source(URL, SOURCES), TODAY)
    assert conv.policy is None and "끝남" in conv.skip_reason
    assert _convert({**PROGRAM, "is_open": False}).policy is None


# ---------------- 전체 (가짜 검색 · 페이지 · AI) ----------------


class Fakes:
    def __init__(self, pages: dict[str, tuple[str, str]], programs: list[dict], search_urls: list[str]):
        self.pages = pages
        self.programs = programs
        self.search_urls = search_urls
        self.searched: list[tuple[str, list[str]]] = []
        self.loaded: list[str] = []

    def search(self, topic, domains):
        self.searched.append((topic, domains))
        return self.search_urls

    def load(self, url):
        self.loaded.append(url)
        if url not in self.pages:
            raise OSError("404")
        return self.pages[url]

    def extract(self, page, url):
        return {"programs": self.programs}


def test_collect_only_trusted_pages_and_saves_valid_policies(tmp_path):
    _seed_ok()
    redirected = "https://www.seoul.go.kr/jump"
    fakes = Fakes({URL: (URL, HTML), redirected: ("https://phishing.example.com/", HTML)}, [PROGRAM],
                  [URL, "https://blog.naver.com/post", redirected, "https://www.childfund.or.kr/none"])
    policies_path = tmp_path / "policies.json"
    policies_path.write_text("[]", encoding="utf-8")

    res, text = web.collect(["장학금"], None, today=TODAY, search=fakes.search, load=fakes.load, extract=fakes.extract,
                            policies_path=tmp_path / "team.json", output_path=policies_path, collected_dir=tmp_path / "c")

    assert "blog.naver.com/post" not in fakes.loaded  # 목록 밖 주소는 읽지도 않는다
    assert fakes.searched[0][0] == "장학금" and "kosaf.go.kr" in fakes.searched[0][1]
    saved = json.loads(policies_path.read_text(encoding="utf-8"))
    assert [p["policy_key"] for p in saved] == ["W001"] and saved[0]["source_type"] == "crawler"
    assert "목록 밖 주소로 넘어감" in text and "페이지를 못 읽음" in text
    assert text.startswith("# 정책 자동 수집 보고서 (2026-11-22, 믿을 만한 기관 웹 수집)")
    assert (tmp_path / "c" / "web_report.md").read_text(encoding="utf-8") == text


def test_same_name_as_existing_policy_is_not_added(tmp_path):
    _seed_ok()
    from collector.tests.test_collector import DETAIL
    from collector.convert import to_policy as gov_policy

    # 같은 제도 = 이름(괄호 · 띄어쓰기 무시)과 기관이 같음
    existing = {**gov_policy(DETAIL, "G001", TODAY).policy, "name": "자립준비청년 꿈장학금(대학생)", "agency": "한국장학재단"}
    policies_path = tmp_path / "policies.json"
    policies_path.write_text(json.dumps([existing], ensure_ascii=False), encoding="utf-8")
    fakes = Fakes({URL: (URL, HTML)}, [PROGRAM], [])
    res, text = web.collect(None, [URL], today=TODAY, search=fakes.search, load=fakes.load, extract=fakes.extract,
                            policies_path=tmp_path / "team.json", output_path=policies_path, collected_dir=tmp_path / "c")
    assert res.added == [] and "(= G001)" in text
    assert fakes.searched == []  # 주소를 주면 검색하지 않는다


def test_rerun_updates_own_crawler_policy(tmp_path):
    _seed_ok()
    policies_path = tmp_path / "policies.json"
    policies_path.write_text("[]", encoding="utf-8")
    kw = dict(today=TODAY, policies_path=tmp_path / "team.json", output_path=policies_path, collected_dir=tmp_path / "c")
    fakes = Fakes({URL: (URL, HTML)}, [PROGRAM], [])
    web.collect(None, [URL], search=fakes.search, load=fakes.load, extract=fakes.extract, **kw)
    res, _ = web.collect(None, [URL], search=fakes.search, load=fakes.load, extract=fakes.extract,
                         **{**kw, "today": date(2026, 11, 29)})
    assert res.unchanged == ["W001"] and res.added == []
    assert json.loads(policies_path.read_text(encoding="utf-8"))[0]["checked_at"] == "2026-11-29"


def test_short_js_page_is_reported(tmp_path):
    fakes = Fakes({URL: (URL, "<html><body><div id=app></div></body></html>")}, [PROGRAM], [])
    page = web.read_page(URL, SOURCES, TODAY, fakes.load, fakes.extract)
    assert page.converted == [] and "자바스크립트" in page.error


def test_openai_search_parses_citations(monkeypatch):
    class Ann:
        type = "url_citation"
        url = "https://www.kosaf.go.kr/a?utm_source=openai"

    class Content:
        annotations = [Ann()]

    class Item:
        content = [Content()]

    class Resp:
        output = [Item()]
        output_text = '{"pages": [{"title": "t", "url": "https://www.bokjiro.go.kr/b"}]}'

    class Client:
        def with_options(self, **_):
            return self

        class responses:  # noqa: N801
            @staticmethod
            def create(**kw):
                assert kw["tools"][0]["type"] == "web_search"
                assert "kosaf.go.kr" in kw["tools"][0]["filters"]["allowed_domains"]
                return Resp()

    import rag.openai_client as oc

    monkeypatch.setattr(oc, "_client", lambda: Client())
    urls = web.openai_search("장학금", [s.domain for s in SOURCES])
    assert urls == ["https://www.kosaf.go.kr/a", "https://www.bokjiro.go.kr/b"]


def test_failed_topic_search_is_reported_and_others_continue(tmp_path):
    _seed_ok()
    policies_path = tmp_path / "policies.json"
    policies_path.write_text("[]", encoding="utf-8")
    fakes = Fakes({URL: (URL, HTML)}, [PROGRAM], [URL])

    def search(topic, domains):
        if topic == "주거":
            raise RuntimeError("400")
        return fakes.search(topic, domains)

    res, text = web.collect(["주거", "장학금"], None, today=TODAY, search=search, load=fakes.load, extract=fakes.extract,
                            policies_path=tmp_path / "team.json", output_path=policies_path, collected_dir=tmp_path / "c", dry_run=True)
    assert res.added == ["W001"] and "## 검색하지 못한 주제 (1)" in text and "주거: RuntimeError" in text
    with pytest.raises(web.WebCollectError):
        web.collect(["주거"], None, today=TODAY, search=search, load=fakes.load, extract=fakes.extract,
                    policies_path=tmp_path / "team.json", output_path=policies_path, collected_dir=tmp_path / "c", dry_run=True)


def test_value_in_page_is_kept_even_if_quote_is_wrong():
    conv = _convert({**PROGRAM, "contact": "한국장학재단 1599-2000", "quotes": {**PROGRAM["quotes"], "contact": "틀린 인용"}})
    assert conv.policy["contact"] == "한국장학재단 1599-2000"


def test_local_gov_site_without_region_is_flagged():
    url = "https://www.seosan.go.kr/welfare/contents.do?key=8889"
    page = html_to_text(HTML)
    conv = web.to_policy(PROGRAM, page, url, trusted_source(url, SOURCES), TODAY)
    assert conv.policy["region"] == "전국" and any("region 확인 필요" in n for n in conv.notes)


def test_web_uses_same_target_rules():
    """실제 웹 수집에서 나온 이름: LH 페이지 제목 '자립준비청년 및 가정 밖 청소년' (다른 대상 섞임), 고용24 '참여수당' (일반 제도)"""
    mixed = _convert({**PROGRAM, "name": "자립준비청년 꿈 장학금 및 가정 밖 청소년",
                      "quotes": {**PROGRAM["quotes"], "name": "자립준비청년 꿈 장학금"}})
    assert mixed.policy is None  # 이름이 원문에 그대로 없거나 다른 대상 → 넣지 않음
    page = html_to_text(HTML.replace("자립준비청년 꿈 장학금", "청년 참여수당"))
    general = web.to_policy({**PROGRAM, "name": "청년 참여수당", "target_description": "만 18~34세 청년 구직자",
                             "quotes": {**PROGRAM["quotes"], "name": "청년 참여수당", "target_description": "청년"}},
                            page.replace("보호종료 5년 이내 자립준비청년 대학생", "만 18~34세 청년 구직자"), URL,
                            trusted_source(URL, SOURCES), TODAY)
    assert general.policy is None and general.off_target
    assert _convert().policy is not None and any(n.startswith("대상: ") for n in _convert().notes)


def test_more_than_20_domains_are_searched_in_groups(monkeypatch):
    """기관이 20곳을 넘으면 나눠서 검색한다 (전에는 21번째부터 조용히 빠짐)"""
    seen = []

    class Resp:
        output = []
        output_text = "https://www.kosaf.go.kr/a?utm_source=openai"

    class Client:
        def with_options(self, **_):
            return self

        class responses:  # noqa: N801
            @staticmethod
            def create(**kw):
                seen.append(kw["tools"][0]["filters"]["allowed_domains"])
                return Resp()

    import rag.openai_client as oc

    monkeypatch.setattr(oc, "_client", lambda: Client())
    domains = [f"d{i}.go.kr" for i in range(45)]
    urls = web.openai_search("장학금", domains)
    assert [len(d) for d in seen] == [20, 20, 5] and sum(seen, []) == domains
    assert urls == ["https://www.kosaf.go.kr/a"]  # 같은 주소는 한 번


def test_web_uses_same_age_rules():
    """웹 수집도 정부24 와 같은 나이 규칙 (11/23): 대상이 '보호종료 5년 이내 자립준비청년 대학생' → 보호가 끝난 청년 15세~"""
    p = _convert().policy
    assert (p["age_min"], p["age_max"]) == (15, None) and any(n.startswith("나이 15세~") for n in _convert().notes)
