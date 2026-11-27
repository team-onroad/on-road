"""백엔드가 호출하는 정책 검색 RAG 함수 (docs/api.md 4장).

흐름: 질문(+용어 사전) 임베딩 → 후보 정책(candidate_policy_keys) 청크 안에서만 Chroma 검색
     → 하이브리드 거르기·순위: 거리 상한 안 이거나, 질문 단어가 들어 있으면 느슨한 상한 안 / 벡터 순위 + 키워드 순위(RRF)
     → 정책당 2개까지 먼저 채우기(여러 정책이 카드로 나오게) → 가장 가까운 정책의 신청 방법 청크 보태기
     → 근거로 LLM 답변 생성(문장별 근거 번호)
     → 환각 검사·출처 표시(guard) → api.md 4장 형식으로 반환.

- 근거가 없거나 LLM 이 근거로 답할 수 없다고 하면 no_evidence (추측성 답변 금지, 계획서 7.3).
- evidences 에는 답변이 실제로 쓴 근거를 먼저 넣고, 근거 앞에 "[출처: 복지로]" 를 붙인다.
  그 뒤에 답변에는 안 썼지만 질문과 뜻이 가까운 정책(벡터 거리 0.6 이하, 3개까지)을 "related": True 로 붙인다 (11/23)
- 질문 내용은 로그·예외 메시지에 넣지 않는다 (api.md 1.6). Chroma 오류는 그대로 올려 백엔드가 503 으로 바꾼다.
- 질문 임베딩(OpenAI)이 안 되면 낱말 검색으로 근거를 찾고 근거 정책 안내 문장으로 답한다 ("fallback": True, 11/23).
  OpenAI 장애 때 검색이 통째로 503 이 되던 것

속도 (검색이 느리다는 의견, 11월):
- 질문 임베딩은 서버 안에 기억해 둔다 (같은 질문은 OpenAI 를 다시 부르지 않음). prefetch 로 미리 만들어 둘 수 있다
  (상담 화면이 "정책 질문인지" AI 에게 묻는 동안 임베딩을 같이 만든다)
- 정책별 신청 방법 청크는 한 번 읽으면 기억해 둔다 (인덱스는 배포 때만 바뀜)
- 답변 LLM 이 늦거나(12초) 실패하면 503 대신 근거 정책 안내 문장으로 대신한다 ("fallback": True, 백엔드는 캐시하지 않음)
- warm_up 이 Chroma 인덱스를 메모리에 올리고 OpenAI 연결을 미리 연다 (서버가 깬 뒤 첫 검색이 느리지 않게)
"""

import logging
import re
import threading
from collections import OrderedDict
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor
from functools import lru_cache

from chromadb.api.models.Collection import Collection

from rag.guard import finalize, source_label
from rag.terms import expand_query, keyword_hits, keywords

logger = logging.getLogger(__name__)

Embed = Callable[[list[str]], list[list[float]]]
# 초안 {"sentences": [{"text", "evidence"}], "missing"} 또는 None(근거로 답할 수 없음)
Answer = Callable[[str, list[dict]], dict | None]

