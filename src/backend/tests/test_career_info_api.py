"""진로 외부 정보 API: 바깥 서버는 부르지 않고 응답을 흉내 낸다"""

import json

import pytest

from app.config import get_settings
from app.services import external

QNET = {
    "header": {"resultCode": "00"},
    "body": {"items": [
        {"implYy": "2026", "implSeq": "4", "description": "국가기술자격 기능사 (2026년도 제4회)",
         "docRegStartDt": "20260818", "docRegEndDt": "20260821", "docExamStartDt": "20260913",
         "docExamEndDt": "20260918", "docPassDt": "20260930", "pracRegStartDt": "20261020",
         "pracRegEndDt": "20261023", "pracExamStartDt": "20261121", "pracExamEndDt": "20261209",
         "pracPassDt": "20261224"},
        {"implYy": "2026", "implSeq": "1", "description": "국가기술자격 기능사 (2026년도 제1회)",
         "docRegStartDt": "20260113", "docExamEndDt": "20260130", "pracPassDt": "20260410"},
        {"implYy": "2026", "implSeq": "4", "description": "국가기술자격 산업기사 (2026년도 제4회)",
         "docRegStartDt": "20260818", "pracPassDt": "20261224"},
        {"implYy": "2026", "implSeq": "4", "description": "국가기술자격 기사 (2026년도 제4회)",
         "docRegStartDt": "20260818", "pracPassDt": "20261224"},
    ], "totalCount": 4},
}

TRAINING = """<?xml version="1.0" encoding="UTF-8"?>
<HRDNet><scn_cnt>2</scn_cnt><srchList>
<scn_list><title>바리스타 자격증 취득 과정</title><subTitle>부산커피학원</subTitle><address>부산광역시 중구</address>
<telNo>051-000-0000</telNo><traStartDate>2026-12-01</traStartDate><traEndDate>2027-01-20</traEndDate>
<courseMan>1,200,000</courseMan><realMan>180000</realMan><trprId>AIG1</trprId><trprDegr>3</trprDegr>
<titleLink>https://www.work24.go.kr/a</titleLink><eiEmplRate6>62.5</eiEmplRate6></scn_list>
<scn_list><title>바리스타 실무</title><subTitle>서울바리스타아카데미</subTitle><address>서울특별시 강남구</address>
<traStartDate>20261215</traStartDate><traEndDate>20270210</traEndDate><courseMan>900000</courseMan>
<realMan>90000</realMan><trprId>AIG2</trprId><trprDegr>1</trprDegr></scn_list>
</srchList></HRDNet>"""

JOBS = {"count": 2, "jobs": [
    {"job_cd": 11, "job_nm": "바리스타 강사", "work": "가르친다"},
    {"job_cd": 22, "job_nm": "바리스타", "work": "커피를 만든다."},
]}

JOB = {
    "baseInfo": {"job_nm": "바리스타", "wage": "2,800", "wage_source": "워크넷", "satisfication": "60.2",
                 "wlb": "보통", "rel_job_nm": "커피전문점 매니저, 로스터"},
    "workList": [{"work": "<p>원두를 고른다</p>"}, {"work": "음료를 만든다"}],
    "abilityList": [{"ability_name": "손재능"}],
    "certiList": [{"certi": "바리스타", "LINK": "x"}],
    "departList": [{"depart_id": 1, "depart_name": "식품조리학과"}],
    "jobReadyList": {"training": [{"training": "학원에서 실습"}], "recruit": [{"recruit": "카페 공채"}]},
    "forecastList": [{"forecast": "늘어날 전망"}],
    "jobRelOrgList": [{"rel_org": "한국커피협회", "rel_org_url": "https://kca.example"}],
}

POSTINGS = """<?xml version="1.0" encoding="UTF-8"?>
<wantedRoot><total>2</total>
<wanted><wantedAuthNo>K1</wantedAuthNo><company>서울카페</company><title>바리스타 모집</title>
<salTpNm>월급</salTpNm><sal>220만원</sal><region>서울 강남구</region><regDt>26-11-20</regDt>
<closeDt>채용시까지</closeDt><wantedMobileInfoUrl>https://m.work24.go.kr/1</wantedMobileInfoUrl></wanted>
<wanted><wantedAuthNo>K2</wantedAuthNo><company>부산카페</company><title>바리스타 구함</title>
<region>부산 중구</region><regDt>26-11-24</regDt></wanted>
</wantedRoot>"""


@pytest.fixture
def keys(monkeypatch):
    s = get_settings()
    for k in ("qnet_service_key", "careernet_api_key", "work24_training_key", "work24_jobs_key"):
        monkeypatch.setattr(s, k, "test-key")


@pytest.fixture
def fake_get(monkeypatch):
    calls = []

    def fake(url, params):
        calls.append((url, params))
        if url == external.QNET_URL:
            return json.dumps(QNET if params["implYy"] == 2026 else {"body": {"items": []}}).encode()
        if url == external.WORK24_TRAINING_URL:
            return TRAINING.encode()
        if url == external.CAREERNET_JOBS_URL:
            return json.dumps(JOBS).encode()
        if url == external.CAREERNET_JOB_URL:
            return json.dumps(JOB).encode()
        if url == external.WORK24_JOBS_URL:
            return POSTINGS.encode()
        raise AssertionError(url)

    monkeypatch.setattr(external, "_get", fake)
    return calls


