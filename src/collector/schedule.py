"""정책 신청 시기 (data/apply_schedule.json): 진로 · 신청 로드맵의 '언제 무엇을 신청'.

정책 키마다 두 가지를 적는다 (AI 없음, 규칙만):
- 언제 단계 (stage): 보호 중일 때 · 보호종료 직후 · 대학 입학 · 등록 때 · 집 구할 때 · 취업 준비 때 · 필요할 때.
  보호 중 아동 정책 · 퇴소 후 정책은 백엔드 app/rules.py 의 규칙(is_in_care_child_policy · LEAVER)을 그대로 써서 앱 판정과 맞춘다
- 1년 중 언제 (status · when): 신청기한 글로 정한다
    open / upcoming / expected  날짜가 있음 → 지금 모집 중 · 곧 모집 · 해마다 비슷한 때로 예상 (next_start · next_end)
    anytime                    상시신청 → 언제든
    event                      "보호종료가 이루어진 당해연도", "등록금 납입일로부터 6개월 이내" → 그 일이 생긴 때 기준
    notice                     "상하반기 공고시" → 공고 때
    no_apply                   "신청불요", "개인 신청절차 없음" → 시설 · 지자체가 처리
    unknown                    "접수기관 별 상이", 비어 있음 → 기관에 확인
  장학금(S, data/scholarships.json)은 kosaf.py 가 지난 회분(2021년~)으로 모집 회차를 채운다. 정부24 정책은 지난 회분이 없어,
  실행할 때마다 본 신청기한을 data/collected/schedule_history.json 에 쌓아 해가 지나면 회차가 늘게 한다

실행 (src 폴더에서): python -m collector.schedule [--dry-run]   (collector.pipeline · collector.kosaf 도 끝에 부른다)
"""

import argparse
import re
import sys
from datetime import date
from pathlib import Path

from collector.merge import read_json, write_json
from collector.run import COLLECTED_DIR, POLICIES_PATH, PROJECT_ROOT

SCHEDULE_PATH = POLICIES_PATH.parent.parent / "apply_schedule.json"  # data/apply_schedule.json
HISTORY_PATH = COLLECTED_DIR / "schedule_history.json"
SCHOLARSHIPS_PATH = POLICIES_PATH.parent.parent / "scholarships.json"  # 장학금 (kosaf.py, 로드맵 전용 · 정책 추천에는 안 넣음)

STAGE_LABELS = {
    "in_care": "보호 중일 때",
    "after_care": "보호종료 직후",
    "college": "대학 입학 · 등록 때",
    "housing": "집 구할 때",
    "job": "취업 준비 때",
    "any": "필요할 때",
}
_COLLEGE = re.compile(r"대학|등록금|장학|학자금|교육비|진학|입학")
_HOUSING = re.compile(r"주택|임대|주거|전세|월세|중개보수|보증금")
_JOB = re.compile(r"일자리|취업|구직|도전\s*지원|챙김|직업\s*훈련|인턴")


def _rules():
    """백엔드 app/rules.py (보호 중 아동 정책 · 퇴소 후 정책 판정)"""
    backend = str(PROJECT_ROOT / "src" / "backend")
    if backend not in sys.path:
        sys.path.insert(0, backend)
    from app import rules

    return rules


# 이름에 보호가 끝난 뒤를 말하는 말 / 보호 중인 아동을 말하는 말 (대상 문장보다 이름이 더 분명하다:
# "가정위탁아동 학습활동 지원"은 대상 문장에 '대학'이 있어도 보호 중 지원)
_AFTER_NAME = re.compile(r"퇴소|종결|보호\s*종료|자립\s*준비\s*청년|자립\s*정착|자립\s*수당|자준청|자립지원\s*전담")
_IN_CARE_NAME = re.compile(r"가정\s*위탁\s*(?:보호\s*)?아동|보호\s*(?:대상\s*)?아동|시설\s*(?:생활\s*)?아동|생활\s*(?:시설\s*)?아동|"
                           r"소년소녀\s*가정|디딤씨앗|아동\s*발달\s*지원\s*계좌")


