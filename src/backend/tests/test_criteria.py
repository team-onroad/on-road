"""시뮬레이션 기준표 로딩·검증 (api.md 5장). DB 불필요."""

import copy
import json

import pytest
from fastapi.testclient import TestClient

from app import criteria as criteria_module
from app.config import get_settings
from app.criteria import TEMP_CRITERIA_PATH, CriteriaError, load_criteria
from app.main import create_app

VALID = {
    "version": "2026-10-v1",
    "source_note": "근거",
    "items": {
        "housing": {"label": "주거비", "minimum": 400000, "policy_categories": ["housing"]},
        "food": {"label": "식비", "minimum": 250000, "policy_categories": ["living_cost"]},
        "transport": {"label": "교통비", "minimum": 60000, "policy_categories": ["living_cost"]},
        "telecom": {"label": "통신비", "minimum": 40000, "policy_categories": ["living_cost"]},
        "other": {"label": "기타", "minimum": 100000, "policy_categories": ["living_cost", "finance"]},
    },
}


def _write(tmp_path, data):
    path = tmp_path / "simulation-criteria.json"
    path.write_text(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return path


@pytest.mark.parametrize("content", [None, "", "  \n"])
def test_missing_or_empty_file_uses_temp_criteria(tmp_path, content):
    path = tmp_path / "simulation-criteria.json" if content is None else _write(tmp_path, content)
    criteria, source = load_criteria(path)
    assert source == TEMP_CRITERIA_PATH
    assert criteria.version.startswith("TEMP-")  # 임시 기준표임이 버전에 드러남
    assert {k: v.minimum for k, v in criteria.items.items()} == {
        "housing": 400000, "food": 250000, "transport": 60000, "telecom": 40000, "other": 100000,
    }


def test_data_team_file_is_used_when_present(tmp_path):
    path = _write(tmp_path, VALID)
    criteria, source = load_criteria(path)
    assert source == path
    assert criteria.version == "2026-10-v1"


def test_item_order_is_fixed_regardless_of_file_order(tmp_path):
    data = copy.deepcopy(VALID)
    data["items"] = dict(reversed(list(data["items"].items())))
    criteria, _ = load_criteria(_write(tmp_path, data))
    assert list(criteria.items) == ["housing", "food", "transport", "telecom", "other"]


def _mutated(fn):
    data = copy.deepcopy(VALID)
    fn(data)
    return data


@pytest.mark.parametrize(
    "data",
    [
        _mutated(lambda d: d["items"].pop("telecom")),  # 항목 누락
        _mutated(lambda d: d["items"].update(savings={"label": "저축", "minimum": 0, "policy_categories": []})),
        _mutated(lambda d: d["items"]["food"].update(minimum=-1)),  # 음수
        _mutated(lambda d: d["items"]["food"].update(minimum="250000")),
        _mutated(lambda d: d["items"]["food"].update(minimum=250000.5)),
        _mutated(lambda d: d["items"]["food"].update(minimum=True)),
        _mutated(lambda d: d["items"]["food"].pop("minimum")),
        _mutated(lambda d: d["items"]["other"].update(policy_categories=["living_cost", "food"])),  # 잘못된 category
        _mutated(lambda d: d["items"]["other"].update(policy_categories="finance")),
        _mutated(lambda d: d["items"]["food"].update(label="식료품비")),  # 1.2 이름과 다름
        _mutated(lambda d: d.update(version="")),
        _mutated(lambda d: d.update(version="v" * 21)),  # 20자 초과
        _mutated(lambda d: d.pop("version")),
        _mutated(lambda d: d.pop("items")),
        _mutated(lambda d: d.update(items=[])),
        _mutated(lambda d: d.update(extra=1)),
        "{not json",
        "[]",
    ],
)
def test_invalid_criteria_raises(tmp_path, data):
    with pytest.raises(CriteriaError):
        load_criteria(_write(tmp_path, data))


def test_server_fails_to_start_with_invalid_criteria(tmp_path, monkeypatch):
    bad = _mutated(lambda d: d["items"]["food"].update(minimum=-1))
    monkeypatch.setenv("SIMULATION_CRITERIA_PATH", str(_write(tmp_path, bad)))
    get_settings.cache_clear()
    criteria_module.get_criteria.cache_clear()
    try:
        with pytest.raises(CriteriaError):
            with TestClient(create_app()):
                pass
    finally:
        monkeypatch.undo()
        get_settings.cache_clear()
        criteria_module.get_criteria.cache_clear()
