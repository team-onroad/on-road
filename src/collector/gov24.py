"""정부24 공공서비스(혜택) API 에서 자립준비청년 관련 지원제도를 모은다.

API·인증키는 formguide/search.py 와 같다 (.env 의 DATA_GO_KR_SERVICE_KEY).
- serviceList: 키워드마다 서비스명 · 지원대상 에서 찾아 서비스ID 를 모은다 (겹치면 한 번)
- serviceDetail: 서비스ID 마다 상세 (지원대상 · 지원내용 · 신청방법 · 구비서류 …)
- supportConditions: 나이 같은 지원조건 (있으면 확인할 거리로만 쓴다)
요청 사이에 잠깐 쉬어 API 에 부담을 주지 않는다. 받은 원본은 data/collected/gov24/{서비스ID}.json 에 남긴다 (대조·감사용).

일시적인 오류(HTTP 429 트래픽 초과 · 5xx · 시간 초과 · 연결 끊김)는 잠깐 쉬었다가 다시 묻는다 (1초 → 2초 → 4초).
그래도 안 되면 상세 · 지원조건은 그 서비스만 건너뛰고(보고서에 남김), 목록은 오류를 올린다 (목록이 빠지면 수집 전체가 틀려지므로).
"""

import re
import time
import urllib.error
from collections.abc import Callable, Iterable
from pathlib import Path

from formguide.search import SearchError, _default_get_json, _url

GetJson = Callable[[str], dict]

# 자립준비청년 · 보호대상아동이 받을 수 있는 지원을 찾는 기본 검색어 (대상 기준. "장학금"처럼 넓은 말은 관련 없는 결과가 많다)
DEFAULT_KEYWORDS = ("자립준비청년", "보호종료", "보호대상아동", "가정위탁", "아동복지시설", "자립지원")
LIST_FIELDS = ("서비스명", "지원대상")
PER_PAGE = 100
MAX_PAGES = 5
PAUSE_SECONDS = 0.2
# 상세 · 지원조건을 동시에 받는 수 (공공데이터 API 에 부담을 주지 않게 적게)
DETAIL_WORKERS = 4
# 일시적 오류 다시 묻기: 횟수와 첫 대기(초). 대기는 두 배씩 (1 → 2 → 4)
RETRIES = 3
RETRY_BASE_SECONDS = 1.0
_TRANSIENT_RE = re.compile(r"HTTP (429|5\d\d)")


def is_transient(e: BaseException) -> bool:
    """다시 물으면 될 수 있는 오류인지 (한도 초과 · 서버 오류 · 시간 초과 · 연결 끊김)"""
    if isinstance(e, SearchError):
        return bool(_TRANSIENT_RE.search(str(e)))
    return isinstance(e, (TimeoutError, ConnectionError, urllib.error.URLError))


def get_with_retry(get_json: GetJson, url: str, sleep: Callable[[float], None] = time.sleep) -> dict:
    for attempt in range(RETRIES + 1):
        try:
            return get_json(url)
        except Exception as e:
            if attempt == RETRIES or not is_transient(e):
                raise
            sleep(RETRY_BASE_SECONDS * 2 ** attempt)
    raise AssertionError("도달하지 않음")


def _rows(data: object) -> list[dict]:
    rows = data.get("data") if isinstance(data, dict) else None
    return [r for r in rows or [] if isinstance(r, dict)]


def find_service_ids(keywords: Iterable[str], get_json: GetJson = _default_get_json,
                     pause: float = PAUSE_SECONDS) -> list[str]:
    """키워드로 찾은 서비스ID (처음 나온 순서, 중복 없음)"""
    ids: dict[str, None] = {}
    for kw in keywords:
        kw = kw.strip()
        if not kw:
            continue
        for field_name in LIST_FIELDS:
            for page in range(1, MAX_PAGES + 1):
                try:
                    data = get_with_retry(get_json, _url("serviceList", {"page": page, "perPage": PER_PAGE,
                                                                          f"cond[{field_name}::LIKE]": kw}))
                except SearchError as e:
                    if is_transient(e):
                        raise  # 다시 물어도 한도 초과 → 목록이 빠진 채로 수집하지 않는다
                    break  # 이 필드로는 찾을 수 없는 경우
                rows = _rows(data)
                for r in rows:
                    sid = r.get("서비스ID")
                    if isinstance(sid, str) and sid.strip():
                        ids.setdefault(sid.strip())
                time.sleep(pause)
                if len(rows) < PER_PAGE:
                    break
    return list(ids)


def fetch_detail(service_id: str, get_json: GetJson = _default_get_json) -> dict | None:
    rows = _rows(get_with_retry(get_json, _url("serviceDetail", {"page": 1, "perPage": 1,
                                                                 "cond[서비스ID::EQ]": service_id})))
    return rows[0] if rows else None


def fetch_conditions(service_id: str, get_json: GetJson = _default_get_json) -> dict | None:
    """지원조건. 없거나 오류면 None (수집은 계속한다)"""
    try:
        rows = _rows(get_with_retry(get_json, _url("supportConditions", {"page": 1, "perPage": 1,
                                                                         "cond[서비스ID::EQ]": service_id})))
    except Exception:  # 원본 보관용이라 못 받아도 수집은 계속
        return None
    return rows[0] if rows else None


def save_raw(raw_dir: Path, service_id: str, detail: dict, conditions: dict | None) -> None:
    from collector.merge import write_json

    write_json(raw_dir / f"{service_id}.json", {"detail": detail, "conditions": conditions})
