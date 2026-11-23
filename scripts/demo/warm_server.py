"""시연 전 배포 서버 깨우기 · 시연 질문 예열 · 시연 동안 깨어 있게 하기.

왜: Render 무료 요금제는 15분 동안 요청이 없으면 잠들어 다음 첫 요청이 수십 초 걸리고, 정책 검색은 처음 묻는
질문이면 2~4초(답변 AI) 걸린다. 서버를 깨운 뒤 시연 질문(questions.txt)을 미리 한 번씩 돌려 두면 검색 캐시
(24시간, src/backend/app/services/policies.py)에 들어가 시연 때는 0.4초 안팎으로 나온다.

- 파이썬 기본 라이브러리만 쓴다 (가상환경 없이 시연 노트북에서 바로)
- 검색은 시연 사용자(19세 · 서울)로 한다. 캐시는 '같은 질문 · 같은 후보 정책'이라 같은 나이 · 지역 사용자도 같이 빨라진다
- --keep 동안 10분마다 깨우고, 그 사이 서버가 다시 켜져(배포 · 재시작) 캐시가 비었으면 다시 예열한다
- 비용: 처음 묻는 질문 하나당 약 2원 (캐시에 있는 질문은 0원)

실행 (저장소 폴더에서):
    python scripts/demo/warm_server.py                 # 깨우고 예열 한 번
    python scripts/demo/warm_server.py --keep 180      # 예열 뒤 3시간 동안 깨어 있게 (Ctrl-C 로 멈춤)
    python scripts/demo/warm_server.py --base http://127.0.0.1:8765/api   # 로컬 서버로
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://onroad-api.onrender.com/api"
DEMO_USER = "00000000-0000-4000-8000-000000000001"  # src/backend/db/demo_seed.py 의 시연 사용자
QUESTIONS = Path(__file__).with_name("questions.txt")
WAKE_LIMIT = 5 * 60  # 깨어날 때까지 기다리는 최대 시간 (무료 요금제는 보통 30~60초)
SLOW = 1.5  # 예열한 질문이 이보다 느리면 캐시가 빈 것(서버가 다시 켜짐)으로 본다


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def request(method: str, url: str, body: dict | None = None, timeout: float = 90) -> tuple[int, dict, float]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            payload = json.loads(res.read().decode("utf-8") or "{}")
            return res.status, payload, time.perf_counter() - start
    except urllib.error.HTTPError as e:
        return e.code, {}, time.perf_counter() - start


def wake(base: str) -> bool:
    deadline = time.time() + WAKE_LIMIT
    start = time.perf_counter()
    while time.time() < deadline:
        try:
            status, _, _ = request("GET", f"{base}/health", timeout=90)
            if status == 200:
                log(f"서버 깨어 있음 ({time.perf_counter() - start:.1f}초)")
                return True
            log(f"서버 응답 {status}, 다시 시도")
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            log(f"아직 깨는 중 ({type(e).__name__}), 다시 시도")
        time.sleep(5)
    log("서버가 5분 안에 깨어나지 않음 — Render 대시보드 확인 필요")
    return False


def search(base: str, user: str, question: str) -> tuple[int, int, float]:
    status, data, secs = request("POST", f"{base}/search", {"user_id": user, "question": question})
    return status, len(data.get("results") or []), secs


def warm(base: str, user: str, questions: list[str]) -> list[str]:
    """한 번 묻고(캐시에 넣기) 다시 물어 빨라졌는지 본다. [문제 있는 질문]"""
    problems = []
    for q in questions:
        s1, n, t1 = search(base, user, q)
        s2, _, t2 = search(base, user, q)
        ok = s1 == 200 and s2 == 200 and n > 0
        if not ok:
            problems.append(q)
        log(f"{'OK ' if ok else '확인'} {q[:24]:<24} 처음 {t1:4.1f}초 → 다시 {t2:4.1f}초 · 카드 {n}"
            + ("" if ok else f" (응답 {s1}/{s2}, 카드 {n})"))
    return problems


def load_questions(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return [x.strip() for x in lines if x.strip() and not x.lstrip().startswith("#")]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="시연 전 서버 깨우기 · 질문 예열 · 깨어 있게 하기")
    parser.add_argument("--base", default=BASE, help=f"API 주소 (기본 {BASE})")
    parser.add_argument("--user", default=DEMO_USER, help="검색할 사용자 (기본: 시연 사용자)")
    parser.add_argument("--questions", type=Path, default=QUESTIONS)
    parser.add_argument("--keep", type=float, default=0, help="예열 뒤 이 시간(분) 동안 깨어 있게. 0 이면 한 번만")
    parser.add_argument("--interval", type=float, default=10, help="깨우는 간격(분, 기본 10 — 무료 요금제는 15분이면 잠듦)")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    questions = load_questions(args.questions)
    base = args.base.rstrip("/")
    if not wake(base):
        return 1
    problems = warm(base, args.user, questions)
    log(f"예열 끝: 질문 {len(questions)}개" + (f" · 확인 필요 {len(problems)}개" if problems else " · 모두 정상"))
    end = time.time() + args.keep * 60
    try:
        while time.time() < end:
            time.sleep(min(args.interval * 60, max(1, end - time.time())))
            if time.time() >= end or not wake(base):
                break
            if questions:
                _, _, secs = search(base, args.user, questions[0])
                if secs > SLOW:  # 서버가 다시 켜져 캐시가 빈 것 → 다시 예열
                    log(f"첫 질문이 {secs:.1f}초 — 캐시가 비어 다시 예열")
                    warm(base, args.user, questions)
    except KeyboardInterrupt:
        log("멈춤")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
