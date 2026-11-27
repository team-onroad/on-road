"""정책 자동 수집 (정부24 → policies.json). 실제 API 대신 가짜 응답을 쓴다."""

import json
import urllib.parse
from datetime import date

import pytest

from collector import convert, gov24, run
from collector.convert import split_docs, to_policy
from collector.merge import KeyMap, merge, write_json
from formguide import search

TODAY = date(2026, 11, 22)

DETAIL = {
    "서비스ID": "000000465790",
    "서비스명": "자립준비청년 자립수당 지급",
    "서비스목적요약": "보호종료 후 경제적 부담 완화",
    "소관기관명": "보건복지부",
    "부서명": "청년정책팀",
    "소관기관유형": "중앙행정기관",
    "지원유형": "현금",
    "지원대상": "보호종료 5년 이내 자립준비청년\r\n(아동복지시설·가정위탁)",
    "선정기준": "보호종료일 기준 2년 이상 연속 보호",
    "지원내용": "매월 50만 원 지급 (매월 20일)",
    "신청방법": "방문: 주소지 행정복지센터\n온라인: 복지로",
    "신청기한": "상시신청",
    "구비서류": "○ 신청서 1부\n○ 신분증\n○ 위임장 (대리 신청 시)\n○ 신분증",
    "접수기관명": "읍면동 행정복지센터",
    "문의처": "보건복지상담센터 129",
    "온라인신청사이트URL": "https://www.bokjiro.go.kr",
    "상세조회URL": "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/000000465790",
    "법령": "아동복지법 제38조",
}


def _seed_ok():
    """db/seed.py 의 검사를 그대로 쓴다 (백엔드 가상환경에서만)"""
    pytest.importorskip("sqlalchemy")


# ---------------- 변환 ----------------


def test_detail_becomes_seed_format_policy():
    conv = to_policy(DETAIL, "G001", TODAY)
    p = conv.policy
    assert p["policy_key"] == "G001" and p["source_type"] == "api" and p["checked_at"] == "2026-11-22"
    assert p["agency"] == "보건복지부 청년정책팀"
    assert p["category"] == "living_cost" and p["region"] == "전국"
    # 원문에 나이가 없고 대상이 보호종료 자립준비청년 → 가장 이른 보호종료 나이 15세부터 (상한 없음)
    assert p["age_min"] == 15 and p["age_max"] == 29
    assert p["easy_text"] is None and p["easy_text_verified"] is False  # 검수 전에는 앱에 안 보임
    assert p["target_description"].endswith("선정기준: 보호종료일 기준 2년 이상 연속 보호")
    assert "신청기한: 상시신청" in p["apply_method"] and "접수기관: 읍면동 행정복지센터" in p["apply_method"]
    assert p["support_amount"] == "매월 50만 원"
    assert p["apply_url"] == "https://www.bokjiro.go.kr"
    assert "\r" not in p["target_description"]


def test_original_text_uses_chunking_headers():
    from rag.chunking import chunk_policy

    p = to_policy(DETAIL, "G001", TODAY).policy
    lines = p["original_text"].split("\n")
    assert lines[:2] == ["[출처 A: 정부24]", DETAIL["상세조회URL"]]
    assert {"[지원대상]", "[지원내용]", "[신청방법]", "[신청서류]", "[문의처]"} <= set(lines)
    chunks = chunk_policy(p)
    assert all(c.source == "A: 정부24" for c in chunks)
    assert any(c.is_apply for c in chunks)  # 신청 안내 청크가 생긴다


def test_docs_split_with_condition_and_no_duplicates():
    docs, notes = split_docs(DETAIL["구비서류"])
    assert docs == [
        {"name": "신청서 1부", "condition": None},
        {"name": "신분증", "condition": None},
        {"name": "위임장", "condition": "대리 신청 시"},
    ]
    assert notes == []
    assert split_docs("없음") == ([], [])
    long, notes = split_docs("- " + "가" * 250)
    assert len(long[0]["name"]) == 200 and notes


@pytest.mark.parametrize("name, code", [
    ("자립준비청년 대학 장학금", "education"),
    ("자립준비청년 전세임대주택", "housing"),
    ("보호종료아동 취업 지원", "employment"),
    ("자립준비청년 심리 상담", "medical"),
    ("디딤씨앗통장", "finance"),
    ("자립수당", "living_cost"),
    ("자립지원전담기관 사례관리", "independence"),
])
def test_category_rules(name, code):
    assert convert.pick_category({"서비스명": name})[0] == code


@pytest.mark.parametrize("agency, code, warns", [
    ("보건복지부", "전국", False),
    ("서울특별시", "서울", False),
    ("강원특별자치도", "강원", False),
    ("전북특별자치도", "전북", False),
    ("충청남도", "충남", False),
    ("경기도 수원시", "경기", True),  # 시·군·구 정책은 확인할 것으로
])
def test_region_from_agency(agency, code, warns):
    region, note = convert.pick_region({"소관기관명": agency})
    assert region == code and bool(note) == warns


def test_missing_required_content_is_skipped():
    conv = to_policy({**DETAIL, "신청방법": " "}, "G001", TODAY)
    assert conv.policy is None and "신청방법" in conv.skip_reason


