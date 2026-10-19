"""핵심 사실 추출·카드 요약 (facts.py). OpenAI 대신 가짜 LLM 을 쓴다.

실행 (src 폴더에서): backend\\.venv\\Scripts\\python -m pytest easytext/tests
"""

import json

from easytext.convert import convert_level, source_units
from easytext.facts import (
    CARD_ITEMS, CARD_LIMITS, FACTS_SYSTEM, MAX_CHARS, MAX_CHARS_LONG, card, check_facts, clean_facts, derive, extract, load,
    passed_facts, rebuild, save, source_hash,
)
from easytext.levels import get_level
from easytext.tests.test_easytext import GOOD, POLICY, FakeLLM


def F(item, text, *src, key=False):
    return {"item": item, "text": text, "src": list(src), "key": key}


# POLICY 원문 단위: S1~S3 대상, S4 지원 내용, S5 금액, S6 기간, S7 신청 방법, S8 신청서, S9 신분증, S10 문의처
GOOD_FACTS = {"facts": [
    F("target", "아동복지시설 보호종료 후 5년 이내", "S1", key=True),
    F("target", "보호 2년 이상", "S2"),
    F("exclusion", "원가정 복귀 시 지원 불가", "S3"),
    F("benefit", "월 50만 원, 매월 현금 지급", "S4", "S5", key=True),
    F("period", "보호종료 후 5년", "S6"),
    F("apply", "주소지 행정복지센터 방문 또는 복지로 온라인", "S7"),
    F("docs", "신청서 1부", "S8"),
    F("docs", "신분증", "S9"),
]}


def _check(facts):
    return check_facts(clean_facts({"facts": facts}), source_units(POLICY))


def test_good_facts_pass():
    r = _check(GOOD_FACTS["facts"])
    assert r.passed, r.feedback()


def test_contact_unit_is_optional():
    # 문의처(S10)는 카드에 넣지 않으므로 인용하지 않아도 된다
    assert not any("S10" in u for u in _check(GOOD_FACTS["facts"]).missing_units)


def test_missing_unit_is_reported():
    facts = [f for f in GOOD_FACTS["facts"] if f["src"] != ["S2"]]
    r = _check(facts)
    assert not r.passed and any(u.startswith("S2 ") for u in r.missing_units)


def test_number_and_boundary_must_stay():
    facts = [F("target", "아동복지시설 보호종료자", "S1") if f["src"] == ["S1"] else f for f in GOOD_FACTS["facts"]]
    r = _check(facts)
    assert any(g.startswith("S1:") and "5년 이내" in g for g in r.unit_gaps)
    # 경계 단어가 바뀌어도 잡는다 ("2년 이상" → "2년 넘게")
    facts = [F("target", "보호 2년 넘게", "S2") if f["src"] == ["S2"] else f for f in GOOD_FACTS["facts"]]
    assert any(g.startswith("S2:") for g in _check(facts).unit_gaps)


def test_unsupported_number_is_reported():
    facts = [F("benefit", "월 70만 원, 매월 현금 지급", "S4", "S5") if f["item"] == "benefit" else f
             for f in GOOD_FACTS["facts"]]
    r = _check(facts)
    assert any("70" in t for t in r.unsupported_numbers)


def test_exclusion_needs_exclusion_item_or_negation():
    facts = [F("target", "원가정 복귀자", "S3") if f["src"] == ["S3"] else f for f in GOOD_FACTS["facts"]]
    r = _check(facts)
    assert any(g.startswith("S3:") for g in r.unit_gaps)
    # "확인 필요"로만 넘기면 제외 조건 자체가 사라지므로 안 된다
    facts = [F("check", "제외 범위 담당기관 확인 필요", "S3") if f["src"] == ["S3"] else f for f in GOOD_FACTS["facts"]]
    assert any(g.startswith("S3:") for g in _check(facts).unit_gaps)


