"""실제 RAG 함수 (src/rag/, api.md 4장). OpenAI 대신 가짜 임베딩·가짜 LLM, Chroma 는 메모리에서 쓴다."""

import inspect
import json
import math
import re
import sys
import uuid

import pytest

from app.config import PROJECT_ROOT
from app.schemas.policies import RagOutput

sys.path.insert(0, str(PROJECT_ROOT / "src"))
chromadb = pytest.importorskip("chromadb")

from rag import policy_search  # noqa: E402
from rag.chunking import MAX_CHARS, MIN_CHARS, chunk_policy  # noqa: E402
from rag.guard import CHECK_NOTICE, MISSING_NOTICE, finalize  # noqa: E402
from rag.index import DUMMY_POLICIES_PATH, POLICIES_PATH, index_policies, load_policies  # noqa: E402
from rag.policy_search import PolicySearcher  # noqa: E402
from rag.store import open_collection  # noqa: E402

_TOKEN_RE = re.compile(r"[0-9A-Za-z가-힣]+")
_DIM = 256


def fake_embed(texts: list[str]) -> list[list[float]]:
    """글자 2-gram 해시 벡터. 같은 단어가 많이 겹치면 가깝다."""
    out = []
    for t in texts:
        v = [0.0] * _DIM
        for tok in _TOKEN_RE.findall(t):
            for i in range(max(1, len(tok) - 1)):
                v[sum(map(ord, tok[i : i + 2])) % _DIM] += 1.0
        norm = math.sqrt(sum(x * x for x in v)) or 1.0
        out.append([x / norm for x in v])
    return out


class FakeAnswer:
    """기본: 한 문장이 모든 근거를 인용. reply 를 주면 그 초안(None 포함)을 그대로 돌려준다."""

    _DEFAULT = object()

    def __init__(self, reply=_DEFAULT):
        self.reply = reply
        self.calls: list[tuple[str, list[dict]]] = []

    def __call__(self, question, hits):
        self.calls.append((question, hits))
        if self.reply is FakeAnswer._DEFAULT:
            return draft(("근거에 따른 답변이에요.", list(range(1, len(hits) + 1))))
        return self.reply


def draft(*sentences: tuple[str, list[int]], missing=False) -> dict:
    return {"sentences": [{"text": t, "evidence": e} for t, e in sentences], "missing": missing}


@pytest.fixture(scope="module")
def policies():
    return load_policies(POLICIES_PATH) + load_policies(DUMMY_POLICIES_PATH)


@pytest.fixture(scope="module")
def collection(policies):
    client = chromadb.EphemeralClient(settings=chromadb.Settings(anonymized_telemetry=False))
    col = open_collection(client, f"test-{uuid.uuid4().hex[:8]}")
    index_policies(col, policies, fake_embed)
    return col


def make_searcher(collection, answer=None, max_distance=1.0):
    return PolicySearcher(collection, fake_embed, answer or FakeAnswer(), max_distance)


# --- 함수 형식 (api.md 4장) ---


def test_signature_matches_spec():
    sig = inspect.signature(policy_search.search_policies)
    assert list(sig.parameters) == ["question", "candidate_policy_keys", "top_k", "answer"]
    assert sig.parameters["top_k"].default == 5
    assert sig.parameters["answer"].default is True  # False: 답변 없이 근거만


# --- 청크 ---


def test_p001_chunks_by_source_and_section(policies):
    p001 = next(p for p in policies if p["policy_key"] == "P001")
    chunks = chunk_policy(p001)

    sources = {c.source for c in chunks}
    assert {"A: 복지로", "B: 2026년 자립정보북 | 대표"} <= sources
    assert any(c.source.startswith("C:") for c in chunks)
    sections = {s for c in chunks for s in c.section.split("·")}
    assert {"지원대상", "신청방법", "신청서류"} <= sections
    for c in chunks:
        assert c.policy_key == "P001"
        assert c.content.startswith(f"[{c.section.split('·')[0]}]\n")
        assert MIN_CHARS <= len(c.content) <= MAX_CHARS + MIN_CHARS + 30
        assert "[출처" not in c.content
    assert len({c.id for c in chunks}) == len(chunks)
    # 짧은 항목(문의처·기준연도)은 따로 청크가 되지 않는다
    assert all(c.section != "문의처" for c in chunks)