def stage_of(policy: dict) -> str:
    """이름의 주제(대학 · 집 · 취업) → 보호 중 아동 정책 → 퇴소 후 정책 → 필요할 때.
    보호 중 · 퇴소 후는 앱 판정(app/rules.py)도 함께 본다"""
    rules = _rules()
    name, target = policy.get("name") or "", policy.get("target_description") or ""
    if policy["policy_key"].startswith("S") or _COLLEGE.search(name):
        return "college"
    if _HOUSING.search(name):
        return "housing"
    if _JOB.search(name):
        return "job"
    if rules.is_in_care_child_policy(name, target) or (_IN_CARE_NAME.search(name) and not _AFTER_NAME.search(name)):
        return "in_care"
    if _AFTER_NAME.search(name) or rules.LEAVER.search(target):
        return "after_care"
    return "any"


# ---------------- 신청기한 → 1년 중 언제 ----------------

_ANYTIME = re.compile(r"상시|연중|수시")
_NO_APPLY = re.compile(r"신청\s*불요|신청\s*불필요|신청\s*절차\s*(?:가\s*)?없|개인\s*신청\s*(?:절차\s*)?(?:없|불)|개인별\s*신청\s*불가|별도의?\s*신청")
_NOTICE = re.compile(r"공고|반기|연\s*\d\s*회|모집\s*시")
_EVENT = re.compile(r"보호\s*종료|퇴소|납입일|이내|당해\s*연도|해당\s*연도|전입")
_DATE = re.compile(r"(20\d{2})\s*[.\-년/]\s*(\d{1,2})\s*[.\-월/]\s*(\d{1,2})")
STALE_DAYS = 400  # 마지막 모집이 이보다 오래면 '해마다 같은 때' 예상을 하지 않는다
_SHORT_DATE = re.compile(r"^\D{0,6}?(\d{1,2})\s*[.월/]\s*(\d{1,2})")  # "~ 9. 30.(수)" (연도 없이 끝날만)


def deadline_text(policy: dict) -> str:
    """원문의 [신청기한] 칸 → 없으면 신청 방법의 '신청기한: …' 줄"""
    if m := re.search(r"\[신청기한\]\n([^\[]*)", policy.get("original_text") or ""):
        return " ".join(m.group(1).split())
    if m := re.search(r"신청기한\s*:\s*([^\n]*)", policy.get("apply_method") or ""):
        return " ".join(m.group(1).split())
    return ""


def parse_rounds(text: str) -> list[tuple[str, str]]:
    """"2026.04.20.~2026.05.08." · "2026. 1. 29.(목). ~ 9. 30.(수)" → [(시작, 끝)] (YYYY-MM-DD)"""
    out = []
    for part in re.split(r"[,/]\s*(?=20\d{2})", text):
        m = _DATE.search(part)
        if not m:
            continue
        try:
            start = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        except ValueError:
            continue
        end = None
        rest = part[m.end():]
        if "~" in rest:
            after = rest.split("~", 1)[1]
            try:
                if m2 := _DATE.search(after):
                    end = date(int(m2.group(1)), int(m2.group(2)), int(m2.group(3)))
                elif m2 := _SHORT_DATE.match(after):
                    end = date(start.year, int(m2.group(1)), int(m2.group(2)))
            except ValueError:
                end = None
        out.append((start.isoformat(), (end or start).isoformat()))
    return out


def _md(iso: str) -> str:
    d = date.fromisoformat(iso)
    return f"{d.month}월 {d.day}일"


def when_text(entry: dict) -> str:
    st = entry.get("status")
    if st == "open":
        return f"지금 모집 중 ({_md(entry['next_end'])}까지)"
    if st == "upcoming":
        return f"{_md(entry['next_start'])}부터 모집"
    if st == "expected":
        d = date.fromisoformat(entry["next_start"])
        return f"{d.year}년 {d.month}월쯤 모집 예상"
    return {"anytime": "언제든 신청", "notice": "공고 때 신청", "no_apply": "신청 없이 받음 (시설 · 지자체가 처리)",
            "unknown": "기관에 신청 시기 확인"}.get(st) or entry.get("deadline") or "기관에 신청 시기 확인"


