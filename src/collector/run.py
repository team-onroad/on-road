"""수집 → 변환 → 합치기 → 형식 검사 → 저장 · 보고서.

실행 (src 폴더에서, 백엔드 가상환경):
    backend\\.venv\\Scripts\\python -m collector                  # 기본 검색어 전체
    backend\\.venv\\Scripts\\python -m collector 장학금 자립       # 검색어 지정
    backend\\.venv\\Scripts\\python -m collector --ids 000000465790  # 서비스ID 지정
    backend\\.venv\\Scripts\\python -m collector --dry-run        # 파일은 그대로, 보고서만 출력

저장하는 파일
- data/policies/policies.json: 합친 정책 (사람이 쓴 정책은 바꾸지 않음. db/seed.py 와 같은 규칙으로 검사를 통과해야 저장)
- data/collected/keys.json: 서비스ID → policy_key (G001 …)
- data/collected/gov24/{서비스ID}.json: 받은 원본
- data/collected/report.md: 사람이 확인할 것 (새로 · 바뀜 · 넣지 못함 · 분야 · 지역 · 나이)
"""

import argparse
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

from collector import gov24
from collector.convert import Converted, to_policy
from collector.merge import (KeyMap, MergeResult, NameIndex, load_auto_values, merge, read_json, save_auto_values,
                             write_json)
from rag.settings import PROJECT_ROOT

POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
COLLECTED_DIR = PROJECT_ROOT / "data" / "collected"


class CollectError(RuntimeError):
    pass


def validate(policies: list[dict]) -> None:
    """db/seed.py 의 검사를 그대로 쓴다 (형식이 어긋나면 배포 때 시드가 실패하므로 저장 전에 막는다)"""
    backend = str(PROJECT_ROOT / "src" / "backend")
    if backend not in sys.path:
        sys.path.insert(0, backend)
    from db.seed import SeedError, validate_policies

    try:
        validate_policies(policies, dummy=False)
    except SeedError as e:
        raise CollectError(f"정책 형식 검사 실패 (저장하지 않음): {e}") from None


def report(res: MergeResult, converted: list[Converted], today: date, source: str = "정부24",
           dupes: list[str] | None = None) -> str:
    by_key = {c.policy["policy_key"]: c for c in converted if c.policy}
    name = {p["policy_key"]: p["name"] for p in res.policies}

    def lines(keys: list[str], with_notes: bool = True) -> list[str]:
        out = []
        for k in keys:
            out.append(f"- **{k}** {name.get(k, '')}")
            if with_notes and k in by_key:
                out += [f"  - {n}" for n in by_key[k].notes]
        return out or ["- 없음"]

    skipped = [c for c in converted if c.policy is None and not c.off_target]
    off_target = [c for c in converted if c.off_target]
    parts = [
        f"# 정책 자동 수집 보고서 ({today.isoformat()}, {source})",
        "",
        "새로 · 바뀐 정책은 `easy_text_verified: false` 라 앱에 보이지 않습니다. "
        "아래 확인할 것을 보고 고친 뒤 쉬운 말 변환·검수(`src/easytext`)를 하면 보입니다.",
        "",
        f"## 새로 들어옴 ({len(res.added)})", *lines(res.added), "",
        f"## 내용이 바뀜 ({len(res.changed)}) — 쉬운 말 다시 확인 필요", *lines(res.changed), "",
        f"## 그대로 (확인일만 바꿈) ({len(res.unchanged)})", *lines(res.unchanged, with_notes=False), "",
        f"## 분야 · 지역 규칙이 바뀌어 새로 고름 ({len(res.relabeled)}) — 사람이 고친 값은 그대로", *lines(res.relabeled), "",
        f"## 사람이 쓴 정책이라 건너뜀 ({len(res.manual_kept)})", *lines(res.manual_kept, with_notes=False), "",
        f"## 넣지 못함 ({len(skipped)})",
        *([f"- {c.service_id}: {c.skip_reason}" for c in skipped] or ["- 없음"]), "",
        f"## 이미 있는 정책과 같은 제도라 건너뜀 ({len(dupes or [])})", *([f"- {d}" for d in dupes or []] or ["- 없음"]), "",
        f"## 대상이 달라 뺌 ({len(off_target)}) — 잘못 뺀 것이 있으면 convert.py 낱말 목록을 고치기",
        *([f"- {c.skip_reason}" for c in off_target] or ["- 없음"]), "",
    ]
    if res.missing:
        parts += [f"## 이번 수집에 없음 ({len(res.missing)}) — 종료된 정책인지 확인 (지우지 않음)",
                  *lines(res.missing, with_notes=False), ""]
    return "\n".join(parts)