def test_text_without_headers_is_one_chunk(policies):
    d001 = next(p for p in policies if p["policy_key"] == "D001")
    chunks = chunk_policy(d001)
    assert len(chunks) == 1
    assert chunks[0].section == "원문"
    assert "월 50만 원" in chunks[0].content


def test_reindex_does_not_duplicate(policies):
    client = chromadb.EphemeralClient(settings=chromadb.Settings(anonymized_telemetry=False))
    col = open_collection(client, f"test-{uuid.uuid4().hex[:8]}")
    n = len(index_policies(col, policies, fake_embed))
    index_policies(col, policies, fake_embed)
    assert col.count() == n


# --- 검색 ---


def test_answered_output_matches_spec(collection):
    out = make_searcher(collection)("자립수당 매월 얼마 받나요?", ["P001", "D001", "D002"])

    RagOutput.model_validate(out)
    assert out["status"] == "answered"
    assert out["answer"].endswith(CHECK_NOTICE)
    assert out["evidences"][0]["policy_key"] in {"P001", "D001"}
    assert all(e["score"] is None or 0 <= e["score"] <= 1 for e in out["evidences"])
    assert "(출처: " in out["answer"]


def test_p001_evidence_shows_source(collection):
    out = make_searcher(collection)("자립수당 지원대상", ["P001"])
    assert all(e["content"].startswith("[출처: ") for e in out["evidences"])


def test_apply_chunk_is_added_for_top_policy(collection):
    answer = FakeAnswer()
    out = make_searcher(collection, answer)("자립수당 매월 50만 원 지원내용", ["P001"], top_k=2)
    _, hits = answer.calls[0]
    assert len(hits) == 2  # top_k 상한 유지
    assert any(h["is_apply"] for h in hits)
    assert len(out["evidences"]) == 2


def test_only_cited_evidences_are_returned(collection):
    out = make_searcher(collection, FakeAnswer(draft(("첫 근거만 써요.", [1]))))("자립수당", ["P001"], top_k=3)
    assert len(out["evidences"]) == 1


def test_searches_only_inside_candidates(collection):
    out = make_searcher(collection)("자립수당 신청 서류가 뭐예요?", ["D002"])
    assert {e["policy_key"] for e in out["evidences"]} == {"D002"}


def test_top_k_is_upper_limit(collection):
    searcher = make_searcher(collection)
    keys = ["P001", "D001", "D002", "D003", "D004"]
    assert len(searcher("자립수당 신청방법", keys)["evidences"]) == 5  # 기본값
    assert len(searcher("자립수당 신청방법", keys, top_k=2)["evidences"]) == 2
    assert len(searcher("자립수당 신청방법", keys, top_k=0)["evidences"]) == 1


def test_far_chunks_are_no_evidence_and_llm_not_called(collection):
    answer = FakeAnswer()
    out = make_searcher(collection, answer, max_distance=0.0)("자립수당", ["P001"])
    assert out == {"status": "no_evidence", "answer": None, "evidences": []}
    assert answer.calls == []


def test_keyword_match_rescues_short_question(collection):
    """"청년"처럼 짧은 질문은 뜻 거리가 멀어도, 그 단어가 들어 있는 청크는 느슨한 상한 안에서 근거로 쓴다."""
    keys = ["P001", "D001", "D002", "D004"]
    vector_only = PolicySearcher(collection, fake_embed, FakeAnswer(), 0.0)
    assert vector_only("청년", keys)["status"] == "no_evidence"

    answer = FakeAnswer()
    out = PolicySearcher(collection, fake_embed, answer, 0.0, keyword_max_distance=1.0)("청년이", keys)
    assert out["status"] == "answered"
    _, hits = answer.calls[0]
    assert hits and all("청년" in h["content"] or "청년" in h["policy_name"] for h in hits if not h.get("is_apply"))
    assert len({h["policy_key"] for h in hits}) >= 2  # 여러 정책이 카드로 나올 수 있게


def test_keyword_rescue_needs_word_in_chunk(collection):
    # 질문 단어가 어느 청크에도 없으면 느슨한 상한을 줘도 근거가 없다
    searcher = PolicySearcher(collection, fake_embed, FakeAnswer(), 0.0, keyword_max_distance=1.0)
    assert searcher("우주선", ["P001", "D001"])["status"] == "no_evidence"


