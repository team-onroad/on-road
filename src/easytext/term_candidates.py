"""용어 사전(glossary.json) 보강 후보 뽑기. API 를 쓰지 않는다.

용어 사전은 P001(업무매뉴얼 · 자립정보북) 중심이라 정부24 수집 정책에 나오는 행정 용어가 빠져 있다.
사전에 없는 말은 앱이 풀이를 연결하지 못하고, 단계 검사 · 용어 지표도 그 말을 '어려운 용어'로 세지 못한다.

후보: 사용자가 실제로 읽는 변환문에 나오는데 사전에 없는 말. 조사를 떼고, 동사 · 형용사 꼴 · 흔한 쉬운 낱말 ·
관청 이름은 뺀다. 행정 용어 꼴(…법 · …시설 · …급여 · …증명서 · …조치 …)을 앞에, 나온 정책 수가 많은 순.
자동으로 사전에 넣지 않는다 — 사람이 고르고 등급(tier) · 쉬운 말(easy) · 풀이(gloss)를 써서 glossary.json 에 넣는다.

실행 (src 폴더에서):
    backend\\.venv\\Scripts\\python -m easytext.term_candidates --collected     # → data/eval_results/glossary_candidates_날짜.md
"""

import argparse
import json
import re
from collections import defaultdict
from datetime import date
from pathlib import Path

from easytext.glossary import load_terms
from rag.settings import PROJECT_ROOT

EASY_DIR = PROJECT_ROOT / "data" / "easy_text"
POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
OUT_DIR = PROJECT_ROOT / "data" / "eval_results"
AUTO_SOURCES = ("api", "crawler")
MIN_DOCS = 3

_JOSA_RE = re.compile(r"(으로써|으로서|에서는|에게는|으로는|이라도|에서|에게|으로|까지|부터|이나|이며|이고|이란|이라|"
                      r"은|는|이|가|을|를|에|의|로|와|과|도|만|등)$")
# 동사 · 형용사 꼴 (용어가 아님)
_VERB_RE = re.compile(r"(니다|습니다|입니다|합니다|됩니다|하시기|하는|되는|있는|없는|있으며|없으며|있으면|있지|없이|"
                      r"해야|하며|하고|하여|하거나|했거나|하기|할|될|된|한|받|받을|받은|받기|않|지않|줍니다|드립니다|"
                      r"인|이면|이고|이어야|여야|거나|으며|며|면|게|지|고|서|던)$")
# 흔한 쉬운 낱말 · 화면 머리말 (변환문 틀에 늘 나오는 말)
_COMMON = set("""
신청 서류 지원 사람 방법 담당 기관 지역 확인 필요 아동 청년 대상 경우 이상 이하 이내 이후 이전 금액 기간 매월 매달 전화
전화번호 온라인 방문 홈페이지 문의 내용 관련 기준 최대 최근 함께 모든 다음 직접 자세 현금 학생 대학 대학생 고등학생
중학생 초등학생 고등학교 가능 해당 따라 따른 통해 위해 동안 사용 받는 것 알아보기 궁금 언제든지 개인 생활 교육 취업
프로그램 서비스 정부 공고 사전 작성 제출 제공 이용 참여 상담 내용 금액 만원 천원 원입니다 년간 개월 인당 매년 연간
아래 위 중 각 등등 자동 모두 일부 때 곳 분 번 회 일 년 월 주 가정 아이 부모 보호자 학교 주소 거주 살고 사는
""".split())
_OFFICE_RE = re.compile(r"(구청|시청|군청|도청|구|시|군|도|동|읍|면)$")
# 행정 용어 꼴: 앞에 둔다
_ADMIN_RE = re.compile(r"(법|시설|기관|급여|보조금|정착금|지원금|수당|장학금|계획서|동의서|확인서|증명서|등본|초본|구비서류|"
                       r"문답표|조치|사례관리|보장원|시스템|센터|전담|관할|실비|입소|퇴소|이탈|주소지|그룹홈|위탁|가정|"
                       r"대상자|대상아동|청년|등록금|계좌|한도|일시금|요건|자격|수료|이력|공제|감면|연계)$")


