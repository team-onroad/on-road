"""백엔드가 호출하는 RAG 함수의 연결 지점 (docs/api.md 4장).

AI 담당 함수(src/rag/)로 바꿀 때는 아래 import 한 줄만 바꾼다. 방법은 src/backend/README.md 참고.
"""

from collections.abc import Callable

from app.rag_mock import search_policies  # ← AI 담당 함수로 교체할 때 이 줄만 바꾼다

SearchPolicies = Callable[..., dict]


def get_search_policies() -> SearchPolicies:
    """FastAPI 의존성. 테스트에서는 app.dependency_overrides 로 가짜 함수를 넣는다."""
    return search_policies
