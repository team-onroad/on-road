"""롤플레잉 상황 데이터와 규칙 (api.md 3.16~3.19). 앱 FakeCoachApi 와 같은 규칙"""

import json
import random
import re
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
    fits: list[StrictStr] = []  # 어울리는 전개(variant_id). 비면 모든 전개 (예: 아이가 뛰는 집에 '공사 중' 돌발은 안 맞음)


class FollowUp(BaseModel):
    """연습 뒤 이어서 해 볼 것 (다른 기능으로 연결). when=missed 면 놓친 게 있을 때만,
    labels 를 주면 그 체크 항목 중 하나라도 놓쳤을 때만"""

    model_config = ConfigDict(extra="forbid")

    kind: Literal["search", "practice", "budget", "month", "forms", "chat"]
    label: Text
    query: str | None = None  # search·chat 검색어/질문
    scenario_id: str | None = None  # practice 상황 id
    when: Literal["always", "missed"] = "always"
    labels: list[str] = []


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
    follow_ups: list[FollowUp] = []


@lru_cache
def get_scenarios() -> dict[str, Scenario]:
    """파일 순서 유지. 형식 오류면 ValidationError (서버 시작 시 확인)"""
    items = TypeAdapter(list[Scenario]).validate_json(SCENARIOS_PATH.read_bytes())
    scenarios = {s.scenario_id: s for s in items}
    if len(scenarios) != len(items):
        raise ValueError("roleplay_scenarios.json scenario_id 중복")
    for s in items:
        known = {v.variant_id for v in s.variants}
        for x in s.surprises:
            if any(f not in known for f in x.fits):
                raise ValueError(f"roleplay_scenarios.json {s.scenario_id}/{x.surprise_id}: 없는 전개 {x.fits}")
        for f in s.follow_ups:
            if f.kind == "practice" and f.scenario_id not in scenarios:
                raise ValueError(f"{s.scenario_id}: 없는 연습 상황 {f.scenario_id}")
            if f.kind in ("search", "chat") and not f.query:
                raise ValueError(f"{s.scenario_id}: {f.kind} 에는 query 가 필요해요")
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
    vid = variant.variant_id if variant else None
    pool = [
        x for x in s.surprises
        if (level == "HARD" or x.level == "NORMAL") and (not x.fits or vid is None or vid in x.fits)
    ]
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
            # 대화가 조금 이어진 뒤에 (첫마디 바로 다음에 갑자기 끼어들지 않게)
            base.insert(min(2 + n * 2, len(base)), x.line)
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


# '묻기 · 물어보기 · 확인' 항목은 질문해도 챙긴 것. 그 밖(챙기기 · 준비 · 말하기 …)은 질문 · 되묻기로는 안 챙긴 것
_ASK_LABEL = re.compile(r"묻|물어|확인")
# 물음표 없이도 질문이 분명한 끝말만 ("있어요 · 돼요"는 대답에도 많이 써서 넣지 않는다)
_QUESTION_END = re.compile(r"(나요|까요|는지|던가요|어때요|인가요|뭐예요|뭔가요|뭐죠|언제죠|어디죠|얼마죠)$")
_QUESTION_WORD = re.compile(r"^(뭐|무엇|언제|어디|얼마|몇|왜|어떻게|누구|어느)")
ECHO_EXTRA = 4  # 되묻기로 보는 길이: 상대가 방금 한 낱말 + 이 글자 수 이하 ("임대차 계약서요")


def is_question(text: str) -> bool:
    """음성 인식 글(물음표가 없을 때가 많음)이 질문인지. 물음표 · 마침표가 있으면 그대로 따른다"""
    t = text.strip()
    if t.endswith("?"):
        return True
    if t.endswith((".", "!")):
        return False
    n = _norm(t)
    return bool(_QUESTION_END.search(n) or _QUESTION_WORD.search(n))


