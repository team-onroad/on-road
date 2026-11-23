"""사람 검수표 · 검수 표시 (easytext.review). 실제 P001 결과 파일 복사본으로 본다 (API 없음)."""

import json
import shutil

from easytext import review


def _setup(tmp_path):
    easy, facts = tmp_path / "easy", tmp_path / "facts"
    easy.mkdir(), facts.mkdir()
    shutil.copy(review.EASY_DIR / "P001.json", easy / "P001.json")
    shutil.copy(review.FACTS_DIR / "P001.json", facts / "P001.json")
    policies = {p["policy_key"]: p for p in json.loads(review.POLICIES_PATH.read_text(encoding="utf-8-sig"))}
    data = json.loads((easy / "P001.json").read_text(encoding="utf-8"))
    l6 = {**data["levels"]["L6"], "passed": True, "verified": False}
    # L5 는 2차 검수에 막힌 것으로
    l5 = {**l6, "level": "L5", "passed": False,
          "judge_issues": [{"severity": "high", "original": "월 50만 원", "converted": "월 5만 원", "reason": "금액이 다르다"}]}
    data["levels"] = {"L6": l6, "L5": l5}
    (easy / "P001.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return easy, facts, policies


def _level(easy, code):
    return json.loads((easy / "P001.json").read_text(encoding="utf-8"))["levels"][code]


def test_sheet_puts_blocked_first_with_reason_and_source(tmp_path):
    easy, facts, policies = _setup(tmp_path)
    items = review.collect(policies, ["P001"], easy, facts)
    by = {i.target: i for i in items}
    assert by["L6"].passed and not by["L5"].passed
    md = review.render(policies, items, "검수표")
    blocked, todo = md.split("## ② ")
    assert "P001" in blocked and "쉬운 말 L5" in blocked and "금액이 다르다" in blocked and "recheck P001 --judge" in blocked
    assert "`--approve P001`" in todo and "쉬운 말 L6" in todo
    assert "[지원 대상]" in md  # 원문(정형 필드)을 나란히 보여 줌


def test_approve_only_passed_and_revoke(tmp_path):
    easy, facts, policies = _setup(tmp_path)
    msgs = review.mark(["P001:L5"], policies, True, easy, facts)
    assert "표시 안 함" in msgs[0] and _level(easy, "L5")["verified"] is False  # 막힌 것에는 달지 않음
    review.mark(["P001"], policies, True, easy, facts)  # 정책 키만: 통과한 것 모두
    assert _level(easy, "L6")["verified"] is True and _level(easy, "L6")["verified_at"]
    assert _level(easy, "L5")["verified"] is False
    text_before = _level(easy, "L6")["text"]
    review.mark(["P001:L6"], policies, False, easy, facts)
    assert _level(easy, "L6")["verified"] is False and _level(easy, "L6")["text"] == text_before


def test_changed_source_is_not_approved(tmp_path):
    easy, facts, policies = _setup(tmp_path)
    saved = json.loads((facts / "P001.json").read_text(encoding="utf-8"))
    (facts / "P001.json").write_text(json.dumps({**saved, "source_hash": "다른원문"}, ensure_ascii=False), encoding="utf-8")
    msgs = review.mark(["P001:L6", "P001:card"], policies, True, easy, facts)
    assert all("원문이 바뀜" in m for m in msgs) and _level(easy, "L6")["verified"] is False
    assert "batch P001" in review.render(policies, review.collect(policies, ["P001"], easy, facts), "t")
