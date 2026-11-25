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
<telNo>051-000-0000</telNo><trainstCstId>500001</trainstCstId><traStartDate>2026-12-01</traStartDate><traEndDate>2027-01-20</traEndDate>
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


W24_LIST = """<?xml version="1.0" encoding="UTF-8"?>
<jobsList><total>2</total>
<jobList><jobClcd>K000</jobClcd><jobClcdNM>음식</jobClcdNM><jobCd>K001</jobCd><jobNm>제빵원</jobNm></jobList>
<jobList><jobClcd>K000</jobClcd><jobClcdNM>음식</jobClcdNM><jobCd>K002</jobCd><jobNm>제과제빵사</jobNm></jobList>
</jobsList>"""

W24_SUMMARY = """<?xml version="1.0" encoding="UTF-8"?>
<jobSum><jobCd>K002</jobCd><jobSum>빵과 과자를 만든다.</jobSum><way>제과제빵 학원이나 특성화고에서 배운다.
관련 자격을 취득한다.</way>
<relMajorList><majorCd>1</majorCd><majorNm>제과제빵과</majorNm></relMajorList>
<relCertList><certNm>제빵기능사</certNm></relCertList><relCertList><certNm>제과기능사</certNm></relCertList>
<sal>하위(25%) 2,400만원</sal><jobSatis>60.1</jobSatis><jobProspect>다소 증가할 것으로 보인다.</jobProspect>
<relJobList><jobCd>K001</jobCd><jobNm>제빵원</jobNm></relJobList></jobSum>"""

W24_DO = """<?xml version="1.0" encoding="UTF-8"?>
<jobsDo><jobCd>K002</jobCd><execJob>- 재료를 계량한다.
- 반죽을 굽는다.</execJob></jobsDo>"""

TRAINING_DETAIL = """<?xml version="1.0" encoding="UTF-8"?>
<HRDNet><inst_base_info><addr1>서울특별시 마포구</addr1><addr2>3층</addr2><inoNm>마포제과학원</inoNm>
<instPerTrco>1,200,000</instPerTrco><perTrco>1020000</perTrco><trDcnt>40</trDcnt><trtm>240</trtm>
<trprNm>제과제빵기능사 취득</trprNm><trprId>AIG1</trprId><trprDegr>3</trprDegr><trprTargetNm>구직자</trprTargetNm>
<trprChapTel>02-000-0000</trprChapTel></inst_base_info>
<inst_detail_info><govBusiNm>음식서비스</govBusiNm><tgcrGnrlTrneOwepAllt>180000</tgcrGnrlTrneOwepAllt></inst_detail_info>
<inst_facility_info><inst_facility_info_list><trafcltyNm>제빵실습실</trafcltyNm></inst_facility_info_list></inst_facility_info>
</HRDNet>"""

DUTIES = {"result": {"빵류 제품 반죽": {"ablt_unit": "1301010101", "ablt_def": "<b>반죽</b>을 만드는 능력이다.",
                                        "job_sdvn": "제과제빵", "job_lcfn": "음식서비스", "job_mcn": "식음료조리",
                                        "job_scfn": "제과제빵"}}}


@pytest.fixture
def keys(monkeypatch):
    s = get_settings()
    for k in ("qnet_service_key", "careernet_api_key", "work24_training_key", "work24_jobs_key",
              "work24_job_info_key", "work24_duty_key"):
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
        if url == external.WORK24_JOB_LIST_URL:
            return W24_LIST.encode()
        if url == external.WORK24_JOB_SUMMARY_URL:
            return W24_SUMMARY.encode()
        if url == external.WORK24_JOB_DO_URL:
            return W24_DO.encode()
        if url == external.WORK24_TRAINING_DETAIL_URL:
            return TRAINING_DETAIL.encode()
        if url == external.WORK24_DUTY_URL:
            return json.dumps(DUTIES, ensure_ascii=False).encode()
        raise AssertionError(url)

    monkeypatch.setattr(external, "_get", fake)
    return calls


def test_without_keys_reports_unavailable(client, monkeypatch, fake_get):
    s = get_settings()
    for k in ("qnet_service_key", "careernet_api_key", "work24_training_key", "work24_jobs_key",
              "work24_job_info_key", "work24_duty_key"):
        monkeypatch.setattr(s, k, None)
    for path in ("/api/career/exams?cert=제빵기능사", "/api/career/trainings?keyword=바리스타",
                 "/api/career/job-info?name=바리스타", "/api/career/postings?keyword=바리스타",
                 "/api/career/duties?keyword=바리스타",
                 "/api/career/trainings/detail?id=A&round=1&institution=B"):
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
    assert body["items"][1]["cost"] == 180000  # 실제 훈련비(realMan)
    assert "my_cost" not in body["items"][1]
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


