"""사람 검수표 만들기 · 검수 표시(verified) 달기.

자동 검사 + 2차 검수(다른 모델)를 통과해도 사람이 원문과 대조하기 전(verified=false)이다. 지금까지는 JSON 파일을
하나씩 열어 원문(policies.json)과 맞춰 보고 "verified" 를 손으로 바꿔야 했다. 이 도구는
1. 검수표(Markdown)를 만든다 — 먼저 볼 것부터:
   ① 앱에 안 보이는 것(자동 검사 · 2차 검수에 막힘): 왜 막혔는지, 고치는 방법
   ② 통과했지만 사람 검수 전: 2차 검수의 가벼운(low) 지적이 있는 것 먼저. 원문 · 변환문을 나란히
   지금 검사기로 다시 검사해 쓴다 (recheck 와 같은 기준, API 없음)
2. 확인한 것에 한 줄로 verified 를 단다. 통과하지 않았거나 원문이 바뀐 것은 달지 않는다
   (앱은 통과한 것만 보여 주므로, 막힌 것은 변환문을 고친 뒤 recheck 로 다시 검사부터)

실행 (src 폴더에서, 백엔드 가상환경):
    backend\\.venv\\Scripts\\python -m easytext.review --collected              # 수집 정책 검수표 → data/eval_results/
    backend\\.venv\\Scripts\\python -m easytext.review --approve G020 G018:L7 G095:card   # 확인한 것 표시
    backend\\.venv\\Scripts\\python -m easytext.review --revoke G020:L6                    # 표시 취소
대상: G020 = 그 정책의 통과한 단계 모두 + 카드 요약, G020:L6 = 쉬운 말 그 단계만, G020:card = 카드 요약만
DB 의 policies.easy_text_verified (노출 여부)는 따로다 — 이 표시는 data/ 파일의 verified 만 바꾼다.
"""

import argparse
import json
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from pathlib import Path

from easytext.checks import check
from easytext.convert import _blocking, source_text
from easytext.facts import check_facts, facts_text, source_hash, source_units
from easytext.levels import LEVELS, get_level
from easytext.recheck import carry_unit_checks
from rag.settings import PROJECT_ROOT

EASY_DIR = PROJECT_ROOT / "data" / "easy_text"
FACTS_DIR = PROJECT_ROOT / "data" / "policy_facts"
POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
OUT_DIR = PROJECT_ROOT / "data" / "eval_results"
AUTO_SOURCES = ("api", "crawler")
CARD = "card"


