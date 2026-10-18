"""정책·지원 공고(공공서비스) 검색.

출처: 공공데이터포털 "행정안전부_대한민국 공공서비스(혜택) 정보" API (정부24 혜택 정보, 이용 제한 없음)
  https://www.data.go.kr/data/15113968/openapi.do
  - 목록: https://api.odcloud.kr/api/gov24/v3/serviceList
  - 상세: https://api.odcloud.kr/api/gov24/v3/serviceDetail
  - .env 의 DATA_GO_KR_SERVICE_KEY 필요 (공공데이터포털에서 활용신청 후 받은 "일반 인증키(Decoding)")
아동권리보장원 게시판은 프로그램 요청을 막아(유효하지 않은 요청) 검색 대상에서 뺐다.

응답 필드 이름은 실제 키로 한 번 확인해야 한다. 이름이 달라도 죽지 않게 여러 후보 이름으로 읽는다.

실행 (src 폴더에서):
    backend\\.venv\\Scripts\\python -m formguide.search "자립수당"
    backend\\.venv\\Scripts\\python -m formguide.search "자립수당" --detail 1        # 1번 결과의 신청방법·구비서류
    backend\\.venv\\Scripts\\python -m formguide.search "자립수당" --attachments 1   # 1번 결과 페이지의 첨부파일
"""

import argparse
import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from formguide.fetch import USER_AGENT
from rag.settings import PROJECT_ROOT

API_BASE = "https://api.odcloud.kr/api/gov24/v3"
_TIMEOUT = 20
MAX_RESULTS = 30
# 검색어를 넣어 볼 필드 (위에서부터, 결과가 없으면 다음 필드)
SEARCH_FIELDS = ("서비스명", "서비스목적요약", "지원대상")

GetJson = Callable[[str], dict]


class SearchError(RuntimeError):
    pass


class _Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=PROJECT_ROOT / ".env", env_file_encoding="utf-8", extra="ignore")
    data_go_kr_service_key: str | None = None


@lru_cache
def _service_key() -> str:
    key = _Settings().data_go_kr_service_key
    if not key:
        raise SearchError(
            "DATA_GO_KR_SERVICE_KEY 가 .env 에 없습니다. 공공데이터포털에서 "
            "'행정안전부_대한민국 공공서비스(혜택) 정보' 활용신청 후 일반 인증키(Decoding)를 넣어 주세요."
        )
    return key


def _pick(row: dict, *names: str) -> str | None:
    for n in names:
        v = row.get(n)
        if isinstance(v, str) and v.strip():
            return v.strip()
    return None


@dataclass(frozen=True)
class Notice:
    service_id: str
    title: str
    agency: str | None
    summary: str | None
    target: str | None
    apply_method: str | None
    deadline: str | None
    url: str | None  # 정부24 상세 페이지
    raw: dict = field(default_factory=dict, compare=False, repr=False)

    @classmethod
    def from_row(cls, row: dict) -> "Notice | None":
        sid, title = _pick(row, "서비스ID", "svcId"), _pick(row, "서비스명", "svcNm")
        if not sid or not title:
            return None
        return cls(
            service_id=sid,
            title=title,
            agency=_pick(row, "소관기관명", "부서명"),
            summary=_pick(row, "서비스목적요약", "서비스목적"),
            target=_pick(row, "지원대상"),
            apply_method=_pick(row, "신청방법"),
            deadline=_pick(row, "신청기한"),
            url=_pick(row, "상세조회URL", "상세조회url"),
            raw=row,
        )


def _default_get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:200]
        raise SearchError(f"공공서비스 API 오류 (HTTP {e.code}): {body}") from None


def _url(op: str, params: dict) -> str:
    return f"{API_BASE}/{op}?{urllib.parse.urlencode({**params, 'serviceKey': _service_key()})}"


def search_notices(keyword: str, limit: int = 10, get_json: GetJson = _default_get_json) -> list[Notice]:
    """검색어로 공공서비스(공고)를 찾는다. 서비스명 → 목적 요약 → 지원대상 순으로 찾아 첫 결과를 쓴다."""
    keyword = keyword.strip()
    if not keyword:
        raise SearchError("검색어가 비어 있습니다.")
    limit = max(1, min(limit, MAX_RESULTS))
    for i, field_name in enumerate(SEARCH_FIELDS):
        try:
            data = get_json(_url("serviceList", {"page": 1, "perPage": limit, f"cond[{field_name}::LIKE]": keyword}))
        except SearchError:
            if i == 0:
                raise
            continue  # 이 필드로는 검색이 안 되는 경우: 다음 필드로
        rows = data.get("data") if isinstance(data, dict) else None
        notices = [n for n in (Notice.from_row(r) for r in rows or [] if isinstance(r, dict)) if n]
        if notices:
            return notices
    return []


# 상세에서 보여줄 필드 (있는 것만)
DETAIL_FIELDS = (
    "서비스명", "소관기관명", "지원대상", "선정기준", "지원내용", "신청방법", "신청기한",
    "구비서류", "접수기관명", "문의처", "온라인신청사이트URL", "법령", "수정일시",
)


def notice_detail(service_id: str, get_json: GetJson = _default_get_json) -> dict:
    data = get_json(_url("serviceDetail", {"page": 1, "perPage": 1, "cond[서비스ID::EQ]": service_id}))
    rows = data.get("data") if isinstance(data, dict) else None
    if not rows:
        raise SearchError(f"상세 정보를 찾지 못했습니다: {service_id}")
    row = rows[0]
    return {k: row[k] for k in DETAIL_FIELDS if isinstance(row.get(k), str) and row[k].strip()}


def main() -> None:
    parser = argparse.ArgumentParser(description="정책·지원 공고(공공서비스) 검색")
    parser.add_argument("keyword")
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--detail", type=int, metavar="N", help="N번 결과의 상세(신청방법·구비서류 등)")
    parser.add_argument("--attachments", type=int, metavar="N", help="N번 결과 상세 페이지의 첨부파일 찾기")
    args = parser.parse_args()

    notices = search_notices(args.keyword, args.limit)
    if not notices:
        print("검색 결과가 없습니다. 더 짧은 검색어(예: '자립', '보호종료')로 다시 찾아 보세요.")
        return
    for i, n in enumerate(notices, 1):
        print(f"{i}. {n.title} — {n.agency or '기관 정보 없음'}")
        if n.summary:
            print(f"   {n.summary[:80]}")
        if n.url:
            print(f"   {n.url}")

    if args.detail:
        print()
        for k, v in notice_detail(notices[args.detail - 1].service_id).items():
            print(f"[{k}]\n{v}\n")
    if args.attachments:
        from formguide.notice import find_attachments, load_page

        chosen = notices[args.attachments - 1]
        if not chosen.url:
            print("상세 페이지 주소가 없습니다.")
            return
        attachments = find_attachments(load_page(chosen.url), chosen.url)
        print()
        if not attachments:
            print("첨부파일을 찾지 못했습니다. 페이지가 자바스크립트로 그려지면 브라우저에서 직접 받아 주세요.")
        for i, a in enumerate(attachments, 1):
            mark = "" if a.downloadable else "  (자동 다운로드 불가)"
            print(f"{i}. [신청서 점수 {a.score:+d}] {a.name}{mark}")


if __name__ == "__main__":
    main()