def test_hits_are_spread_over_policies(collection):
    # 한 정책이 근거 자리를 다 차지하지 않게 정책당 2개까지 먼저 채운다
    answer = FakeAnswer()
    make_searcher(collection, answer)("자립수당 지원대상 신청방법 서류", ["P001", "D001", "D002"], top_k=4)
    _, hits = answer.calls[0]
    keys = [h["policy_key"] for h in hits if not h.get("is_apply")]
    assert keys.count("P001") <= 2 and len(set(keys)) >= 2


def test_llm_says_no_evidence(collection):
    out = make_searcher(collection, FakeAnswer(None))("자립수당", ["P001"])
    assert out == {"status": "no_evidence", "answer": None, "evidences": []}


def test_empty_candidates_or_question():
    class Boom:
        def query(self, **_):
            raise AssertionError("검색하면 안 됨")

    searcher = PolicySearcher(Boom(), fake_embed, FakeAnswer(), 1.0)
    assert searcher("자립수당", [])["status"] == "no_evidence"
    assert searcher("   ", ["P001"])["status"] == "no_evidence"


def test_llm_gets_only_retrieved_hits(collection):
    answer = FakeAnswer()
    make_searcher(collection, answer)("월세 지원", ["D002"], top_k=3)
    question, hits = answer.calls[0]
    assert question == "월세 지원"
    assert {h["policy_key"] for h in hits} == {"D002"}
    assert {"policy_name", "content", "source", "section"} <= set(hits[0])


def test_errors_propagate_without_question():
    class Broken:
        def query(self, **_):
            raise RuntimeError("chroma down")

    with pytest.raises(RuntimeError) as e:
        PolicySearcher(Broken(), fake_embed, FakeAnswer(), 1.0)("내 비밀 질문", ["P001"])
    assert "내 비밀 질문" not in str(e.value)


# --- 환각 검사 ---

NAME = "자립준비청년 자립수당 지급"
HITS = [
    {"policy_name": NAME, "source": "A: 복지로", "content": "[지원내용]\n매월 50만 원 지급. 보호종료 5년 이내"},
    {"policy_name": NAME, "source": "B: 2026년 자립정보북 | 대표", "content": "[신청방법]\n행정복지센터 방문 또는 복지로 온라인 신청. 30일 이내 사전신청"},
]


def test_guard_passes_grounded_answer_with_sources():
    out = finalize(draft(("자립수당은 매월 50만 원을 지급합니다", [1]), ("복지로에서 온라인으로 신청합니다.", [2])), HITS)
    assert out.answer == (
        "자립수당은 매월 50만 원을 지급합니다. (출처: 복지로) "
        f"복지로에서 온라인으로 신청합니다. (출처: 2026년 자립정보북) {CHECK_NOTICE}"
    )
    assert out.used == [0, 1]
    assert not out.fallback


def test_guard_checks_numbers_against_cited_evidence_only():
    # "30일" 은 근거 2 에만 있다. 근거 1 만 인용하면 버린다
    out = finalize(draft(("매월 50만 원이에요.", [1]), ("30일 이내 사전신청해요.", [1])), HITS)
    assert "30일" not in out.answer
    assert out.used == [0]


@pytest.mark.parametrize("evidence", [[], [3], [0], "1", [True]])
def test_guard_drops_sentence_without_valid_citation(evidence):
    out = finalize({"sentences": [{"text": "매월 50만 원이에요.", "evidence": evidence}]}, HITS)
    assert out.fallback


def test_guard_adds_missing_notice():
    out = finalize(draft(("매월 50만 원이에요.", [1]), missing=True), HITS)
    assert MISSING_NOTICE in out.answer
    assert out.answer.endswith(CHECK_NOTICE)


def test_guard_strips_inline_citation_marks():
    out = finalize(draft(("매월 50만 원이에요 [1].", [1])), HITS)
    assert "[1]" not in out.answer


def test_guard_reads_inline_marks_when_evidence_is_missing():
    # LLM 이 evidence 칸 없이 문장 끝에 "(1)" 만 다는 경우: 그 번호를 근거로 읽고, "1" 을 원문에 없는 숫자로 보지 않는다
    out = finalize({"sentences": [{"text": "자립수당은 매월 50만 원을 지급합니다.(1)"},
                                  {"text": "복지로에서 온라인으로 신청합니다 (1, 2)", "evidence": []}]}, HITS)
    assert not out.fallback and out.used == [0, 1]
    assert "(1)" not in out.answer and "(1, 2)" not in out.answer
    # 번호가 범위 밖이면 여전히 버린다
    assert finalize({"sentences": [{"text": "매월 50만 원입니다.(7)"}]}, HITS).fallback


