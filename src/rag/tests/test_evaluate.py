"""RAG 정량 평가 도구 (rag.evaluate). API 없이 가짜 임베딩 · 가짜 답변으로 잰다."""

import json
from datetime import date

import pytest

pytest.importorskip("chromadb")

from rag import evaluate  # noqa: E402
from rag.evaluate import Record, RecordingAnswer, evaluate_one, summarize  # noqa: E402

P = {
    "policy_key": "P001", "name": "자립준비청년 자립수당 지급", "agency": "보건복지부", "source": "A: 복지로",
    "original_text": "[출처 A: 복지로]\n[지원내용]\n매월 50만 원을 지급합니다. 지급일은 매월 20일입니다.\n[신청방법]\n행정복지센터 방문 신청",
}
Q = {"policy_key": "G001", "name": "가정위탁아동 대학진학자금 지원", "agency": "충청북도 단양군",
     "original_text": "[출처 A: 정부24]\n[지원내용]\n대학 진학 시 1인 200만원 지원\n[신청방법]\n군청 방문"}


def _rec(**kw):
    base = dict(id="x", type="answerable", question="q", status="answered", seconds=1.0, retrieved_keys=["P001"],
                hit=True, rank=1, evidence_keys=["P001"], facts_expected=[], facts_found=[])
    return Record(**{**base, **kw})


def test_summary_metrics():
    rows = [
        _rec(rank=1, facts_expected=["50만", "20일"], facts_found=["50만"], draft_sentences=2, draft_rejected=1),
        _rec(rank=2, seconds=3.0, draft_sentences=2, draft_mixed=1),
        _rec(hit=False, rank=None, status="no_evidence"),  # 답할 수 있는데 거절
        _rec(type="no_evidence", hit=None, rank=None, status="no_evidence"),
        _rec(type="no_evidence", hit=None, rank=None, status="answered", final_assertion=True),
    ]
    s = summarize(rows)
    assert s["검색"]["Hit@5 (정답 정책이 근거 상위 5개 안)"] == round(2 / 3, 3)
    assert s["검색"]["MRR (정답 정책 순위의 역수 평균)"] == round((1 + 0.5) / 3, 3)
    assert s["거절"]["근거 없는 질문을 거절한 비율"] == 0.5
    assert s["거절"]["답할 수 있는 질문을 잘못 거절한 비율"] == round(1 / 3, 3)
    assert s["사실"]["답변에 원문 핵심 사실이 들어간 비율"] == 0.5
    assert s["환각"]["환각 검사에 걸려 버려진 초안 문장 비율"] == 0.25
    assert s["환각"]["최종 답변에 자격 단정이 남은 질문 수"] == 1
    assert s["정책 섞임"]["서로 다른 정책 근거를 함께 쓴 초안 문장 비율"] == 0.25
    assert s["속도"]["p95 응답 초"] == 3.0


@pytest.fixture
def searcher():
    s, rec, _ = evaluate.build_searcher([P, Q], mock=True)
    return s, rec


def test_hallucinated_and_mixed_draft_sentences_are_counted(searcher):
    s, _ = searcher

    def answer(question, hits):  # 근거 없는 숫자 1문장 + 두 정책을 섞은 1문장 + 정상 1문장
        p = next(i for i, h in enumerate(hits, 1) if h["policy_key"] == "P001")
        g = next((i for i, h in enumerate(hits, 1) if h["policy_key"] == "G001"), p)
        return {"sentences": [
            {"text": "매월 70만 원을 지급합니다.", "evidence": [p]},
            {"text": "두 제도가 있습니다.", "evidence": [p, g]},
            {"text": "매월 50만 원을 지급합니다.", "evidence": [p]},
        ], "missing": False}

    rec = RecordingAnswer(answer)
    s.answer = rec
    r = evaluate_one(s, rec, {"id": "A", "type": "answerable", "question": "자립수당 매월 지급 대학 진학",
                              "expect_keys": ["P001"], "expect_facts": ["50만", "70만"]}, ["P001", "G001"])
    assert r.draft_sentences == 3 and r.draft_rejected == 1 and r.draft_mixed == 1
    assert r.hit and r.rank >= 1
    assert r.facts_found == ["50만"]  # 근거 없는 70만은 최종 답변에서 빠짐
    assert r.final_unsupported == [] and r.final_assertion is False