def test_too_long_and_format_problems():
    long_text = "가" * (MAX_CHARS + 1)
    r = _check(GOOD_FACTS["facts"] + [F("target", long_text, "S1"), F("모름", "보호종료자", "S99"), F("target", "", "S1")])
    assert r.too_long and len(r.format_problems) == 3
    assert not r.passed
    # 서류·확인 필요는 카드에 안 나와서 더 길어도 된다
    assert not _check(GOOD_FACTS["facts"] + [F("check", "가" * MAX_CHARS_LONG, "S1")]).too_long


def test_copied_sentences_memos_and_menu_paths_are_reported():
    bad = [F("exclusion", "정보북은 원가정 복귀한 경우를", "S3"), F("apply", "복지로 신청한다", "S7"),
           F("apply", "로그인 > 서비스 신청", "S7"), F("check", "기본 첨부는 이수증이며", "S8")]
    problems = _check(GOOD_FACTS["facts"] + bad).format_problems
    assert sum("출처" in p for p in problems) == 1
    assert sum("메뉴 경로" in p for p in problems) == 1
    assert sum("명사형" in p for p in problems) == 3
    # 명사형으로 끝나면 통과 ("지원 불가", "1부", "신청")
    assert _check(GOOD_FACTS["facts"]).passed


def test_fragment_and_key_limit():
    r = _check(GOOD_FACTS["facts"] + [F("target", "(2018년 이후)", "S1")])
    assert any("조각" in p for p in r.format_problems)
    n = CARD_LIMITS["target"]
    many = [F("target", f"보호종료 후 5년 이내 {i}", "S1", key=True) for i in range(n + 1)]
    r = _check(GOOD_FACTS["facts"] + many)
    assert any("target 의 key" in p for p in r.format_problems)


def test_clean_facts_tolerates_bad_output():
    assert clean_facts(None) == []
    assert clean_facts({"facts": "x"}) == []
    assert clean_facts({"facts": [1, {"item": " target ", "text": " 보호  2년 ", "src": "S1"}]}) == [
        {"item": "target", "text": "보호 2년", "src": [], "key": False}]


def test_card_only_card_items_in_order_and_deduped():
    facts = clean_facts(GOOD_FACTS) + [F("exclusion", "원가정 복귀 시 지원 불가", "S3")]
    c = card(facts)
    assert [s["item"] for s in c] == [i for i in CARD_ITEMS if any(f["item"] == i for f in facts)]
    assert "docs" not in [s["item"] for s in c]
    target = next(s for s in c if s["item"] == "target")
    # key 구절만 카드에 (key 없는 "보호 2년 이상"은 빠짐)
    assert target["label"] == "지원 대상" and target["items"] == ["아동복지시설 보호종료 후 5년 이내"]
    exclusion = next(s for s in c if s["item"] == "exclusion")
    assert exclusion["items"] == ["원가정 복귀 시 지원 불가"]  # key 가 없으면 앞에서부터, 같은 구절은 한 번


def test_card_respects_limits_without_keys():
    facts = [F("apply", f"신청 방법 {i}", "S7") for i in range(5)]
    assert card(facts)[0]["items"] == [f"신청 방법 {i}" for i in range(CARD_LIMITS["apply"])]


def test_extract_retries_until_pass():
    bad = {"facts": GOOD_FACTS["facts"][:2]}  # 대부분 빠짐
    llm = FakeLLM(bad, GOOD_FACTS)
    r = extract(POLICY, llm)
    assert r["passed"] and r["attempts"] == 2
    assert "S3" in llm.calls[1][-1]["content"]  # 빠진 단위를 지적해 다시 요청
    assert r["card"] and r["notice"] and r["verified"] is False
    assert r["source_hash"] == source_hash(POLICY)
    assert "S1" in llm.calls[0][1]["content"] and FACTS_SYSTEM == llm.calls[0][0]["content"]


def test_extract_keeps_best_attempt_when_never_passes():
    worse = {"facts": []}
    almost = {"facts": GOOD_FACTS["facts"][:-1]}
    r = extract(POLICY, FakeLLM(almost, worse, worse, worse))
    assert not r["passed"] and r["attempts"] == 4
    assert len(r["facts"]) == len(almost["facts"])


