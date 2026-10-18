"""공고 검색 (공공서비스 API). 실제 API 대신 가짜 응답을 쓴다."""

import urllib.parse

import pytest

from formguide import search
from formguide.search import Notice, SearchError, notice_detail, search_notices

ROW = {
    "서비스ID": "000000465790",
    "서비스명": "자립준비청년 자립수당 지급",
    "소관기관명": "보건복지부",
    "서비스목적요약": "보호종료 후 경제적 부담 완화",
    "지원대상": "보호종료 5년 이내 자립준비청년",
    "신청방법": "방문, 온라인",
    "신청기한": "상시신청",
    "상세조회URL": "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/000000465790",
}


@pytest.fixture(autouse=True)
def fake_key(monkeypatch):
    monkeypatch.setattr(search, "_service_key", lambda: "TEST-KEY")


class FakeApi:
    def __init__(self, by_field: dict[str, list[dict]] | None = None, detail: list[dict] | None = None, fail=()):
        self.by_field = by_field or {}
        self.detail = detail or []
        self.fail = set(fail)
        self.urls: list[str] = []

    def __call__(self, url):
        self.urls.append(url)
        q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        assert q["serviceKey"] == ["TEST-KEY"]
        if "serviceDetail" in url:
            return {"data": self.detail}
        cond = next(k for k in q if k.startswith("cond["))
        field = cond[5:].split("::")[0]
        if field in self.fail:
            raise SearchError("지원하지 않는 조건")
        return {"data": self.by_field.get(field, []), "totalCount": len(self.by_field.get(field, []))}


def test_search_by_service_name():
    api = FakeApi({"서비스명": [ROW]})
    notices = search_notices("자립수당", get_json=api)
    assert notices == [Notice.from_row(ROW)]
    n = notices[0]
    assert n.title == "자립준비청년 자립수당 지급" and n.agency == "보건복지부"
    assert n.url.startswith("https://www.gov.kr/")
    q = urllib.parse.parse_qs(urllib.parse.urlparse(api.urls[0]).query)
    assert q["cond[서비스명::LIKE]"] == ["자립수당"]


def test_falls_back_to_other_fields():
    api = FakeApi({"지원대상": [ROW]}, fail={"서비스목적요약"})
    assert search_notices("보호종료", get_json=api)[0].service_id == "000000465790"
    assert len(api.urls) == 3


def test_first_field_error_is_raised():
    with pytest.raises(SearchError):
        search_notices("자립", get_json=FakeApi(fail={"서비스명"}))


def test_no_result_and_bad_rows():
    api = FakeApi({"서비스명": [{"서비스명": "ID 없음"}, "x"]})
    assert search_notices("자립", get_json=api) == []


def test_empty_keyword_and_limit():
    with pytest.raises(SearchError):
        search_notices("  ", get_json=FakeApi())
    api = FakeApi({"서비스명": [ROW]})
    search_notices("자립", limit=999, get_json=api)
    q = urllib.parse.parse_qs(urllib.parse.urlparse(api.urls[0]).query)
    assert q["perPage"] == [str(search.MAX_RESULTS)]


def test_detail_keeps_known_fields_only():
    api = FakeApi(detail=[{**ROW, "구비서류": "신청서, 신분증", "온라인신청사이트URL": "https://www.bokjiro.go.kr", "기타": "x", "법령": ""}])
    d = notice_detail("000000465790", get_json=api)
    assert d["구비서류"] == "신청서, 신분증"
    assert "기타" not in d and "법령" not in d
    with pytest.raises(SearchError):
        notice_detail("없음", get_json=FakeApi())


def test_missing_key_message(monkeypatch):
    monkeypatch.undo()
    search._service_key.cache_clear()
    monkeypatch.setattr(search._Settings, "model_config", {**search._Settings.model_config, "env_file": None})
    monkeypatch.delenv("DATA_GO_KR_SERVICE_KEY", raising=False)
    with pytest.raises(SearchError, match="DATA_GO_KR_SERVICE_KEY"):
        search._service_key()
    search._service_key.cache_clear()
