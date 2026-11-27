"""정책 데이터 갱신을 한 번에: 수집 → 쉬운 말 변환 → 서비스 노출 칸 반영 → 시드 검사 → 보고서.

전에는 명령 네 개(collector · easytext.batch · easytext.publish · db.seed)를 사람이 차례로 돌려야 해서,
아무도 안 돌리면 정책이 오래된 채로 남았다. 주기 실행(.github/workflows/update-policies.yml)이 이 명령을 부른다.

단계마다 멈추는 장치:
1. 수집 (정부24, DATA_GO_KR_SERVICE_KEY) — 실패하면(한도 초과 · 연결 오류) 여기서 멈추고 파일은 그대로. 수집기는 정책을 지우지 않는다
2. 쉬운 말 변환 (새로 들어왔거나 원문이 바뀐 것만, OPENAI_API_KEY) — 예상 비용이 --max-won 을 넘으면 그만큼만 하고 나머지는 다음 실행으로.
   실패한 변환은 노출하지 않는다 (publish 가 통과한 것만 노출)
3. 노출 칸 반영 (easytext.publish: 자동 검수 통과 → easy_text · easy_text_verified)
4. 시드 검사 (db/seed.py 검사 그대로 — 배포 때 시드가 실패하지 않게). DB 에 넣는 것은 배포(Render)가 한다
5. 장학금 (collector.kosaf → data/scholarships.json, 로드맵 전용. 안 되면 건너뜀 · --reprocess 때는 안 함)
6. 신청 시기 (collector.schedule → data/apply_schedule.json: 언제 단계 · 1년 중 언제. AI 없음, 비용 없음)
보고서: data/collected/pipeline_report.md (주기 실행은 이것을 PR 설명으로 쓴다)

실행 (src 폴더에서, 백엔드 가상환경):
    backend\\.venv\\Scripts\\python -m collector.pipeline --dry-run     # 무엇이 바뀔지만 (수집은 API 를 부르지만 파일은 그대로)
    backend\\.venv\\Scripts\\python -m collector.pipeline                # 실제 갱신 (변환 비용 상한 기본 3,000원)
    backend\\.venv\\Scripts\\python -m collector.pipeline --reprocess    # 수집 API 없이 저장한 원본으로 (규칙만 고쳤을 때)
"""

import argparse
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

from collector import run as collect_run
from collector.merge import read_json, write_json

MAX_WON = 3000  # 한 번에 쓰는 쉬운 말 변환 비용 상한 (11/22 실측: 정책 하나 약 180원 → 약 16개)
REPORT_PATH = collect_run.COLLECTED_DIR / "pipeline_report.md"


@dataclass
class Step:
    name: str
    ok: bool
    summary: str
    lines: list[str] = field(default_factory=list)


def step_collect(today: date, dry_run: bool, reprocess: bool, collect: Callable = collect_run.collect) -> Step:
    try:
        res, _ = collect(None, None, today=today, dry_run=dry_run, reprocess=reprocess)
    except Exception as e:  # 한도 초과 · 연결 오류 · 형식 검사 실패: 파일은 그대로 두고 멈춘다
        return Step("수집", False, f"실패 — {type(e).__name__}: {str(e)[:120]} (파일은 그대로)")
    lines = []
    if res.added:
        lines.append(f"새로 들어옴: {', '.join(res.added)}")
    if res.changed:
        lines.append(f"내용이 바뀜 (쉬운 말 다시 확인 → 숨김 후 다시 변환): {', '.join(res.changed)}")
    if res.missing:
        lines.append(f"이번에 안 나옴 (지우지 않음 · 종료됐는지 확인): {', '.join(res.missing)}")
    src = "저장한 원본으로 다시 변환" if reprocess else "정부24"
    return Step("수집", True, f"{src} · 새로 {len(res.added)} · 바뀜 {len(res.changed)} · 그대로 {len(res.unchanged)}"
                f" · 안 나옴 {len(res.missing)}", lines)