@pytest.mark.parametrize("bad_draft", [None, {}, {"sentences": "x"}, {"sentences": [None, {"text": 1}]}])
def test_guard_handles_broken_draft(bad_draft):
    assert finalize(bad_draft, HITS).fallback


@pytest.mark.parametrize(
    "bad",
    [
        "자립수당은 매월 70만 원을 지급해요.",  # 근거에 없는 숫자
        "보호종료 3년 이내라면 받을 수 있어요.",
        "질문하신 분은 신청 가능합니다.",  # 신청 가능 단정
        "회원님은 자립수당 지원 대상이에요.",
        "자격이 충분해요.",
    ],
)
def test_guard_replaces_unsafe_answer(bad):
    out = finalize(draft((bad, [1])), HITS)
    assert out.fallback
    assert out.answer.startswith(f"질문과 관련된 정책으로 {NAME}")
    assert out.answer.endswith(CHECK_NOTICE)


@pytest.mark.parametrize(
    "ok",
    [
        "자립수당은 온라인으로 신청할 수 있어요. 대리인은 신청할 수 없어요.",  # 신청 방법 안내
        "보호종료 5년 이내라면 해당될 수 있어요.",  # 조건부 안내
        "서비스 신청이 가능해요.",
    ],
)
def test_guard_keeps_method_and_conditional_answers(ok):
    out = finalize(draft((ok, [1, 2])), HITS)
    assert out.answer == f"{ok} (출처: 복지로, 2026년 자립정보북) {CHECK_NOTICE}"


def test_keywords_skip_question_endings():
    from rag.terms import keyword_hits, keywords

    assert keywords("청년 지원 정책 있나요?") == ["청년"]
    assert keywords("월세 월세 알려줘") == ["월세"]
    assert keyword_hits(["청년이", "월세"], "자립준비청년 주거비") == 1


def test_query_expansion():
    from rag.terms import expand_query

    assert "보호종료" in expand_query("퇴소하고 매달 받는 돈")
    assert "현금지급" in expand_query("용돈 받을 수 있나요")
    assert expand_query("자립수당") == "자립수당"


@pytest.mark.parametrize("q", ["돈가스 맛집 알려줘", "가정위탁", "오늘 날씨 어때?"])
def test_query_expansion_no_false_positive(q):
    from rag.terms import expand_query

    assert expand_query(q) == q


def test_real_mode_uses_rag_function(monkeypatch):
    from app import rag_client
    from app.config import get_settings

    monkeypatch.setattr(get_settings(), "rag_mode", "real")
    assert rag_client.get_search_policies() is policy_search.search_policies


def test_policies_json_is_valid_json():
    json.loads(POLICIES_PATH.read_text(encoding="utf-8"))


def test_without_answer_skips_llm(collection):
    """answer=False: 답변 LLM 을 부르지 않고 근거(정책)만 돌려준다 (분야별로 보기)"""
    answer = FakeAnswer()
    out = make_searcher(collection, answer)("자립수당 매월 얼마 받나요?", ["P001", "D001"], answer=False)
    assert answer.calls == []
    assert out["status"] == "answered" and out["answer"] is None
    assert {e["policy_key"] for e in out["evidences"]} <= {"P001", "D001"} and out["evidences"]


# --- 속도: 임베딩 기억 · 미리 만들기 · 신청 방법 청크 기억 · 답변 LLM 실패 시 대신 안내 ---


class CountingEmbed:
    def __init__(self, error: Exception | None = None):
        self.calls: list[list[str]] = []
        self.error = error

    def __call__(self, texts):
        self.calls.append(list(texts))
        if self.error is not None:
            raise self.error
        return fake_embed(texts)


def test_same_question_embeds_once(collection):
    embed = CountingEmbed()
    searcher = PolicySearcher(collection, embed, FakeAnswer(), 1.0)
    first = searcher("자립수당 매월 얼마 받나요?", ["P001", "D001"])
    second = searcher("  자립수당 매월 얼마 받나요? ", ["P001"])  # 후보가 달라도 질문이 같으면 다시 부르지 않음
    assert len(embed.calls) == 1
    assert first["status"] == second["status"] == "answered"