def test_support_condition_codes_are_not_used_as_ages():
    """지원조건 JA0110·JA0111 은 뜻이 확인되지 않음 (실제 응답: 자립준비청년 정책이 17~18) → 나이로 쓰지 않음"""
    conv = to_policy(DETAIL, "G001", TODAY, {"JA0110": 17, "JA0111": 18})
    # 지원조건의 17~18 이 아니라 대상 종류(보호종료 자립준비청년)로 정한 15세~ (11/23)
    assert (conv.policy["age_min"], conv.policy["age_max"]) == (15, 29)
    assert not any("17" in n for n in conv.notes)


def test_docs_include_self_check_docs_and_skip_none():
    detail = {**DETAIL, "구비서류": "해당없음", "본인확인필요구비서류": "○ 보호종료확인서", "공무원확인구비서류": "○ 주민등록표 등본"}
    p = to_policy(detail, "G001", TODAY).policy
    assert p["required_docs"] == [{"name": "보호종료확인서", "condition": None}]
    assert "[공무원확인구비서류]\n○ 주민등록표 등본" in p["original_text"]  # 내는 서류는 아니지만 원문에는 남김


# ---------------- 합치기 ----------------

MANUAL = {"policy_key": "P001", "name": "자립수당 (수동)", "source_type": "manual", "checked_at": "2026-10-12"}


def test_merge_never_touches_manual_and_adds_new():
    new = to_policy(DETAIL, "G001", TODAY).policy
    res = merge([MANUAL], [new, {**new, "policy_key": "P001"}])
    assert res.policies[0] == MANUAL and res.manual_kept == ["P001"]
    assert res.added == ["G001"] and res.policies[1] == new


def test_merge_same_content_only_updates_checked_at():
    old = {**to_policy(DETAIL, "G001", date(2026, 10, 1)).policy, "easy_text": "쉬운 말", "easy_text_verified": True,
           "age_min": 18}
    res = merge([old], [to_policy(DETAIL, "G001", TODAY).policy])
    p = res.policies[0]
    assert res.unchanged == ["G001"]
    assert p["checked_at"] == "2026-11-22" and p["easy_text_verified"] is True and p["age_min"] == 18


def test_merge_changed_content_needs_review_again():
    old = {**to_policy(DETAIL, "G001", date(2026, 10, 1)).policy, "easy_text": "쉬운 말", "easy_text_verified": True,
           "category": "independence", "is_active": False}
    new = to_policy({**DETAIL, "지원내용": "매월 60만 원 지급"}, "G001", TODAY).policy
    # 수집기가 전에 넣은 분야는 living_cost 였는데 지금 independence → 사람이 고친 값
    p = merge([old], [new], auto_prev={"G001": {"category": "living_cost", "region": "전국"}}).policies[0]
    assert p["support_content"] == "매월 60만 원 지급" and p["support_amount"] == "매월 60만 원"
    assert p["easy_text_verified"] is False and p["easy_text"] == "쉬운 말"  # 다시 확인할 때 참고
    assert p["category"] == "independence" and p["is_active"] is False  # 사람이 고친 값은 유지


def test_full_run_reports_missing_api_policies():
    gone = {**to_policy(DETAIL, "G009", TODAY).policy}
    assert merge([gone], [], full_run=True).missing == ["G009"]
    assert merge([gone], []).missing == []


def test_keymap_keeps_keys(tmp_path):
    km = KeyMap(tmp_path / "keys.json")
    assert km.key_for("A", set()) == "G001" and km.key_for("B", {"G002"}) == "G003"
    km.save()
    again = KeyMap(tmp_path / "keys.json")
    assert again.key_for("B", set()) == "G003" and again.key_for("C", set()) == "G002"


def test_write_json_keeps_crlf(tmp_path):
    path = tmp_path / "p.json"
    path.write_bytes(b"[\r\n]\r\n")
    write_json(path, [{"a": "가"}])
    raw = path.read_bytes()
    assert b"\r\n" in raw and "가".encode() in raw


# ---------------- 수집 전체 (가짜 API) ----------------


class FakeApi:
    def __init__(self, details: dict[str, dict], list_rows: list[dict]):
        self.details = details
        self.list_rows = list_rows
        self.urls: list[str] = []

    def __call__(self, url):
        self.urls.append(url)
        q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        if "serviceList" in url:
            return {"data": self.list_rows if q["page"] == ["1"] else []}
        sid = q["cond[서비스ID::EQ]"][0]
        if "supportConditions" in url:
            return {"data": [{"서비스ID": sid, "JA0110": "18", "JA0111": "39"}]}
        return {"data": [self.details[sid]] if sid in self.details else []}


@pytest.fixture(autouse=True)
def fake_key(monkeypatch):
    monkeypatch.setattr(search, "_service_key", lambda: "TEST-KEY")


def test_find_service_ids_dedupes_over_keywords_and_fields():
    api = FakeApi({}, [{"서비스ID": "1"}, {"서비스ID": "2"}, {"서비스ID": "1"}])
    assert gov24.find_service_ids(["자립", "보호종료"], api, pause=0) == ["1", "2"]
    fields = {k for u in api.urls for k in urllib.parse.parse_qs(urllib.parse.urlparse(u).query) if k.startswith("cond")}
    assert fields == {"cond[서비스명::LIKE]", "cond[지원대상::LIKE]"}