def step_convert(dry_run: bool, max_won: int, workers: int, convert: Callable | None = None) -> Step:
    from easytext import batch

    policies = batch.select(None, True)
    todo, _ = batch.run(policies, ["L6"], None, None, "", workers)
    won, minutes = batch.estimate(todo, workers)
    if not todo:
        return Step("쉬운 말 변환", True, "할 일 없음 (새로 들어왔거나 원문이 바뀐 정책 없음)")
    # 상한 안에 드는 만큼 앞에서부터 (정책마다 예상 비용이 다르다: 사실 추출 + 단계 여럿이면 더 비쌈.
    # 평균으로 나누면 앞쪽에 비싼 정책이 몰렸을 때 상한을 넘던 것)
    limit, spent = 0, 0
    for p in todo:
        cost = batch.estimate([p], 1)[0]
        if spent + cost > max_won:
            break
        spent, limit = spent + cost, limit + 1
    plan = f"할 일 {len(todo)}개 · 예상 약 {won:,}원 · 약 {minutes}분"
    if limit < len(todo):
        plan += f" → 상한 {max_won:,}원이라 {limit}개만 (약 {spent:,}원, 나머지 {len(todo) - limit}개는 다음 실행)"
    if dry_run or limit == 0:
        # 미리보기는 수집 결과를 파일에 쓰지 않으므로 지금 파일 기준 (이번에 새로 들어올 정책의 변환은 빠짐)
        return Step("쉬운 말 변환", True, plan + (" (미리보기: 이번 수집 반영 전 기준)" if dry_run else ""),
                    [f"{p.policy['policy_key']} — {', '.join(p.reasons)}" for p in todo[:20]])
    results = (convert or _convert_now)(policies, workers, limit)
    # 통과: 오류 없고, 다시 한 단계가 모두 통과하고, 카드 요약(사실)을 다시 뽑았으면 그것도 통과
    # (카드 요약만 다시 한 정책은 levels 가 비어 있다 — 그것을 실패로 세던 것)
    ok = [r["policy_key"] for r in results
          if not r["error"] and all(r["levels"].values()) and r.get("facts") is not False]
    failed = [r["policy_key"] for r in results if not r["error"] and r["policy_key"] not in ok]
    errors = [f"{r['policy_key']} ({r['error']})" for r in results if r["error"]]
    lines = [f"통과: {', '.join(ok)}"] if ok else []
    if failed:
        lines.append(f"검사 실패 (노출 안 함 · 검수표 참고): {', '.join(failed)}")
    if errors:
        lines.append(f"오류: {', '.join(errors)}")
    return Step("쉬운 말 변환", not errors or bool(ok), f"{plan} · 통과 {len(ok)} · 실패 {len(failed)} · 오류 {len(errors)}", lines)


def _convert_now(policies: list[dict], workers: int, limit: int) -> list[dict]:
    from easytext import batch
    from easytext.convert import _openai_generate
    from rag.settings import get_rag_settings

    s = get_rag_settings()
    _, results = batch.run(policies, ["L6"], _openai_generate(s.easytext_model), _openai_generate(s.easytext_judge_model),
                           s.easytext_model, workers, limit=limit)
    return results


def step_publish(dry_run: bool, path: Path = collect_run.POLICIES_PATH) -> tuple[Step, list[dict] | None]:
    from easytext import publish

    items = read_json(path, [])
    new, res = publish.apply(items)
    lines = []
    if res.shown:
        lines.append(f"새로 노출: {', '.join(res.shown)}")
    if res.hidden:
        lines.append(f"숨김으로 (통과한 쉬운 말 없음 · 원문 바뀜): {', '.join(res.hidden)}")
    hidden_now = [p["policy_key"] for p in new if p.get("source_type") in publish.AUTO_SOURCES and not p.get("easy_text_verified")]
    if hidden_now:
        lines.append(f"노출 안 됨 (검수표 ①): {', '.join(hidden_now)}")
    summary = (f"노출 {len(res.levels)}개 · 새로 {len(res.shown)} · 글 바뀜 {len(res.updated)} · 숨김으로 {len(res.hidden)}"
               + (" (미리보기)" if dry_run else ""))
    return Step("노출 칸 반영", True, summary, lines), new


def step_validate(policies: list[dict]) -> Step:
    try:
        collect_run.validate(policies)
    except collect_run.CollectError as e:
        return Step("시드 검사", False, str(e)[:200])
    return Step("시드 검사", True, f"정책 {len(policies)}개 통과 (db/seed.py 검사). DB 반영은 배포 때")


def step_schedule(today: date, dry_run: bool, policies_path: Path, schedule_path: Path) -> Step:
    from collector import schedule

    try:
        real = schedule_path == schedule.SCHEDULE_PATH  # 테스트는 다른 폴더: 장학금 · 기록도 그 옆 파일을 쓴다
        got = schedule.update(today, policies_path=policies_path, schedule_path=schedule_path,
                              scholarships_path=schedule.SCHOLARSHIPS_PATH if real else schedule_path.with_name("scholarships.json"),
                              history_path=schedule.HISTORY_PATH if real else schedule_path.with_name("schedule_history.json"),
                              dry_run=dry_run)
    except Exception as e:  # 신청 시기는 부가 정보라 실패해도 정책 갱신은 그대로 둔다
        return Step("신청 시기", False, f"실패 — {type(e).__name__}: {str(e)[:120]}")
    return Step("신청 시기", True, schedule.summary(got) + (" (미리보기)" if dry_run else ""))