def test_concurrent_same_question_embeds_once(collection):
    import threading
    import time

    started = threading.Event()

    class SlowEmbed(CountingEmbed):
        def __call__(self, texts):
            started.set()
            time.sleep(0.2)
            return super().__call__(texts)

    embed = SlowEmbed()
    searcher = PolicySearcher(collection, embed, FakeAnswer(), 1.0)
    t = threading.Thread(target=searcher.vector, args=("월세 지원",))
    t.start()
    started.wait(1)
    searcher.vector("월세 지원")  # 먼저 만들고 있는 것을 기다린다
    t.join()
    assert len(embed.calls) == 1


def test_failed_embedding_is_not_remembered(collection):
    embed = CountingEmbed(RuntimeError("openai down"))
    searcher = PolicySearcher(collection, embed, FakeAnswer(), 1.0)
    for _ in range(2):
        assert searcher("자립수당", ["P001"])["fallback"] is True  # 낱말 검색으로 대신 (11/23)
    assert len(embed.calls) == 2 and len(searcher.vectors) == 0  # 실패는 기억하지 않아 다음에 다시 시도


def test_apply_chunk_is_read_once(collection):
    gets = []

    class Counting:
        def __getattr__(self, name):
            return getattr(collection, name)

        def get(self, **kw):
            gets.append(kw)
            return collection.get(**kw)

    searcher = PolicySearcher(Counting(), fake_embed, FakeAnswer(), 1.0)
    a = searcher("자립수당 매월 50만 원 지원내용", ["P001"], top_k=2)
    b = searcher("자립수당 지원내용 매월", ["P001"], top_k=2)
    assert len(gets) == 1
    assert len(a["evidences"]) == len(b["evidences"]) == 2


@pytest.mark.parametrize("error", [TimeoutError(), RuntimeError("bad json")])
def test_answer_llm_failure_falls_back_to_policy_notice(collection, error):
    """답변 LLM 이 늦거나 실패해도 근거는 찾았으니 503 대신 근거 정책 안내 + 원문 카드"""

    def broken(question, hits):
        raise error

    out = PolicySearcher(collection, fake_embed, broken, 1.0)("자립수당 매월 얼마 받나요?", ["P001"])
    RagOutput.model_validate(out)
    assert out["status"] == "answered" and out["fallback"] is True
    assert out["answer"].endswith(CHECK_NOTICE) and out["evidences"]
    assert "자립수당 매월 얼마" not in out["answer"]


def test_warm_up_remembers_sample_question(collection):
    embed = CountingEmbed()
    searcher = PolicySearcher(collection, embed, FakeAnswer(), 1.0)
    searcher.warm_up("자립수당")
    searcher("자립수당", ["P001"])
    assert len(embed.calls) == 1


def test_search_function_exposes_prefetch():
    assert callable(getattr(policy_search.search_policies, "prefetch", None))


def test_answer_failure_with_only_keyword_matched_hits_is_no_evidence(collection):
    """LLM 이 실패했을 때 낱말만 겹친 먼 근거("오늘 서울 날씨" ↔ 서울 정책)로 대신 안내하지 않는다 (평가로 찾은 버그)"""

    def broken(question, hits):
        raise TimeoutError()

    # 거리 상한 0.0: 어떤 근거도 '충분히 가깝지' 않음. 키워드 상한은 넓게 둬서 낱말이 겹친 근거는 찾힌다
    searcher = PolicySearcher(collection, fake_embed, broken, max_distance=0.0, keyword_max_distance=1.0)
    assert searcher.retrieve("자립수당", ["P001"], 5)  # 근거는 찾았지만
    assert searcher("자립수당", ["P001"]) == {"status": "no_evidence", "answer": None, "evidences": []}


# --- 평가로 찾은 보완 (11/22): 매월 질문 순위 · 근거 번호 고쳐 달기 ---


class _FakeCol:
    """거리를 정해 둔 가짜 Chroma (순위 규칙만 보려고)"""

    def __init__(self, rows):
        self.rows = rows  # (id, policy_key, content, distance)

    def query(self, **_):
        r = sorted(self.rows, key=lambda x: x[3])
        meta = [{"policy_key": k, "policy_name": k, "source": "", "section": "", "is_apply": False} for _, k, _, _ in r]
        return {"ids": [[x[0] for x in r]], "documents": [[x[2] for x in r]], "metadatas": [meta],
                "distances": [[x[3] for x in r]]}

    def get(self, **_):
        return {"ids": [], "documents": [], "metadatas": []}