def test_collect_writes_valid_policies_report_and_raw(tmp_path):
    _seed_ok()
    policies_path = tmp_path / "policies.json"
    policies_path.write_text(json.dumps([]), encoding="utf-8")
    broken = {**DETAIL, "서비스ID": "000000000002", "서비스명": "내용 없는 서비스", "지원내용": ""}
    api = FakeApi({DETAIL["서비스ID"]: DETAIL, broken["서비스ID"]: broken},
                  [{"서비스ID": DETAIL["서비스ID"]}, {"서비스ID": broken["서비스ID"]}, {"서비스ID": "없는ID"}])

    res, text = run.collect(["자립"], None, today=TODAY, get_json=api, policies_path=tmp_path / "team.json", output_path=policies_path,
                            collected_dir=tmp_path / "collected", pause=0)

    saved = json.loads(policies_path.read_text(encoding="utf-8"))
    assert [p["policy_key"] for p in saved] == ["G001"] and res.added == ["G001"]
    run.validate(saved)  # 시드 검사 통과
    assert json.loads((tmp_path / "collected" / "keys.json").read_text(encoding="utf-8")) == {DETAIL["서비스ID"]: "G001"}
    assert (tmp_path / "collected" / "gov24" / f"{DETAIL['서비스ID']}.json").exists()
    report = (tmp_path / "collected" / "report.md").read_text(encoding="utf-8")
    assert report == text and "자립준비청년 자립수당 지급" in report
    assert "지원내용 없음" in report and "상세 정보 없음" in report


def test_dry_run_writes_nothing(tmp_path):
    _seed_ok()
    policies_path = tmp_path / "policies.json"
    api = FakeApi({DETAIL["서비스ID"]: DETAIL}, [])
    res, _ = run.collect(None, [DETAIL["서비스ID"]], today=TODAY, get_json=api, policies_path=tmp_path / "team.json", output_path=policies_path,
                         collected_dir=tmp_path / "collected", dry_run=True, pause=0)
    assert res.added == ["G001"]
    assert not policies_path.exists() and not (tmp_path / "collected").exists()


def test_invalid_result_is_not_saved(tmp_path, monkeypatch):
    _seed_ok()
    policies_path = tmp_path / "policies.json"
    monkeypatch.setattr(run, "to_policy", lambda *a, **k: convert.Converted(
        {**to_policy(DETAIL, "G001", TODAY).policy, "category": "잘못된값"}, DETAIL["서비스ID"]))
    api = FakeApi({DETAIL["서비스ID"]: DETAIL}, [])
    with pytest.raises(run.CollectError, match="category"):
        run.collect(None, [DETAIL["서비스ID"]], today=TODAY, get_json=api, policies_path=tmp_path / "team.json", output_path=policies_path,
                    collected_dir=tmp_path / "collected", pause=0)
    assert not policies_path.exists()


def test_real_policies_json_still_valid_after_merge():
    """지금 policies.json(P001 + 수집한 G…)도 시드 검사를 통과하고, 새 정책을 더 합쳐도 기존 정책은 그대로다"""
    _seed_ok()
    current = json.loads(run.POLICIES_PATH.read_text(encoding="utf-8-sig"))
    run.validate(current)
    res = merge(current, [to_policy(DETAIL, "G999", TODAY).policy])  # 지금 파일에 없는 키
    run.validate(res.policies)
    assert res.policies[: len(current)] == current and res.added == ["G999"]


def test_same_name_as_manual_policy_is_not_added(tmp_path):
    """정부24 '자립준비청년 자립수당 지급'은 사람이 쓴 P001 과 같은 제도 → 넣지 않고 보고서로. 팀 파일은 그대로"""
    _seed_ok()
    team = tmp_path / "team.json"
    team.write_bytes(run.POLICIES_PATH.read_bytes())  # 지금 팀 policies.json (P001)
    before = team.read_bytes()
    out = tmp_path / "collected" / "policies.json"
    api = FakeApi({DETAIL["서비스ID"]: DETAIL}, [])
    res, text = run.collect(None, [DETAIL["서비스ID"]], today=TODAY, get_json=api, policies_path=team, output_path=out,
                            collected_dir=tmp_path / "collected", pause=0)
    assert res.added == [] and "(= P001)" in text
    assert team.read_bytes() == before  # 팀 파일은 읽기만
    assert json.loads(out.read_text(encoding="utf-8")) == []
    assert not (tmp_path / "collected" / "keys.json").exists() or "G001" not in (
        tmp_path / "collected" / "keys.json").read_text(encoding="utf-8")


def test_write_json_keeps_missing_final_newline(tmp_path):
    path = tmp_path / "p.json"
    path.write_bytes(b"[\r\n  1\r\n]")
    write_json(path, [1])
    assert path.read_bytes() == b"[\r\n  1\r\n]"


# ---------------- 대상 고르기 · 같은 제도 (실제 정부24 응답에서 나온 이름들) ----------------