def _words(text: str) -> list[tuple[str, str]]:
    """(낱말, 원래 꼴). 조사를 두 번까지 뗀다 ("보호조치를" → "보호조치")"""
    out = []
    for tok in re.findall(r"[가-힣]{2,14}", text):
        w = tok
        for _ in range(2):
            w = _JOSA_RE.sub("", w)
        out.append((w, tok))
    return out


def _is_candidate(word: str, known: list[str]) -> bool:
    if len(word) < 2 or word in _COMMON or _VERB_RE.search(word):
        return False
    if _OFFICE_RE.search(word) and not _ADMIN_RE.search(word) and len(word) <= 5:
        return False  # "북구청" · "서초구" 같은 관청 · 지역 이름
    return not any(k == word or k in word for k in known)  # 사전 용어(또는 그것이 든 합성어)는 이미 풀이가 연결됨


def candidates(policies: dict[str, dict], keys: list[str], easy_dir: Path = EASY_DIR,
               min_docs: int = MIN_DOCS) -> list[dict]:
    known = [t.term.replace(" ", "") for t in load_terms()]
    docs: dict[str, set[str]] = defaultdict(set)
    example: dict[str, str] = {}
    for key in keys:
        path = easy_dir / f"{key}.json"
        if not path.exists():
            continue
        levels = (json.loads(path.read_text(encoding="utf-8")).get("levels") or {}).values()
        for text in (lv.get("text") or "" for lv in levels):
            for sent in re.split(r"(?<=[.!?])\s+|\n+", text):
                for word, _ in _words(sent):
                    if _is_candidate(word, known):
                        docs[word].add(key)
                        example.setdefault(word, sent.strip()[:90])
    rows = [{"word": w, "policies": len(ks), "admin": bool(_ADMIN_RE.search(w)), "keys": sorted(ks)[:5],
             "example": example[w]} for w, ks in docs.items() if len(ks) >= min_docs]
    # 합성어 안에 든 짧은 후보는 긴 쪽이 같은 정책들에 나오면 뺀다 ("보호대상" ⊂ "보호대상아동")
    words = {r["word"]: r for r in rows}
    rows = [r for r in rows if not any(o != r["word"] and r["word"] in o and words[o]["policies"] >= r["policies"]
                                       for o in words)]
    return sorted(rows, key=lambda r: (not r["admin"], -r["policies"], r["word"]))


def to_markdown(rows: list[dict], n_policies: int, today: date) -> str:
    lines = [f"# 용어 사전 보강 후보 ({today.isoformat()})", "",
             f"변환문 {n_policies}개 정책에 나오는데 `src/easytext/glossary.json` 에 없는 말 (정책 {MIN_DOCS}개 이상). "
             "자동으로 넣지 않습니다. 어려운 말을 골라 등급 · 쉬운 말 · 풀이를 써 주세요.", "",
             "등급: T2 = 쉬운 단계(L1~L2)에서만 풀이 · T3 = 모든 단계에서 사전 풀이 연결 · T4 = L1~L5 에서 쉬운 말로 바꿔 씀", "",
             "| 후보 | 정책 수 | 행정 용어 꼴 | 예 (변환문) | 넣을지 · 등급 · 쉬운 말 · 풀이 |", "|---|---|---|---|---|"]
    for r in rows:
        ex = r["example"].replace("|", "/")
        lines.append(f"| {r['word']} | {r['policies']} | {'O' if r['admin'] else ''} | {ex} | |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="용어 사전 보강 후보 (변환문에 나오는데 사전에 없는 말)")
    parser.add_argument("policy_keys", nargs="*")
    parser.add_argument("--collected", action="store_true", help="자동 수집 정책만 (api · crawler)")
    parser.add_argument("--min-docs", type=int, default=MIN_DOCS)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    policies = {p["policy_key"]: p for p in json.loads(POLICIES_PATH.read_text(encoding="utf-8-sig"))}
    keys = [k for k in (args.policy_keys or sorted(policies))
            if not args.collected or policies[k].get("source_type") in AUTO_SOURCES]
    rows = candidates(policies, keys, min_docs=args.min_docs)
    today = date.today()
    out = args.out or OUT_DIR / f"glossary_candidates_{today.strftime('%Y%m%d')}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(to_markdown(rows, len(keys), today), encoding="utf-8")
    print(f"후보 {len(rows)}개 (행정 용어 꼴 {sum(r['admin'] for r in rows)}개) → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
