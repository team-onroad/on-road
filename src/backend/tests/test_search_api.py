"""POST /search (api.md 3.5, 4장 / db-schema.md 1장, 5.3).

RAG 함수는 가짜(FakeRag)로 바꿔서 호출 여부·전달 인자·출력 형식을 통제한다.
"""

import logging
import uuid
from datetime import date

import pytest
from sqlalchemy import text

from app.main import app
from app.rag_client import get_search_policies
from app.services.policies import candidate_policies
from app.rules import judge_eligibility
from tests.conftest import TABLES
from tests.factories import make_policy, make_user

QUESTION = "퇴소하고 나서 매달 받을 수 있는 돈이 있나요?"
RESULT_KEYS = {
    "policy_id", "policy_key", "name", "agency", "category", "checked_at", "is_outdated",
    "eligibility", "target_description", "support_content", "support_amount", "support_period",
    "original_text", "easy_text", "source_url", "evidences",
}


_NO_EVIDENCE = {"status": "no_evidence", "answer": None, "evidences": []}


class FakeRag:
    """기본은 no_evidence. output 에 넣은 값(None 포함)을 그대로 돌려준다."""

    def __init__(self):
        self.calls: list[dict] = []
        self.output: object = _NO_EVIDENCE
        self.error: Exception | None = None

    def answer_with(self, *evidences: tuple[str, str], answer="근거 기반 답변"):
        self.output = {
            "status": "answered",
            "answer": answer,
            "evidences": [{"policy_key": k, "content": c, "score": 0.9} for k, c in evidences],
        }

    def __call__(self, question, candidate_policy_keys, top_k=5):
        self.calls.append(
            {"question": question, "candidate_policy_keys": list(candidate_policy_keys), "top_k": top_k}
        )
        if self.error is not None:
            raise self.error
        return self.output


@pytest.fixture
def rag(api):
    fake = FakeRag()
    app.dependency_overrides[get_search_policies] = lambda: fake
    yield fake
    app.dependency_overrides.pop(get_search_policies, None)


def _search(api, user_id, question=QUESTION):
    return api.post("/api/search", json={"user_id": str(user_id), "question": question})


def _assert_error(res, status_code, code):
    assert res.status_code == status_code, res.text
    assert set(res.json()) == {"error"}
    assert res.json()["error"]["code"] == code


# ------------------------------------------------------------------ 정상 응답


def test_answered_groups_evidences_by_policy(api, db_session, rag):
    user = make_user(db_session)
    p1 = make_policy(db_session, policy_key="P001", name="자립수당")
    p2 = make_policy(db_session, policy_key="P002", name="주거 지원", support_amount=None, support_period=None)
    rag.answer_with(("P002", "근거 A"), ("P001", "근거 B"), ("P002", "근거 C"))

    res = _search(api, user.id)

    assert res.status_code == 200, res.text
    data = res.json()
    assert data["answer_status"] == "answered"
    assert data["answer"] == "근거 기반 답변"  # RAG 문장을 그대로 전달
    # evidences 에 처음 나온 순서: P002 → P001, 정책 안의 evidences 도 RAG 순서
    assert [r["policy_key"] for r in data["results"]] == ["P002", "P001"]
    assert data["results"][0]["evidences"] == [{"content": "근거 A"}, {"content": "근거 C"}]
    assert data["results"][1]["evidences"] == [{"content": "근거 B"}]

    r2 = data["results"][0]
    assert set(r2) == RESULT_KEYS
    assert r2["policy_id"] == p2.id
    assert (r2["support_amount"], r2["support_period"]) == (None, None)  # null 가능 필드
    assert r2["eligibility"] == {"status": "match", "age": "match", "region": "match"}
    assert r2["is_outdated"] is False
    nullable = {"support_amount", "support_period"}
    assert all(data["results"][1][k] is not None for k in RESULT_KEYS - nullable)
    assert data["results"][1]["policy_id"] == p1.id


