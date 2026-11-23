"""쉬운 말 변환 정량 평가 (README: "핵심조건(대상/금액/기간/신청방법) 누락 없이 검증").

data/easy_text/{policy_key}.json 의 단계별 변환문을 변환 때와 같은 자동 검사(easytext.checks)로 다시 잰다. API 를 쓰지 않는다.
- 핵심 조건 포함률: 원문 정형 필드에서 뽑은 사실(금액 · 기간 · 나이 · 신청 방법 · 서류 …) 중 변환문에 있는 비율
- 정확성: 원문에 없는 숫자 · 자격 단정 · 조건이 빠져 범위가 넓어진 서류 (0 이어야 함)
- 쉬움: 어려운 용어 감소율(단계 묶음별 · 합계 기준) · 밀도(1,000자당), 문장 길이(어절)가 단계 기준 안인지
- 자동 재변환 효과: 첫 변환에서 빠졌던 사실이 자동 검사 → 재변환으로 채워졌는지, 평균 시도 횟수
- 사람 검수 진행: verified 된 단계 수

실행 (src 폴더에서, 백엔드 가상환경):
    backend\\.venv\\Scripts\\python -m easytext.evaluate
결과: data/eval_results/easy_text_eval_{날짜}.json · .md
"""

import argparse
import json
import statistics
from datetime import date
from pathlib import Path

from easytext.checks import check, core_facts, metrics
from easytext.convert import source_text
from easytext.levels import TOLERANCE, get_level
from rag.settings import PROJECT_ROOT

EASY_DIR = PROJECT_ROOT / "data" / "easy_text"
POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
DUMMY_PATH = PROJECT_ROOT / "src" / "backend" / "db" / "dummy_policies.json"
RESULTS_DIR = PROJECT_ROOT / "data" / "eval_results"


