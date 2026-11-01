"""서류 속 숨겨진 위험 찾기 (POST /api/documents/risk-check, app/doc_risk.py)."""

import base64
from datetime import date

import pymupdf
import pytest

from app import llm_mock
from app.doc_llm import DocLlm, get_doc_llm
from app.doc_risk import Context, get_rules, judge, mask, money
from app.main import app
from tests.factories import make_user

TODAY = date(2026, 10, 4)  # tests.conftest FixedClock 기본값과 같게


def _pdf(lines: list[str]) -> bytes:
    doc = pymupdf.open()
    page = doc.new_page()
    page.insert_font(fontname="ko", fontbuffer=pymupdf.Font("cjk").buffer)
    y = 72
    for line in lines:
        page.insert_text((72, y), line, fontname="ko", fontsize=11)
        y += 20
    return doc.tobytes()


def _png() -> bytes:
    pix = pymupdf.Pixmap(pymupdf.csRGB, pymupdf.IRect(0, 0, 400, 300), False)
    pix.clear_with(255)
    return pix.tobytes("png")


class FakeRiskLlm:
    def __init__(self):
        self.output: object = {"values": {}, "findings": []}
        self.calls: list[tuple] = []

    def read_risk(self, rules, form_text, images):
        self.calls.append((rules, form_text, images))
        return self.output

    def as_llm(self) -> DocLlm:
        return DocLlm(llm_mock.route_message, llm_mock.assist_field, llm_mock.extract_answer, llm_mock.read_form, self.read_risk)


@pytest.fixture
def risk_llm(api):
    fake = FakeRiskLlm()
    app.dependency_overrides[get_doc_llm] = fake.as_llm
    yield fake
    app.dependency_overrides.pop(get_doc_llm, None)


def _check(api, user, data: bytes, doc_type="labor", **extra):
    body = {"user_id": str(user.id), "doc_type": doc_type, "file_base64": base64.b64encode(data).decode(), **extra}
    return api.post("/api/documents/risk-check", json=body)


def _by_id(body):
    return {f["rule_id"]: f for f in body["findings"]}


def _out(values=None, **status):
    return {"values": values or {}, "findings": [
        {"rule_id": k.replace("_", "-"), "status": v[0], "evidence": v[1]} for k, v in status.items()
    ]}


# ------------------------------------------------------------------ 규칙 파일 · 값 다듬기


def test_rules_file_loads_with_three_docs():
    rf = get_rules()
    assert set(rf.docs) == {"registry", "lease", "labor"}
    assert [len(d.rules) for d in rf.docs.values()] == [8, 7, 7]
    assert rf.params["min_wage"] == {"2026": 10320, "2027": 10700}


@pytest.mark.parametrize("raw, won", [
    (350_000_000, 350_000_000), ("350,000,000원", 350_000_000), ("1억 2천만 원", 120_000_000),
    ("30만 원", 300_000), ("금 1억원", 100_000_000), ("", None), (True, None), (-5, None),
])
def test_money(raw, won):
    assert money(raw) == won


def test_mask_hides_resident_and_account_numbers():
    assert mask("임대인 900101-1234567 계좌 110-123-456789") == "임대인 ******-******* 계좌 ***-***-***"


# ------------------------------------------------------------------ 판정 (서버가 다시 확인)


def test_min_wage_is_recomputed_by_year():
    j = judge("labor", _out({"hourly_wage": 10000, "contract_start": "2026-11-01"}, LAB_01=("ok", None)), Context(), TODAY, None)
    f = _by_id(j)["LAB-01"]
    assert f["status"] == "danger" and "10,320원" in f["explain"]
    assert f["practice"] == {"scenario_id": "wage", "title": "밀린 알바비 받기"}
    j = judge("labor", _out({"hourly_wage": 10500, "contract_start": "2027-01-02"}, LAB_01=("ok", None)), Context(), TODAY, None)
    assert _by_id(j)["LAB-01"]["status"] == "danger" and "10,700원" in _by_id(j)["LAB-01"]["explain"]
    j = judge("labor", _out({"hourly_wage": 10320, "contract_start": "2026-11-01"}, LAB_01=("danger", "시급")), Context(), TODAY, None)
    assert _by_id(j)["LAB-01"]["status"] == "ok"


def test_probation_reduction_only_when_allowed():
    base = {"hourly_wage": 9300, "contract_start": "2026-11-01", "probation": "수습 3개월"}
    long_office = {**base, "contract_end": "2027-12-31", "job_type": "사무 보조"}
    assert _by_id(judge("labor", _out(long_office), Context(), TODAY, None))["LAB-01"]["status"] == "check"
    short = {**base, "contract_end": "2027-01-31", "job_type": "사무 보조"}
    assert _by_id(judge("labor", _out(short), Context(), TODAY, None))["LAB-01"]["status"] == "danger"
    simple = {**long_office, "job_type": "단순 포장"}
    assert _by_id(judge("labor", _out(simple), Context(), TODAY, None))["LAB-01"]["status"] == "danger"