@pytest.mark.parametrize("name, target, keep", [
    ("자립준비청년(보호종료아동) 자립수당 지급", "보호종료 5년 이내", True),
    ("퇴소 및 보호종결아동 자립정착금 지원", "시설 퇴소 아동", True),
    ("가정위탁 아동 능력개발비 지원", "가정위탁 아동", True),  # 띄어쓰기가 달라도
    ("가정위탁아동 대학진학자금 지원", "가정위탁 아동", True),
    ("기존주택 전세임대주택 지원사업", "자립준비청년, 신혼부부, 청년", True),  # 대상에 있고 자립에 쓰이는 제도
    ("은평자준청 운영", "시설 퇴소 청년", True),
    ("가정위탁아동 양육보조금 지원", "가정위탁 세대", False),  # 위탁부모가 받는 돈
    ("입양축하금 지원", "입양 가정", False),
    ("장애인거주시설 퇴소자 자립지원", "장애인거주시설 퇴소자", False),  # '자립'이 있어도 다른 대상
    ("한부모가족 복지시설 퇴소자 자립지원금 지원", "한부모가족 복지시설 퇴소자", False),
    ("체육시설 이용요금 감면", "기초수급자, 아동복지시설 아동", False),
    ("에너지바우처", "보호대상아동 포함 취약계층", False),
    ("아동 생활안정 지원", "저소득 아동", False),
])
def test_relevance_rules(name, target, keep):
    ok, why = convert.relevance({"서비스명": name, "지원대상": target})
    assert ok is keep, why


def test_off_target_is_reported_not_added():
    conv = to_policy({**DETAIL, "서비스명": "입양축하금 지원", "지원대상": "입양 가정"}, "G001", TODAY)
    assert conv.policy is None and conv.off_target and "입양" in conv.skip_reason


def test_same_program_needs_same_name_and_agency():
    from collector.merge import NameIndex, agency_key

    idx = NameIndex([{"policy_key": "P001", "name": "자립준비청년 자립수당 지급", "agency": "보건복지부 청년정책팀"}])
    assert idx.duplicate_of("자립준비청년(보호종료아동) 자립수당 지급", "보건복지부", None) == "P001"
    assert idx.duplicate_of("자립준비청년 자립수당 지급", "충청북도 보은군", None) is None  # 군의 따로 된 제도
    assert agency_key("서울특별시 서초구 복지정책과") == "서울특별시서초구" != agency_key("서울특별시 강남구")
    assert idx.duplicate_of("자립준비청년 자립수당 지급", "보건복지부", "P001") is None  # 내가 넣은 것 다시 수집


def test_doc_split_rejoins_broken_line():
    """실제 응답 (국토교통부 전세임대): '개인정보 수집' / '이용 및 제3자 제공 동의서' 가 두 줄로 옴"""
    docs, _ = split_docs("전세임대주택 공급신청서\n신분증\n개인정보 수집\n이용 및 제3자 제공 동의서")
    assert [d["name"] for d in docs] == ["전세임대주택 공급신청서", "신분증", "개인정보 수집·이용 및 제3자 제공 동의서"]
    docs, _ = split_docs("○ 신청서\n○ 이용권 사본")  # 글머리표가 있으면 따로 된 서류
    assert [d["name"] for d in docs] == ["신청서", "이용권 사본"]


# ---------------- 구비서류 구조 읽기 (실제 정부24 원문 모양) ----------------


def _names(raw):
    return [(d["name"], d["condition"]) for d in split_docs(raw)[0]]


def test_docs_heading_lines_are_not_docs():
    """G008: 'o 제출서류' 는 묶음 제목"""
    assert _names("o 제출서류\n- 신청서 및 개인정보 수집·이용동의서\n- 신청자 신분증\n- 통장사본") == [
        ("신청서 및 개인정보 수집·이용동의서", None), ("신청자 신분증", None), ("통장사본", None)]


def test_docs_group_label_becomes_condition_and_commas_split():
    """G033: '○ 공통서류' 제목, '○ 자립준비청년' 아래 서류의 조건, 쉼표로 여러 서류"""
    raw = "○ 공통서류\n- 참여신청서, 개인정보동의서, 상담원문답표\n○ 자립준비청년\n- 보호종료확인서"
    assert _names(raw) == [("참여신청서", None), ("개인정보동의서", None), ("상담원문답표", None),
                           ("보호종료확인서", "자립준비청년")]


def test_docs_inline_label_and_section_note():
    """G032 · G088: '신혼부부 : 혼인관계증명서', '※ 추가제출서류(해당 대상자)', '3. 우선공급 청년 :'"""
    raw = ("1. 입주신청서 [별첨 1]\n※ 추가제출서류(해당 대상자)\n1. 자동차등록원부(갑)\n2. 신혼부부 : 혼인관계증명서\n"
           "3. 우선공급 청년 :\n- 우선공급 청년 신청확인서 [별첨 5]\n4. 농·축산·임·어업인\n- 농업경영제 등록확인서, 어업경영체 등록확인서")
    assert _names(raw) == [
        ("입주신청서 [별첨 1]", None), ("자동차등록원부(갑)", "해당 대상자"), ("혼인관계증명서", "신혼부부"),
        ("우선공급 청년 신청확인서 [별첨 5]", "우선공급 청년"),
        ("농업경영제 등록확인서", "농·축산·임·어업인"), ("어업경영체 등록확인서", "농·축산·임·어업인")]