def test_without_keys_reports_unavailable(client, monkeypatch, fake_get):
    s = get_settings()
    for k in ("qnet_service_key", "careernet_api_key", "work24_training_key", "work24_jobs_key"):
        monkeypatch.setattr(s, k, None)
    for path in ("/api/career/exams?cert=제빵기능사", "/api/career/trainings?keyword=바리스타",
                 "/api/career/job-info?name=바리스타", "/api/career/postings?keyword=바리스타"):
        body = client.get(path).json()
        assert body["available"] is False
        assert body["items"] == []
    assert fake_get == []


def test_exam_schedule_keeps_upcoming_rounds_of_grade(client, keys, fake_get, monkeypatch):
    from datetime import date

    from app.clock import get_today
    from app.main import app

    app.dependency_overrides[get_today] = lambda: date(2026, 11, 25)
    try:
        body = client.get("/api/career/exams", params={"cert": "제빵기능사"}).json()
    finally:
        app.dependency_overrides.pop(get_today, None)
    assert body["available"] is True
    assert body["grade"] == "기능사"
    assert [r["round"] for r in body["items"]] == [4]  # 끝난 1회 · 산업기사 · 기사는 빠진다
    r = body["items"][0]
    assert r["written_apply_start"] == "2026-08-18"
    assert r["practical_pass"] == "2026-12-24"


def test_exam_grade_gisa_does_not_match_sanup(client, keys, fake_get):
    from datetime import date

    res = external.exam_schedule("정보처리기사", date(2026, 11, 25))
    assert [r["name"] for r in res["items"]] == ["국가기술자격 기사 (2026년도 제4회)"]


def test_exam_for_private_cert_has_no_rounds(client, keys, fake_get):
    body = client.get("/api/career/exams", params={"cert": "바리스타"}).json()
    assert body["available"] is True
    assert body["items"] == []
    assert body["grade"] is None
    assert fake_get == []


def test_trainings_put_my_region_first(client, keys, fake_get):
    body = client.get("/api/career/trainings", params={"keyword": "바리스타", "region": "서울특별시"}).json()
    assert body["available"] is True
    assert body["total"] == 2
    first = body["items"][0]
    assert first["institution"] == "서울바리스타아카데미"
    assert first["nearby"] is True
    assert first["start"] == "2026-12-15"
    assert first["my_cost"] == 90000
    assert body["items"][1]["cost"] == 1200000
    params = fake_get[0][1]
    assert params["srchTraProcessNm"] == "바리스타"
    assert params["returnType"] == "XML"


def test_job_info_picks_exact_name_and_cleans_html(client, keys, fake_get):
    body = client.get("/api/career/job-info", params={"name": "바리스타"}).json()
    job = body["job"]
    assert job["id"] == "22"
    assert job["works"] == ["원두를 고른다", "음료를 만든다"]
    assert job["majors"] == ["식품조리학과"]
    assert job["related_jobs"] == ["커피전문점 매니저", "로스터"]
    assert job["prepare"]["training"] == ["학원에서 실습"]
    assert job["orgs"] == [{"name": "한국커피협회", "url": "https://kca.example"}]
    assert fake_get[1][1]["seq"] == 22


def test_postings_put_my_region_first(client, keys, fake_get):
    body = client.get("/api/career/postings", params={"keyword": "바리스타", "region": "부산광역시"}).json()
    assert [p["id"] for p in body["items"]] == ["K2", "K1"]
    assert body["items"][1]["url"] == "https://m.work24.go.kr/1"
    assert body["total"] == 2


def test_failure_is_not_cached(client, keys, monkeypatch):
    def broken(url, params):
        raise external.ExternalError("timeout")

    monkeypatch.setattr(external, "_get", broken)
    body = client.get("/api/career/postings", params={"keyword": "바리스타"}).json()
    assert body["available"] is False
    assert "다시" in body["message"]

    monkeypatch.setattr(external, "_get", lambda url, params: POSTINGS.encode())
    body = client.get("/api/career/postings", params={"keyword": "바리스타"}).json()
    assert body["available"] is True
    assert len(body["items"]) == 2


def test_same_query_is_cached(client, keys, fake_get):
    client.get("/api/career/postings", params={"keyword": "바리스타"})
    client.get("/api/career/postings", params={"keyword": "바리스타"})
    assert len(fake_get) == 1


def test_keyword_is_required(client):
    assert client.get("/api/career/trainings").status_code in (400, 422)


def test_qnet_key_encoded_or_not_is_sent_the_same(client, keys, fake_get, monkeypatch):
    from datetime import date

    monkeypatch.setattr(get_settings(), "qnet_service_key", "ab%2Bc%3D%3D")
    external.exam_schedule("제빵기능사", date(2026, 11, 25))
    external._cache.clear()
    monkeypatch.setattr(get_settings(), "qnet_service_key", "ab+c==")
    external.exam_schedule("제빵기능사", date(2026, 11, 25))
    keys_sent = {p["serviceKey"] for _, p in fake_get}
    assert keys_sent == {"ab+c=="}
