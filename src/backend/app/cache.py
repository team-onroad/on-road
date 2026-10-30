"""서버 안 메모리 캐시 (응답 속도 개선, docs/performance.md)

- 오래 걸리는 계산 결과를 잠깐 다시 쓴다: RAG 정책 검색 (같은 질문 · 같은 후보 정책)
- 프로세스 하나 안에서만 쓰고, 서버를 다시 켜면 비워진다
- 개인 정보가 섞인 응답은 담지 않는다 (키에 사용자 정보 대신 후보 정책 목록을 쓴다)
"""

import threading
import time
from collections import OrderedDict
from collections.abc import Callable, Hashable
from typing import Generic, TypeVar

T = TypeVar("T")

_ALL: list["TtlCache"] = []


class TtlCache(Generic[T]):
    """오래된 것부터 버리는 TTL 캐시 (스레드 안전)"""

    def __init__(self, name: str, ttl_seconds: float, max_size: int = 256) -> None:
        self.name = name
        self.ttl = ttl_seconds
        self.max_size = max_size
        self._data: OrderedDict[Hashable, tuple[float, T]] = OrderedDict()
        self._lock = threading.Lock()
        self.hits = 0
        self.misses = 0
        _ALL.append(self)

    def get_or_set(self, key: Hashable, make: Callable[[], T]) -> T:
        now = time.monotonic()
        with self._lock:
            hit = self._data.get(key)
            if hit is not None and now - hit[0] < self.ttl:
                self._data.move_to_end(key)
                self.hits += 1
                return hit[1]
            self.misses += 1
        value = make()  # 오래 걸릴 수 있어 잠그지 않고 만든다 (같은 키가 동시에 와도 결과는 같다)
        with self._lock:
            self._data[key] = (time.monotonic(), value)
            self._data.move_to_end(key)
            while len(self._data) > self.max_size:
                self._data.popitem(last=False)
        return value

    def clear(self) -> None:
        with self._lock:
            self._data.clear()
            self.hits = self.misses = 0

    def __len__(self) -> int:
        return len(self._data)


def clear_all() -> None:
    for c in _ALL:
        c.clear()


def stats() -> dict[str, dict[str, int]]:
    return {c.name: {"size": len(c), "hits": c.hits, "misses": c.misses} for c in _ALL}
