"""저장된 쉬운 말 · 사실을 지금 검사기로 다시 검사 (easytext.recheck). API 대신 가짜 2차 검수."""

import json
import shutil

from easytext import evaluate, recheck


def _copy_p001(tmp_path):
    easy, facts = tmp_path / "easy", tmp_path / "facts"
    easy.mkdir(), facts.mkdir()
    shutil.copy(evaluate.EASY_DIR / "P001.json", easy / "P001.json")
    shutil.copy(recheck.FACTS_DIR / "P001.json", facts / "P001.json")
    return easy, facts


def test_old_false_flag_is_fixed_after_judge(tmp_path):
    """옛 검사기로 passed=false 가 붙은 멀쩡한 변환문 → 자동 검사 통과 + 2차 검수(지적 없음)면 true (앱에 보임)"""
    easy, facts = _copy_p001(tmp_path)
    data = json.loads((easy / "P001.json").read_text(encoding="utf-8"))
    data["levels"] = {"L6": {**data["levels"]["L6"], "passed": False, "auto_checks_passed": False, "judge_issues": None}}
    (easy / "P001.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    text_before = data["levels"]["L6"]["text"]

    # 2차 검수 없이: 통과로 바꾸지 않고 '2차 검수 필요'
    rows = recheck.run(["P001"], judge=None, easy_dir=easy, facts_dir=facts)
    assert ("P001", "쉬운 말 L6", "False → False", "2차 검수 필요") in rows

    calls = []
    rows = recheck.run(["P001"], judge=lambda m: calls.append(m) or {"issues": []}, easy_dir=easy, facts_dir=facts)
    saved = json.loads((easy / "P001.json").read_text(encoding="utf-8"))["levels"]["L6"]
    assert saved["passed"] is True and saved["judge_issues"] == [] and len(calls) == 1
    assert saved["text"] == text_before and saved["verified"] == data["levels"]["L6"]["verified"]  # 글 · 사람 검수 그대로


def test_judge_finding_keeps_failed(tmp_path):
    easy, facts = _copy_p001(tmp_path)
    data = json.loads((easy / "P001.json").read_text(encoding="utf-8"))
    data["levels"] = {"L6": {**data["levels"]["L6"], "passed": False, "judge_issues": None}}
    (easy / "P001.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    finding = {"issues": [{"type": "changed", "severity": "high", "original": "월 50만 원", "converted": "월 5만 원",
                           "reason": "금액이 다르다"}]}
    recheck.run(["P001"], judge=lambda m: finding, easy_dir=easy, facts_dir=facts)
    assert json.loads((easy / "P001.json").read_text(encoding="utf-8"))["levels"]["L6"]["passed"] is False


def test_dry_run_and_changed_source_are_left_alone(tmp_path):
    easy, facts = _copy_p001(tmp_path)
    saved = json.loads((facts / "P001.json").read_text(encoding="utf-8"))
    (facts / "P001.json").write_text(json.dumps({**saved, "source_hash": "다른원문"}, ensure_ascii=False), encoding="utf-8")
    before = {p.name: p.read_bytes() for p in (easy / "P001.json", facts / "P001.json")}
    rows = recheck.run(["P001"], judge=None, dry_run=True, easy_dir=easy, facts_dir=facts)
    assert ("P001", "카드 요약(사실)", f"{bool(saved['passed'])} → {bool(saved['passed'])}", "원문 바뀜 (다시 뽑기)") in rows
    assert before == {p.name: p.read_bytes() for p in (easy / "P001.json", facts / "P001.json")}


def test_unit_check_results_are_kept(tmp_path):
    """원문 인용 검사(unit_gaps)는 다시 할 수 없어 변환 때 결과를 이어받는다 — 지우면 막혀야 할 것이 통과로 바뀜"""
    easy, facts = _copy_p001(tmp_path)
    data = json.loads((easy / "P001.json").read_text(encoding="utf-8"))
    l6 = data["levels"]["L6"]
    gap = "S2: 아동양육시설 — 원문: \"…\""
    data["levels"] = {"L6": {**l6, "passed": False, "judge_issues": None, "checks": {**(l6.get("checks") or {}), "unit_gaps": [gap]}}}
    (easy / "P001.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    calls = []
    rows = recheck.run(["P001"], judge=lambda m: calls.append(m) or {"issues": []}, easy_dir=easy, facts_dir=facts)
    saved = json.loads((easy / "P001.json").read_text(encoding="utf-8"))["levels"]["L6"]
    assert ("P001", "쉬운 말 L6", "False → False", "실패") in rows and not calls
    assert saved["passed"] is False and saved["checks"]["unit_gaps"] == [gap]
