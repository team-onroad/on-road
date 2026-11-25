"""정책 데이터 갱신 한 번에 (collector.pipeline). 수집 · 변환은 가짜로 바꿔 흐름과 멈추는 장치만 본다 (API 없음)."""

import json
from datetime import date

import pytest

from collector import pipeline
from collector.merge import MergeResult
from easytext import batch

TODAY = date(2026, 11, 23)


@pytest.fixture
def policies(tmp_path):
    from collector.run import POLICIES_PATH

    path = tmp_path / "policies.json"
    path.write_bytes(POLICIES_PATH.read_bytes())  # 실제 정책 파일 복사본 (원본은 건드리지 않음)
    return path


def _ok_collect(*_, **__):
    return MergeResult(policies=[], added=["G999"], changed=[], unchanged=["G001"]), ""


def test_collect_failure_stops_and_keeps_files(policies):
    before = policies.read_bytes()

    def broken(*_, **__):
        raise RuntimeError("HTTP 429 트래픽 허용 횟수를 초과")

    steps = pipeline.run_pipeline(TODAY, collect=broken, policies_path=policies)
    assert [s.name for s in steps] == ["수집"] and not steps[0].ok and "RuntimeError" in steps[0].summary
    assert policies.read_bytes() == before  # 수집이 실패하면 아무것도 바꾸지 않는다


def test_full_run_order_and_report(policies, monkeypatch):
    monkeypatch.setattr(pipeline, "step_convert", lambda *a, **k: pipeline.Step("쉬운 말 변환", True, "할 일 없음"))
    steps = pipeline.run_pipeline(TODAY, collect=_ok_collect, policies_path=policies)
    assert [s.name for s in steps] == ["수집", "쉬운 말 변환", "노출 칸 반영", "시드 검사", "신청 시기"] and all(s.ok for s in steps)
    assert json.loads((policies.parent / "apply_schedule.json").read_text(encoding="utf-8"))  # 정책 파일 옆에 신청 시기
    text = pipeline.report(steps, TODAY, dry_run=False)
    assert "결과: 성공" in text and "새로 들어옴: G999" in text
    json.loads(policies.read_text(encoding="utf-8-sig"))  # 저장해도 올바른 정책 파일


def test_dry_run_writes_nothing(policies, monkeypatch):
    monkeypatch.setattr(pipeline, "step_convert", lambda *a, **k: pipeline.Step("쉬운 말 변환", True, "미리보기"))
    # 노출 칸을 일부러 비워 두면 publish 는 바꿀 것이 생긴다 → 미리보기에서는 그래도 파일 그대로
    items = json.loads(policies.read_text(encoding="utf-8-sig"))
    for p in items:
        if p.get("source_type") == "api":
            p["easy_text_verified"] = False
    policies.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
    before = policies.read_bytes()
    steps = pipeline.run_pipeline(TODAY, dry_run=True, collect=_ok_collect, policies_path=policies)
    assert "새로" in steps[2].summary and "(미리보기)" in steps[2].summary
    assert policies.read_bytes() == before
    assert not (policies.parent / "apply_schedule.json").exists()


def test_convert_respects_cost_cap(monkeypatch):
    todo = [batch.Plan({"policy_key": f"G{i}"}, True, ["L6"], ["사실: 없음"]) for i in range(10)]
    monkeypatch.setattr(batch, "select", lambda *a, **k: [p.policy for p in todo])
    monkeypatch.setattr(batch, "run", lambda *a, **k: (todo, []))
    calls = []

    def fake_convert(policies, workers, limit):
        calls.append(limit)
        return [{"policy_key": f"G{i}", "levels": {"L6": i % 2 == 0}, "error": None} for i in range(limit)]

    step = pipeline.step_convert(False, max_won=540, workers=4, convert=fake_convert)  # 정책 하나 약 180원 → 3개
    assert calls == [3] and "상한 540원이라 3개만" in step.summary and "나머지 7개는 다음 실행" in step.summary
    assert step.ok and "통과 2" in step.summary and "실패 1" in step.summary
    preview = pipeline.step_convert(True, max_won=540, workers=4, convert=fake_convert)
    assert calls == [3] and "(미리보기" in preview.summary  # 미리보기는 변환을 부르지 않는다


def test_convert_all_errors_stops_before_publish(policies, monkeypatch):
    todo = [batch.Plan({"policy_key": "G1"}, True, ["L6"], ["사실: 없음"])]
    monkeypatch.setattr(batch, "select", lambda *a, **k: [todo[0].policy])
    monkeypatch.setattr(batch, "run", lambda *a, **k: (todo, []))
    down = lambda *a: [{"policy_key": "G1", "levels": {}, "error": "APIConnectionError: 연결 안 됨"}]  # noqa: E731
    monkeypatch.setattr(pipeline, "_convert_now", down)
    steps = pipeline.run_pipeline(TODAY, collect=_ok_collect, policies_path=policies)
    assert [s.name for s in steps] == ["수집", "쉬운 말 변환"] and not steps[-1].ok


def test_facts_only_retry_is_counted_as_passed(monkeypatch):
    """카드 요약만 다시 뽑은 정책(쉬운 말 단계 없음)은 사실이 통과하면 통과 — 실패로 세던 것"""
    todo = [batch.Plan({"policy_key": "G1"}, True, [], ["사실: 실패 다시"])]
    monkeypatch.setattr(batch, "select", lambda *a, **k: [todo[0].policy])
    monkeypatch.setattr(batch, "run", lambda *a, **k: (todo, []))
    step = pipeline.step_convert(False, 3000, 4, convert=lambda *a: [
        {"policy_key": "G1", "facts": True, "levels": {}, "error": None}])
    assert "통과 1" in step.summary and "실패 0" in step.summary
    step = pipeline.step_convert(False, 3000, 4, convert=lambda *a: [
        {"policy_key": "G1", "facts": False, "levels": {}, "error": None}])
    assert "통과 0" in step.summary and "실패 1" in step.summary