TOP_K_MAX = 20
# 벡터 검색으로 거리를 받아 오는 후보 청크 수 (키워드 일치를 그 안에서 찾는다)
POOL = 50
RRF_K = 60
# 처음 채울 때 한 정책에서 가져오는 근거 수. 남은 자리는 순위대로 채운다
MAX_PER_POLICY = 2
# 지급 주기를 묻는 질문 ↔ 매월 지급이라고 적힌 근거 ("월 50만 원", "매월 20일 지급"). 날짜("11월")는 숫자 앞 '월'만 봐서 피한다
_MONTHLY_QUERY_RE = re.compile(r"매달|매월|한\s?달|달마다|월마다|다달이")
_MONTHLY_DOC_RE = re.compile(r"매월|매달|월\s?[\d,]+(?:\.\d+)?\s*(?:만|천)?\s*원")
# 기억해 두는 질문 임베딩 수 (1536차원 하나가 12KB 쯤 → 512개면 6MB 남짓)
VECTOR_CACHE_SIZE = 512
NO_EVIDENCE = {"status": "no_evidence", "answer": None, "evidences": []}
# 답변 LLM 이 실패했을 때 대신 안내해도 되는 가장 먼 근거 거리. 평가 질문셋(11/22)에서 답할 수 있는 질문의 최솟거리는
# 0.602 이하, 근거 없는 질문은 0.632 이상 → AI 없이 안내하는 비상 상황이라 조금 엄격하게 (질문셋이 늘면 다시 맞춘다)
FALLBACK_MAX_DISTANCE = 0.6
# 임베딩 없이 낱말로만 찾을 때 근거로 인정하는 최소 질문 낱말 수 (아니면 3글자 이상 낱말이 정책 이름에 있어야 함)
KEYWORD_ONLY_MIN_WORDS = 2
_EXPAND_HEADER_WORDS = {"용어"}  # rag.terms.expand_query 의 "(관련 용어: …)" ('관련'은 이미 STOPWORDS)
# 비슷한 정책: 답변에 쓰지 않았지만 질문과 뜻이 가까운 정책도 카드로 (낱말이 아니라 벡터 거리로만, 11/23)
RELATED_MAX = 3
# 평가 질문 30개(11/23)로 정함: 0.5 이하는 관련 정책(자립수당 ↔ 자립정착금, 대학 입학 ↔ 대학진학자금),
# 0.52 이상은 약한 것이 섞임 (대리 신청 질문 ↔ 부동산 중개보수 0.55, 디딤씨앗통장 ↔ 자립정착금 0.59)
RELATED_MAX_DISTANCE = 0.5


class VectorCache:
    """질문 글 → 임베딩. 같은 글이 동시에 와도 OpenAI 는 한 번만 부른다 (먼저 온 쪽이 만들고 나머지는 기다림).
    실패한 것은 기억하지 않는다 (다음에 다시 만든다)"""

    def __init__(self, embed: Embed, max_size: int = VECTOR_CACHE_SIZE):
        self.embed = embed
        self.max_size = max_size
        self._data: OrderedDict[str, Future] = OrderedDict()
        self._lock = threading.Lock()

    def get(self, text: str) -> list[float]:
        with self._lock:
            fut = self._data.get(text)
            owner = fut is None
            if owner:
                fut = Future()
                self._data[text] = fut
                while len(self._data) > self.max_size:
                    self._data.popitem(last=False)
            else:
                self._data.move_to_end(text)
        if owner:
            try:
                fut.set_result(self.embed([text])[0])
            except BaseException as e:
                with self._lock:
                    if self._data.get(text) is fut:
                        del self._data[text]
                fut.set_exception(e)
        return fut.result()

    def __len__(self) -> int:
        return len(self._data)


