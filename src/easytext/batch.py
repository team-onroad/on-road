"""여러 정책을 한꺼번에: 핵심 사실 추출(카드 요약) → 쉬운 말 변환. 몇 개씩 동시에 하고, 할 필요 없는 것은 건너뛴다.

하나씩 돌리면(facts → convert) 정책당 30~60초라 수집 정책 88개면 1시간쯤 걸리고, 이미 통과한 것도 다시 변환해 비용이 든다.

건너뛰는 기준 (정책마다 · 단계마다)
- 이미 통과했고 원문이 그대로 → 건너뜀
- 원문이 바뀜 (핵심 사실의 source_hash 가 다름 — 수집기가 내용을 새로 씀) → 사실 · 변환 모두 다시
- 같은 원문으로 이미 실패 → 기본은 건너뜀 (같은 것을 또 돌려도 대개 다시 실패하고 비용만 듦). --retry-failed 로 다시
- 없음 → 만듦
결과는 facts.save · convert.save 로 저장한다 (사람 검수 verified 를 지키는 규칙 그대로). 정책마다 파일이 따로라 동시에 해도 겹치지 않는다.

실행 (src 폴더에서, 백엔드 가상환경. OPENAI_API_KEY 필요):
    backend\\.venv\\Scripts\\python -m easytext.batch --collected --dry-run        # 할 일 · 예상 비용 · 시간만
    backend\\.venv\\Scripts\\python -m easytext.batch --collected --limit 20       # 수집 정책 앞에서 20개
    backend\\.venv\\Scripts\\python -m easytext.batch G084 G018 --levels L5 L6 --workers 4
`.env`: EASYTEXT_MODEL 은 gpt-4o 권장 (README: gpt-4o-mini 는 출처 메모를 옮기거나 문장을 자르는 일이 많음)
"""

import argparse
import json
import time
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

from easytext import facts as facts_mod
from easytext.convert import OUT_DIR as EASY_DIR
from easytext.convert import POLICIES_PATH, Generate, _openai_generate, convert_level
from easytext.convert import save as save_easy
from easytext.levels import DEFAULT_LEVEL, LEVELS, get_level

FACTS_DIR = facts_mod.OUT_DIR
WORKERS = 4
AUTO_SOURCES = ("api", "crawler")
# 예상 비용 · 시간 (11/22 수집 정책 10개 실측, gpt-4o · 2차 검수 켬): 사실 추출 약 80원 · 단계 하나 약 100원, 정책당 약 35초
EST_WON_FACTS, EST_WON_LEVEL, EST_SECONDS = 80, 100, 35


@dataclass
class Plan:
    policy: dict
    facts: bool  # 핵심 사실을 (다시) 뽑을지
    levels: list[str]  # (다시) 변환할 단계
    reasons: list[str] = field(default_factory=list)

    @property
    def empty(self) -> bool:
        return not self.facts and not self.levels