def test_run_writes_results(tmp_path):
    qs = tmp_path / "q.json"
    qs.write_text(json.dumps({"questions": [
        {"id": "A1", "type": "answerable", "question": "자립수당 매월 얼마", "expect_keys": ["P001"], "expect_facts": []},
        {"id": "N1", "type": "no_evidence", "question": "오늘 날씨", "expect_keys": [], "expect_facts": []},
    ]}, ensure_ascii=False), encoding="utf-8")
    pol = tmp_path / "p.json"
    pol.write_text(json.dumps([P, {**Q, "policy_key": "D001"}], ensure_ascii=False), encoding="utf-8")  # 더미는 빠짐
    summary, records, base = evaluate.run(mock=True, questions_path=qs, policies_path=pol, out_dir=tmp_path / "out",
                                          today=date(2026, 11, 22))
    assert base.name == "rag_eval_20261122_mock"
    saved = json.loads(base.with_suffix(".json").read_text(encoding="utf-8"))
    assert saved["meta"]["policies"] == 1 and len(saved["records"]) == 2
    md = base.with_suffix(".md").read_text(encoding="utf-8")
    assert "## 검색" in md and "| A1 |" in md


def test_question_set_is_valid():
    qs = evaluate.load_questions()
    assert len(qs) >= 30 and len({q["id"] for q in qs}) == len(qs)
    assert {q["type"] for q in qs} == {"answerable", "boundary", "no_evidence"}
    keys = {p["policy_key"] for p in evaluate.load_policies()}
    for q in qs:
        assert set(q["expect_keys"]) <= keys, q["id"]  # 질문셋 정답 키가 실제 정책에 있다
        assert (q["type"] == "no_evidence") == (not q["expect_keys"])


def test_concurrent_run_gives_same_records_as_one_by_one(tmp_path):
    """질문을 동시에 물어도 초안 기록이 섞이지 않고, 하나씩 물은 결과와 같다 (응답 시간만 다름)"""
    qs = tmp_path / "q.json"
    qs.write_text(json.dumps({"questions": [
        {"id": f"A{i}", "type": "answerable", "question": q, "expect_keys": ["P001"], "expect_facts": ["50만"]}
        for i, q in enumerate(["자립수당 매월 얼마", "자립수당 지급일", "행정복지센터 신청", "대학 진학 자금", "자립수당 방문 신청"])
    ]}, ensure_ascii=False), encoding="utf-8")
    pol = tmp_path / "p.json"
    pol.write_text(json.dumps([P, Q], ensure_ascii=False), encoding="utf-8")
    kw = dict(mock=True, questions_path=qs, policies_path=pol, out_dir=tmp_path / "out")
    _, one, _ = evaluate.run(workers=1, **kw)
    _, many, _ = evaluate.run(workers=4, **kw)
    strip = lambda rs: [{k: v for k, v in r.__dict__.items() if k != "seconds"} for r in rs]  # noqa: E731
    assert strip(one) == strip(many)


def test_results_are_not_overwritten_and_label_is_used(tmp_path):
    qs = tmp_path / "q.json"
    qs.write_text(json.dumps({"questions": [
        {"id": "A1", "type": "answerable", "question": "자립수당 매월", "expect_keys": ["P001"], "expect_facts": []}]},
        ensure_ascii=False), encoding="utf-8")
    pol = tmp_path / "p.json"
    pol.write_text(json.dumps([P], ensure_ascii=False), encoding="utf-8")
    kw = dict(mock=True, questions_path=qs, policies_path=pol, out_dir=tmp_path / "out", today=date(2026, 11, 22))
    first, second = evaluate.run(**kw)[2], evaluate.run(**kw)[2]
    assert first.name == "rag_eval_20261122_mock" and second.name == "rag_eval_20261122_mock_2"
    assert evaluate.run(label="gpt 4.1/mini", **kw)[2].name == "rag_eval_20261122_mock_gpt-4.1-mini"


def test_cached_embed_reuses_same_text(tmp_path):
    calls = []

    def embed(texts):
        calls.append(list(texts))
        return [[float(len(t))] for t in texts]

    path = tmp_path / "cache.json"
    c = evaluate.CachedEmbed(embed, path, "m")
    assert c(["가", "나나", "가"]) == [[1.0], [2.0], [1.0]] and calls == [["가", "나나"]]
    c.save()
    again = evaluate.CachedEmbed(embed, path, "m")
    assert again(["나나", "다다다"]) == [[2.0], [3.0]] and calls[-1] == ["다다다"]  # 저장한 것은 다시 부르지 않음
    assert (again.reused, again.made) == (1, 1)
    assert evaluate.CachedEmbed(embed, path, "다른모델")(["가"]) and calls[-1] == ["가"]  # 모델이 다르면 새로
