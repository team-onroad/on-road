"""백엔드가 호출하는 정책 검색 RAG 함수 (docs/api.md 4장).

흐름: 질문(+용어 사전) 임베딩 → 후보 정책(candidate_policy_keys) 청크 안에서만 Chroma 검색 → 거리 상한으로 근거 거르기
     → 가장 가까운 정책의 신청 방법 청크 보태기 → 근거로 LLM 답변 생성(문장별 근거 번호)
     → 환각 검사·출처 표시(guard) → api.md 4장 형식으로 반환.

- 근거가 없거나 LLM 이 근거로 답할 수 없다고 하면 no_evidence (추측성 답변 금지, 계획서 7.3).
- evidences 에는 답변이 실제로 쓴 근거만 넣고, 근거 앞에 "[출처: 복지로]" 를 붙인다.
- 질문 내용은 로그·예외 메시지에 넣지 않는다 (api.md 1.6). 오류는 그대로 올려 백엔드가 503 으로 바꾼다.
"""

from collections.abc import Callable
from functools import lru_cache

from chromadb.api.models.Collection import Collection

from rag.guard import finalize, source_label
from rag.terms import expand_query

Embed = Callable[[list[str]], list[list[float]]]
# 초안 {"sentences": [{"text", "evidence"}], "missing"} 또는 None(근거로 답할 수 없음)
Answer = Callable[[str, list[dict]], dict | None]

TOP_K_MAX = 20
NO_EVIDENCE = {"status": "no_evidence", "answer": None, "evidences": []}


class PolicySearcher:
    def __init__(self, collection: Collection, embed: Embed, answer: Answer, max_distance: float):
        self.collection = collection
        self.embed = embed
        self.answer = answer
        self.max_distance = max_distance

    def retrieve(self, question: str, candidate_policy_keys: list[str], top_k: int) -> list[dict]:
        keys = list(dict.fromkeys(candidate_policy_keys))
        res = self.collection.query(
            query_embeddings=self.embed([expand_query(question)]),
            n_results=top_k,
            where={"policy_key": {"$in": keys}},
            include=["documents", "metadatas", "distances"],
        )
        hits = []
        for cid, doc, meta, dist in zip(res["ids"][0], res["documents"][0], res["metadatas"][0], res["distances"][0]):
            if meta["policy_key"] in keys and dist <= self.max_distance:
                hits.append({**meta, "id": cid, "content": doc, "distance": dist})
        return hits

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

    return PolicySearcher(open_collection(), embed, generate_answer, get_rag_settings().rag_max_distance)


def search_policies(question: str, candidate_policy_keys: list[str], top_k: int = 5) -> dict:
    return _default_searcher()(question, candidate_policy_keys, top_k)