def timing(policy: dict, today: date, history: dict[str, list]) -> dict:
    """신청기한 → {status, deadline, (next_start, next_end, basis, rounds …)}. 본 날짜 회차는 history 에 쌓는다"""
    from collector.kosaf import schedule_for

    text = deadline_text(policy)
    out: dict = {"deadline": text or None}
    if rounds := parse_rounds(text):
        seen = history.setdefault(policy["policy_key"], [])
        for r in rounds:
            if list(r) not in seen:
                seen.append(list(r))
        got = schedule_for([tuple(r) for r in seen], today)
        last_end = max(date.fromisoformat(e) for _, e in seen)
        if got["status"] == "expected" and (today - last_end).days > STALE_DAYS:
            # 몇 해 전 기록 하나뿐이면 (정부24 에 2024년 신청기한이 그대로 남은 것) 날짜를 지어내지 않는다
            return {**out, "status": "notice", "rounds": got["rounds"], "basis": f"{last_end.year}년 모집 기록만 있음"}
        return {**out, **got}
    method = policy.get("apply_method") or ""
    if _NO_APPLY.search(text) or (not text and _NO_APPLY.search(method)):
        status = "no_apply"
    elif _ANYTIME.search(text):
        status = "anytime"
    elif _EVENT.search(text):
        status = "event"
    elif _NOTICE.search(text) or (not text and _NOTICE.search(method)):
        status = "notice"
    else:
        status = "unknown"
    return {**out, "status": status}


# ---------------- 파일 ----------------

_KOSAF_KEYS = ("status", "next_start", "next_end", "basis", "rounds", "months", "expected_rounds")


def build(policies: list[dict], today: date, previous: dict, history: dict[str, list]) -> dict:
    """정책마다 {name, stage, stage_label, status, when, …}. 장학금(S)의 모집 회차는 kosaf.py 가 쓴 것(previous)을 쓴다"""
    out = {}
    for p in policies:
        key = p["policy_key"]
        if key.startswith("S"):
            prev = previous.get(key) or {}
            t = {k: prev[k] for k in _KOSAF_KEYS if k in prev} or {"status": "unknown"}
            t["source"] = prev.get("source")
        else:
            t = timing(p, today, history)
        stage = stage_of(p)
        entry = {"name": p["name"], "stage": stage, "stage_label": STAGE_LABELS[stage], **t}
        entry["when"] = when_text(entry)
        out[key] = {k: v for k, v in entry.items() if v is not None}
    return dict(sorted(out.items()))


def update(today: date, *, policies_path: Path = POLICIES_PATH, scholarships_path: Path = SCHOLARSHIPS_PATH,
           schedule_path: Path = SCHEDULE_PATH, history_path: Path = HISTORY_PATH, dry_run: bool = False) -> dict:
    """정책(숨긴 것도: 노출은 DB 가 정한다. P001 처럼 시연 때 켜는 것도 있음) + 장학금"""
    policies = read_json(policies_path, []) + read_json(scholarships_path, [])
    history = read_json(history_path, {})
    schedule = build(policies, today, read_json(schedule_path, {}), history)
    if not dry_run:
        write_json(schedule_path, schedule)
        write_json(history_path, dict(sorted(history.items())))
    return schedule


def summary(schedule: dict) -> str:
    from collections import Counter

    stages = Counter(v["stage_label"] for v in schedule.values())
    status = Counter(v["status"] for v in schedule.values())
    return (f"정책 {len(schedule)}개 · 단계 " + ", ".join(f"{k} {n}" for k, n in stages.most_common())
            + " · 시기 " + ", ".join(f"{k} {n}" for k, n in status.most_common()))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="정책 신청 시기 (data/apply_schedule.json)")
    parser.add_argument("--dry-run", action="store_true", help="파일은 그대로, 요약만")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    schedule = update(date.today(), dry_run=args.dry_run)
    print(summary(schedule))
    for k, v in schedule.items():
        print(f"{k} | {v['name'][:26]} | {v['stage_label']} | {v['when']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