def _lump_and_monthly():
    lump = [(f"G{i}", f"G{i:03d}", "퇴소아동 자립정착금 1,000만원 일시 지급", 0.30 + i / 100) for i in range(6)]
    return _FakeCol(lump + [("P1", "P001", "본인 명의 계좌로 매월 현금 지급. 월 50만 원", 0.45)])


def test_monthly_question_lifts_monthly_payment_evidence():
    """'매달 받는 돈' 질문이면 매월 지급 근거가 일시금 근거들에 밀리지 않는다 (평가 A05)"""
    searcher = PolicySearcher(_lump_and_monthly(), lambda t: [[1.0]], FakeAnswer(), 0.65)
    keys = ["P001"] + [f"G{i:03d}" for i in range(6)]
    assert "P001" in [h["policy_key"] for h in searcher.retrieve("퇴소하고 매달 받는 돈 있어?", keys, 5)]
    # 주기를 묻지 않으면 순위 규칙은 그대로 (거리순)
    assert "P001" not in [h["policy_key"] for h in searcher.retrieve("퇴소하고 받는 돈 있어?", keys, 5)]


def test_monthly_doc_pattern_ignores_dates():
    assert policy_search._MONTHLY_DOC_RE.search("월 50만 원") and policy_search._MONTHLY_DOC_RE.search("매월 20일")
    assert not policy_search._MONTHLY_DOC_RE.search("2026년 11월 1일까지 신청")


REPAIR_HITS = [
    {"policy_key": "G049", "policy_name": "경계선지능 사례관리", "source": "", "content": "[신청방법]\n문서24로 공문 제출"},
    {"policy_key": "G049", "policy_name": "경계선지능 사례관리", "source": "", "content": "선별비 지원(아동 1인당 최대 30만원)"},
    {"policy_key": "D001", "policy_name": "다른 정책", "source": "", "content": "1인당 최대 30만원 지원"},
    {"policy_key": "G084", "policy_name": "디딤씨앗통장", "source": "", "content": "매월 20일 적립. 만 18세 미만"},
]


def test_wrong_citation_is_fixed_within_same_policy():
    """금액은 같은 정책의 다른 조각에 있는데 번호를 잘못 단 문장 → 버리지 않고 그 조각을 근거로 더한다 (평가 A17)"""
    out = finalize(draft(("선별비는 아동 1인당 최대 30만원입니다.", [1])), REPAIR_HITS)
    assert not out.fallback and "30만원" in out.answer
    assert out.used == [0, 1]  # 다른 정책(D001) 조각은 더하지 않는다


def test_repair_never_uses_other_policy_or_user_numbers():
    # 같은 정책에 그 숫자가 없으면 (다른 정책에만 있으면) 고치지 않고 버린다
    assert finalize(draft(("최대 30만원을 지원합니다.", [4])), REPAIR_HITS).fallback
    # 사용자가 질문에 쓴 나이: 같은 정책 다른 조각의 '20일'과 숫자만 같아도 살리지 않는다 (평가 B03)
    hits = [REPAIR_HITS[3], {**REPAIR_HITS[3], "content": "[신청방법]\n주민센터 방문"}]
    assert finalize(draft(("20세인 경우에는 새로 가입할 수 없습니다.", [2])), hits).fallback