def test_docs_comma_inside_parens_and_lead_condition():
    """괄호 안 쉼표는 나누지 않음, G015 앞 괄호 조건"""
    assert _names("- 금융정보 등(금융, 신용, 보험정보) 제공 동의서") == [("금융정보 등(금융, 신용, 보험정보) 제공 동의서", None)]
    assert _names("○ 자립정착금 사용계획서, (자립교육신청·수료 후) 교육수료 확인서") == [
        ("자립정착금 사용계획서", None), ("교육수료 확인서", "자립교육신청·수료 후")]


def test_reprocess_uses_saved_raw_without_api(tmp_path):
    """하루 한도(HTTP 429)를 다 쓴 날 · 규칙을 고친 뒤: 저장한 원본으로 다시 변환. 확인일은 그대로"""
    _seed_ok()
    policies_path = tmp_path / "policies.json"
    policies_path.write_text("[]", encoding="utf-8")
    api = FakeApi({DETAIL["서비스ID"]: DETAIL}, [])
    kw = dict(policies_path=tmp_path / "team.json", output_path=policies_path, collected_dir=tmp_path / "collected", pause=0)
    run.collect(None, [DETAIL["서비스ID"]], today=date(2026, 11, 1), get_json=api, **kw)

    def no_api(url):
        raise AssertionError("API 를 부르면 안 됨")

    res, text = run.collect(None, None, today=TODAY, get_json=no_api, reprocess=True, **kw)
    assert res.unchanged == ["G001"]
    assert json.loads(policies_path.read_text(encoding="utf-8"))[0]["checked_at"] == "2026-11-01"
    assert (tmp_path / "collected" / "reprocess_report.md").exists() and "저장한 원본" in text


def test_amounts_with_million_units():
    """G012 실제 원문: '20년 5백만원, 21년 10백만원, 22년 이후 15백만원'"""
    assert convert.find_amounts("'20년 5백만원, 21년 10백만원, 22년 이후 15백만원(1차") == "5백만원, 10백만원, 15백만원"
    assert convert.find_amounts("1인당 1천만원 이내") == "1인당 1천만원"  # 앞말(월 · 1인당)은 남긴다


def test_amount_rule_change_updates_policy():
    """금액만 달라지면(뽑는 규칙을 고친 뒤) 금액 칸만 새로 쓰고, 원문은 그대로라 쉬운 말 검수 상태는 지킨다"""
    old = {**to_policy(DETAIL, "G001", TODAY).policy, "support_amount": None, "easy_text": "쉬운 말", "easy_text_verified": True}
    res = merge([old], [to_policy(DETAIL, "G001", TODAY).policy])
    assert res.changed == [] and "G001" in res.relabeled
    p = res.policies[0]
    assert p["support_amount"] == "매월 50만 원" and p["easy_text_verified"] is True and p["easy_text"] == "쉬운 말"


def test_total_amount_for_split_payments():
    """금액이 여러 개여도 한 사람이 받는 총액이 분명하면 넣는다 (자립정착금 분할 지급)"""
    f = convert.policy_amount_text
    assert f("자립정착금 1,500만원 지급 (당해년도 1,000만원 + 익년도 500만원)") == "1,500만 원"
    assert f("'20년 5백만원, 21년 10백만원, 22년 이후 15백만원(1차 10백만원, 2차 5백만원)") == "1,500만 원"
    assert f("1회 : 10,000,000원 지원 / 2회 : 5,000,000원 지원") == "1,500만 원"
    assert f("1회 1천5백만원의 자립정착금 지급(1차, 2차 분할지급)") == "1회 1,500만 원"
    assert f("자립정착금 천만 원 지원(1회)") == "1,000만 원"
    assert f("1인 1000만원 지급 (구 자체 추가지원100만원/1인)") == "1,100만 원"
    # 월 금액이 섞였거나 총액이 분명하지 않으면 비운다
    assert f("월 10만 원, 10만 원, 월 50만 원") is None
    assert f("가구당 30만원, 1인 가구 20만원") is None


def test_real_policies_json_keeps_manual_first():
    """실제 policies.json: 사람이 쓴 정책(P001)이 맨 앞에 그대로, 나머지는 자동 수집(api · crawler), 시드 검사 통과"""
    _seed_ok()
    items = json.loads(run.POLICIES_PATH.read_text(encoding="utf-8-sig"))
    run.validate(items)
    manual = [p for p in items if p["source_type"] == "manual"]
    assert items[: len(manual)] == manual and manual[0]["policy_key"] == "P001"
    assert all(p["source_type"] in ("api", "crawler") for p in items[len(manual):])


def test_default_saves_into_policies_file_keeping_manual(tmp_path):
    """output_path 를 안 주면 policies_path 에 바로 합친다 (사람이 쓴 정책은 그대로)"""
    _seed_ok()
    team = tmp_path / "policies.json"
    team.write_bytes(run.POLICIES_PATH.read_bytes())
    p001 = json.loads(team.read_text(encoding="utf-8-sig"))[0]
    api = FakeApi({"999": {**DETAIL, "서비스ID": "999", "서비스명": "자립준비청년 테스트 정착금", "소관기관명": "경기도 테스트시"}}, [])
    res, _ = run.collect(None, ["999"], today=TODAY, get_json=api, policies_path=team,
                         collected_dir=tmp_path / "collected", pause=0)
    saved = json.loads(team.read_text(encoding="utf-8"))
    before_keys = {p["policy_key"] for p in json.loads(run.POLICIES_PATH.read_text(encoding="utf-8-sig"))}
    # keys.json 이 없어도 이미 있는 키(G001 …)를 덮어쓰지 않고 새 키를 받는다
    assert saved[0] == p001 and len(res.added) == 1 and res.added[0] not in before_keys and res.changed == []
    assert saved[-1]["policy_key"] == res.added[0] and len(saved) == len(before_keys) + 1


