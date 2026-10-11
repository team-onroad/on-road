"""RAG 목(mock) 함수. AI 담당의 src/rag/ 함수가 완성되기 전까지 사용한다 (docs/api.md 4장).

- 형식은 api.md 4장 search_policies 와 같다.
- 더미 정책(db/dummy_policies.json) 안에서만 키워드로 찾는다. 후보 목록 밖의 정책은 보지 않는다.
- 랜덤 없이 항상 같은 결과를 낸다.
- 답변 문장에 지원 금액·자격·신청 가능 여부를 만들지 않는다 (계획서 7.3).
"""

import json
import re
from functools import lru_cache

from db.seed import DUMMY_POLICIES_PATH

_TEXT_FIELDS = ("name", "target_description", "support_content", "original_text", "easy_text")
_TOKEN_RE = re.compile(r"[0-9A-Za-z가-힣]+")
_SENTENCE_RE = re.compile(r"(?<=[.!?。])\s+")
# 질문 끝의 조사·어미를 한 글자 떼고 다시 찾아보기 위한 최소 길이
_MIN_TOKEN = 2


@lru_cache
def _dummy_policies() -> dict[str, dict]:
    items = json.loads(DUMMY_POLICIES_PATH.read_text(encoding="utf-8"))
    return {p["policy_key"]: p for p in items}


def _tokens(question: str) -> list[str]:
    seen: dict[str, None] = {}
    for t in _TOKEN_RE.findall(question):
        if len(t) >= _MIN_TOKEN:
            seen.setdefault(t, None)
    return list(seen)


def _matched(token: str, text: str) -> str | None:
    if token in text:
        return token
    # "월세를" → "월세" 처럼 끝 글자(조사)를 뗀 형태도 확인
    if len(token) > _MIN_TOKEN and token[:-1] in text:
        return token[:-1]
    return None


def search_policies(question: str, candidate_policy_keys: list[str], top_k: int = 5) -> dict:
    policies = _dummy_policies()
    tokens = _tokens(question)
    scored: list[tuple[float, int, str, list[str]]] = []
    for order, key in enumerate(candidate_policy_keys):
        policy = policies.get(key)
        if policy is None or not tokens:
            continue
        text = " ".join(policy.get(f) or "" for f in _TEXT_FIELDS)
        hits = [m for t in tokens if (m := _matched(t, text))]
        if hits:
            scored.append((len(hits) / len(tokens), order, key, hits))

    if not scored:
        return {"status": "no_evidence", "answer": None, "evidences": []}

    scored.sort(key=lambda s: (-s[0], s[1]))
    evidences = []
    for score, _, key, hits in scored:
        sentences = _SENTENCE_RE.split(policies[key]["original_text"])
        snippet = next((s for s in sentences if any(h in s for h in hits)), sentences[0])
        evidences.append({"policy_key": key, "content": snippet, "score": round(score, 2)})
    evidences = evidences[:top_k]

    names = ", ".join(policies[e["policy_key"]]["name"] for e in evidences)
    answer = f"(목 답변) 질문과 관련된 정책으로 {names}이(가) 있어요. 자세한 조건은 공식 원문에서 꼭 확인해 주세요."
    return {"status": "answered", "answer": answer, "evidences": evidences}
