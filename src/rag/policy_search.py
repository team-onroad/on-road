"""백엔드가 호출하는 정책 검색 RAG 함수 (docs/api.md 4장).

흐름: 질문(+용어 사전) 임베딩 → 후보 정책(candidate_policy_keys) 청크 안에서만 Chroma 검색
     → 하이브리드 거르기·순위: 거리 상한 안 이거나, 질문 단어가 들어 있으면 느슨한 상한 안 / 벡터 순위 + 키워드 순위(RRF)
     → 정책당 2개까지 먼저 채우기(여러 정책이 카드로 나오게) → 가장 가까운 정책의 신청 방법 청크 보태기
     → 근거로 LLM 답변 생성(문장별 근거 번호)
     → 환각 검사·출처 표시(guard) → api.md 4장 형식으로 반환.

- 근거가 없거나 LLM 이 근거로 답할 수 없다고 하면 no_evidence (추측성 답변 금지, 계획서 7.3).
- evidences 에는 답변이 실제로 쓴 근거만 넣고, 근거 앞에 "[출처: 복지로]" 를 붙인다.
- 질문 내용은 로그·예외 메시지에 넣지 않는다 (api.md 1.6). 오류는 그대로 올려 백엔드가 503 으로 바꾼다.
"""

from collections.abc import Callable
from functools import lru_cache

from chromadb.api.models.Collection import Collection

from rag.guard import finalize, source_label
from rag.terms import expand_query, keyword_hits, keywords

Embed = Callable[[list[str]], list[list[float]]]
# 초안 {"sentences": [{"text", "evidence"}], "missing"} 또는 None(근거로 답할 수 없음)
Answer = Callable[[str, list[dict]], dict | None]

TOP_K_MAX = 20
# 벡터 검색으로 거리를 받아 오는 후보 청크 수 (키워드 일치를 그 안에서 찾는다)
POOL = 50
RRF_K = 60
# 처음 채울 때 한 정책에서 가져오는 근거 수. 남은 자리는 순위대로 채운다
MAX_PER_POLICY = 2
NO_EVIDENCE = {"status": "no_evidence", "answer": None, "evidences": []}


class PolicySearcher:
    def __init__(self, collection: Collection, embed: Embed, answer: Answer, max_distance: float,
                 keyword_max_distance: float | None = None):
        self.collection = collection
        self.embed = embed
        self.answer = answer
        self.max_distance = max_distance
        # None 이면 키워드로 더 넓게 인정하지 않는다 (벡터 거리 상한만)
        self.keyword_max_distance = max(max_distance, keyword_max_distance or max_distance)

    def retrieve(self, question: str, candidate_policy_keys: list[str], top_k: int) -> list[dict]:
        keys = list(dict.fromkeys(candidate_policy_keys))
        res = self.collection.query(
            query_embeddings=self.embed([expand_query(question)]),
            n_results=max(top_k, POOL),
            where={"policy_key": {"$in": keys}},
            include=["documents", "metadatas", "distances"],
        )
        words = keywords(question)
        rows = []
        for cid, doc, meta, dist in zip(res["ids"][0], res["documents"][0], res["metadatas"][0], res["distances"][0]):
            if meta["policy_key"] not in keys:
                continue
            kw = keyword_hits(words, f"{meta.get('policy_name') or ''} {doc}")
            if dist <= self.max_distance or (kw and dist <= self.keyword_max_distance):
                rows.append({**meta, "id": cid, "content": doc, "distance": dist, "_kw": kw})
        # 벡터 순위(거리순)와 키워드 순위(일치 단어 수, 같으면 거리순)를 RRF 로 합친다
        k_rank = {r["id"]: i for i, r in enumerate(sorted((r for r in rows if r["_kw"]), key=lambda r: -r["_kw"]))}
        score = {r["id"]: 1 / (RRF_K + i) + (1 / (RRF_K + k_rank[r["id"]]) if r["id"] in k_rank else 0)
                 for i, r in enumerate(rows)}
        ranked = sorted(rows, key=lambda r: -score[r["id"]])
        picked, per_policy = [], {}
        for r in ranked:
            if len(picked) < top_k and per_policy.get(r["policy_key"], 0) < MAX_PER_POLICY:
                picked.append(r)
                per_policy[r["policy_key"]] = per_policy.get(r["policy_key"], 0) + 1
        picked += [r for r in ranked if r not in picked][: top_k - len(picked)]
        picked.sort(key=lambda r: -score[r["id"]])
        return [{k: v for k, v in r.items() if k != "_kw"} for r in picked]

    def add_apply_chunk(self, hits: list[dict], top_k: int) -> list[dict]:
        """가장 가까운 정책의 신청 방법 청크가 없으면 하나 보탠다 (추천 → 신청 안내로 이어지게).

        top_k 상한은 지킨다: 자리가 없으면 가장 먼 근거를 뺀다.
        """
        top_key = hits[0]["policy_key"]
        if top_k < 2 or any(h["policy_key"] == top_key and h.get("is_apply") for h in hits):
            return hits
        got = self.collection.get(
            where={"$and": [{"policy_key": top_key}, {"is_apply": True}]},
            include=["documents", "metadatas"],
        )
        if not got["ids"]:
            return hits
        cid, doc, meta = min(zip(got["ids"], got["documents"], got["metadatas"]), key=lambda x: x[0])
        if len(hits) >= top_k:
            hits = hits[: top_k - 1]
        return [*hits, {**meta, "id": cid, "content": doc, "distance": None}]

    def __call__(self, question: str, candidate_policy_keys: list[str], top_k: int = 5) -> dict:
        question = question.strip()
        if not question or not candidate_policy_keys:
            return dict(NO_EVIDENCE)
        top_k = max(1, min(top_k, TOP_K_MAX))

        hits = self.retrieve(question, candidate_policy_keys, top_k)
        if not hits:
            return dict(NO_EVIDENCE)
        hits = self.add_apply_chunk(hits, top_k)

        draft = self.answer(question, hits)
        if draft is None:
            return dict(NO_EVIDENCE)

        checked = finalize(draft, hits)
        return {
            "status": "answered",
            "answer": checked.answer,
            "evidences": [_evidence(hits[i]) for i in checked.used],
        }


def _evidence(hit: dict) -> dict:
    content = f"[출처: {source_label(hit)}]\n{hit['content']}" if hit.get("source") else hit["content"]
    score = None if hit["distance"] is None else round(1 - hit["distance"], 4)
    return {"policy_key": hit["policy_key"], "content": content, "score": score}


@lru_cache
def _default_searcher() -> PolicySearcher:
    from rag.openai_client import embed, generate_answer
    from rag.settings import get_rag_settings
    from rag.store import open_collection

    s = get_rag_settings()
    return PolicySearcher(open_collection(), embed, generate_answer, s.rag_max_distance, s.rag_keyword_max_distance)


def search_policies(question: str, candidate_policy_keys: list[str], top_k: int = 5) -> dict:
    return _default_searcher()(question, candidate_policy_keys, top_k)