# ---------------- 일시적 오류 (HTTP 429) — 실제로 수집 중에 났던 것 ----------------


@pytest.fixture
def no_wait(monkeypatch):
    monkeypatch.setattr(gov24, "RETRY_BASE_SECONDS", 0)


def test_transient_error_is_retried(no_wait):
    calls = []

    def flaky(url):
        calls.append(url)
        if len(calls) < 3:
            raise search.SearchError("공공서비스 API 오류 (HTTP 429): 트래픽 허용 횟수를 초과")
        return {"data": [DETAIL]}

    assert gov24.fetch_detail(DETAIL["서비스ID"], flaky) == DETAIL and len(calls) == 3


def test_non_transient_error_is_not_retried(no_wait):
    calls = []

    def bad(url):
        calls.append(url)
        raise search.SearchError("공공서비스 API 오류 (HTTP 400): 잘못된 요청")

    with pytest.raises(search.SearchError):
        gov24.fetch_detail("1", bad)
    assert len(calls) == 1


def test_one_failed_detail_does_not_stop_collection(tmp_path, no_wait):
    """상세 하나가 다시 물어도 429 면 그것만 건너뛰고 나머지는 저장 (전에는 전체가 멈추고 아무것도 저장 안 됨)"""
    _seed_ok()
    other = {**DETAIL, "서비스ID": "000000000002", "서비스명": "자립준비청년 테스트 정착금", "소관기관명": "경기도 테스트시"}

    class Api(FakeApi):
        def __call__(self, url):
            if "serviceDetail" in url and "000000000002" in url:
                raise search.SearchError("공공서비스 API 오류 (HTTP 429)")
            return super().__call__(url)

    api = Api({DETAIL["서비스ID"]: DETAIL, other["서비스ID"]: other}, [])
    out = tmp_path / "out.json"
    res, text = run.collect(None, [DETAIL["서비스ID"], other["서비스ID"]], today=TODAY, get_json=api,
                            policies_path=tmp_path / "team.json", output_path=out, collected_dir=tmp_path / "c", pause=0)
    assert res.added == ["G001"] and "상세를 받지 못함" in text
    assert [p["policy_key"] for p in json.loads(out.read_text(encoding="utf-8"))] == ["G001"]


def test_failed_detail_is_not_reported_missing(tmp_path, no_wait):
    """전에 넣은 정책을 이번에 못 받았으면 '종료된 정책인지 확인'으로 알리지 않는다"""
    _seed_ok()
    out = tmp_path / "out.json"
    kw = dict(today=TODAY, policies_path=tmp_path / "team.json", output_path=out, collected_dir=tmp_path / "c", pause=0)
    run.collect(None, [DETAIL["서비스ID"]], get_json=FakeApi({DETAIL["서비스ID"]: DETAIL}, []), **kw)

    class Down(FakeApi):
        def __call__(self, url):
            if "serviceDetail" in url:
                raise search.SearchError("공공서비스 API 오류 (HTTP 503)")
            return super().__call__(url)

    res, _ = run.collect(["자립"], None, get_json=Down({}, [{"서비스ID": DETAIL["서비스ID"]}]), **kw)
    res2, _ = run.collect(None, None, get_json=Down({}, [{"서비스ID": DETAIL["서비스ID"]}]), **kw)  # 전체 수집
    assert res2.missing == [] and json.loads(out.read_text(encoding="utf-8"))[0]["policy_key"] == "G001"


# ---------------- 분야 · 지역: 사람이 고친 것만 지키기 ----------------


def test_rule_change_relabels_untouched_policy():
    """분류 규칙을 고친 뒤: 사람이 안 고친 정책은 새 분야로, 내용은 같으니 검수 상태는 그대로"""
    old = {**to_policy(DETAIL, "G001", TODAY).policy, "category": "independence", "easy_text_verified": True,
           "easy_text": "쉬운 말"}
    new = to_policy(DETAIL, "G001", TODAY).policy  # 새 규칙: living_cost
    res = merge([old], [new], auto_prev={"G001": {"category": "independence", "region": "전국"}})
    p = res.policies[0]
    assert res.relabeled == ["G001"] and p["category"] == "living_cost" and p["easy_text_verified"] is True
    assert res.auto_values["G001"] == {"category": "living_cost", "region": "전국", "age_min": 15, "age_max": 29}


def test_human_edited_category_is_kept_even_if_rule_changes():
    old = {**to_policy(DETAIL, "G001", TODAY).policy, "category": "finance"}  # 사람이 finance 로 고침
    res = merge([old], [to_policy(DETAIL, "G001", TODAY).policy],
                auto_prev={"G001": {"category": "independence", "region": "전국"}})
    assert res.relabeled == [] and res.policies[0]["category"] == "finance"