def _load(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else None


def make_plan(policy: dict, levels: list[str], easy_dir: Path = EASY_DIR, facts_dir: Path = FACTS_DIR,
              force: bool = False, retry_failed: bool = False) -> Plan:
    key = policy["policy_key"]
    old_facts = facts_mod.load(key, facts_dir)
    changed = bool(old_facts) and old_facts.get("source_hash") != facts_mod.source_hash(policy)
    plan = Plan(policy, False, [])
    if force or not old_facts or changed or (retry_failed and not old_facts.get("passed")):
        plan.facts = True
        plan.reasons.append("사실: " + ("없음" if not old_facts else "원문 바뀜" if changed else "다시" if force else "실패 다시"))
    saved_levels = ((_load(easy_dir / f"{key}.json") or {}).get("levels")) or {}
    # 원문이 바뀌면 요청하지 않은 단계도 다시 만든다: 사실을 새로 저장하면 그 단계는 원문이 바뀐 줄 모르게 된다
    # (변환 결과에는 source_hash 가 없고 사실 파일로 판단하므로. G018 의 L7 이 옛 원문 그대로 남던 것)
    for code in (list(dict.fromkeys(levels + list(saved_levels))) if changed else levels):
        old = saved_levels.get(code)
        if force or not old or changed or (retry_failed and not old.get("passed")):
            plan.levels.append(code)
            plan.reasons.append(f"{code}: " + ("없음" if not old else "원문 바뀜" if changed else "다시" if force else "실패 다시"))
    return plan


def run_plan(plan: Plan, generate: Generate, judge: Generate | None, model: str,
             easy_dir: Path = EASY_DIR, facts_dir: Path = FACTS_DIR) -> dict:
    """한 정책. 오류가 나도 다른 정책은 계속되게 결과에 담아 돌려준다"""
    key = plan.policy["policy_key"]
    out = {"policy_key": key, "name": plan.policy.get("name"), "facts": None, "levels": {}, "error": None}
    t = time.perf_counter()
    try:
        if plan.facts:
            r = facts_mod.extract(plan.policy, generate, judge)
            facts_mod.save(r, model, facts_dir)
            out["facts"] = r["passed"]
        facts_list = facts_mod.passed_facts(plan.policy, facts_dir)  # 통과한 사실이 있으면 변환 기준으로 씀
        results = [convert_level(plan.policy, get_level(c), generate, judge, facts_list) for c in plan.levels]
        if results:
            save_easy(plan.policy, results, model, easy_dir)
        out["levels"] = {r["level"]: r["passed"] for r in results}
    except Exception as e:  # 한 정책이 실패해도 일괄 작업은 계속
        out["error"] = f"{type(e).__name__}: {str(e)[:80]}"
    out["seconds"] = round(time.perf_counter() - t, 1)
    return out


def select(keys: list[str] | None, collected: bool, path: Path = POLICIES_PATH) -> list[dict]:
    items = json.loads(path.read_text(encoding="utf-8-sig"))
    if keys:
        missing = set(keys) - {p["policy_key"] for p in items}
        if missing:
            raise SystemExit(f"{path} 에 없는 정책: {', '.join(sorted(missing))}")
        return [p for p in items if p["policy_key"] in set(keys)]
    if collected:
        return [p for p in items if p.get("source_type") in AUTO_SOURCES]
    raise SystemExit("정책 키를 주거나 --collected 를 쓰세요.")


def run(policies: list[dict], levels: list[str], generate: Generate | None, judge: Generate | None, model: str,
        workers: int = WORKERS, force: bool = False, retry_failed: bool = False, limit: int | None = None,
        easy_dir: Path = EASY_DIR, facts_dir: Path = FACTS_DIR,
        progress: Callable[[dict], None] | None = None) -> tuple[list[Plan], list[dict]]:
    """(할 일 목록, 결과). generate 가 None 이면 할 일만 만든다 (미리보기)"""
    plans = [make_plan(p, levels, easy_dir, facts_dir, force, retry_failed) for p in policies]
    todo = [p for p in plans if not p.empty][: limit or None]
    if generate is None:
        return todo, []
    results = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        for r in pool.map(lambda pl: run_plan(pl, generate, judge, model, easy_dir, facts_dir), todo):
            results.append(r)
            if progress:
                progress(r)
    return todo, results


def estimate(todo: list[Plan], workers: int) -> tuple[int, int]:
    won = sum(EST_WON_FACTS * p.facts + EST_WON_LEVEL * len(p.levels) for p in todo)
    minutes = round(len(todo) * EST_SECONDS / max(1, workers) / 60)
    return won, minutes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="여러 정책 핵심 사실 추출 → 쉬운 말 변환 (동시에, 할 필요 없는 것은 건너뜀)")
    parser.add_argument("policy_keys", nargs="*")
    parser.add_argument("--collected", action="store_true", help="policies.json 의 자동 수집 정책 전부 (api · crawler)")
    parser.add_argument("--levels", nargs="+", default=[DEFAULT_LEVEL], choices=list(LEVELS))
    parser.add_argument("--workers", type=int, default=WORKERS, help=f"동시에 처리할 정책 수 (기본 {WORKERS})")
    parser.add_argument("--limit", type=int, help="할 일 중 앞에서 N개만")
    parser.add_argument("--no-judge", action="store_true", help="2차 검수 끄기 (싸지만 통과(passed) 표시가 안 붙어 앱에 안 보임)")
    parser.add_argument("--retry-failed", action="store_true", help="같은 원문으로 이미 실패한 것도 다시")
    parser.add_argument("--force", action="store_true", help="통과한 것도 모두 다시")
    parser.add_argument("--dry-run", action="store_true", help="할 일 · 예상 비용 · 시간만 보여 주고 끝")
    args = parser.parse_args(argv)

    policies = select(args.policy_keys, args.collected)
    todo, _ = run(policies, args.levels, None, None, "", args.workers, args.force, args.retry_failed, args.limit)
    won, minutes = estimate(todo, args.workers)
    print(f"정책 {len(policies)}개 중 할 일 {len(todo)}개 · 예상 약 {won:,}원 · 약 {minutes}분 (동시 {args.workers}개)")
    for p in todo[:30]:
        print(f"  {p.policy['policy_key']} {p.policy.get('name', '')[:24]} — {', '.join(p.reasons)}")
    if len(todo) > 30:
        print(f"  … 외 {len(todo) - 30}개")
    if args.dry_run or not todo:
        return 0

    from rag.settings import get_rag_settings

    s = get_rag_settings()
    generate = _openai_generate(s.easytext_model)
    judge = None if args.no_judge else _openai_generate(s.easytext_judge_model)
    done = []

    def progress(r: dict) -> None:
        done.append(r)
        lv = " ".join(f"{c} {'통과' if ok else '실패'}" for c, ok in r["levels"].items())
        fa = "" if r["facts"] is None else f"사실 {'통과' if r['facts'] else '실패'} · "
        print(f"[{len(done)}/{len(todo)}] {r['policy_key']} {fa}{lv}{' · 오류 ' + r['error'] if r['error'] else ''} ({r['seconds']}초)",
              flush=True)

    _, results = run(policies, args.levels, generate, judge, s.easytext_model, args.workers, args.force,
                     args.retry_failed, args.limit, progress=progress)
    ok = sum(1 for r in results if not r["error"] and r["levels"] and all(r["levels"].values()))
    print(f"\n끝: {len(results)}개 중 쉬운 말 모두 통과 {ok}개 · 오류 {sum(bool(r['error']) for r in results)}개")
    print("통과한 것은 앱이 쉬운 말로 보여 줄 수 있습니다 (사람 검수(verified) 전이면 '검토 중' 안내와 함께). "
          "정책 자체가 서비스에 나오려면 DB 의 easy_text_verified 가 true 여야 합니다 (docs/db-schema.md). "
          "실패한 것은 python -m easytext.review 검수표로 이유 확인")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