def _counts_for(c: Checkpoint, turn: str, partner_before: str) -> bool:
    """이 말로 항목을 챙겼다고 볼 수 있는지 (낱말 규칙). 묻기 항목은 다 되고,
    나머지는 질문이거나 상대가 방금 한 낱말을 짧게 되받은 말(되묻기)이면 안 된다 → AI 판정에 맡긴다"""
    if _ASK_LABEL.search(c.label):
        return True
    if is_question(turn):
        return False
    said, before = _norm(turn), _norm(partner_before)
    for k in c.keywords:
        parts = [_norm(p) for p in k.split("|") if p.strip()]
        if parts and all(p in said and p in before for p in parts) and len(said) <= sum(map(len, parts)) + ECHO_EXTRA:
            return False
    return True


def _said(c: Checkpoint, user_turns: list[str], partner_before: list[str] | None) -> bool:
    """챙길 수 있는 말만 이어 붙여 찾는다 (음성 인식이 한 말을 둘로 나눠도: "이번" + "주 안에요" → "이번 주")"""
    before = partner_before or [""] * len(user_turns)
    return said_any(_norm(" ".join(t for t, b in zip(user_turns, before) if _counts_for(c, t, b))), c.keywords)


def feedback_items(
    s: Scenario, user_turns: list[str], plan: Plan | None = None, judged: frozenset[str] = frozenset(),
    partner_before: list[str] | None = None,
) -> tuple[list[FeedbackItem], int]:
    """체크 항목별 결과와 점수 (0~100). 돌발 상황 대처는 2배로 친다. 끝까지 대화하면 10점 더.
    partner_before: 사용자 말마다 바로 앞의 상대 말 (되묻기 판단용)"""
    checks: list[tuple[Checkpoint, str]] = [(c, "basic") for c in s.checkpoints]
    if plan and plan.variant:
        checks += [(c, "situation") for c in plan.variant.checkpoints]
    if plan:
        checks += [(x.checkpoint, "surprise") for x in plan.surprises]
    # 음성 인식 글은 띄어쓰기가 들쭉날쭉해서 띄어쓰기를 빼고 비교한다 ("필요 없" ↔ "필요없어요")
    items = [
        FeedbackItem(c.label, c.label in judged or _said(c, user_turns, partner_before), kind, c.example)
        for c, kind in checks
    ]
    weight = {"basic": 1, "situation": 1, "surprise": 2}
    total = sum(weight[i.kind] for i in items) or 1
    got = sum(weight[i.kind] for i in items if i.ok)
    score = round(90 * got / total) + (10 if len(user_turns) >= FINISHED_MIN_TURNS else 0)
    return items, min(score, 100)


def _norm(text: str) -> str:
    """띄어쓰기 · 문장 부호를 빼고 비교 ("이번 주" = "이번주", "확정 일자" = "확정일자")"""
    return re.sub(r"[\s.,!?~…·'\"“”‘’]+", "", text).lower()


def said_any(said_norm: str, keywords: list[str]) -> bool:
    """낱말 중 하나라도 말에 들어 있으면 챙긴 것. 낱말 안의 '|'는 둘 다 있어야 함 (예: "집주인|계좌")"""
    for k in keywords:
        parts = [_norm(p) for p in k.split("|") if p.strip()]
        if parts and all(p in said_norm for p in parts):
            return True
    return False


def all_checks(s: Scenario, plan: Plan | None = None) -> list[Checkpoint]:
    checks = list(s.checkpoints) + (list(plan.variant.checkpoints) if plan and plan.variant else [])
    return checks + [x.checkpoint for x in (plan.surprises if plan else ())]


def unmet_checks(s: Scenario, user_turns: list[str], plan: Plan | None = None) -> list[Checkpoint]:
    """낱말 규칙으로 아직 못 챙긴 항목"""
    return [c for c in all_checks(s, plan) if not _said(c, user_turns, None)]


def grade(score: int) -> str:
    return next(label for cut, label in GRADES if score >= cut)