def test_collect_saves_auto_values_for_next_merge(tmp_path):
    _seed_ok()
    run.collect(None, [DETAIL["서비스ID"]], today=TODAY, get_json=FakeApi({DETAIL["서비스ID"]: DETAIL}, []),
                policies_path=tmp_path / "team.json", output_path=tmp_path / "out.json",
                collected_dir=tmp_path / "c", pause=0)
    saved = json.loads((tmp_path / "c" / "auto_values.json").read_text(encoding="utf-8"))
    assert saved == {"G001": {"category": "living_cost", "region": "전국", "age_min": 15, "age_max": 29}}


def test_support_amount_only_when_single_amount():
    """여러 금액을 이어 붙이면 뜻이 틀어짐 (G084: 50만 원은 추가 적립 한도) → 하나일 때만 넣는다"""
    many = to_policy({**DETAIL, "지원내용": "1:2 매칭으로 월 10만 원 내 지원. 10만 원 적립 후 월 50만 원 내 추가 적립"}, "G1", TODAY)
    assert many.policy["support_amount"] is None and "월 50만 원" in many.policy["support_content"]
    assert to_policy(DETAIL, "G1", TODAY).policy["support_amount"] == "매월 50만 원"


def test_legal_aid_is_not_medical_and_foundation_prefix_is_ignored():
    """실제 데이터에서 찾은 분류 오류: 소송대리가 '의료'(법률상담의 '상담'), 인천 재단 장학금이 '전국'"""
    assert convert.pick_category({"서비스명": "무료 소송대리 서비스(보호대상아동)", "지원내용": "무료 법률상담"})[0] == "independence"
    assert convert.pick_region({"소관기관명": "(재)인천인재평생교육진흥원"})[0] == "인천"
    assert convert.pick_region({"소관기관명": "재단법인 경기도청소년재단"})[0] == "경기"
    # 시 이름으로 시작하는 기관 (11/23): 전에는 '전국'으로 들어감
    assert convert.pick_region({"소관기관명": "재단법인목포인재육성재단"})[0] == "전남"
    assert convert.pick_region({"소관기관명": "보건복지부"})[0] == "전국"


def test_same_criteria_not_appended():
    """선정기준이 지원대상을 되풀이하면 붙이지 않는다 (대상 문단이 두 번 들어가던 것, G018 등)"""
    same = {**DETAIL, "선정기준": DETAIL["지원대상"] + " "}
    target = to_policy(same, "G001", TODAY).policy["target_description"]
    assert "선정기준:" not in target and "보호종료 5년 이내" in target


# ---------------- 나이 (11/23) ----------------


@pytest.mark.parametrize("target, expected", [
    ("○ 천안시 거주 18~39세 이하 미취업 청년\n- 자립준비청년(보호종료아동)", (18, 39)),  # G023
    ("○ 만18세 이상의 보호가 종료된 아동복지시설 퇴소 아동", (18, None)),  # G005
    ("○ 보호대상 아동 : 만 18세 미만의 아동양육시설 아동", (None, 17)),  # G084
    ("◎ 18세 ~ 34세 미취업 청년 , 자립준비청년\n◎ 지역특화청년 : 35~39세 인천청년", (18, 39)),  # G028: 감싸는 범위
])
def test_ages_from_clear_text(target, expected):
    from collector.convert import pick_ages

    assert pick_ages(target)[:2] == expected


@pytest.mark.parametrize("target", [
    "○ 만 18세 이후 보호가 종료된 아동",  # 시점이지 지금 나이가 아님
    "○ 만 15세 이후 조기 보호종료된 경우 18세가 된 때로부터 5년간 지원",
    "18세 미만의 아동(18세 이상인 경우에도 고등학교 재학 중인 아동은 포함)",  # 예외 (G069)
    "○ 가정위탁 아동으로 책정된 아동(만18세 미만, 보호연장 가능)",  # G067
    "문답표 21점 이상 19~39세 청년 또는 자립준비청년*",  # 나이 없는 대상과 '또는' (G025)
    "(지원대상) 구직단념청년*, 자립준비청년, 지역특화청년\n* 구직단념청년: 21점 이상인 청년(만 18세 이상~34세 이하)",  # 각주 (G019)
    "○ 청년\n- 18세 이상 45세 이하의 무주택자\n○ 자립준비청년\n- 보호종료자",  # 나이 없는 묶음 (G032)
    "가. 전문가정위탁보호: 피해아동, 2세이하 아동등 특별한 보호가 필요한 아동",  # 유형 하나의 조건 (G057)
    "혼인 7년 이내이거나 6세 이하 자녀를 둔 부부",  # 자녀 나이
    "○ 보호종료 5년 이내 자립준비청년",  # 나이 없음
])
def test_unclear_ages_are_left_empty(target):
    from collector.convert import pick_ages

    assert pick_ages(target)[:2] == (None, None)


