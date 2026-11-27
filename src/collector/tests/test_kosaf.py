"""한국장학재단 학자금지원정보 수집 (collector.kosaf). 실제 API 대신 가짜 응답을 쓴다."""

import json
from datetime import date

from collector import kosaf
from collector.kosaf import (bulleted, collect, display_name, doc_text, pick_region, relevance, row_id, schedule_for,
                             to_policy)

TODAY = date(2026, 11, 25)


def make_row(**kw) -> dict:
    row = {
        "운영기관명": "(재)희망장학재단", "상품명": "자립준비청년 장학금", "운영기관구분": "공익단체", "상품구분": "장학금",
        "학자금유형구분": "생활비", "대학구분": "4년제(5~6년제포함)전문대(2~3년제)", "학년구분": "대학신입생",
        "학과구분": "제한없음", "성적기준 상세내용": "해당없음", "소득기준 상세내용": "해당없음",
        "지원내역 상세내용": "○ 학기당 생활비 200만 원○ 멘토링", "특정자격 상세내용": "○ 보호종료 5년 이내 자립준비청년○ 대학 재학생",
        "지역거주여부 상세내용": "해당없음", "선발방법 상세내용": "○ 서류심사○ 면접", "선발인원 상세내용": "○ 20명",
        "자격제한 상세내용": "해당없음", "추천필요여부 상세내용": "해당없음",
        "제출서류 상세내용": "○ 장학금 신청서○ 보호종료확인서○ 재학증명서※ 자세한 사항은 홈페이지 참고",
        "홈페이지 주소": "https://example.or.kr/notice/1", "모집시작일": "2026-03-02", "모집종료일": "2026-03-20",
    }
    row.update(kw)
    return row


def test_relevance_target_and_preference():
    assert relevance(make_row())[0]
    general = make_row(상품명="일반 장학금", **{"특정자격 상세내용": "○ 대학 재학생"})
    assert relevance(general) == (False, "자립준비청년 · 보호대상아동 관련 자격이 없음")
    prefer = make_row(상품명="일반 장학금", **{"특정자격 상세내용": "○ 대학 재학생",
                                         "선발방법 상세내용": "○ 서류심사○ 우대사항 : 차상위계층/자립준비청년"})
    ok, why = relevance(prefer)
    assert ok and "우대" in why
    assert "우대: 우대사항 : 차상위계층/자립준비청년" in to_policy(prefer, "S001", TODAY).policy["target_description"]


def test_graduate_only_is_skipped():
    # 대학구분이 붙어 와도 '전문대학원' · '기술대학원'을 학부로 보지 않는다
    assert relevance(make_row(대학구분="일반대학원전문대학원해외대학")) == (False, "대학원 전용")
    assert relevance(make_row(대학구분="4년제(5~6년제포함)기술대학원격대학일반대학원"))[0]
    assert relevance(make_row(대학구분="해당없음"))[0]  # 대학구분이 없으면 거르지 않는다


def test_generic_name_gets_agency():
    assert display_name(make_row(상품명="장학금")) == "희망장학재단 장학금"
    assert display_name(make_row(상품명="이사장 추천 장학생")) == "희망장학재단 이사장 추천 장학생"
    assert display_name(make_row()) == "자립준비청년 장학금"


def test_region_county_and_city():
    assert pick_region(make_row())[0] == "전국"
    code, note = pick_region(make_row(**{"지역거주여부 상세내용": "○ 보호자가 강진군에 1년 이상 주소"}))
    assert code == "전남" and "시·군·구" in note
    assert pick_region(make_row(운영기관명="태안군사랑장학회", 운영기관구분="지자체"))[0] == "충남"
    assert pick_region(make_row(**{"지역거주여부 상세내용": "○ 서울특별시 거주"}))[0] == "서울"


def test_bullets_and_docs_are_split():
    assert bulleted("○ 가○ 나※ 다") == "- 가\n- 나\n※ 다"
    assert bulleted("○ 하나") == "하나"
    assert doc_text("○ 신청서○ 재학증명서") == "○ 신청서\n○ 재학증명서"
    p = to_policy(make_row(), "S001", TODAY).policy
    assert [d["name"] for d in p["required_docs"]] == ["장학금 신청서", "보호종료확인서", "재학증명서"]
    assert p["support_content"] == "- 학기당 생활비 200만 원\n- 멘토링"
    assert "신청기한: 2026-03-02 ~ 2026-03-20" in p["apply_method"]
    assert p["apply_url"] == "https://example.or.kr/notice/1"
    assert p["original_text"].startswith("[출처 A: 한국장학재단 학자금지원정보]")


def test_schedule_open_upcoming_expected():
    assert schedule_for([("2026-11-01", "2026-12-10")], TODAY)["status"] == "open"
    assert schedule_for([("2026-12-01", "2026-12-20")], TODAY)["status"] == "upcoming"
    s = schedule_for([("2024-03-04", "2024-03-22"), ("2025-03-03", "2025-03-21"), ("2026-03-02", "2026-03-20")], TODAY)
    assert s["status"] == "expected" and s["next_start"] == "2027-03-02" and s["next_end"] == "2027-03-20"
    assert s["months"] == [3] and "2024, 2025, 2026년 3월" in s["basis"]
    assert schedule_for([("", "")], TODAY) == {"rounds": [], "status": "unknown"}
    # 1년에 두 번 뽑으면 마지막 회차(8월)의 1년 뒤가 아니라 더 이른 1월 회차가 다음 모집
    twice = schedule_for([("2025-08-04", "2025-08-22"), ("2026-01-05", "2026-01-23"), ("2026-08-03", "2026-08-21")], TODAY)
    assert (twice["next_start"], twice["next_end"]) == ("2027-01-05", "2027-01-23")
    assert twice["expected_rounds"] == [["2027-01-05", "2027-01-23"], ["2027-08-03", "2027-08-21"]]