def test_weekly_holiday_rule():
    def status(values):
        return _by_id(judge("labor", _out(values), Context(), TODAY, None))["LAB-03"]["status"]

    assert status({"work_hours_per_week": 20, "weekly_holiday": None}) == "check"
    assert status({"work_hours_per_week": 20, "weekly_holiday": "주휴일 일요일"}) == "ok"
    assert status({"work_hours_per_week": 12}) == "ok"


def test_debt_ratio_needs_market_price():
    values = {"mortgage_max_total": 120_000_000, "deposit": 50_000_000}
    f = _by_id(judge("registry", _out(values), Context(), TODAY, None))["REG-04"]
    assert f["status"] == "check"
    f = _by_id(judge("registry", _out(values), Context(market_price=200_000_000), TODAY, None))["REG-04"]
    assert f["status"] == "danger" and "85%" in f["explain"]
    f = _by_id(judge("registry", _out(values), Context(market_price=300_000_000), TODAY, None))["REG-04"]
    assert f["status"] == "ok"
    no_debt = {"mortgages": False}
    f = _by_id(judge("registry", _out(no_debt), Context(deposit=10_000_000, market_price=100_000_000), TODAY, None))["REG-04"]
    assert f["status"] == "ok"


def test_owner_and_address_match_user_context():
    values = {"owner_names": ["김 철 수"], "address": "서울특별시 도봉구 방학로 1, 101동 202호"}
    j = _by_id(judge("registry", _out(values), Context(landlord_name="김철수", address="서울 도봉구 방학로 1 101동 202호"), TODAY, None))
    assert j["REG-01"]["status"] == "ok" and j["REG-06"]["status"] == "ok"
    j = _by_id(judge("registry", _out(values), Context(landlord_name="이영희", address="서울 도봉구 방학로 1 101동 203호"), TODAY, None))
    assert j["REG-01"]["status"] == "danger" and j["REG-06"]["status"] == "danger"
    j = _by_id(judge("registry", _out(values), Context(), TODAY, None))
    assert j["REG-01"]["status"] == "unknown"


def test_registry_dates():
    fresh = _by_id(judge("registry", _out({"issued_at": "2026-10-01", "owner_changed_at": "2026-08-01"}), Context(), TODAY, None))
    assert fresh["REG-08"]["status"] == "ok" and fresh["REG-07"]["status"] == "check"
    old = _by_id(judge("registry", _out({"issued_at": "2026-09-01", "owner_changed_at": "2020-01-01"}), Context(), TODAY, None))
    assert old["REG-08"]["status"] == "check" and "33일" in old["REG-08"]["explain"] and old["REG-07"]["status"] == "ok"


def test_danger_without_evidence_becomes_check_and_made_up_evidence_is_dropped():
    out = _out(REG_02=("danger", None), REG_03=("danger", "가압류 서울지방법원"), REG_05=("danger", "임차권등기 2025"))
    j = _by_id(judge("registry", out, Context(), TODAY, "갑구 소유권이전 가압류 서울지방법원 2026"))
    assert j["REG-02"]["status"] == "check"  # 근거 없음
    assert j["REG-03"]["status"] == "danger" and j["REG-03"]["evidence"] == "가압류 서울지방법원"
    assert j["REG-05"]["status"] == "check" and j["REG-05"]["evidence"] is None  # PDF 글자에 없는 근거


def test_summary_order_and_rule_texts():
    out = _out(LAB_04=("danger", "중도 퇴사 시 위약금 50만 원"), LAB_05=("ok", "매월 10일"), LAB_06=("check", "3.3% 공제"))
    j = judge("labor", out, Context(), TODAY, None)
    statuses = [f["status"] for f in j["findings"]]
    assert statuses == sorted(statuses, key=["danger", "check", "unknown", "ok"].index)
    assert j["summary"]["level"] == "danger"
    assert j["summary"]["counts"]["danger"] == 1
    f = _by_id(j)["LAB-04"]
    assert f["title"] == "그만두면 돈을 물어내라는 조항" and f["action"].startswith("이 조항은 빼 달라고")
    assert _by_id(j)["LAB-05"]["action"] is None and _by_id(j)["LAB-05"]["practice"] is None


# ------------------------------------------------------------------ API