def collect(keywords: list[str] | None, ids: list[str] | None, *, today: date, get_json=gov24._default_get_json,
            policies_path: Path = POLICIES_PATH, collected_dir: Path = COLLECTED_DIR, dry_run: bool = False,
            limit: int | None = None, pause: float = gov24.PAUSE_SECONDS,
            reprocess: bool = False, output_path: Path | None = None) -> tuple[MergeResult, str]:
    """policies_path: 팀 정책 파일 (읽기만 한다. 같은 제도 거르기 · 키 겹침 검사용)
    output_path: 수집한 정책을 쓰는 파일 (기본: policies_path 에 바로 합침. 주면 따로 저장하고 policies_path 는 읽기만)
    reprocess: API 를 부르지 않고 data/collected/gov24 에 남긴 원본으로 다시 변환 (규칙을 고친 뒤 · 하루 한도를 다 쓴 날)"""
    # 기본은 팀 policies.json 에 바로 합친다 (사람이 쓴 정책은 바꾸지 않음). output_path 를 주면 따로 저장하고 팀 파일은 읽기만
    output_path = output_path or policies_path
    keymap = KeyMap(collected_dir / "keys.json")
    saved: dict[str, dict] = {}
    if reprocess:
        for sid in sorted(keymap.keys, key=keymap.keys.get):  # 키 순서대로 → 같은 키
            path = collected_dir / "gov24" / f"{sid}.json"
            if path.exists():
                saved[sid] = read_json(path, {})
        service_ids = list(saved)
        full_run = False
    else:
        full_run = not ids and not keywords
        service_ids = ids or gov24.find_service_ids(keywords or gov24.DEFAULT_KEYWORDS, get_json, pause)
    if limit:
        service_ids = service_ids[:limit]

    base: list[dict] = [] if output_path == policies_path else read_json(policies_path, [])  # 따로 저장할 때만: 팀 정책 (읽기만)
    current: list[dict] = read_json(output_path, [])  # 전에 수집한 정책
    current_by_key = {p["policy_key"]: p for p in current}
    # 새 키는 파일에 이미 있는 모든 키를 피한다 (이 수집기가 정한 키만 다시 쓸 수 있음.
    # keys.json 이 없거나 어긋나도 다른 정책을 덮어쓰지 않게)
    own = set(keymap.keys.values())
    taken = {p["policy_key"] for p in base + current if p["policy_key"] not in own}
    names = NameIndex(base + current)
    converted: list[Converted] = []
    dupes: list[str] = []
    raws: list[tuple[str, dict, dict | None]] = []
    # 상세는 한 건씩 받으면 오래 걸려(250건 ≈ 몇 분) 몇 개씩 동시에 받는다. 처리는 순서대로 (키가 실행마다 같게)
    if reprocess:
        details = [saved[s].get("detail") for s in service_ids]
    else:
        def fetch(sid: str) -> dict | None | Exception:
            # 다시 물어도 안 되는 서비스는 오류를 돌려받아 건너뛴다 (하나 때문에 전체가 멈추지 않게)
            try:
                return gov24.fetch_detail(sid, get_json)
            except Exception as e:
                return e

        with ThreadPoolExecutor(max_workers=gov24.DETAIL_WORKERS) as pool:
            details = list(pool.map(fetch, service_ids))
    kept: list[tuple[str, dict]] = []
    failed: set[str] = set()  # 받지 못한 서비스 — 전에 넣은 정책은 그대로 두고 '이번에 없음'으로도 보지 않는다
    for sid, detail in zip(service_ids, details):
        if isinstance(detail, Exception):
            failed.add(sid)
            converted.append(Converted(None, sid, skip_reason=f"상세를 받지 못함 ({str(detail)[:60] or type(detail).__name__}) — 다음 수집 때 다시"))
            continue
        if detail is None:
            converted.append(Converted(None, sid, skip_reason="상세 정보 없음"))
            continue
        conv = to_policy(detail, "", today)
        if conv.policy is not None:
            # 사람이 쓴 정책 · 웹 수집과 같은 제도(이름 · 기관이 같음)면 넣지 않는다
            # (보건복지부 "자립준비청년(보호종료아동) 자립수당 지급" = P001)
            if same := names.duplicate_of(conv.policy["name"], conv.policy["agency"], keymap.keys.get(sid)):
                dupes.append(f"{conv.policy['name']} · {conv.policy['agency']} (= {same})")
                continue
            # 넣을 수 있는 것만 키를 받는다 (넣지 못한 서비스가 번호를 차지하지 않게)
            conv.policy["policy_key"] = keymap.key_for(sid, taken)
            names.add(conv.policy["name"], conv.policy["agency"], conv.policy["policy_key"])
            if reprocess and (old := current_by_key.get(conv.policy["policy_key"])):
                conv.policy["checked_at"] = old["checked_at"]  # 원문을 다시 확인한 것이 아니므로 확인일은 그대로
            kept.append((sid, detail))
        converted.append(conv)

    # 원본은 넣은 정책만 남긴다 (대조용. 지원조건은 뜻이 확인되지 않아 정책 칸에는 쓰지 않음)
    if not dry_run:
        if reprocess:
            conditions = [saved[sid].get("conditions") for sid, _ in kept]
        else:
            with ThreadPoolExecutor(max_workers=gov24.DETAIL_WORKERS) as pool:
                conditions = list(pool.map(lambda k: gov24.fetch_conditions(k[0], get_json), kept))
        raws = [(sid, detail, cond) for (sid, detail), cond in zip(kept, conditions)]

    auto_prev = load_auto_values(collected_dir)
    res = merge(current, [c.policy for c in converted if c.policy], full_run=full_run, auto_prev=auto_prev)
    failed_keys = {keymap.keys[s] for s in failed if s in keymap.keys}
    res.missing = [k for k in res.missing if k not in failed_keys]  # 못 받은 것은 '종료된 정책'으로 오해하지 않게
    validate(base + res.policies)  # 팀 정책과 합쳐도 시드 검사 통과 (키 겹침 포함)
    text = report(res, converted, today, source="정부24 · 저장한 원본으로 다시 변환" if reprocess else "정부24", dupes=dupes)
    if not dry_run:
        for sid, detail, conditions in raws:
            gov24.save_raw(collected_dir / "gov24", sid, detail, conditions)
        write_json(output_path, res.policies)  # 팀 파일(policies_path)은 쓰지 않는다
        keymap.save()
        save_auto_values(collected_dir, auto_prev, res)
        # 다시 변환 보고서는 따로 (전체 수집 보고서의 '대상이 달라 뺌' 목록을 덮지 않게)
        (collected_dir / ("reprocess_report.md" if reprocess else "report.md")).write_text(text, encoding="utf-8")
    return res, text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="정부24 지원제도 → policies.json 자동 수집")
    parser.add_argument("keywords", nargs="*", help=f"검색어 (없으면 기본: {' '.join(gov24.DEFAULT_KEYWORDS)})")
    parser.add_argument("--ids", nargs="+", metavar="서비스ID", help="검색 대신 이 서비스만")
    parser.add_argument("--limit", type=int, help="앞에서 N개만 (처음 확인할 때)")
    parser.add_argument("--dry-run", action="store_true", help="파일은 그대로 두고 보고서만 출력")
    parser.add_argument("--reprocess", action="store_true",
                        help="API 없이 data/collected/gov24 원본으로 다시 변환 (규칙을 고친 뒤 · 하루 한도를 다 쓴 날)")
    args = parser.parse_args(argv)
    try:
        res, text = collect(args.keywords, args.ids, today=date.today(), dry_run=args.dry_run, limit=args.limit,
                            reprocess=args.reprocess)
    except (CollectError, gov24.SearchError) as e:
        print(e, file=sys.stderr)
        return 1
    print(text)
    if not args.dry_run:
        print(f"\n저장: {POLICIES_PATH} (새로 {len(res.added)} · 바뀜 {len(res.changed)}), "
              f"보고서 {COLLECTED_DIR / ('reprocess_report.md' if args.reprocess else 'report.md')}")
    return 0