class FakeApi:
    """API 명세(회분 2개) + 회분마다 행"""

    def __init__(self, versions: dict[str, list[dict]]):
        self.versions = versions  # 회분 날짜(YYYYMMDD) → 행
        self.calls: list[str] = []

    def __call__(self, url: str) -> dict:
        self.calls.append(url)
        if url == kosaf.OAS_URL:
            return {"paths": {f"/15028252/v1/uddi:{d}": {"get": {"summary": f"학자금지원정보_{d}"}} for d in self.versions}}
        for d, rows in self.versions.items():
            if f"uddi:{d}?" in url:
                return {"data": rows, "totalCount": len(rows)}
        raise AssertionError(url)


def test_collect_writes_policies_and_schedule(tmp_path):
    other = make_row(상품명="일반 장학금", **{"특정자격 상세내용": "○ 대학 재학생"})
    old = make_row(모집시작일="2025-03-03", 모집종료일="2025-03-21")
    api = FakeApi({"20250310": [old, other], "20261110": [make_row(), other]})
    policies_path = tmp_path / "policies.json"
    policies_path.write_text("[]", encoding="utf-8")
    scholarships_path = tmp_path / "scholarships.json"
    kw = dict(today=TODAY, get_json=api, key="k", policies_path=policies_path, scholarships_path=scholarships_path,
              collected_dir=tmp_path / "c", schedule_path=tmp_path / "apply_schedule.json")
    converted, schedule, text = collect(**kw)

    # 장학금은 정책 추천에 넣지 않는다: policies.json 은 그대로, scholarships.json 에만
    assert policies_path.read_text(encoding="utf-8") == "[]"
    saved = json.loads(scholarships_path.read_text(encoding="utf-8"))
    assert [p["policy_key"] for p in saved] == ["S001"] and "easy_text_verified" not in saved[0]
    s = json.loads((tmp_path / "apply_schedule.json").read_text(encoding="utf-8"))["S001"]
    assert s["status"] == "expected" and s["next_start"] == "2027-03-02" and len(s["rounds"]) == 2
    assert s["stage_label"] == "대학 입학 · 등록 때" and s["when"] == "2027년 3월쯤 모집 예상"
    assert "S001" in text and (tmp_path / "c" / "kosaf_report.md").exists()
    assert (tmp_path / "c" / "kosaf" / "versions" / "2025-03-10.json").exists()

    # 다시 돌리면 지난 회분은 저장한 것을 쓰고(API 안 부름), 키는 그대로
    api.calls.clear()
    collect(**kw)
    assert not any("uddi:20250310?" in u for u in api.calls)
    assert [p["policy_key"] for p in json.loads(scholarships_path.read_text(encoding="utf-8"))] == ["S001"]


def test_same_scholarship_already_in_gov24_is_skipped(tmp_path):
    # 정부24 에 이름만 다르게 있는 장학금: 운영기관 + 모집 시작일이 같으면 넣지 않는다
    gov = to_policy(make_row(상품명="아동복지시설 퇴소대학생 지원"), "G095", TODAY).policy
    gov["original_text"] = "[출처 A: 정부24]\n[신청기한]\n2026.03.02.~2026.03.20.\n"
    policies_path = tmp_path / "policies.json"
    policies_path.write_text(json.dumps([gov], ensure_ascii=False), encoding="utf-8")
    converted, _, text = collect(today=TODAY, get_json=FakeApi({"20261110": [make_row()]}), key="k",
                                 policies_path=policies_path, collected_dir=tmp_path / "c", schedule_path=tmp_path / "s.json",
                                 dry_run=True, history=False)
    assert not [c for c in converted if c.policy] and "(= G095)" in text


def test_collect_dry_run_writes_nothing(tmp_path):
    api = FakeApi({"20261110": [make_row()]})
    policies_path = tmp_path / "policies.json"
    policies_path.write_text("[]", encoding="utf-8")
    collect(today=TODAY, get_json=api, key="k", policies_path=policies_path, collected_dir=tmp_path / "c",
            schedule_path=tmp_path / "s.json", dry_run=True, history=False)
    assert policies_path.read_text(encoding="utf-8") == "[]" and not (tmp_path / "s.json").exists()


def test_versions_cache_used_when_spec_is_down(tmp_path):
    api = FakeApi({"20261110": [make_row()]})
    cache = tmp_path / "versions.json"
    assert kosaf.list_versions(api, cache) == [("/15028252/v1/uddi:20261110", "2026-11-10")]

    def down(url):
        raise OSError("503")

    assert kosaf.list_versions(down, cache) == [("/15028252/v1/uddi:20261110", "2026-11-10")]


def test_row_id_ignores_legal_prefix_and_spaces():
    assert row_id(make_row(운영기관명="재단법인 희망장학재단", 상품명="자립준비청년  장학금")) == row_id(make_row())