def _load(path: Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else []


def evaluate_level(policy: dict, code: str, saved: dict) -> dict:
    level = get_level(code)
    src = source_text(policy)
    core, full = saved.get("core_text") or "", saved.get("text") or ""
    r = check(policy, core, full, level, src)
    total = len(core_facts(policy))
    m = metrics(src, core)
    first_missing = saved.get("first_attempt_missing")
    if isinstance(first_missing, str):  # 예전 파일은 문자열로 저장됨
        first_missing = [x.strip(" '\"") for x in first_missing.strip("[]").split(",") if x.strip(" '\"")]
    return {
        "level": code,
        "facts_total": total,
        "facts_missing": r.missing_facts,
        "fact_coverage": round(1 - len(r.missing_facts) / total, 3) if total else None,
        "unsupported_numbers": r.unsupported_numbers,
        "assertion": r.assertion,
        "condition_gaps": r.condition_gaps,
        "style_violations": len(r.style_violations),
        "avg_eojeol": r.avg_eojeol,
        "avg_eojeol_limit": round(level.avg_eojeol * TOLERANCE, 1),
        "level_problems": r.level_problems,
        "passed": r.passed,
        "term_reduction": m["term_reduction"],
        "hard_terms_source": m["hard_terms_source"],
        "hard_terms_converted": m["hard_terms_converted"],
        "term_density_source": m["term_density_source"],
        "term_density_converted": m["term_density_converted"],
        "t4_terms_source": m["t4_terms_source"],
        "t4_terms_converted": m["t4_terms_converted"],
        # L6 · L7 은 원래 용어를 그대로 쓰고 앱이 사전 풀이를 연결한다 (levels.py) → 용어를 바꾸는 단계와 따로 센다
        "keeps_terms": level.plain_tier >= 4,
        "first_attempt_missing": len(first_missing or []),
        "attempts": int(saved.get("attempts") or 0) if str(saved.get("attempts") or "").isdigit() else None,
        "verified": str(saved.get("verified")).lower() == "true",
    }


def _mean(xs: list) -> float | None:
    xs = [x for x in xs if x is not None]
    return round(statistics.mean(xs), 3) if xs else None


MIN_TERMS_FOR_MEAN = 3  # 원문 용어가 1~2개면 하나만 남아도 0% · -100% 로 흔들려 정책별 평균에서 뺀다


def _reduction(rows: list[dict], src_key: str = "hard_terms_source", out_key: str = "hard_terms_converted") -> float | None:
    """합계 기준 감소율: 1 - 변환문 용어 합 / 원문 용어 합"""
    src = sum(r.get(src_key) or 0 for r in rows)
    return round(1 - sum(r.get(out_key) or 0 for r in rows) / src, 3) if src else None


def _term_summary(rows: list[dict]) -> dict:
    # levels.py: L1~L5 는 T4 를 쉬운 말로 바꾸고 T3 은 그대로(사전 풀이 연결), L6~L7 은 원래 용어 그대로
    change = [r for r in rows if not r.get("keeps_terms")]
    keep = [r for r in rows if r.get("keeps_terms")]
    return {
        "T4 용어(쉬운 말로 바꿀 말) 감소율 · L1~L5 (합계 기준)": _reduction(change, "t4_terms_source", "t4_terms_converted"),
        "어려운 용어(T3 이상) 감소율 · L1~L5 (합계 기준)": _reduction(change),
        "어려운 용어(T3 이상) 감소율 · 원래 용어 유지 단계 L6~L7 (합계 기준, 요약으로 줄어든 몫)": _reduction(keep),
        f"어려운 용어 감소율 (정책별 평균, 원문 용어 {MIN_TERMS_FOR_MEAN}개 이상)": _mean(
            [r["term_reduction"] for r in rows if (r.get("hard_terms_source") or 0) >= MIN_TERMS_FOR_MEAN]),
        "어려운 용어 밀도 · 원문 (1,000자당, 평균)": _mean([r.get("term_density_source") for r in rows]),
        "어려운 용어 밀도 · L1~L5 변환문 (1,000자당, 평균)": _mean([r.get("term_density_converted") for r in change]),
        "어려운 용어 밀도 · L6~L7 변환문 (1,000자당, 평균)": _mean([r.get("term_density_converted") for r in keep]),
    }


def summarize(rows: list[dict]) -> dict:
    n = len(rows)
    fixed = [r for r in rows if r["first_attempt_missing"]]
    return {
        "대상": {"정책 수": len({r["policy_key"] for r in rows}), "단계 수": n},
        "핵심 조건": {
            "핵심 조건 포함률 (평균)": _mean([r["fact_coverage"] for r in rows]),
            "핵심 조건이 하나도 안 빠진 단계 비율": round(sum(not r["facts_missing"] for r in rows) / n, 3) if n else None,
        },
        "정확성": {
            "원문에 없는 숫자가 있는 단계 수": sum(bool(r["unsupported_numbers"]) for r in rows),
            "자격 단정이 있는 단계 수": sum(r["assertion"] for r in rows),
            "조건이 빠진 서류가 있는 단계 수": sum(bool(r["condition_gaps"]) for r in rows),
        },
        "쉬움": {
            **_term_summary(rows),
            "문장 길이가 단계 기준 안인 비율": round(sum(r["avg_eojeol"] <= r["avg_eojeol_limit"] for r in rows) / n, 3) if n else None,
            "합니다체가 아닌 문장 수": sum(r["style_violations"] for r in rows),
        },
        "자동 재변환 효과": {
            "첫 변환에서 핵심 조건이 빠졌던 단계 수": len(fixed),
            "그중 지금은 다 채워진 단계 수": sum(not r["facts_missing"] for r in fixed),
            "평균 시도 횟수": _mean([r["attempts"] for r in rows]),
        },
        "검수": {
            "자동 검사 모두 통과한 단계 비율": round(sum(r["passed"] for r in rows) / n, 3) if n else None,
            "사람 검수(verified) 끝난 단계 수": sum(r["verified"] for r in rows),
        },
    }


def _fmt(name: str, value) -> str:
    if value is None:
        return "-"
    if isinstance(value, float) and ("율" in name or "비율" in name):
        return f"{value:.1%}"
    return str(value)


def to_markdown(summary: dict, rows: list[dict], today: date) -> str:
    lines = [f"# 쉬운 말 변환 평가 ({today.isoformat()})", "",
             "변환 때와 같은 자동 검사(`src/easytext/checks.py`)로 저장된 변환문을 다시 잰 결과입니다. API 를 쓰지 않습니다.", ""]
    for group, items in summary.items():
        lines += [f"## {group}", "", "| 항목 | 값 |", "|---|---|", *[f"| {k} | {_fmt(k, v)} |" for k, v in items.items()], ""]
    lines += ["## 단계별", "", "| 정책 | 단계 | 핵심 조건 | 빠진 것 | 용어 감소 | 평균 어절(기준) | 통과 | 검수 |", "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        cov = f"{r['facts_total'] - len(r['facts_missing'])}/{r['facts_total']}"
        lines.append(f"| {r['policy_key']} | {r['level']} | {cov} | {', '.join(r['facts_missing']) or '-'} | "
                     f"{_fmt('율', r['term_reduction'])} | {r['avg_eojeol']} ({r['avg_eojeol_limit']}) | "
                     f"{'O' if r['passed'] else 'X'} | {'O' if r['verified'] else '-'} |")
    return "\n".join(lines) + "\n"


def run(easy_dir: Path = EASY_DIR, out_dir: Path = RESULTS_DIR, today: date | None = None) -> tuple[dict, list[dict], Path]:
    today = today or date.today()
    policies = {p["policy_key"]: p for p in _load(POLICIES_PATH) + _load(DUMMY_PATH)}
    rows = []
    for path in sorted(easy_dir.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        key = data.get("policy_key") or path.stem
        if key not in policies:
            continue
        for code, saved in sorted((data.get("levels") or {}).items()):
            rows.append({"policy_key": key, **evaluate_level(policies[key], code, saved)})
    summary = summarize(rows)
    out_dir.mkdir(parents=True, exist_ok=True)
    base, n = out_dir / f"easy_text_eval_{today.strftime('%Y%m%d')}", 2
    while base.with_suffix(".json").exists():  # 같은 날 다시 돌려도 앞 결과를 덮지 않게
        base, n = out_dir / f"easy_text_eval_{today.strftime('%Y%m%d')}_{n}", n + 1
    base.with_suffix(".json").write_text(json.dumps({"date": today.isoformat(), "summary": summary, "levels": rows},
                                                    ensure_ascii=False, indent=2), encoding="utf-8")
    base.with_suffix(".md").write_text(to_markdown(summary, rows, today), encoding="utf-8")
    return summary, rows, base


def main(argv: list[str] | None = None) -> int:
    argparse.ArgumentParser(description="쉬운 말 변환 정량 평가 (API 없음)").parse_args(argv)
    summary, _, base = run()
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n저장: {base}.json · .md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