def test_job_info_falls_back_to_work24_without_careernet(client, keys, fake_get, monkeypatch):
    monkeypatch.setattr(get_settings(), "careernet_api_key", None)
    body = client.get("/api/career/job-info", params={"name": "제과제빵사"}).json()
    assert body["source"] == "고용24 직업정보"
    job = body["job"]
    assert job["id"] == "K002"
    assert job["works"] == ["재료를 계량한다.", "반죽을 굽는다."]
    assert job["certificates"] == ["제빵기능사", "제과기능사"]
    assert job["majors"] == ["제과제빵과"]
    assert job["related_jobs"] == ["제빵원"]
    assert job["prepare"]["curriculum"] == ["제과제빵 학원이나 특성화고에서 배운다.", "관련 자격을 취득한다."]
    assert job["satisfaction"] == "60.1"
    detail_calls = [p for u, p in fake_get if u == external.WORK24_JOB_SUMMARY_URL]
    assert detail_calls[0]["jobCd"] == "K002"


def test_training_list_has_institution_id(client, keys, fake_get):
    body = client.get("/api/career/trainings", params={"keyword": "바리스타"}).json()
    assert {i["institution_id"] for i in body["items"]} == {"500001", None}


def test_training_detail(client, keys, fake_get):
    body = client.get("/api/career/trainings/detail", params={"id": "AIG1", "round": "3", "institution": "500001"}).json()
    c = body["course"]
    assert c["institution"] == "마포제과학원"
    assert c["address"] == "서울특별시 마포구 3층"
    assert c["cost"] == 1200000
    assert c["my_cost"] == 180000
    assert c["days"] == 40
    assert c["field"] == "음식서비스"
    assert c["facilities"] == ["제빵실습실"]
    assert "tracseId=AIG1" in c["url"]
    p = [p for u, p in fake_get if u == external.WORK24_TRAINING_DETAIL_URL][0]
    assert (p["srchTrprId"], p["srchTrprDegr"], p["srchTorgId"], p["outType"]) == ("AIG1", "3", "500001", 2)


def test_duties(client, keys, fake_get):
    body = client.get("/api/career/duties", params={"keyword": "제과제빵"}).json()
    assert body["items"] == [{
        "code": "1301010101", "unit": "빵류 제품 반죽", "definition": "반죽을 만드는 능력이다.",
        "field": "제과제빵", "group": "음식서비스 > 식음료조리 > 제과제빵", "skills": [],
    }]


def test_job_name_variants():
    assert external._name_variants("제과제빵사") == ["제과제빵사", "제과제빵", "제과"]
    assert external._name_variants("바리스타") == ["바리스타"]
    assert external._name_variants("소프트웨어 개발자") == ["소프트웨어 개발자", "소프트웨어개발", "소프트웨어"]


def test_work24_job_retries_with_shorter_name(client, keys, monkeypatch):
    monkeypatch.setattr(get_settings(), "careernet_api_key", None)
    words = []

    def fake(url, params):
        if url == external.WORK24_JOB_LIST_URL:
            words.append(params["keyword"])
            return (W24_LIST if params["keyword"] == "제과" else "<jobsList><total>0</total></jobsList>").encode()
        return {external.WORK24_JOB_SUMMARY_URL: W24_SUMMARY, external.WORK24_JOB_DO_URL: W24_DO}[url].encode()

    monkeypatch.setattr(external, "_get", fake)
    body = client.get("/api/career/job-info", params={"name": "제과제빵사"}).json()
    assert words == ["제과제빵사", "제과제빵", "제과"]
    assert body["job"]["id"] == "K002"  # 목록에서 이름이 같은 직업


def test_duty_skill_labels_from_list():
    assert external._skill_labels([{"knwg_tchn_attd": "http://x", "knwg_tchn_attd_label": "선입선출에 대한 지식"}]) == [
        "선입선출에 대한 지식"]


def test_qnet_error_header_is_reported(client, keys, monkeypatch):
    from datetime import date

    monkeypatch.setattr(external, "_get", lambda url, params: json.dumps(
        {"header": {"resultCode": "30", "resultMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}).encode())
    res = external.exam_schedule("제빵기능사", date(2026, 11, 25))
    assert res["available"] is False
    assert res["reason"] == "30 SERVICE_KEY_IS_NOT_REGISTERED_ERROR"
