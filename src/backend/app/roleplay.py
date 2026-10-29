"""롤플레잉 상황 데이터와 규칙 (api.md 3.16~3.19). 앱 FakeCoachApi 와 같은 규칙"""

import json
import random
from dataclasses import dataclass
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
    example: str | None = None  # 놓쳤을 때 "이렇게 말해 보세요"


class Better(BaseModel):
    model_config = ConfigDict(extra="forbid")

    before: Text
    after: Text


class Variant(BaseModel):
    """같은 상황의 다른 전개 (할 때마다 하나를 고른다)"""

    model_config = ConfigDict(extra="forbid")

    variant_id: Annotated[StrictStr, Field(min_length=1, max_length=40)]
    title: Text  # 앱에 보여 줄 이번 상황 (예: "집주인이 수리를 미룸")
    situation: str  # 상대 역할이 따를 전개 (LLM 에 줌). 빈 문자열이면 기본 상황
    lines: list[Text]  # 비어 있으면 상황의 기본 대사 흐름
    checkpoints: list[Checkpoint]  # 이 전개에서 더 확인할 것
    better: list["Better"]


class Surprise(BaseModel):
    """난이도별 돌발 상황: 보통은 1개, 어려움은 2개까지 대화 중간에 끼어든다"""

    model_config = ConfigDict(extra="forbid")

    surprise_id: Annotated[StrictStr, Field(min_length=1, max_length=40)]
    level: Literal["NORMAL", "HARD"]
    line: Text  # 상대가 할 말 (LLM 은 상황에 맞게 바꿔 말해도 됨)
    checkpoint: Checkpoint  # 돌발 상황에 잘 대처했는지


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
    variants: list[Variant] = []
    surprises: list[Surprise] = []


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


@dataclass(frozen=True)
class Plan:
    """이번 연습의 전개: 고른 전개 + 돌발 상황 (앱이 opening 에서 받아 reply·feedback 에 그대로 보낸다)"""

    variant: Variant | None
    surprises: tuple[Surprise, ...]


SURPRISE_COUNT = {"EASY": 0, "NORMAL": 1, "HARD": 2}


def pick_plan(s: Scenario, level: str, variant_id: str | None = None, rng: random.Random | None = None) -> Plan:
    """전개는 무작위(또는 variant_id), 돌발 상황은 난이도만큼. 어려움은 HARD 돌발을 먼저 고른다"""
    rng = rng or random.Random()
    variant = None
    if s.variants:
        variant = next((v for v in s.variants if v.variant_id == variant_id), None) or rng.choice(s.variants)
    count = SURPRISE_COUNT.get(level, 0)
    pool = [x for x in s.surprises if level == "HARD" or x.level == "NORMAL"]
    hard = [x for x in pool if x.level == "HARD"]
    rest = [x for x in pool if x.level != "HARD"]
    rng.shuffle(hard)
    rng.shuffle(rest)
    return Plan(variant, tuple((hard + rest)[:count]))


def plan_of(s: Scenario, variant_id: str | None, surprise_ids: list[str]) -> Plan:
    """앱이 보낸 id 로 전개를 되살린다. 모르는 id 는 무시"""
    variant = next((v for v in s.variants if v.variant_id == variant_id), None)
    by_id = {x.surprise_id: x for x in s.surprises}
    return Plan(variant, tuple(by_id[i] for i in surprise_ids if i in by_id))


def lines_for(s: Scenario, level: str, plan: Plan | None = None) -> list[str]:
    """대사 흐름: 전개의 대사(없으면 기본) + 실전 압박 질문 + 돌발 상황(두 번째 대사부터 사이사이)"""
    base = list(plan.variant.lines) if plan and plan.variant and plan.variant.lines else list(s.lines)
    if level == "HARD" and s.hard_lines:
        mixed = []
        for i, line in enumerate(base):
            mixed.append(line)
            if i < len(s.hard_lines):
                mixed.append(s.hard_lines[i])
        base = mixed
    if plan:
        for n, x in enumerate(plan.surprises):
            base.insert(min(1 + n * 2, len(base)), x.line)
    return base


@dataclass(frozen=True)
class FeedbackItem:
    label: str
    ok: bool
    kind: str  # basic | situation | surprise
    example: str | None


GRADES = ((85, "아주 잘했어요"), (60, "잘했어요"), (0, "한 번 더 해 봐요"))


def feedback(s: Scenario, user_turns: list[str], plan: Plan | None = None) -> tuple[list[str], list[str]]:
    items, _ = feedback_items(s, user_turns, plan)
    good = [i.label for i in items if i.ok]
    missed = [i.label for i in items if not i.ok]
    if len(user_turns) >= FINISHED_MIN_TURNS:
        good.append(FINISHED_GOOD)
    return good, missed


def feedback_items(s: Scenario, user_turns: list[str], plan: Plan | None = None) -> tuple[list[FeedbackItem], int]:
    """체크 항목별 결과와 점수 (0~100). 돌발 상황 대처는 2배로 친다. 끝까지 대화하면 10점 더"""
    said = " ".join(user_turns)
    checks: list[tuple[Checkpoint, str]] = [(c, "basic") for c in s.checkpoints]
    if plan and plan.variant:
        checks += [(c, "situation") for c in plan.variant.checkpoints]
    if plan:
        checks += [(x.checkpoint, "surprise") for x in plan.surprises]
    items = [FeedbackItem(c.label, any(k in said for k in c.keywords), kind, c.example) for c, kind in checks]
    weight = {"basic": 1, "situation": 1, "surprise": 2}
    total = sum(weight[i.kind] for i in items) or 1
    got = sum(weight[i.kind] for i in items if i.ok)
    score = round(90 * got / total) + (10 if len(user_turns) >= FINISHED_MIN_TURNS else 0)
    return items, min(score, 100)


def grade(score: int) -> str:
    return next(label for cut, label in GRADES if score >= cut)