class PolicySearcher:
    def __init__(self, collection: Collection, embed: Embed, answer: Answer, max_distance: float,
                 keyword_max_distance: float | None = None):
        self.collection = collection
        self.vectors = VectorCache(embed)
        self.answer = answer
        self.max_distance = max_distance
        # None 이면 키워드로 더 넓게 인정하지 않는다 (벡터 거리 상한만)
        self.keyword_max_distance = max(max_distance, keyword_max_distance or max_distance)
        # policy_key → 신청 방법 청크 (id, 글, 메타) 또는 None(없음)
        self._apply: dict[str, tuple[str, str, dict] | None] = {}

    def vector(self, question: str) -> list[float]:
        return self.vectors.get(expand_query(question.strip()))

    def retrieve(self, question: str, candidate_policy_keys: list[str], top_k: int) -> list[dict]:
        keys = list(dict.fromkeys(candidate_policy_keys))
        res = self.collection.query(
            query_embeddings=[self.vector(question)],
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
        # 지급 주기 순위: "매달 받는 돈" 처럼 주기를 물으면 매월 지급이라고 적힌 근거를 세 번째 순위로 더한다.
        # (평가로 찾음: 수집 정책이 늘자 '퇴소' 낱말이 겹친 일시금(자립정착금)들이 자립수당(매월)을 밀어냄)
        if _MONTHLY_QUERY_RE.search(question):
            monthly = [r for r in rows if _MONTHLY_DOC_RE.search(r["content"])]
            for i, r in enumerate(monthly):
                score[r["id"]] += 1 / (RRF_K + i)
        ranked = sorted(rows, key=lambda r: -score[r["id"]])
        picked, per_policy = [], {}
        for r in ranked:
            if len(picked) < top_k and per_policy.get(r["policy_key"], 0) < MAX_PER_POLICY:
                picked.append(r)
                per_policy[r["policy_key"]] = per_policy.get(r["policy_key"], 0) + 1
        picked += [r for r in ranked if r not in picked][: top_k - len(picked)]
        picked.sort(key=lambda r: -score[r["id"]])
        return [{k: v for k, v in r.items() if k != "_kw"} for r in picked]

    def keyword_retrieve(self, question: str, candidate_policy_keys: list[str], top_k: int) -> list[dict]:
        """임베딩(OpenAI)이 안 될 때: 후보 정책 청크를 낱말로만 찾는다 (거리 없음 → distance None).

        벡터가 없으니 뜻으로 거르지 못한다 → 낱말이 충분히 맞은 근거만: 질문 낱말이 2개 이상 들어 있거나,
        3글자 이상 질문 낱말이 정책 이름에 있어야 한다 ("오늘 서울 날씨" 가 '서울' 하나로 서울 정책에 걸리지 않게).
        순위는 질문 낱말 수 → 용어 사전으로 넓힌 낱말 수 → 청크 순서. 정책당 2개까지 먼저 채운다 (retrieve 와 같음)"""
        keys = list(dict.fromkeys(candidate_policy_keys))
        got = self.collection.get(where={"policy_key": {"$in": keys}}, include=["documents", "metadatas"])
        words = keywords(question)
        # 용어 사전으로 넓힌 낱말만 (expand_query 가 붙이는 머리말 "(관련 용어: …)" 의 '용어'는 빼고)
        extra = [w for w in keywords(expand_query(question)) if w not in words and w not in _EXPAND_HEADER_WORDS]
        rows = []
        for cid, doc, meta in zip(got["ids"], got["documents"], got["metadatas"]):
            name = meta.get("policy_name") or ""
            text = f"{name} {doc}"
            hits, extra_hits = keyword_hits(words, text), keyword_hits(extra, text)
            # 두 글자 조각("비자" · "받아")은 우연히 겹치기 쉬워 3글자 이상으로 맞은 낱말만 센다 ("해외여행 비자는 어떻게 받아?")
            long = [f for w in words if (f := _matched_form(w, text)) and len(f) >= 3]
            strong = (len(long) >= KEYWORD_ONLY_MIN_WORDS
                      or any(f in name for f in long)  # "자립수당은" → 정책 이름의 '자립수당'
                      or extra_hits >= KEYWORD_ONLY_MIN_WORDS)  # 일상어를 넓힌 원문 용어 ("퇴소 · 매달" → 보호종료 · 매월 · 수당)
            if (hits or extra_hits) and strong:
                rows.append({**meta, "id": cid, "content": doc, "distance": None, "_score": (hits, extra_hits)})
        ranked = sorted(rows, key=lambda r: (-r["_score"][0], -r["_score"][1], r["id"]))
        picked, per_policy = [], {}
        for r in ranked:
            if len(picked) < top_k and per_policy.get(r["policy_key"], 0) < MAX_PER_POLICY:
                picked.append(r)
                per_policy[r["policy_key"]] = per_policy.get(r["policy_key"], 0) + 1
        picked += [r for r in ranked if r not in picked][: top_k - len(picked)]
        return [{k: v for k, v in r.items() if k != "_score"} for r in picked]

    def related(self, question: str, candidate_policy_keys: list[str], exclude: set[str],
                limit: int = RELATED_MAX, exclude_names: set[str] | None = None) -> list[dict]:
        """답변 근거(exclude) 말고 질문과 뜻이 가까운 정책. 정책마다 가장 가까운 조각 하나, 가까운 순.
        낱말 겹침은 보지 않는다 (낱말만 겹친 정책은 retrieve 의 키워드 순위가 이미 다룸).
        이름이 같은 정책은 하나만 (사람이 쓴 P001 과 정부24 G007 이 둘 다 '자립준비청년 자립수당 지급' → 카드가 겹쳐 보임)"""
        rest = [k for k in dict.fromkeys(candidate_policy_keys) if k not in exclude]
        if not rest or limit < 1:
            return []
        res = self.collection.query(
            query_embeddings=[self.vector(question)], n_results=POOL, where={"policy_key": {"$in": rest}},
            include=["documents", "metadatas", "distances"],
        )
        best: dict[str, dict] = {}
        names = set(exclude_names or ())
        cap = min(self.max_distance, RELATED_MAX_DISTANCE)
        rows = sorted(zip(res["ids"][0], res["documents"][0], res["metadatas"][0], res["distances"][0]), key=lambda r: r[3])
        for cid, doc, meta, dist in rows:
            key, name = meta["policy_key"], _name_key(meta.get("policy_name"))
            if key in rest and dist <= cap and key not in best and name not in names:
                best[key] = {**meta, "id": cid, "content": doc, "distance": dist}
                names.add(name)
        return sorted(best.values(), key=lambda h: h["distance"])[:limit]

    def _with_related(self, out: dict, question: str, candidate_policy_keys: list[str], cited: list[dict]) -> dict:
        """evidences 뒤에 비슷한 정책을 붙인다 (백엔드는 evidences 를 정책별 카드로 묶으므로 그대로 카드가 된다).
        "related": True 는 답변 근거가 아니라는 표시 (백엔드는 쓰지 않고, 앱이 '비슷한 정책'으로 나눠 보여 줄 때 쓸 수 있음)"""
        used = {e["policy_key"] for e in out["evidences"]}
        try:
            extra = self.related(question, candidate_policy_keys, used,
                                 exclude_names={_name_key(h.get("policy_name")) for h in cited})
        except Exception as e:
            # 비슷한 정책은 덧붙이는 카드라, 이 검색(Chroma 한 번 더)이 실패해도 이미 만든 답변은 그대로 준다 (503 으로 버리지 않게)
            logger.warning("비슷한 정책 찾기 실패, 답변만 돌려줌: %s", type(e).__name__)
            return out
        return {**out, "evidences": out["evidences"] + [{**_evidence(h), "related": True} for h in extra]}

    def apply_chunk(self, policy_key: str) -> tuple[str, str, dict] | None:
        """정책의 신청 방법 청크 (id 가 가장 앞선 것). 한 번 읽으면 기억해 둔다"""
        if policy_key not in self._apply:
            got = self.collection.get(
                where={"$and": [{"policy_key": policy_key}, {"is_apply": True}]},
                include=["documents", "metadatas"],
            )
            rows = list(zip(got["ids"], got["documents"], got["metadatas"]))
            self._apply[policy_key] = min(rows, key=lambda x: x[0]) if rows else None
        return self._apply[policy_key]

    def add_apply_chunk(self, hits: list[dict], top_k: int) -> list[dict]:
        """가장 가까운 정책의 신청 방법 청크가 없으면 하나 보탠다 (추천 → 신청 안내로 이어지게).

        top_k 상한은 지킨다: 자리가 없으면 가장 먼 근거를 뺀다.
        """
        top_key = hits[0]["policy_key"]
        if top_k < 2 or any(h["policy_key"] == top_key and h.get("is_apply") for h in hits):
            return hits
        found = self.apply_chunk(top_key)
        if found is None:
            return hits
        cid, doc, meta = found
        if len(hits) >= top_k:
            hits = hits[: top_k - 1]
        return [*hits, {**meta, "id": cid, "content": doc, "distance": None}]

    def __call__(self, question: str, candidate_policy_keys: list[str], top_k: int = 5, answer: bool = True) -> dict:
        """answer=False: 답변 문장을 만들지 않고 근거(정책)만 (분야별 목록처럼 카드만 보여 줄 때. LLM 호출이 없어 빠르다)"""
        question = question.strip()
        if not question or not candidate_policy_keys:
            return dict(NO_EVIDENCE)
        top_k = max(1, min(top_k, TOP_K_MAX))

        try:
            self.vector(question)  # 임베딩만 먼저 (기억해 두므로 retrieve 에서 다시 부르지 않음)
        except Exception as e:
            # 질문 임베딩(OpenAI)이 안 되면 503 대신 낱말 검색으로. Chroma 오류는 낱말 검색도 못 하니 그대로 올린다(503)
            logger.warning("질문 임베딩 실패, 낱말 검색으로 대신함: %s", type(e).__name__)
            return self._keyword_only(question, candidate_policy_keys, top_k, answer)
        hits = self.retrieve(question, candidate_policy_keys, top_k)
        if not hits:
            return dict(NO_EVIDENCE)
        if not answer:
            return self._with_related({"status": "answered", "answer": None, "evidences": [_evidence(h) for h in hits]},
                                      question, candidate_policy_keys, hits)
        hits = self.add_apply_chunk(hits, top_k)

        try:
            draft = self.answer(question, hits)
        except Exception as e:
            # 답변 LLM 이 늦거나(시간 초과) 실패해도 근거는 이미 찾았다 → 503 대신 근거 정책 안내 + 원문 카드.
            # 단, 뜻이 충분히 가까운 근거(엄격한 거리 상한 안)가 있을 때만. 낱말만 겹친 근거
            # ("오늘 서울 날씨" ↔ 서울 정책)는 평소엔 LLM 이 거절하는데, LLM 없이 안내하면 엉뚱한 답이 된다 (평가로 찾음)
            best = min((h["distance"] for h in hits if h.get("distance") is not None), default=None)
            if best is None or best > min(self.max_distance, FALLBACK_MAX_DISTANCE):
                logger.warning("RAG 답변 생성 실패, 가까운 근거가 없어 근거 없음으로: %s", type(e).__name__)
                return dict(NO_EVIDENCE)
            logger.warning("RAG 답변 생성 실패, 근거 정책 안내로 대신함: %s", type(e).__name__)
            checked = finalize(None, hits)
            return self._with_related({
                "status": "answered",
                "answer": checked.answer,
                "evidences": [_evidence(hits[i]) for i in checked.used],
                "fallback": True,
            }, question, candidate_policy_keys, [hits[i] for i in checked.used])
        if draft is None:
            return dict(NO_EVIDENCE)

        checked = finalize(draft, hits)
        return self._with_related({
            "status": "answered",
            "answer": checked.answer,
            "evidences": [_evidence(hits[i]) for i in checked.used],
        }, question, candidate_policy_keys, [hits[i] for i in checked.used])

    def _keyword_only(self, question: str, candidate_policy_keys: list[str], top_k: int, answer: bool) -> dict:
        """OpenAI 가 안 될 때의 결과. 답변 LLM 도 같은 OpenAI 라 부르지 않고(부르면 12초 기다린 뒤 실패)
        근거 정책 안내 문장으로 바로 답한다. "fallback": True → 백엔드는 캐시하지 않는다 (복구되면 제대로 답하게)"""
        hits = self.keyword_retrieve(question, candidate_policy_keys, top_k)
        if not hits:
            return dict(NO_EVIDENCE)
        if not answer:
            return {"status": "answered", "answer": None, "evidences": [_evidence(h) for h in hits], "fallback": True}
        hits = self.add_apply_chunk(hits, top_k)
        checked = finalize(None, hits)
        return {"status": "answered", "answer": checked.answer,
                "evidences": [_evidence(hits[i]) for i in checked.used], "fallback": True}

    def warm_up(self, sample_question: str = "자립수당") -> None:
        """Chroma 인덱스를 메모리에 올리고 OpenAI 연결을 연다. 예시 질문 임베딩은 그대로 기억해 둔다"""
        self.collection.query(
            query_embeddings=[self.vector(sample_question)], n_results=1, include=["distances"],
        )


def _name_key(name: str | None) -> str:
    """같은 이름인지 비교용 (띄어쓰기 · 괄호 · 기호 무시)"""
    return re.sub(r"[\W_]", "", name or "")


def _matched_form(word: str, text: str) -> str | None:
    """text 에 있는 꼴: 낱말 그대로, 아니면 끝 한 글자(조사)를 뗀 꼴 (keyword_hits 와 같은 규칙)"""
    if word in text:
        return word
    if len(word) > 2 and word[:-1] in text:
        return word[:-1]
    return None


def _evidence(hit: dict) -> dict:
    content = f"[출처: {source_label(hit)}]\n{hit['content']}" if hit.get("source") else hit["content"]
    score = None if hit["distance"] is None else round(1 - hit["distance"], 4)
    return {"policy_key": hit["policy_key"], "content": content, "score": score}


_searcher_lock = threading.Lock()


@lru_cache
def _build_searcher() -> PolicySearcher:
    from rag.openai_client import embed_query, generate_answer
    from rag.settings import get_rag_settings
    from rag.store import open_collection

    s = get_rag_settings()
    return PolicySearcher(open_collection(), embed_query, generate_answer, s.rag_max_distance,
                          s.rag_keyword_max_distance)


def _default_searcher() -> PolicySearcher:
    """서버에 하나만 둔다. lru_cache 만으로는 서버 시작 미리 열기와 첫 요청이 겹치면 두 번 만들어져
    (Chroma 연결 두 개, 미리 만든 임베딩이 버려지는 쪽에 들어감) 처음 만들 때만 잠근다"""
    with _searcher_lock:
        return _build_searcher()


_default_searcher.cache_clear = _build_searcher.cache_clear  # 테스트에서 다시 만들 때


def search_policies(question: str, candidate_policy_keys: list[str], top_k: int = 5, answer: bool = True) -> dict:
    return _default_searcher()(question, candidate_policy_keys, top_k, answer)


_prefetch_pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="rag-prefetch")


def _prefetch_now(question: str) -> None:
    try:
        _default_searcher().vector(question)
    except Exception as e:  # 미리 만들기는 실패해도 괜찮다 (검색할 때 다시 만든다)
        logger.warning("RAG 질문 임베딩 미리 만들기 실패: %s", type(e).__name__)


def prefetch(question: str) -> None:
    """질문 임베딩을 백그라운드에서 미리 만든다 (기다리지 않음). 곧 검색할지도 모를 때 부른다"""
    if question.strip():
        _prefetch_pool.submit(_prefetch_now, question)


# 백엔드는 rag 함수만 받으므로 함수에 달아 둔다 (getattr(rag, "prefetch", None) — 목 함수에는 없음)
search_policies.prefetch = prefetch


def warm_up() -> None:
    """서버 시작 때 Chroma 인덱스 · OpenAI 연결을 미리 연다 (첫 검색이 느리지 않게)"""
    searcher = _default_searcher()
    try:
        searcher.warm_up()
    except Exception as e:  # OpenAI 키 · 인덱스 문제는 첫 검색 때 오류로 알린다
        logger.warning("RAG 미리 검색 실패: %s", type(e).__name__)