NEXT_STEPS_MAX = 3


def my_better(better: list[Better], user_turns: list[str]) -> list[tuple[str, str]]:
    """'이렇게 말해 보면 더 좋아요'는 내가 실제로 비슷하게 말한 것만, 내 말 그대로 보여 준다 (낱말 절반 이상 겹침)"""
    out: list[tuple[str, str]] = []
    for b in better:
        want = set(_WORD.findall(b.before))
        if not want:
            continue
        for t in user_turns:
            if len(want & set(_WORD.findall(t))) / len(want) >= 0.5 or re.sub(r"\s+", "", b.before) in re.sub(r"\s+", "", t):
                out.append((t.strip(), b.after))
                break
    return out


def next_steps(s: Scenario, items: list[FeedbackItem]) -> list[FollowUp]:
    """놓친 것과 이어지는 것 먼저, 그다음 늘 권하는 것 (최대 3개)"""
    missed = {i.label for i in items if not i.ok}
    hit = [f for f in s.follow_ups if f.when == "missed" and missed and (not f.labels or missed & set(f.labels))]
    always = [f for f in s.follow_ups if f.when == "always"]
    return (hit + always)[:NEXT_STEPS_MAX]


# ------------------------------------------------------------------ 대화 중 힌트 ("뭐라고 하지?")

CLOSING_LABEL = "마무리 인사하기"
CLOSING_EXAMPLE = "감사합니다. 말씀하신 대로 준비해서 다시 올게요."
_WORD = re.compile(r"[가-힣A-Za-z0-9]{2,}")


@dataclass(frozen=True)
class Hint:
    kind: str  # basic | situation | surprise | closing
    label: str
    example: str
    remaining: int


def _close_to(a: str, b: str) -> bool:
    """상대가 돌발 대사를 바꿔 말해도 알아보게: 두 글자 이상 낱말이 2개 이상 겹치면 같은 말로 본다"""
    return len(set(_WORD.findall(a)) & set(_WORD.findall(b))) >= 2


def progress(
    s: Scenario, user_turns: list[str], partner_turns: list[str], plan: Plan | None = None, judged: frozenset[str] = frozenset(),
    partner_before: list[str] | None = None,
) -> list[FeedbackItem]:
    """대화 중 보여 줄 '챙길 것' (기본 · 이번 전개). 돌발 상황 대처는 상대가 그 말을 꺼낸 뒤에만 보인다 (미리 알려 주지 않음)"""
    items, _ = feedback_items(s, user_turns, plan, judged, partner_before)
    shown = {
        x.checkpoint.label
        for x in (plan.surprises if plan else ())
        if any(_close_to(x.line, said) for said in partner_turns)
    }
    return [i for i in items if i.kind != "surprise" or i.ok or i.label in shown]


def hint(
    s: Scenario, user_turns: list[str], last_partner: str, plan: Plan | None = None, judged: frozenset[str] = frozenset(),
    partner_before: list[str] | None = None,
) -> Hint:
    """아직 안 챙긴 것 하나. 방금 돌발 상황이 나왔으면 그 대처부터, 그다음 이번 전개 → 기본 순서"""
    items, _ = feedback_items(s, user_turns, plan, judged, partner_before)
    todo = [i for i in items if not i.ok]
    if not todo:
        return Hint("closing", CLOSING_LABEL, CLOSING_EXAMPLE, 0)
    just_now = {x.checkpoint.label for x in (plan.surprises if plan else ()) if _close_to(x.line, last_partner)}
    order = (
        [i for i in todo if i.kind == "surprise" and i.label in just_now]
        + [i for i in todo if i.kind == "situation"]
        + [i for i in todo if i.kind == "basic"]
        + [i for i in todo if i.kind == "surprise"]
    )
    pick = order[0]
    return Hint(pick.kind, pick.label, pick.example or f"{pick.label}에 대해 말해 보세요.", len(todo))