def _load(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else None


@dataclass
class Item:
    """검수할 것 하나: 쉬운 말 한 단계 또는 카드 요약"""
    policy_key: str
    target: str  # "L6" · "L7" … 또는 "card"
    passed: bool
    verified: bool
    stale: bool  # 원문이 바뀌어 다시 만들어야 함
    problems: list[str] = field(default_factory=list)  # 자동 검사 지적
    judge: list[dict] = field(default_factory=list)  # 2차 검수 지적
    text: str = ""
    # 재검사(recheck)를 거쳐 변환 때의 원문 인용 검사 결과가 남아 있지 않음 (11/23 전 recheck 가 지웠음) → 꼼꼼히 대조
    no_unit_record: bool = False

    @property
    def label(self) -> str:
        return "카드 요약" if self.target == CARD else f"쉬운 말 {self.target}"

    @property
    def spec(self) -> str:
        return f"{self.policy_key}:{self.target}"


def collect(policies: dict[str, dict], keys: list[str], easy_dir: Path = EASY_DIR,
            facts_dir: Path = FACTS_DIR) -> list[Item]:
    out = []
    for key in keys:
        policy = policies[key]
        facts = _load(facts_dir / f"{key}.json")
        stale = bool(facts) and facts.get("source_hash") != source_hash(policy)
        src = source_text(policy)
        for code, saved in (((_load(easy_dir / f"{key}.json") or {}).get("levels")) or {}).items():
            r = carry_unit_checks(check(policy, saved.get("core_text") or "", saved.get("text") or "",
                                        get_level(code), src), saved)
            out.append(Item(key, code, bool(saved.get("passed")) and r.passed and not stale,
                            bool(saved.get("verified")), stale, r.feedback(), saved.get("judge_issues") or [],
                            saved.get("text") or "", bool(saved.get("rechecked_at"))))
        if facts:
            r = check_facts(facts.get("facts") or [], source_units(policy))
            out.append(Item(key, CARD, bool(facts.get("passed")) and r.passed and not stale,
                            bool(facts.get("verified")), stale, r.feedback(), facts.get("judge_issues") or [],
                            facts_text(facts.get("facts") or [])))
    return out


def _judge_lines(issues: list[dict]) -> list[str]:
    lines = []
    for i in issues:
        sev = str(i.get("severity") or "high").lower()
        quote = ""
        if i.get("original") or i.get("converted"):
            quote = f' (원문 "{(i.get("original") or "")[:80]}" → 변환 "{(i.get("converted") or "")[:80]}")'
        lines.append(f"[2차 검수 · {sev}] {i.get('reason', '')}{quote}")
    return lines


def _fence(text: str) -> str:
    return "```text\n" + text.replace("```", "'''").strip() + "\n```"


def _details(policy: dict, items: list[Item]) -> str:
    """원문은 정책마다 한 번만 (쉬운 말 · 카드 요약을 함께 보여 줘도)"""
    body = "".join(f"\n\n**{i.label}**\n\n{_fence(i.text)}" for i in items)
    labels = " · ".join(i.label for i in items)
    return (f"<details><summary>원문 · {labels}</summary>\n\n**원문 (policies.json 정형 필드)**\n\n"
            f"{_fence(source_text(policy))}{body}\n\n</details>")


def _how_to_fix(item: Item) -> str:
    if item.stale:
        return f"원문이 바뀜 → `python -m easytext.batch {item.policy_key}` 로 다시 만들기"
    path = (f"data/policy_facts/{item.policy_key}.json 의 facts" if item.target == CARD
            else f"data/easy_text/{item.policy_key}.json 의 levels.{item.target}.text · core_text")
    return (f"① {path} 를 원문에 맞게 고친 뒤 `python -m easytext.recheck {item.policy_key} --judge` "
            f"② 고치기 어려우면 그대로 둠 (앱은 이 {item.label} 없이 원문 안내만 보여 줌)")


def _by_policy(items: list[Item]) -> dict[str, list[Item]]:
    out: dict[str, list[Item]] = {}
    for i in items:
        out.setdefault(i.policy_key, []).append(i)
    return out


def render(policies: dict[str, dict], items: list[Item], title: str) -> str:
    blocked = [i for i in items if not i.passed]
    todo = [i for i in items if i.passed and not i.verified]
    done = [i for i in items if i.passed and i.verified]
    name = lambda k: policies[k].get("name", "")  # noqa: E731
    lines = [
        f"# {title}", "",
        f"만든 날: {date.today().isoformat()} · `python -m easytext.review` (지금 검사기로 다시 검사한 결과)", "",
        "| 구분 | 수 |", "|---|---|",
        f"| ① 앱에 안 보임 (자동 검사 · 2차 검수에 막힘) | {len(blocked)} |",
        f"| ② 통과 · 사람 검수 전 | {len(todo)} |",
        f"| 사람 검수 끝 (verified) | {len(done)} |", "",
        "확인한 것은 `python -m easytext.review --approve G020 G018:L7 G095:card` 로 표시합니다 "
        "(정책 키만 쓰면 그 정책의 통과한 것 모두). 틀린 곳이 있으면 표시하지 말고 ① 처럼 고쳐 주세요.", "",
        f"## ① 앱에 안 보이는 것 ({len(blocked)})", "",
    ]
    for key, group in _by_policy(blocked).items():
        lines += [f"### {key} {name(key)}", ""]
        for i in group:
            reasons = (["원문이 바뀜 (다시 만들어야 함)"] if i.stale else []) + _judge_lines(_blocking(i.judge)) + [
                p.strip() for p in i.problems]
            lines += [f"**{i.label}**", ""]
            lines += [f"- {r}" for r in reasons or ["(지적 없음: 2차 검수를 아직 안 받음 → recheck --judge)"]]
            lines += ["", f"고치는 법: {_how_to_fix(i)}", ""]
        lines += [_details(policies[key], group), ""]

    groups = _by_policy(todo)
    # 2차 검수의 가벼운 지적 · 인용 검사 기록 없음이 있는 정책부터
    order = sorted(groups, key=lambda k: (not any(i.judge for i in groups[k]),
                                          not any(i.no_unit_record for i in groups[k]), k))
    lines += [f"## ② 통과 · 사람 검수 전 ({len(todo)}, 정책 {len(groups)}개)", "",
              "원문과 대조해 조건 · 숫자 · 대상이 같은지 봅니다. 주의할 것이 있는 정책이 위에 있습니다.",
              "'인용 기록 없음' 은 재검사를 거쳐 변환 때의 원문 인용 검사 결과가 남아 있지 않은 것 — 빠진 조건이 없는지 꼼꼼히.", "",
              "| 정책 | 이름 | 볼 것 | 주의 | 표시 명령 |", "|---|---|---|---|---|"]
    for key in order:
        g = groups[key]
        notes = [x.get("reason", "")[:50] for i in g for x in i.judge]
        notes += [f"{i.label} 인용 기록 없음" for i in g if i.no_unit_record]
        note = " / ".join(notes).replace("|", "/") or "-"
        lines.append(f"| {key} | {name(key)[:24]} | {' · '.join(i.label for i in g)} | {note} | `--approve {key}` |")
    lines.append("")
    for key in order:
        lines += [f"### {key} {name(key)}", "", _details(policies[key], groups[key]), ""]
    return "\n".join(lines)


def _parse(spec: str) -> tuple[str, str | None]:
    key, _, target = spec.partition(":")
    if target and target != CARD and target not in LEVELS:
        raise SystemExit(f"{spec}: 대상은 L1~L7 또는 card")
    return key, target or None


def mark(specs: list[str], policies: dict[str, dict], value: bool = True, easy_dir: Path = EASY_DIR,
         facts_dir: Path = FACTS_DIR) -> list[str]:
    """verified 를 달거나(value=True) 뗀다. [결과 줄]. 통과하지 않은 것 · 원문이 바뀐 것에는 달지 않는다."""
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    msgs = []
    for spec in specs:
        key, target = _parse(spec)
        if key not in policies:
            msgs.append(f"{spec}: policies.json 에 없는 정책")
            continue
        items = {i.target: i for i in collect(policies, [key], easy_dir, facts_dir)}
        targets = [target] if target else [t for t, i in items.items() if i.passed or not value]
        if not targets:
            msgs.append(f"{spec}: 통과한 것이 없음 (검수표 ① 참고)")
        for t in targets:
            item = items.get(t)
            if item is None:
                msgs.append(f"{key}:{t}: 파일에 없음")
                continue
            if value and not item.passed:
                why = "원문이 바뀜" if item.stale else "자동 검사 · 2차 검수 통과 전"
                msgs.append(f"{key}:{t}: 표시 안 함 — {why} (앱에 안 보임. 고친 뒤 recheck)")
                continue
            path = (facts_dir if t == CARD else easy_dir) / f"{key}.json"
            data = _load(path)
            node = data if t == CARD else data["levels"][t]
            node["verified"] = value
            node["verified_at"] = stamp if value else None
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
            msgs.append(f"{key}:{t}: {'검수 끝으로 표시' if value else '표시 취소'}")
    return msgs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="사람 검수표 만들기 · 검수 표시(verified) 달기")
    parser.add_argument("policy_keys", nargs="*", help="없으면 결과 파일이 있는 모든 정책")
    parser.add_argument("--collected", action="store_true", help="자동 수집 정책만 (api · crawler)")
    parser.add_argument("--approve", nargs="+", metavar="정책[:L6|card]", help="확인한 것에 verified 달기")
    parser.add_argument("--revoke", nargs="+", metavar="정책[:L6|card]", help="verified 떼기")
    parser.add_argument("--out", type=Path, help="검수표 경로 (기본 data/eval_results/easy_text_review_날짜.md)")
    args = parser.parse_args(argv)

    policies = {p["policy_key"]: p for p in _load(POLICIES_PATH) or []}
    if args.approve or args.revoke:
        for m in mark(args.approve or [], policies, True) + mark(args.revoke or [], policies, False):
            print(m)
        return 0
    have = {p.stem for p in EASY_DIR.glob("*.json")} | {p.stem for p in FACTS_DIR.glob("*.json")}
    keys = [k for k in (args.policy_keys or sorted(have)) if k in policies
            and (not args.collected or policies[k].get("source_type") in AUTO_SOURCES)]
    items = collect(policies, keys)
    out = args.out or OUT_DIR / f"easy_text_review_{date.today().strftime('%Y%m%d')}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    title = "쉬운 말 · 카드 요약 사람 검수표" + (" (수집 정책)" if args.collected else "")
    out.write_text(render(policies, items, title), encoding="utf-8")
    blocked = sum(not i.passed for i in items)
    todo = sum(i.passed and not i.verified for i in items)
    print(f"정책 {len(keys)}개 · 검수할 것 {len(items)}개 (앱에 안 보임 {blocked} · 사람 검수 전 {todo}) → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
