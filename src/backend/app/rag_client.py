"""백엔드가 호출하는 RAG 함수의 연결 지점 (docs/api.md 4장).

.env 의 RAG_MODE 로 고른다. mock(기본)은 app/rag_mock.py, real 은 src/rag/policy_search.py.
real 은 OPENAI_API_KEY 와 Chroma 인덱스(python -m rag.index)가 있어야 한다. 방법은 src/backend/README.md 참고.
"""

import sys
from collections.abc import Callable
from functools import lru_cache

from app.config import PROJECT_ROOT, get_settings
from app.rag_mock import search_policies as mock_search_policies

SearchPolicies = Callable[..., dict]


@lru_cache
def _real_search_policies() -> SearchPolicies:
    # chromadb·openai 는 real 일 때만 불러온다
    src = str(PROJECT_ROOT / "src")
    if src not in sys.path:
        sys.path.insert(0, src)
    from rag.policy_search import search_policies

    return search_policies


def get_search_policies() -> SearchPolicies:
    """FastAPI 의존성. 테스트에서는 app.dependency_overrides 로 가짜 함수를 넣는다."""
    if get_settings().rag_mode == "real":
        return _real_search_policies()
    return mock_search_policies
