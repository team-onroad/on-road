"""자동 검수를 통과한 쉬운 말을 서비스 노출 칸(policies.json 의 easy_text · easy_text_verified)에 반영한다.

설계 (docs/db-schema.md 4장): 정책은 DB 에서 is_active AND easy_text_verified 일 때만 서비스에 나오고,
easy_text_verified 가 true 이면 easy_text 가 있어야 한다. easy_text_verified 의 뜻은 '핵심조건 누락 검증 통과'.
이 도구는 8장 협의 사항("검증 통과한 쉬운 말을 easy_text · easy_text_verified 에 반영 — json 갱신 후 재시드")을
json 갱신 쪽으로 한다. DB 는 기존 시드(src/backend/db/seed.py)가 policies.json 을 기준으로 맞춘다.

노출 기준 (11/23 결정): 자동 검수 통과(passed = 자동 검사 + 2차 검수(다른 모델)). 사람 검수(verified)는 기다리지 않는다
— 사람 손을 거의 없애는 것이 이 기능의 목표. 사람 검수 전이면 앱이 '검토 중' 안내를 함께 보여 준다 (docs/api.md 3.23).
- 통과한 단계가 있으면: easy_text = 기본 단계(L6), 없으면 가장 가까운 통과 단계의 전체 글 · easy_text_verified = true
- 통과한 단계가 없거나 원문이 바뀌었으면(사실 파일의 source_hash 다름): easy_text_verified = false (숨김).
  easy_text 는 다시 고칠 때 참고하도록 남긴다 (수집기 merge 규칙과 같음)
- 자동 수집 정책(api · crawler)만 고친다. 사람이 쓴 정책(P001 등)은 그대로 둔다
- 저장 전에 db/seed.py 검사를 그대로 돌린다 (배포 때 시드가 실패하지 않게)

실행 (src 폴더에서, 백엔드 가상환경):
    backend\\.venv\\Scripts\\python -m easytext.publish --dry-run     # 무엇이 바뀌는지만
    backend\\.venv\\Scripts\\python -m easytext.publish               # policies.json 에 반영 → 그다음 시드(db.seed)로 DB 에
"""

import argparse
from dataclasses import dataclass, field
from pathlib import Path

from easytext.levels import DEFAULT_LEVEL, LEVELS
from easytext.review import CARD, EASY_DIR, FACTS_DIR, POLICIES_PATH, collect

AUTO_SOURCES = ("api", "crawler")


@dataclass
class PublishResult:
    shown: list[str] = field(default_factory=list)  # 새로 노출
    updated: list[str] = field(default_factory=list)  # 노출 중 · 글이 바뀜
    hidden: list[str] = field(default_factory=list)  # 노출 → 숨김
    unchanged: list[str] = field(default_factory=list)
    levels: dict[str, str] = field(default_factory=dict)  # 정책 → 반영한 단계


def pick(passed: dict[str, str], want: str = DEFAULT_LEVEL) -> str | None:
    """반영할 단계: 기본 단계, 없으면 가장 가까운 단계 (같으면 쉬운 쪽) — 앱(ai_content.pick_level)과 같은 규칙"""
    if not passed:
        return None
    if want in passed:
        return want
    order = list(LEVELS)
    return min(passed, key=lambda c: (abs(order.index(c) - order.index(want)), order.index(c)))


def apply(items: list[dict], easy_dir: Path = EASY_DIR, facts_dir: Path = FACTS_DIR) -> tuple[list[dict], PublishResult]:
    """(고친 정책 목록, 결과). items 는 policies.json 내용 그대로 (자동 수집 정책만 고친다)"""
    policies = {p["policy_key"]: p for p in items}
    res = PublishResult()
    out = []
    for p in items:
        key = p["policy_key"]
        if p.get("source_type") not in AUTO_SOURCES:
            out.append(p)
            continue
        passed = {i.target: i.text for i in collect(policies, [key], easy_dir, facts_dir)
                  if i.target != CARD and i.passed and i.text}
        code = pick(passed)
        before_on, before_text = bool(p.get("easy_text_verified")), p.get("easy_text")
        if code:
            new = {**p, "easy_text": passed[code], "easy_text_verified": True}
            res.levels[key] = code
            (res.shown if not before_on else res.updated if before_text != passed[code] else res.unchanged).append(key)
        else:
            new = {**p, "easy_text_verified": False}
            (res.hidden if before_on else res.unchanged).append(key)
        out.append(new)
    return out, res


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="자동 검수를 통과한 쉬운 말을 policies.json 노출 칸에 반영")
    parser.add_argument("--dry-run", action="store_true", help="파일은 그대로, 바뀌는 것만 출력")
    parser.add_argument("--file", type=Path, default=POLICIES_PATH)
    args = parser.parse_args(argv)

    from collector.merge import read_json, write_json
    from collector.run import validate

    items = read_json(args.file, [])
    new, res = apply(items)
    validate(new)  # db/seed.py 검사 (easy_text_verified 면 easy_text 필수 등)
    auto = sum(p.get("source_type") in AUTO_SOURCES for p in items)
    print(f"자동 수집 정책 {auto}개 중 노출 {len(res.levels)}개 "
          f"(새로 {len(res.shown)} · 글 바뀜 {len(res.updated)} · 숨김으로 {len(res.hidden)} · 그대로 {len(res.unchanged)})")
    other = sorted({c for c in res.levels.values() if c != DEFAULT_LEVEL})
    if other:
        print("  기본 단계가 없어 다른 단계로 반영: " + ", ".join(f"{k}({c})" for k, c in res.levels.items() if c != DEFAULT_LEVEL))
    if res.hidden:
        print("  숨김으로 바뀜: " + ", ".join(res.hidden))
    not_shown = [p["policy_key"] for p in items if p.get("source_type") in AUTO_SOURCES and p["policy_key"] not in res.levels]
    if not_shown:
        print(f"  노출 안 함 (통과한 쉬운 말 없음 · 검수표 ① 참고): {', '.join(not_shown)}")
    if args.dry_run:
        print("(저장 안 함)")
        return 0
    write_json(args.file, new)
    print(f"저장: {args.file} → DB 에는 시드(src/backend 에서 python -m db.seed)로 반영")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
