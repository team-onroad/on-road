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
    assert list(sig.parameters) == ["question", "candidate_policy_keys", "top_k"]
    assert sig.parameters["top_k"].default == 5


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
