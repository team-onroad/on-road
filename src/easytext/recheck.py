"""저장된 쉬운 말 변환 · 핵심 사실(카드 요약)을 지금 검사기로 다시 검사해 '통과' 표시를 고친다.

왜: 앱(백엔드 app/ai_content.py)은 저장할 때 붙인 passed 가 true 인 것만 보여 준다. 검사기를 고친 뒤에도
옛 검사기로 붙인 passed=false 가 그대로 남아, 멀쩡한 변환문이 앱에 안 나왔다 (11/22 수집 정책 10개 중 8개).
다시 변환하면 비용(시도 3~4번 + 검수)이 드니, 저장된 글은 그대로 두고 검사만 다시 한다.

- 자동 검사는 API 없이 다시 한다 (easytext.checks · facts.check_facts)
- 통과(passed)는 변환 때와 같이 '자동 검사 + 다른 모델의 2차 검수'를 모두 통과해야 한다.
  2차 검수를 아직 안 받은 것(옛 검사기에 걸려 못 받음)은 --judge 를 주면 그것만 한 번씩 받는다
- 글(변환문 · 사실)은 바꾸지 않는다. 사람 검수(verified)도 그대로 둔다
- 원문이 바뀐 사실 파일(source_hash 다름)은 건너뛴다 (다시 뽑아야 함)

실행 (src 폴더에서, 백엔드 가상환경):
    backend\\.venv\\Scripts\\python -m easytext.recheck                 # 모든 파일, API 없이 (2차 검수 필요한 것은 알려만 줌)
    backend\\.venv\\Scripts\\python -m easytext.recheck G084 G018 --judge  # 2차 검수 안 받은 것만 받음
    backend\\.venv\\Scripts\\python -m easytext.recheck --dry-run         # 파일은 그대로, 결과만
"""

import argparse
import json
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from easytext.checks import check
from easytext.convert import Generate, _blocking, _judge, _openai_generate, source_text
from easytext.facts import _keyword_judge, check_facts, facts_text, source_hash, source_units
from easytext.levels import get_level
from rag.settings import PROJECT_ROOT

EASY_DIR = PROJECT_ROOT / "data" / "easy_text"
FACTS_DIR = PROJECT_ROOT / "data" / "policy_facts"
POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
DUMMY_PATH = PROJECT_ROOT / "src" / "backend" / "db" / "dummy_policies.json"


def _load(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else None


def carry_unit_checks(r, saved: dict):
    """원문 단위 인용 검사(missing_units · unit_gaps)는 변환 초안의 인용 번호(src)가 있어야 해서 다시 할 수 없다
    (초안은 저장하지 않음). 변환 때 결과를 그대로 이어받는다 — 11/23 전에는 빈 값으로 덮어써 그 지적이 사라졌다"""
    old = saved.get("checks") or {}
    r.missing_units = list(old.get("missing_units") or [])
    r.unit_gaps = list(old.get("unit_gaps") or [])
    return r


def _judged(auto: bool, issues: list | None) -> bool:
    return auto and issues is not None and not _blocking(issues)


def recheck_level(policy: dict, code: str, saved: dict, judge: Generate | None) -> tuple[dict, str]:
    """(고친 단계 dict, 상태). 상태: 통과 · 실패 · 2차 검수 필요"""
    level = get_level(code)
    src = source_text(policy)
    full = saved.get("text") or ""
    r = carry_unit_checks(check(policy, saved.get("core_text") or "", full, level, src), saved)
    issues = saved.get("judge_issues")
    if r.passed and issues is None and judge is not None:
        issues = _judge(judge, src, full, level)
    out = {**saved, "auto_checks_passed": r.passed, "passed": _judged(r.passed, issues),
           "checks": asdict(r), "judge_issues": issues if r.passed else saved.get("judge_issues")}
    state = "통과" if out["passed"] else ("2차 검수 필요" if r.passed and issues is None else "실패")
    return out, state


def recheck_facts(policy: dict, saved: dict, judge: Generate | None) -> tuple[dict, str]:
    if saved.get("source_hash") != source_hash(policy):
        return saved, "원문 바뀜 (다시 뽑기)"
    r = check_facts(saved.get("facts") or [], source_units(policy))
    issues = saved.get("judge_issues")
    if r.passed and issues is None and judge is not None:
        issues = _judge(_keyword_judge(judge), source_text(policy), facts_text(saved["facts"]), get_level("L7"))
    out = {**saved, "auto_checks_passed": r.passed, "passed": _judged(r.passed, issues),
           "checks": asdict(r), "judge_issues": issues if r.passed else saved.get("judge_issues")}
    state = "통과" if out["passed"] else ("2차 검수 필요" if r.passed and issues is None else "실패")
    return out, state


def run(keys: list[str] | None = None, judge: Generate | None = None, dry_run: bool = False,
        easy_dir: Path = EASY_DIR, facts_dir: Path = FACTS_DIR) -> list[tuple[str, str, str, str]]:
    """[(정책, 대상, 전 → 후, 상태)]"""
    policies = {p["policy_key"]: p for p in (_load(POLICIES_PATH) or []) + (_load(DUMMY_PATH) or [])}
    names = keys or sorted({p.stem for p in easy_dir.glob("*.json")} | {p.stem for p in facts_dir.glob("*.json")})
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    rows = []
    for key in names:
        policy = policies.get(key)
        if policy is None:
            continue
        easy_path = easy_dir / f"{key}.json"
        if (data := _load(easy_path)) is not None:
            changed = False
            for code, saved in (data.get("levels") or {}).items():
                new, state = recheck_level(policy, code, saved, judge)
                rows.append((key, f"쉬운 말 {code}", f"{bool(saved.get('passed'))} → {new['passed']}", state))
                if new != saved:
                    data["levels"][code] = {**new, "rechecked_at": stamp}
                    changed = True
            if changed and not dry_run:
                easy_path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        facts_path = facts_dir / f"{key}.json"
        if (saved := _load(facts_path)) is not None:
            new, state = recheck_facts(policy, saved, judge)
            rows.append((key, "카드 요약(사실)", f"{bool(saved.get('passed'))} → {new['passed']}", state))
            if new != saved and not dry_run:
                facts_path.write_text(json.dumps({**new, "rechecked_at": stamp}, ensure_ascii=False, indent=2),
                                      encoding="utf-8")
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="저장된 쉬운 말 · 사실을 지금 검사기로 다시 검사해 통과 표시 고치기")
    parser.add_argument("policy_keys", nargs="*", help="없으면 data/easy_text · data/policy_facts 의 모든 정책")
    parser.add_argument("--judge", action="store_true", help="2차 검수를 아직 안 받은 것만 다른 모델로 받기 (API 사용)")
    parser.add_argument("--dry-run", action="store_true", help="파일은 그대로, 결과만 출력")
    args = parser.parse_args(argv)
    judge = None
    if args.judge:
        from rag.settings import get_rag_settings

        judge = _openai_generate(get_rag_settings().easytext_judge_model)
    rows = run(args.policy_keys or None, judge, args.dry_run)
    for key, what, change, state in rows:
        print(f"{key} · {what}: {change} ({state})")
    passed = sum(s == "통과" for *_, s in rows)
    print(f"\n통과 {passed} / {len(rows)}" + (" (저장 안 함)" if args.dry_run else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