def step_scholarships(today: date, dry_run: bool, collect: Callable | None = None) -> Step:
    """한국장학재단 장학금 (collector.kosaf → data/scholarships.json, 로드맵 전용). 지난 회분은 저장본을 써서 API 는 몇 번만 부른다.
    이 API 는 공공데이터포털 활용신청이 따로 필요해, 안 되면 건너뛰고 정책 갱신은 그대로 둔다 (장학금은 이전 파일)"""
    try:
        if collect is None:
            from collector.kosaf import collect
        converted, _, _ = collect(today=today, dry_run=dry_run)
    except Exception as e:
        return Step("장학금", True, f"건너뜀 — {type(e).__name__}: {str(e)[:120]} (이전 장학금 파일 그대로)")
    n = sum(1 for c in converted if c.policy)
    return Step("장학금", True, f"한국장학재단 장학금 {n}개 (로드맵 전용 · 정책 추천에는 안 넣음)" + (" (미리보기)" if dry_run else ""))


def run_pipeline(today: date, *, dry_run: bool = False, reprocess: bool = False, max_won: int = MAX_WON,
                 workers: int = 4, collect: Callable = collect_run.collect, convert: Callable | None = None,
                 policies_path: Path = collect_run.POLICIES_PATH, schedule_path: Path | None = None,
                 scholarships: Callable | None = None) -> list[Step]:
    """scholarships: 장학금 수집 (기본: 실제 정책 파일로 돌 때만 collector.kosaf. --reprocess 는 API 를 안 부르므로 건너뜀)"""
    steps = [step_collect(today, dry_run, reprocess, collect)]
    if not steps[-1].ok:
        return steps
    steps.append(step_convert(dry_run, max_won, workers, convert))
    if not steps[-1].ok:
        return steps
    pub, new = step_publish(dry_run, policies_path)
    steps.append(pub)
    check = step_validate(new or [])
    steps.append(check)
    if check.ok and not dry_run and new is not None and new != read_json(policies_path, []):
        write_json(policies_path, new)
    if check.ok and not reprocess and (scholarships or policies_path == collect_run.POLICIES_PATH):
        steps.append(step_scholarships(today, dry_run, scholarships))
    if check.ok:
        from collector.schedule import SCHEDULE_PATH

        # 테스트처럼 다른 정책 파일을 쓰면 신청 시기도 그 옆에 (실제 data/apply_schedule.json 을 건드리지 않게)
        default = SCHEDULE_PATH if policies_path == collect_run.POLICIES_PATH else policies_path.with_name("apply_schedule.json")
        steps.append(step_schedule(today, dry_run, policies_path, schedule_path or default))
    return steps


def report(steps: list[Step], today: date, dry_run: bool) -> str:
    ok = all(s.ok for s in steps)
    out = [f"# 정책 데이터 자동 갱신 ({today.isoformat()}{' · 미리보기' if dry_run else ''})", "",
           f"결과: {'성공' if ok else '중간에 멈춤 (아래 실패 단계 확인)'}", "", "| 단계 | 결과 | 내용 |", "|---|---|---|"]
    out += [f"| {s.name} | {'O' if s.ok else 'X'} | {s.summary} |" for s in steps]
    for s in steps:
        if s.lines:
            out += ["", f"## {s.name}", *[f"- {x}" for x in s.lines]]
    out += ["", "배포(develop 에 합치면 Render): 검색 색인 `rag.index` 는 빌드 때 새로 만든다. "
            "쉬운 말 검수표는 `python -m easytext.review --collected`"]
    return "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="정책 데이터 갱신 한 번에: 수집 → 쉬운 말 변환 → 노출 반영 → 시드 검사")
    parser.add_argument("--dry-run", action="store_true", help="무엇이 바뀔지만 (파일 그대로, 변환 안 함)")
    parser.add_argument("--reprocess", action="store_true", help="수집 API 없이 저장한 원본으로")
    parser.add_argument("--max-won", type=int, default=MAX_WON, help=f"쉬운 말 변환 비용 상한 (기본 {MAX_WON:,}원)")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--report", type=Path, default=REPORT_PATH)
    args = parser.parse_args(argv)
    today = date.today()
    steps = run_pipeline(today, dry_run=args.dry_run, reprocess=args.reprocess, max_won=args.max_won, workers=args.workers)
    text = report(steps, today, args.dry_run)
    print(text)
    if not args.dry_run:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text, encoding="utf-8")
    return 0 if all(s.ok for s in steps) else 1


if __name__ == "__main__":
    sys.exit(main())
