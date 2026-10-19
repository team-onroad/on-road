"""시연 전 점검용: 서버·DB 없이 실제 AI 상담·롤플레잉과 대화해 본다 (OPENAI_API_KEY 필요).

백엔드와 같은 순서로 처리한다: 위기 신호 → 의도 분류 → 정책 질문이면 정책 검색(RAG), 아니면 상담 답변.

실행 (src 폴더에서):
    backend\\.venv\\Scripts\\python -m coach.try_chat                         # 청년 상담
    backend\\.venv\\Scripts\\python -m coach.try_chat --stage child           # 아동 상담
    backend\\.venv\\Scripts\\python -m coach.try_chat --stage youth --status leaving_soon --d-day 40
    backend\\.venv\\Scripts\\python -m coach.try_chat --roleplay landlord     # 롤플레잉 (상황 id)
    backend\\.venv\\Scripts\\python -m coach.try_chat --roleplay list         # 상황 목록

대화 중: 빈 줄 또는 q → 끝내기, /new → 대화 처음부터
"""

import argparse
import json
import sys

from coach import llm
from rag.settings import PROJECT_ROOT

BACKEND = PROJECT_ROOT / "src" / "backend"
SCENARIOS_PATH = BACKEND / "app" / "roleplay_scenarios.json"
POLICY_KEYS = ["P001", "D001", "D002", "D003", "D004"]


def _crisis():
    if str(BACKEND) not in sys.path:
        sys.path.insert(0, str(BACKEND))
    from app.crisis import CRISIS_CONTACTS, CRISIS_MESSAGE, is_crisis  # 순수 파이썬 (DB 불필요)

    return is_crisis, CRISIS_MESSAGE, CRISIS_CONTACTS


def _scenario(scenario_id: str, stage: str) -> tuple[dict, str] | None:
    """백엔드 app/roleplay.py 와 같은 규칙: 아동은 EASY, 실전(HARD)은 압박 질문을 대사 사이에 섞음."""
    for s in json.loads(SCENARIOS_PATH.read_text(encoding="utf-8")):
        if s["scenario_id"] != scenario_id:
            continue
        level = "EASY" if stage == "child" else (s.get("level_by_stage") or {}).get(stage, s["level"])
        lines = list(s["lines"])
        if level == "HARD" and s.get("hard_lines"):
            lines = [x for i, line in enumerate(s["lines"])
                     for x in ([line, s["hard_lines"][i]] if i < len(s["hard_lines"]) else [line])]
        scenario = {k: s[k] for k in ("scenario_id", "title", "partner", "setting", "goal")}
        return {**scenario, "level": level, "lines": lines}, s["opening"]
    return None


def _ask(prompt: str) -> str | None:
    try:
        text = input(prompt).strip()
    except (EOFError, KeyboardInterrupt):
        return None
    return None if text in ("", "q") else text


def counsel(stage: str, status: str | None, d_day: int | None) -> None:
    is_crisis, crisis_message, contacts = _crisis()
    profile = {"stage": stage, "status": status, "d_day": d_day}
    history: list[dict] = []
    print(f"[상담] 단계 {stage} · 상태 {status or '-'} · D-day {d_day if d_day is not None else '-'}  (빈 줄·q 끝, /new 처음부터)\n")
    while (message := _ask("나: ")) is not None:
        if message == "/new":
            history = []
            print("-- 대화를 처음부터 시작해요 --\n")
            continue
        if is_crisis(message):
            print(f"AI [위기 안내]: {crisis_message}")
            print("    " + " / ".join(f"{c['name']} {c['phone']}" for c in contacts) + "\n")
            continue
        intent = llm.classify_intent(message, history)
        if intent == "policy":
            from rag.policy_search import search_policies

            out = search_policies(message, POLICY_KEYS)
            keys = list(dict.fromkeys(e["policy_key"] for e in out["evidences"]))
            print(f"AI [정책 질문 → 정책 검색] {out['answer'] or '확인할 수 있는 공식 정보가 없어요.'}")
            print(f"    정책 카드: {', '.join(keys) or '없음'}\n")
            continue  # 백엔드도 정책 답변은 상담 대화 기록에 넣지 않는다 (앱이 history 를 관리)
        reply = llm.counsel_reply(message, history, profile)
        print(f"AI [상담]: {reply}\n")
        history += [{"role": "user", "content": message}, {"role": "assistant", "content": reply}]


def roleplay(scenario_id: str, stage: str) -> None:
    found = _scenario(scenario_id, stage)
    if found is None:
        for s in json.loads(SCENARIOS_PATH.read_text(encoding="utf-8")):
            print(f"  {s['scenario_id']:<18} {s['title']} (단계: {', '.join(s['stages'])})")
        return
    is_crisis, crisis_message, _ = _crisis()
    scenario, opening = found
    print(f"[롤플레잉] {scenario['title']} · 상대 {scenario['partner']} · 난이도 {scenario['level']}")
    print(f"상황: {scenario['setting']} / 목표: {scenario['goal']}  (빈 줄·q 끝)\n")
    print(f"{scenario['partner']}: {opening}")
    history = [{"role": "assistant", "content": opening}]
    while (message := _ask("나: ")) is not None:
        if is_crisis(message):
            print(f"{scenario['partner']} [위기 안내]: {crisis_message}")
            return
        out = llm.roleplay_reply(scenario, history, message)
        print(f"{scenario['partner']}: {out['reply']}")
        history += [{"role": "user", "content": message}, {"role": "assistant", "content": out["reply"]}]
        if out["is_finished"]:
            print("-- 연습이 끝났어요 --")
            return


def main() -> None:
    parser = argparse.ArgumentParser(description="AI 상담·롤플레잉 대화 확인")
    parser.add_argument("--stage", choices=["child", "teen", "youth"], default="youth")
    parser.add_argument("--status", choices=["in_care", "leaving_soon", "left_care"])
    parser.add_argument("--d-day", type=int, help="퇴소·목표일까지 남은 날")
    parser.add_argument("--roleplay", metavar="SCENARIO_ID", help="롤플레잉 상황 id (list 면 목록)")
    args = parser.parse_args()
    if args.roleplay:
        roleplay(args.roleplay, args.stage)
    else:
        counsel(args.stage, args.status, args.d_day)


if __name__ == "__main__":
    main()