def test_extract_with_judge():
    issue = {"issues": [{"type": "changed", "severity": "high", "original": "월 50만 원",
                         "converted": "월 50만 원", "reason": "금액 주기가 달라짐"}]}
    llm, judge = FakeLLM(GOOD_FACTS), FakeLLM(issue, {"issues": []})
    r = extract(POLICY, llm, judge)
    assert r["passed"] and len(judge.calls) == 2 and r["attempts"] == 2
    assert "키워드 목록" in judge.calls[0][0]["content"]  # 키워드 줄임은 low 로 보라는 말을 덧붙임


def test_save_load_and_passed_facts(tmp_path):
    r = extract(POLICY, FakeLLM(GOOD_FACTS))
    path = save(r, "fake", tmp_path)
    assert load("T001", tmp_path)["model"] == "fake"
    assert passed_facts(POLICY, tmp_path) == r["facts"]
    # 원문이 바뀌면 예전 사실은 쓰지 않는다
    assert passed_facts({**POLICY, "support_amount": "월 60만 원"}, tmp_path) is None
    # 사람이 확인한 결과는 같은 내용으로 다시 뽑아도 verified 를 지우지 않는다
    data = json.loads(path.read_text(encoding="utf-8"))
    path.write_text(json.dumps({**data, "verified": True}, ensure_ascii=False), encoding="utf-8")
    save(r, "fake", tmp_path)
    assert load("T001", tmp_path)["verified"] is True
    changed = {**r, "facts": r["facts"][:-1]}
    save(changed, "fake", tmp_path)
    assert load("T001", tmp_path)["verified"] is False


def test_convert_uses_facts_as_checklist():
    facts = clean_facts(GOOD_FACTS)
    llm = FakeLLM(GOOD)
    r = convert_level(POLICY, get_level("L6"), llm, facts=facts)
    assert r["facts_used"] and "[핵심 사실 목록]" in llm.calls[0][1]["content"]
    assert r["passed"], r["checks"]


def test_convert_reports_fact_numbers_missing():
    facts = clean_facts(GOOD_FACTS) + [F("check", "지급일 매월 20일", "S4")]
    r = convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), facts=facts)
    assert not r["passed"]
    assert any("지급일 매월 20일" in p for p in r["checks"]["level_problems"])


def test_derive_checklist_sections_and_doc_labels():
    policy = {**POLICY, "required_docs": [{"name": "신청서 1부", "condition": None}, "신분증", "위임장", "대리인 신분증"]}
    # 원문 단위: S8 신청서, S9 신분증, S10 위임장, S11 대리인 신분증
    facts = clean_facts(GOOD_FACTS) + [
        F("apply", "온라인 신청 가능", "S7"), F("docs", "위임장·대리인 신분증 (대리 신청 시)", "S10", "S11")]
    d = derive(policy, facts)
    assert d["card"] == card(facts)
    target = d["checklist"]["target_check"]
    assert [s["item"] for s in target] == ["target", "exclusion"]
    assert target[0]["items"] == ["아동복지시설 보호종료 후 5년 이내", "보호 2년 이상"]  # key 와 상관없이 전부
    assert d["checklist"]["apply"][0]["items"][-1] == "온라인 신청 가능"
    # 서류 하나만 인용한 구절만 짧은 이름으로 (여러 서류를 묶은 구절은 쓰지 않음)
    assert d["doc_labels"] == {"신청서 1부": "신청서 1부", "신분증": "신분증"}


def test_rebuild_keeps_facts_and_verified(tmp_path):
    r = extract(POLICY, FakeLLM(GOOD_FACTS))
    path = save(r, "fake", tmp_path)
    data = json.loads(path.read_text(encoding="utf-8"))
    del data["checklist"]
    path.write_text(json.dumps({**data, "verified": True}, ensure_ascii=False), encoding="utf-8")
    assert rebuild(POLICY, tmp_path) == path
    again = load("T001", tmp_path)
    assert again["checklist"] and again["verified"] is True and again["facts"] == r["facts"]
    assert rebuild({**POLICY, "policy_key": "NONE"}, tmp_path) is None
