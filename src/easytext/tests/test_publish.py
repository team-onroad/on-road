"""자동 검수 통과분을 노출 칸에 반영 (easytext.publish). 실제 P001 결과 파일 복사본을 자동 수집 정책처럼 쓴다."""

import json
import shutil

import pytest

from easytext import publish, review


@pytest.fixture
def setup(tmp_path):
    easy, facts = tmp_path / "easy", tmp_path / "facts"
    easy.mkdir(), facts.mkdir()
    p001 = next(p for p in json.loads(review.POLICIES_PATH.read_text(encoding="utf-8-sig")) if p["policy_key"] == "P001")
    auto = {**p001, "policy_key": "G900", "source_type": "api", "easy_text": None, "easy_text_verified": False}
    data = json.loads((review.EASY_DIR / "P001.json").read_text(encoding="utf-8"))
    l6 = {**data["levels"]["L6"], "passed": True}
    (easy / "G900.json").write_text(json.dumps({"levels": {"L6": l6}}, ensure_ascii=False), encoding="utf-8")
    shutil.copy(review.FACTS_DIR / "P001.json", facts / "G900.json")
    return easy, facts, auto, p001, l6


def test_passed_is_shown_manual_untouched(setup):
    easy, facts, auto, p001, l6 = setup
    manual = {**p001, "easy_text": "사람이 쓴 글", "easy_text_verified": False}
    out, res = publish.apply([manual, auto], easy, facts)
    assert out[0] == manual  # 사람이 쓴 정책은 그대로
    assert out[1]["easy_text_verified"] is True and out[1]["easy_text"] == l6["text"]
    assert res.shown == ["G900"] and res.levels == {"G900": "L6"}
    out2, res2 = publish.apply(out, easy, facts)  # 다시 돌려도 그대로
    assert out2 == out and res2.unchanged == ["G900"]


def test_failed_or_changed_source_is_hidden(setup):
    easy, facts, auto, _, l6 = setup
    shown = {**auto, "easy_text": l6["text"], "easy_text_verified": True}
    saved = json.loads((facts / "G900.json").read_text(encoding="utf-8"))
    (facts / "G900.json").write_text(json.dumps({**saved, "source_hash": "다른원문"}, ensure_ascii=False), encoding="utf-8")
    out, res = publish.apply([shown], easy, facts)
    assert out[0]["easy_text_verified"] is False and out[0]["easy_text"] == l6["text"]  # 글은 참고용으로 남김
    assert res.hidden == ["G900"]


def test_nearest_level_when_default_missing():
    assert publish.pick({"L7": "a"}) == "L7"
    assert publish.pick({"L5": "a", "L7": "b"}) == "L5"  # 같은 거리면 쉬운 쪽
    assert publish.pick({}) is None
