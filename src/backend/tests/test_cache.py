"""응답 속도: 메모리 캐시 · gzip · Cache-Control (docs/performance.md)"""

import time

from app.cache import TtlCache
from app.services import policies as policy_service
from tests.factories import make_policy, make_user


def test_ttl_cache_reuses_until_expired():
    c: TtlCache[int] = TtlCache("t", ttl_seconds=0.2, max_size=2)
    calls = []
    make = lambda: calls.append(1) or len(calls)  # noqa: E731
    assert c.get_or_set("a", make) == 1
    assert c.get_or_set("a", make) == 1 and (c.hits, c.misses) == (1, 1)
    time.sleep(0.25)
    assert c.get_or_set("a", make) == 2  # 시간이 지나면 다시 만든다


def test_ttl_cache_drops_oldest():
    c: TtlCache[str] = TtlCache("t2", ttl_seconds=60, max_size=2)
    for k in "abc":
        c.get_or_set(k, lambda k=k: k)
    assert len(c) == 2
    made = []
    c.get_or_set("a", lambda: made.append("a") or "a")
    assert made == ["a"]  # 가장 오래된 a 는 버려졌다


def test_errors_are_not_cached():
    c: TtlCache[int] = TtlCache("t3", ttl_seconds=60)

    def boom() -> int:
        raise RuntimeError

    for _ in range(2):
        try:
            c.get_or_set("x", boom)
        except RuntimeError:
            pass
    assert c.misses == 2 and len(c) == 0


def test_static_get_has_cache_header(client):
    res = client.get("/api/coach/roleplay/scenarios")
    assert res.headers["cache-control"] == "public, max-age=600"
    assert client.get("/api/simulations/criteria").headers["cache-control"] == "public, max-age=3600"


def test_user_get_is_not_cached(client):
    res = client.get("/api/health")
    assert res.headers["cache-control"] == "no-cache"


def test_large_response_is_gzipped(client):
    res = client.get("/api/coach/roleplay/scenarios", headers={"accept-encoding": "gzip"})
    assert res.headers.get("content-encoding") == "gzip"
    assert res.json()["scenarios"]  # 내용은 그대로


def test_same_search_reuses_rag(api, db_session, monkeypatch):
    """같은 질문 · 같은 후보 정책이면 두 번째는 RAG 를 부르지 않는다 (서버 기본 RAG 함수일 때)"""
    make_policy(db_session, policy_key="allowance")
    user = make_user(db_session)
    calls = []
    real = policy_service._call_rag_now

    def counting(rag, question, keys):
        calls.append(question)
        return real(rag, question, keys)

    monkeypatch.setattr(policy_service, "_call_rag_now", counting)
    body = {"user_id": str(user.id), "question": "자립수당   받을 수 있나요?"}
    first = api.post("/api/search", json=body).json()
    second = api.post("/api/search", json={**body, "question": "자립수당 받을 수 있나요?"}).json()
    assert len(calls) == 1 and first == second