def test_pdf_labor_contract_with_mock_llm(api, db_session):
    user = make_user(db_session)
    pdf = _pdf([
        "근로계약서", "근로계약기간: 2026.11.10 부터 2027.02.09 까지", "시급: 10,000원",
        "근로시간: 주 20시간", "임금 지급일: 매월 10일", "3개월 이내 퇴사 시 교육비 30만 원 배상",
        "4대보험 대신 3.3% 공제",
    ])
    res = _check(api, user, pdf)
    assert res.status_code == 200, res.text
    body = res.json()
    j = _by_id(body)
    assert j["LAB-01"]["status"] == "danger"
    assert j["LAB-03"]["status"] == "check"
    assert j["LAB-04"]["status"] == "danger" and "배상" in j["LAB-04"]["evidence"]
    assert j["LAB-06"]["status"] == "check"
    assert body["summary"]["level"] == "danger" and body["label"] == "근로계약서"
    assert body["rules_version"] == get_rules().version


def test_photo_is_sent_as_image(api, db_session, risk_llm):
    user = make_user(db_session)
    risk_llm.output = _out(LEA_03=("danger", "전입신고를 하지 않기로 한다"))
    res = _check(api, user, _png(), doc_type="lease")
    assert res.status_code == 200, res.text
    rules, text, images = risk_llm.calls[0]
    assert rules["doc_type"] == "lease" and {r["id"] for r in rules["rules"]} >= {"LEA-01", "LEA-07"}
    assert "explain" not in rules["rules"][0]  # 판정 조건만 보낸다
    assert text is None and images[0].startswith("data:image/jpeg;base64,")
    assert _by_id(res.json())["LEA-03"]["status"] == "danger"


def test_context_is_used_for_registry(api, db_session, risk_llm):
    user = make_user(db_session)
    risk_llm.output = _out({"owner_names": ["박집주"], "mortgage_max_total": 90_000_000, "issued_at": "2026-10-03"})
    res = _check(api, user, _png(), doc_type="registry",
                 context={"landlord_name": "박집주", "deposit": 30_000_000, "market_price": 150_000_000})
    j = _by_id(res.json())
    assert j["REG-01"]["status"] == "ok"
    assert j["REG-04"]["status"] == "danger" and "80%" in j["REG-04"]["explain"]
    assert j["REG-08"]["status"] == "ok"


def test_llm_bad_output_is_503(api, db_session, risk_llm):
    user = make_user(db_session)
    risk_llm.output = ["x"]
    res = _check(api, user, _png())
    assert res.status_code == 503 and res.json()["error"]["code"] == "AI_UNAVAILABLE"


def test_errors(api, db_session):
    user = make_user(db_session)
    res = api.post("/api/documents/risk-check", json={"user_id": "00000000-0000-0000-0000-000000000000", "doc_type": "x"})
    assert res.status_code == 404 and res.json()["error"]["code"] == "USER_NOT_FOUND"
    res = _check(api, user, _png(), doc_type="resume")
    assert res.status_code == 422 and res.json()["error"]["code"] == "VALIDATION_ERROR"
    res = api.post("/api/documents/risk-check", json={"user_id": str(user.id), "doc_type": "labor", "file_base64": "@@@"})
    assert res.status_code == 422 and res.json()["error"]["code"] == "UPLOAD_INVALID"
    res = _check(api, user, b"hello", doc_type="labor")
    assert res.status_code == 422 and res.json()["error"]["code"] == "UPLOAD_INVALID"
    res = _check(api, user, _png(), context={"market_price": -1})
    assert res.status_code == 422


def test_lease_two_years_ending_day_before_is_ok():
    j = _by_id(judge("lease", _out({"start_date": "2026-12-01", "end_date": "2028-11-30"}), Context(), TODAY, None))
    assert j["LEA-05"]["status"] == "ok"
    j = _by_id(judge("lease", _out({"start_date": "2026-12-01", "end_date": "2027-11-30"}), Context(), TODAY, None))
    assert j["LEA-05"]["status"] == "check"


def test_pdf_lease_with_mock_llm(api, db_session):
    user = make_user(db_session)
    pdf = _pdf([
        "주택 월세 계약서", "보증금: 금 10,000,000원", "입금 계좌: 가상은행 김대리 명의 계좌",
        "임대차기간: 2026.12.01 부터 2027.11.30 까지",
        "1. 임차인은 전입신고 및 확정일자를 하지 않기로 한다.",
        "2. 임차인은 거주 중 발생하는 모든 수리 및 원상복구를 부담한다.",
        "임대인: 박집주",
    ])
    j = _by_id(_check(api, user, pdf, doc_type="lease").json())
    assert j["LEA-01"]["status"] == "danger" and "김대리" in j["LEA-01"]["evidence"]
    assert j["LEA-03"]["status"] == "danger"
    assert j["LEA-05"]["status"] == "check"
    assert j["LEA-07"]["status"] == "check" and j["LEA-07"]["practice"]["scenario_id"] == "landlord"