def test_merge_ages_follow_rule_unless_human_edited():
    """나이는 분야 · 지역처럼: 수집기가 넣은 값 그대로면 새 규칙 값, 사람이 고쳤으면 지킨다"""
    with_age = {**DETAIL, "지원대상": "○ 천안시 거주 18~39세 이하 미취업 청년"}
    old = {**to_policy(DETAIL, "G001", TODAY).policy, "age_min": None, "age_max": None}  # 예전 규칙: 나이 비움
    new = to_policy(with_age, "G001", TODAY).policy
    assert (new["age_min"], new["age_max"]) == (18, 39)
    p = merge([old], [new], auto_prev={"G001": {"category": "living_cost", "region": "전국"}}).policies[0]
    assert (p["age_min"], p["age_max"]) == (18, 39)  # 기록 없음(예전엔 늘 null) → 새 규칙 값
    edited = {**new, "age_max": 34}  # 사람이 상한을 34로 고침
    p = merge([edited], [new], auto_prev={"G001": {"category": "living_cost", "region": "전국", "age_min": 18,
                                                   "age_max": 39}}).policies[0]
    assert p["age_max"] == 34


@pytest.mark.parametrize("name, target, content, expected", [
    ("가정위탁아동 중고교생 교통비 지원", "○ 가정위탁아동 중 중고등학생", "", (None, 17)),  # 보호 중 아동: 18세 미만
    ("생활시설아동용돈지급", "○ 아동복지시설 입소아동 중 초·중·고등학생", "", (None, 17)),
    ("아동복지시설 퇴소자 지원", "○ 만 18세 이후 보호가 종료된 아동복지시설 및 가정위탁 보호종료아동", "", (18, 29)),
    ("아동복지시설 만기퇴소 청소년 자립정착금 지원", "○ 관내 보호종료청소년", "", (18, 29)),  # 만기
    ("보호종료아동 자립정착금 지원", "○ 만 15세 이후 아동복지시설 및 가정위탁 보호가 조기 종료되었거나", "", (15, 29)),
    ("은평자준청 운영", "○ 아동복지시설 퇴소하거나 퇴소 예정인 자립준비(예정)청년", "", (15, 29)),  # 아직 보호 중
    ("가정위탁 종결아동 자립정착금 지원", "○ 가정위탁 종결 아동", "", (15, 29)),  # 나이 말이 없으면 가장 이른 15세
])
def test_ages_from_target_kind(name, target, content, expected):
    from collector.convert import infer_ages

    assert infer_ages(name, target, content)[:2] == expected


@pytest.mark.parametrize("name, target, content", [
    ("보호아동 생활안정 지원", "○ 자립정착금: 보호종료 5년 이내인 자립준비청년\n○ 능력개발비: 아동복지시설 입소 아동", ""),  # 섞임
    ("임대주택 임대 지원", "국가유공자, 기초생활수급자, 장애인, 아동복지시설퇴소자 등", ""),  # 다른 대상
    ("가정위탁아동 대학진학자금 지원", "가정위탁 보호아동", ""),  # 대학 진학: 입학 나이가 다양
    ("가정위탁보호아동 지원", "○ 보호자가 없거나 이탈된 가정위탁 보호아동", "○ 18세 이상 가정위탁보호 종료 아동 전세주택 지원"),  # 내용에 보호종료자
    ("전문멘토", "○ 멘토 : 관내 지역사회전문가", ""),  # 대상 종류를 모름
])
def test_unclear_target_kind_is_left_empty(name, target, content):
    from collector.convert import infer_ages

    assert infer_ages(name, target, content)[:2] == (None, None)


def test_explicit_age_wins_over_target_kind():
    detail = {**DETAIL, "서비스명": "가정위탁아동 지원", "지원대상": "○ 가정위탁 보호 중인 만 18세 미만 아동"}
    p = to_policy(detail, "G001", TODAY).policy
    assert (p["age_min"], p["age_max"]) == (None, 17)  # 원문 나이가 있으면 그것 (대상 종류의 24 가 아니라)


def test_housing_condition_is_not_another_target():
    from collector.convert import infer_ages

    # '무주택' · '미혼'은 다른 대상이 아니라 조건 (W002) → 보호가 끝난 청년 · 퇴소 예정 15세~
    assert infer_ages("보호종료아동 임대주택 지원", "아동복지시설 : 퇴소 예정이거나 퇴소한지 5년이 지나지 아니한 미혼 무주택자")[:2] == (15, 29)


def test_normalize_amount_units():
    # 수집처마다 다른 단위를 저장할 때 맞춘다 (앞말은 그대로, 1억 원 이상은 사업 예산으로 보고 비운다)
    n = convert.normalize_amount
    assert n("300천원") == "30만 원"
    assert n("10,000,000원") == "1,000만 원"
    assert n("1인당 1천만원") == "1인당 1,000만 원"
    assert n("5백만원") == "500만 원"
    assert n("매월 50만 원") == "매월 50만 원"
    assert n("월 최대 20만원") == "월 최대 20만 원"
    assert n("5,500원") == "5,500원"
    assert n("659,900천원") is None
    assert n(None) is None


def test_write_json_failure_keeps_old_file(tmp_path, monkeypatch):
    """쓰는 도중 실패해도 원래 파일은 그대로, 임시 파일은 남지 않는다"""
    import collector.merge as merge_mod

    path = tmp_path / "policies.json"
    write_json(path, [{"policy_key": "G001"}])
    before = path.read_bytes()

    def broken(*a):
        raise OSError("disk full")

    monkeypatch.setattr(merge_mod.os, "replace", broken)
    with pytest.raises(OSError):
        write_json(path, [{"policy_key": "G002"}])
    assert path.read_bytes() == before and list(tmp_path.iterdir()) == [path]
