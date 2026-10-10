"""롤플레잉 상황 데이터와 규칙 (api.md 3.16~3.19). 앱 FakeCoachApi 와 같은 규칙"""

import json
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictStr, TypeAdapter

from app.codes import ROLEPLAY_LEVELS, USER_STAGES
from app.errors import AppError

SCENARIOS_PATH = Path(__file__).resolve().parent / "roleplay_scenarios.json"
FINISHED_GOOD = "끝까지 대화를 이어 갔어요"
FINISHED_MIN_TURNS = 3

Level = Literal[ROLEPLAY_LEVELS]
Stage = Literal[USER_STAGES]
Text = Annotated[StrictStr, Field(min_length=1)]


class Checkpoint(BaseModel):
    model_config = ConfigDict(extra="forbid")

    keywords: Annotated[list[Text], Field(min_length=1)]
    label: Text


class Better(BaseModel):
    model_config = ConfigDict(extra="forbid")

    before: Text
    after: Text


class Scenario(BaseModel):
    model_config = ConfigDict(extra="forbid")

    scenario_id: Annotated[StrictStr, Field(min_length=1, max_length=50)]  # roleplay_results VARCHAR(50)
    title: Text
    category: Text
    partner: Text
    setting: Text
    goal: Text
    level: Level
    level_by_stage: dict[Stage, Level]
    stages: Annotated[list[Stage], Field(min_length=1)]
    opening: Text
    lines: list[Text]
    hard_lines: list[Text]
    checkpoints: list[Checkpoint]
    documents: list[Text]
    better: list[Better]


@lru_cache
def get_scenarios() -> dict[str, Scenario]:
    """파일 순서 유지. 형식 오류면 ValidationError (서버 시작 시 확인)"""
    items = TypeAdapter(list[Scenario]).validate_json(SCENARIOS_PATH.read_bytes())
    scenarios = {s.scenario_id: s for s in items}
    if len(scenarios) != len(items):
        raise ValueError("roleplay_scenarios.json scenario_id 중복")
    return scenarios


def scenario_not_found() -> AppError:
    return AppError(404, "SCENARIO_NOT_FOUND", "연습 상황을 찾을 수 없습니다.")


def get_scenario(scenario_id: str) -> Scenario:
    scenario = get_scenarios().get(scenario_id)
    if scenario is None:
        raise scenario_not_found()
    return scenario


def level_for(s: Scenario, stage: str | None) -> str:
    if stage == "child":
        return "EASY"
    return s.level_by_stage.get(stage, s.level) if stage else s.level


def lines_for(s: Scenario, level: str) -> list[str]:
    """실전은 압박 질문을 대사 사이사이에 섞음"""
    if level != "HARD" or not s.hard_lines:
        return list(s.lines)
    mixed = []
    for i, line in enumerate(s.lines):
        mixed.append(line)
        if i < len(s.hard_lines):
            mixed.append(s.hard_lines[i])
    return mixed


def feedback(s: Scenario, user_turns: list[str]) -> tuple[list[str], list[str]]:
    said = " ".join(user_turns)
    good, missed = [], []
    for c in s.checkpoints:
        (good if any(k in said for k in c.keywords) else missed).append(c.label)
    if len(user_turns) >= FINISHED_MIN_TURNS:
        good.append(FINISHED_GOOD)
    return good, missed
