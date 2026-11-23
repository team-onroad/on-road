"""여러 정책 일괄 변환 (easytext.batch). 실제 LLM 대신 가짜 추출 · 변환으로 건너뛰기 규칙과 동시 처리를 본다."""

import json
import threading
import time

import pytest

from easytext import batch
from easytext import facts as facts_mod


def _policy(key: str, text: str = "매월 50만 원 지급") -> dict:
    return {"policy_key": key, "name": f"정책 {key}", "agency": "기관", "target_description": "보호종료 5년 이내",
            "support_content": text, "apply_method": "방문 신청", "required_docs": [], "source_type": "api"}


@pytest.fixture
def dirs(tmp_path):
    return tmp_path / "easy", tmp_path / "facts"


@pytest.fixture
def fake_llm(monkeypatch):
    """가짜 사실 추출 · 변환. 부른 정책을 기록하고, 동시에 도는 수를 센다"""
    calls = {"facts": [], "levels": [], "running": 0, "max_running": 0}
    lock = threading.Lock()

    def extract(policy, generate, judge):
        with lock:
            calls["facts"].append(policy["policy_key"])
        return {"policy_key": policy["policy_key"], "name": policy["name"], "facts": [], "passed": True,
                "source_hash": facts_mod.source_hash(policy)}

    def convert_level(policy, level, generate, judge, facts):
        with lock:
            calls["levels"].append((policy["policy_key"], level.code))
            calls["running"] += 1
            calls["max_running"] = max(calls["max_running"], calls["running"])
        time.sleep(0.05)
        with lock:
            calls["running"] -= 1
        if policy["policy_key"] == "BAD":
            raise RuntimeError("API 오류")
        return {"level": level.code, "passed": policy["policy_key"] != "FAIL", "text": "변환문"}

    monkeypatch.setattr(batch.facts_mod, "extract", extract)
    monkeypatch.setattr(batch, "convert_level", convert_level)
    return calls


def _run(policies, dirs, **kw):
    easy, facts = dirs
    return batch.run(policies, kw.pop("levels", ["L6"]), lambda m: {}, None, "fake", easy_dir=easy, facts_dir=facts, **kw)


def test_creates_then_skips_passed(dirs, fake_llm):
    ps = [_policy("G1"), _policy("G2")]
    todo, results = _run(ps, dirs)
    assert [p.policy["policy_key"] for p in todo] == ["G1", "G2"] and fake_llm["facts"] == ["G1", "G2"]
    assert json.loads((dirs[0] / "G1.json").read_text(encoding="utf-8"))["levels"]["L6"]["passed"] is True
    todo, results = _run(ps, dirs)  # 두 번째: 이미 통과 · 원문 그대로 → 할 일 없음 (비용 0)
    assert todo == [] and results == [] and len(fake_llm["facts"]) == 2


def test_changed_source_redoes_facts_and_levels(dirs, fake_llm):
    _run([_policy("G1")], dirs)
    todo, _ = _run([_policy("G1", "매월 60만 원 지급")], dirs)  # 수집기가 내용을 새로 씀
    assert todo[0].facts and todo[0].levels == ["L6"] and "원문 바뀜" in todo[0].reasons[0]


def test_failed_is_skipped_unless_retry(dirs, fake_llm):
    _run([_policy("FAIL")], dirs)
    assert _run([_policy("FAIL")], dirs)[0] == []  # 같은 원문으로 실패한 것은 또 돌리지 않음
    todo, _ = _run([_policy("FAIL")], dirs, retry_failed=True)
    assert todo[0].levels == ["L6"] and "실패 다시" in todo[0].reasons[-1]


def test_new_level_only_converts_that_level(dirs, fake_llm):
    _run([_policy("G1")], dirs)
    todo, _ = _run([_policy("G1")], dirs, levels=["L6", "L5"])
    assert not todo[0].facts and todo[0].levels == ["L5"]
    saved = json.loads((dirs[0] / "G1.json").read_text(encoding="utf-8"))["levels"]
    assert set(saved) == {"L6", "L5"}  # 있던 단계는 남는다


def test_runs_concurrently_and_one_error_does_not_stop_others(dirs, fake_llm):
    ps = [_policy(k) for k in ("G1", "G2", "BAD", "G3", "G4", "G5")]
    _, results = _run(ps, dirs, workers=4)
    assert fake_llm["max_running"] > 1  # 동시에 돎
    by_key = {r["policy_key"]: r for r in results}
    assert by_key["BAD"]["error"].startswith("RuntimeError") and not (dirs[0] / "BAD.json").exists()
    assert all(by_key[k]["levels"] == {"L6": True} for k in ("G1", "G2", "G3", "G4", "G5"))
    assert [r["policy_key"] for r in results] == ["G1", "G2", "BAD", "G3", "G4", "G5"]  # 순서 그대로


def test_dry_run_and_limit_do_not_call_llm(dirs, fake_llm, tmp_path, monkeypatch, capsys):
    path = tmp_path / "policies.json"
    path.write_text(json.dumps([_policy("G1"), _policy("G2"), {**_policy("P001"), "source_type": "manual"}],
                               ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(batch, "POLICIES_PATH", path)
    monkeypatch.setattr(batch, "EASY_DIR", dirs[0])
    monkeypatch.setattr(batch, "FACTS_DIR", dirs[1])
    assert [p["policy_key"] for p in batch.select(None, True, path)] == ["G1", "G2"]  # 사람이 쓴 정책은 빠짐
    todo, results = _run(batch.select(None, True, path), dirs, limit=1)
    assert len(todo) == 1
    todo, _ = batch.run(batch.select(None, True, path), ["L6"], None, None, "", easy_dir=dirs[0], facts_dir=dirs[1])
    won, minutes = batch.estimate(todo, 4)
    assert won == batch.EST_WON_FACTS + batch.EST_WON_LEVEL  # G2 만 남음 (G1 은 위에서 만듦)


def test_changed_source_redoes_all_saved_levels(dirs, fake_llm):
    """원문이 바뀌면 이번에 요청하지 않은 단계(L7)도 다시 — 안 하면 사실을 새로 저장한 뒤 옛 원문 그대로 남는다"""
    _run([_policy("G1")], dirs, levels=["L6", "L7"])
    todo, _ = _run([_policy("G1", "매월 60만 원 지급")], dirs, levels=["L6"])
    assert todo[0].levels == ["L6", "L7"]
    assert _run([_policy("G1", "매월 60만 원 지급")], dirs, levels=["L6"])[0] == []  # 다시 돌리면 할 일 없음