def test_evidences_contain_content_only(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    rag.answer_with(("P001", "근거"))
    evidences = _search(api, user.id).json()["results"][0]["evidences"]
    assert evidences == [{"content": "근거"}]  # policy_key, score 는 빼고 content 만


def test_rag_receives_candidates_and_stripped_question(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    make_policy(db_session, policy_key="P002")

    _search(api, user.id, question=f"  {QUESTION}  ")

    assert rag.calls == [
        {"question": QUESTION, "candidate_policy_keys": ["P001", "P002"], "top_k": 5}
    ]


def test_is_outdated_in_results(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P180", checked_at=date(2026, 4, 7))  # 180일
    make_policy(db_session, policy_key="P181", checked_at=date(2026, 4, 6))  # 181일
    rag.answer_with(("P180", "a"), ("P181", "b"))

    results = _search(api, user.id).json()["results"]

    assert {r["policy_key"]: r["is_outdated"] for r in results} == {"P180": False, "P181": True}


# ------------------------------------------------------- 후보 필터 (노출·연령·지역)


def test_candidates_exclude_invisible_and_excluded_policies(api, db_session, rag):
    user = make_user(db_session, region="서울")  # 만 21세
    make_policy(db_session, policy_key="OK_ALL")  # 전국, 나이 제한 없음
    make_policy(db_session, policy_key="OK_SEOUL", region="서울", age_min=19, age_max=34)
    make_policy(db_session, policy_key="OK_EDGE", age_min=21, age_max=21)
    make_policy(db_session, policy_key="NO_REGION", region="경기")
    make_policy(db_session, policy_key="NO_AGE_LOW", age_min=22)
    make_policy(db_session, policy_key="NO_AGE_HIGH", age_max=20)
    make_policy(db_session, policy_key="NO_INACTIVE", is_active=False)
    make_policy(db_session, policy_key="NO_UNVERIFIED", easy_text_verified=False)
    make_policy(db_session, policy_key="NO_NO_EASY", easy_text_verified=False, easy_text=None)

    _search(api, user.id)

    assert rag.calls[0]["candidate_policy_keys"] == ["OK_ALL", "OK_SEOUL", "OK_EDGE"]


def test_candidates_follow_birthday(api, db_session, rag, clock):
    make_policy(db_session, policy_key="P_19", age_min=19, checked_at=date(2026, 3, 1))
    clock.today = date(2026, 3, 14)  # 19번째 생일 전날 → 만 18세
    user = make_user(db_session, today=clock.today, birth_date=date(2007, 3, 15))

    _search(api, user.id)
    clock.today = date(2026, 3, 15)  # 생일 당일 → 만 19세
    _search(api, user.id)

    # 첫 번째 검색은 후보가 비어 RAG 를 호출하지 않았고, 생일 당일에만 후보가 생긴다.
    assert [c["candidate_policy_keys"] for c in rag.calls] == [["P_19"]]


def test_sql_candidate_filter_matches_eligibility_rule(db_session):
    """DB 필터(1단계)와 응답 판정(eligibility)이 같은 결과를 내는지 모든 조합으로 확인."""
    ranges = [(None, None), (21, None), (22, None), (None, 21), (None, 20), (21, 21), (19, 34), (10, 20)]
    regions = ["전국", "서울", "부산"]
    expected = []
    for i, (lo, hi) in enumerate(ranges):
        for j, region in enumerate(regions):
            key = f"G{i}{j}"
            make_policy(db_session, policy_key=key, age_min=lo, age_max=hi, region=region)
            if judge_eligibility(21, "서울", lo, hi, region)["status"] != "excluded":
                expected.append(key)

    got = [p.policy_key for p in candidate_policies(db_session, 21, "서울")]

    assert got == expected
    assert len(expected) == 10  # 나이 match 5개 × 지역 match 2개


# ----------------------------------------------------------------- no_evidence


def test_empty_candidates_skip_rag(api, db_session, rag):
    user = make_user(db_session, region="서울")
    make_policy(db_session, policy_key="NO_REGION", region="부산")
    make_policy(db_session, policy_key="NO_INACTIVE", is_active=False)
    rag.answer_with(("NO_REGION", "근거"))

    res = _search(api, user.id)

    assert res.status_code == 200
    assert res.json() == {"answer_status": "no_evidence", "answer": None, "results": []}
    assert rag.calls == []  # 후보가 없으면 RAG 를 호출하지 않음


def test_no_policies_at_all_skip_rag(api, db_session, rag):
    user = make_user(db_session)
    assert _search(api, user.id).json()["answer_status"] == "no_evidence"
    assert rag.calls == []


def test_rag_no_evidence(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    res = _search(api, user.id)
    assert res.json() == {"answer_status": "no_evidence", "answer": None, "results": []}
    assert len(rag.calls) == 1


def test_out_of_candidate_policy_keys_are_ignored(api, db_session, rag):
    user = make_user(db_session, region="서울")
    make_policy(db_session, policy_key="P001")
    make_policy(db_session, policy_key="P_BUSAN", region="부산")  # 후보 아님
    make_policy(db_session, policy_key="P_OFF", is_active=False)  # 후보 아님
    rag.answer_with(("P_BUSAN", "x"), ("P001", "근거"), ("UNKNOWN", "y"), ("P_OFF", "z"))

    results = _search(api, user.id).json()["results"]

    assert [r["policy_key"] for r in results] == ["P001"]
    assert results[0]["evidences"] == [{"content": "근거"}]


def test_all_evidences_outside_candidates_becomes_no_evidence(api, db_session, rag):
    user = make_user(db_session, region="서울")
    make_policy(db_session, policy_key="P001")
    rag.answer_with(("UNKNOWN", "근거 없는 답변의 근거"))

    res = _search(api, user.id)

    assert res.json() == {"answer_status": "no_evidence", "answer": None, "results": []}


# ------------------------------------------------------------------- 503 오류


def test_rag_exception_returns_503(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    rag.error = RuntimeError("chroma down")
    _assert_error(_search(api, user.id), 503, "RAG_UNAVAILABLE")


@pytest.mark.parametrize(
    "output",
    [
        None,
        "answered",
        {},
        {"status": "maybe", "answer": "a", "evidences": []},
        {"status": "answered", "answer": None, "evidences": [{"policy_key": "P001", "content": "c"}]},
        {"status": "answered", "answer": "", "evidences": [{"policy_key": "P001", "content": "c"}]},
        {"status": "answered", "answer": "a"},  # evidences 누락
        {"status": "answered", "answer": "a", "evidences": [{"policy_key": "P001"}]},  # content 누락
        {"status": "answered", "answer": "a", "evidences": [{"content": "c"}]},  # policy_key 누락
        {"status": "answered", "answer": "a", "evidences": "P001"},
    ],
)
def test_rag_malformed_output_returns_503(api, db_session, rag, output):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    rag.output = output
    _assert_error(_search(api, user.id), 503, "RAG_UNAVAILABLE")


def test_rag_score_is_optional(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    rag.output = {"status": "answered", "answer": "a", "evidences": [{"policy_key": "P001", "content": "c"}]}
    assert _search(api, user.id).json()["answer_status"] == "answered"


# --------------------------------------------------------------- 요청 검증·404


@pytest.mark.parametrize("user_id", [str(uuid.uuid4()), "not-a-uuid", ""])
def test_unknown_user_returns_404(api, db_session, rag, user_id):
    _assert_error(_search(api, user_id), 404, "USER_NOT_FOUND")


@pytest.mark.parametrize("question", ["", "   ", "가" * 501, None, 123])
def test_unknown_user_takes_priority_over_invalid_question(api, rag, question):
    res = api.post("/api/search", json={"user_id": "not-a-uuid", "question": question})
    _assert_error(res, 404, "USER_NOT_FOUND")


@pytest.mark.parametrize(
    "body",
    [
        {"question": QUESTION},  # user_id 누락
        {"user_id": 123, "question": QUESTION},  # 문자열 아님
        ["not", "an", "object"],
    ],
)
def test_user_id_missing_or_not_string_is_422(api, db_session, rag, body):
    _assert_error(api.post("/api/search", json=body), 422, "VALIDATION_ERROR")


@pytest.mark.parametrize(
    "body",
    [
        {"question": ""},
        {"question": "   "},
        {"question": "가" * 501},
        {"question": None},
        {"question": 123},
        {},  # question 누락
        {"question": QUESTION, "top_k": 3},  # 명세에 없는 필드
    ],
)
def test_invalid_question_is_422(api, db_session, rag, body):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    res = api.post("/api/search", json={"user_id": str(user.id), **body})
    _assert_error(res, 422, "VALIDATION_ERROR")
    assert rag.calls == []


def test_question_500_chars_is_allowed(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    assert _search(api, user.id, question="가" * 500).status_code == 200


def test_malformed_json_is_422(api, rag):
    res = api.post("/api/search", content="{bad", headers={"content-type": "application/json"})
    _assert_error(res, 422, "VALIDATION_ERROR")


# --------------------------------------------------------- 개인정보·질문 보관 안 함


def test_question_is_not_stored_or_logged(api, db_session, rag, caplog):
    secret = "비밀질문-월세-12345"
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    rag.answer_with(("P001", "근거"))
    caplog.set_level(logging.DEBUG)

    _search(api, user.id, question=secret)
    rag.error = RuntimeError(f"failed for {secret}")  # 예외 메시지에 질문이 들어 있어도
    _search(api, user.id, question=secret)

    assert secret not in caplog.text
    for table in TABLES:
        rows = db_session.execute(text(f"SELECT * FROM {table}")).all()
        assert all(secret not in str(row) for row in rows)


def test_search_never_says_apply_possible(api, db_session, rag):
    user = make_user(db_session)
    make_policy(db_session, policy_key="P001")
    rag.answer_with(("P001", "근거"))
    assert "신청 가능" not in _search(api, user.id).text