def test_default_searcher_is_built_once_under_concurrency(monkeypatch):
    """서버 시작 미리 열기와 첫 요청이 겹쳐도 검색기는 하나 (점검에서 2개 생기던 것)"""
    import threading
    import time

    made = []

    def slow_build(*a, **k):
        made.append(1)
        time.sleep(0.2)
        return object()

    monkeypatch.setattr(policy_search, "PolicySearcher", slow_build)
    monkeypatch.setattr("rag.store.open_collection", lambda *a, **k: None)
    policy_search._default_searcher.cache_clear()
    try:
        ts = [threading.Thread(target=policy_search._default_searcher) for _ in range(4)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        assert len(made) == 1
    finally:
        policy_search._default_searcher.cache_clear()


# --- OpenAI 장애: 질문 임베딩이 안 되면 낱말 검색으로 (11/23) ---


def _broken_embed(texts):
    raise TimeoutError("OpenAI 응답 없음")


def test_embedding_failure_falls_back_to_keyword_search(collection):
    answer = FakeAnswer()
    s = PolicySearcher(collection, _broken_embed, answer, 1.0)
    out = s("자립수당 신청 방법 알려줘", ["P001", "D001", "D002"])
    assert out["status"] == "answered" and out["fallback"] is True
    assert out["evidences"] and out["evidences"][0]["policy_key"] == "P001"
    assert all(e["score"] is None for e in out["evidences"])  # 거리가 없으니 점수도 없음
    assert answer.calls == []  # 답변 LLM 도 같은 OpenAI 라 부르지 않는다 (12초 기다리지 않게)
    assert CHECK_NOTICE in out["answer"]
    RagOutput.model_validate(out)  # 백엔드 형식 그대로 (fallback 이면 캐시하지 않음)


def test_embedding_failure_does_not_answer_from_one_common_word(collection):
    s = PolicySearcher(collection, _broken_embed, FakeAnswer(), 1.0)
    # '서울' 하나만 겹치는 엉뚱한 질문은 근거 없음 (낱말 2개 이상 또는 3글자 이상 낱말이 정책 이름에 있어야 함)
    assert s("오늘 서울 날씨 어때", ["P001", "D001", "D002"])["status"] == "no_evidence"


def test_embedding_failure_without_answer_marks_fallback(collection):
    s = PolicySearcher(collection, _broken_embed, FakeAnswer(), 1.0)
    out = s("자립수당", ["P001"], answer=False)
    assert out["status"] == "answered" and out["answer"] is None and out["fallback"] is True


@pytest.mark.parametrize("question", [
    "자립수당은 어디서 신청해요?",  # 조사 붙은 낱말 → 정책 이름의 '자립수당'
    "퇴소하고 매달 받는 돈 있어?",  # 일상어 → 용어 사전으로 넓힌 원문 용어(보호종료 · 매월 · 수당)
])
def test_keyword_only_understands_josa_and_everyday_words(collection, question):
    keys = [p for p in ("P001", "G007", "G034", "D001", "D002")]
    out = PolicySearcher(collection, _broken_embed, FakeAnswer(), 1.0)(question, keys)
    assert out["status"] == "answered" and {"P001", "G007"} & {e["policy_key"] for e in out["evidences"]}


def test_keyword_only_ignores_two_letter_fragments(collection):
    # "비자" · "받아" 같은 두 글자 조각이 우연히 겹친 것은 근거로 보지 않는다 (평가 질문 N06)
    out = PolicySearcher(collection, _broken_embed, FakeAnswer(), 1.0)("해외여행 비자는 어떻게 받아?", ["P001", "G007"])
    assert out["status"] == "no_evidence"


# --- 비슷한 정책도 카드로 (11/23) ---


@pytest.fixture
def tiny():
    """거리를 정한 작은 색인: 질문 [1,0] · A(답변 근거) 거리 0 · B 0.1(뜻이 가까움) · C 1.0(먼 정책)"""
    client = chromadb.EphemeralClient(settings=chromadb.Settings(anonymized_telemetry=False))
    col = open_collection(client, f"tiny-{uuid.uuid4().hex[:8]}")
    vecs = {"A": [1.0, 0.0], "B": [0.9, 0.43589], "C": [0.0, 1.0]}
    col.add(ids=[f"{k}-1" for k in vecs], embeddings=list(vecs.values()),
            documents=[f"[지원내용]\n정책 {k} 내용" for k in vecs],
            metadatas=[{"policy_key": k, "policy_name": f"정책 {k}", "source": "A: 복지로", "section": "지원내용",
                        "is_apply": False} for k in vecs])
    return col


def test_similar_policies_follow_cited_evidence(tiny):
    answer = FakeAnswer(draft(("정책 A 로 답합니다.", [1])))  # 답변은 A 만 인용
    s = PolicySearcher(tiny, lambda texts: [[1.0, 0.0] for _ in texts], answer, 1.0)
    out = s("질문", ["A", "B", "C"])
    keys = [(e["policy_key"], e.get("related", False)) for e in out["evidences"]]
    assert keys[0] == ("A", False)  # 답변 근거가 먼저
    assert ("B", True) in keys and all(k != "C" for k, _ in keys)  # 뜻이 가까운 B 만 (C 는 0.6 넘음)
    RagOutput.model_validate(out)  # 백엔드 형식 그대로 → B 도 정책 카드가 된다


def test_similar_policies_skip_non_candidates_and_keyword_only(tiny):
    answer = FakeAnswer(draft(("정책 A 로 답합니다.", [1])))
    s = PolicySearcher(tiny, lambda texts: [[1.0, 0.0] for _ in texts], answer, 1.0)
    assert all(e["policy_key"] != "B" for e in s("질문", ["A", "C"])["evidences"])  # 후보(연령 · 지역) 밖은 안 붙임
    broken = PolicySearcher(tiny, _broken_embed, answer, 1.0)
    out = broken("정책 A 내용", ["A", "B", "C"])
    assert not any(e.get("related") for e in out["evidences"])  # 임베딩이 안 되면 '뜻이 가까운'을 알 수 없어 안 붙임


def test_similar_policy_with_same_name_is_not_repeated():
    """사람이 쓴 P001 과 정부24 G007 이 둘 다 '자립준비청년 자립수당 지급' → 같은 이름 카드는 하나만"""
    client = chromadb.EphemeralClient(settings=chromadb.Settings(anonymized_telemetry=False))
    col = open_collection(client, f"same-{uuid.uuid4().hex[:8]}")
    rows = {"P001": ("자립준비청년 자립수당 지급", [1.0, 0.0]), "G007": ("자립준비청년 자립수당 지급", [0.95, 0.312]),
            "G034": ("보호종료아동 자립수당", [0.9, 0.43589]), "G035": ("보호종료아동 자립수당", [0.88, 0.475])}
    col.add(ids=[f"{k}-1" for k in rows], embeddings=[v for _, v in rows.values()],
            documents=[f"[지원내용]\n{n}" for n, _ in rows.values()],
            metadatas=[{"policy_key": k, "policy_name": n, "source": "A: 복지로", "section": "지원내용", "is_apply": False}
                       for k, (n, _) in rows.items()])
    s = PolicySearcher(col, lambda texts: [[1.0, 0.0] for _ in texts], FakeAnswer(draft(("자립수당입니다.", [1]))), 0.3)
    keys = [e["policy_key"] for e in s("자립수당", list(rows))["evidences"]]
    assert keys[0] == "P001" and "G007" not in keys  # 근거와 이름이 같음
    assert keys.count("G034") + keys.count("G035") == 1  # 비슷한 정책끼리도 이름이 같으면 하나


def test_keyword_only_ignores_expand_header_word():
    """용어 사전으로 넓힐 때 붙는 머리말 "(관련 용어: …)" 의 '용어'는 낱말로 세지 않는다"""
    from rag.terms import expand_query

    assert "용어" in expand_query("퇴소하고 받는 돈")  # 머리말이 붙는 질문
    client = chromadb.EphemeralClient(settings=chromadb.Settings(anonymized_telemetry=False))
    col = open_collection(client, f"hdr-{uuid.uuid4().hex[:8]}")
    col.add(ids=["X-1"], embeddings=[[1.0, 0.0]], documents=["[안내]\n어려운 용어 풀이 모음 · 지원금 안내"],
            metadatas=[{"policy_key": "X", "policy_name": "용어 사전", "source": "A", "section": "안내", "is_apply": False}])
    out = PolicySearcher(col, _broken_embed, FakeAnswer(), 1.0)("퇴소하고 받는 돈", ["X"])
    assert out["status"] == "no_evidence"  # '용어' + '지원금' 으로 넓힌 원문 용어 2개가 된 것처럼 보이면 안 됨


def test_related_lookup_failure_keeps_answer(collection, monkeypatch):
    """비슷한 정책 찾기(Chroma 한 번 더)가 실패해도 이미 만든 답변은 그대로 준다 (전에는 검색 전체가 503)"""
    searcher = make_searcher(collection, FakeAnswer(draft(("자립수당은 매월 지급됩니다.", [1]))))

    def broken(*a, **k):
        raise RuntimeError("chroma")

    monkeypatch.setattr(searcher, "related", broken)
    out = searcher("자립수당 매월 얼마 받나요?", ["P001"])
    RagOutput.model_validate(out)
    assert out["status"] == "answered" and out["answer"] and not any(e.get("related") for e in out["evidences"])
