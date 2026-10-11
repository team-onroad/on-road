"""RAG 목 함수 (api.md 4장 형식) 와, 목 함수를 그대로 쓴 검색 흐름."""

import inspect

from app.rag_client import get_search_policies
from app.rag_mock import search_policies
from app.schemas.policies import RagOutput
from db.seed import seed_policies
from tests.factories import make_user

ALL_DUMMY = ["D001", "D002", "D003", "D004"]


def test_signature_matches_spec():
    sig = inspect.signature(search_policies)
    assert list(sig.parameters) == ["question", "candidate_policy_keys", "top_k"]
    assert sig.parameters["top_k"].default == 5


def test_rag_client_uses_mock_by_default():
    assert get_search_policies() is search_policies


def test_keyword_match_is_answered():
    out = search_policies("월세 지원이 있나요?", ALL_DUMMY)

    RagOutput.model_validate(out)  # 4장 형식
    assert out["status"] == "answered"
    assert out["answer"]
    assert out["evidences"][0]["policy_key"] == "D002"  # 월세 → 청년 월세 지원
    assert all(set(e) == {"policy_key", "content", "score"} for e in out["evidences"])


def test_particle_is_stripped_when_matching():
    assert search_policies("월세를", ALL_DUMMY)["evidences"][0]["policy_key"] == "D002"


def test_no_match_is_no_evidence():
    out = search_policies("우주여행 보험", ALL_DUMMY)
    assert out == {"status": "no_evidence", "answer": None, "evidences": []}


def test_only_searches_candidates():
    out = search_policies("월세 지원", ["D001", "D004"])
    assert {e["policy_key"] for e in out["evidences"]} <= {"D001", "D004"}
    assert search_policies("월세", ["D001"])["status"] == "no_evidence"


def test_unknown_keys_and_empty_inputs():
    assert search_policies("월세", ["P999"])["status"] == "no_evidence"
    assert search_policies("월세", [])["status"] == "no_evidence"
    assert search_policies("?!", ALL_DUMMY)["status"] == "no_evidence"


def test_top_k_limits_evidences():
    assert len(search_policies("지원", ALL_DUMMY, top_k=2)["evidences"]) == 2


def test_deterministic():
    results = {repr(search_policies("자립준비청년 지원금", ALL_DUMMY)) for _ in range(20)}
    assert len(results) == 1


def test_answer_does_not_make_up_eligibility():
    out = search_policies("지원", ALL_DUMMY)
    assert "신청 가능" not in out["answer"]


def test_search_with_mock_and_dummy_seed(api, db_session, tmp_path):
    """시드한 더미 정책 + 목 RAG 로 /search 전체 흐름 (서울 거주 만 21세)."""
    empty = tmp_path / "policies.json"
    empty.write_text("", encoding="utf-8")
    seed_policies(db_session, path=empty)
    db_session.commit()
    seoul = make_user(db_session, region="서울")
    gyeonggi = make_user(db_session, region="경기")

    res = api.post("/api/search", json={"user_id": str(seoul.id), "question": "월세 지원 받을 수 있나요?"})
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["answer_status"] == "answered"
    assert data["results"][0]["policy_key"] == "D002"
    assert data["results"][0]["eligibility"]["status"] == "match"

    # 경기 거주자에게 서울 정책(D002)은 후보가 아니므로 나오지 않는다
    data = api.post(
        "/api/search", json={"user_id": str(gyeonggi.id), "question": "월세 지원 받을 수 있나요?"}
    ).json()
    assert "D002" not in [r["policy_key"] for r in data["results"]]
