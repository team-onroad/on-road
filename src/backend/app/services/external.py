"""진로 외부 정보: 큐넷 시험일정 · 고용24 훈련과정 · 커리어넷 직업정보 · 고용24 채용공고

- 키는 서버 환경변수로만 받는다 (app/config.py). 키가 없으면 available=false
- 바깥 서버가 느리거나 실패해도 앱 화면이 멈추지 않게 짧은 제한시간, 실패는 available=false + message
- 같은 질의는 6시간 동안 메모리 캐시 (실패한 응답은 담지 않는다)
"""

from __future__ import annotations

import json
import logging
import re
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import date, timedelta
from typing import Any

from app.cache import TtlCache
from app.config import get_settings

log = logging.getLogger(__name__)

QNET_URL = "https://apis.data.go.kr/B490007/qualExamSchd/getQualExamSchdList"
CAREERNET_JOBS_URL = "https://www.career.go.kr/cnet/front/openapi/jobs.json"
CAREERNET_JOB_URL = "https://www.career.go.kr/cnet/front/openapi/job.json"
WORK24_TRAINING_URL = "https://www.work24.go.kr/cm/openApi/call/hr/callOpenApiSvcInfo310L01.do"
WORK24_TRAINING_DETAIL_URL = "https://www.work24.go.kr/cm/openApi/call/hr/callOpenApiSvcInfo310L02.do"
WORK24_JOBS_URL = "https://www.work24.go.kr/cm/openApi/call/wk/callOpenApiSvcInfo210L01.do"
WORK24_JOB_LIST_URL = "https://www.work24.go.kr/cm/openApi/call/wk/callOpenApiSvcInfo212L01.do"
WORK24_JOB_SUMMARY_URL = "https://www.work24.go.kr/cm/openApi/call/wk/callOpenApiSvcInfo212D01.do"
WORK24_JOB_DO_URL = "https://www.work24.go.kr/cm/openApi/call/wk/callOpenApiSvcInfo212D02.do"
WORK24_DUTY_URL = "https://www.work24.go.kr/cm/openApi/call/wk/callOpenApiSvcInfo215L11.do"

SOURCES = {
    "exams": "큐넷 국가자격 시험일정",
    "trainings": "고용24 국민내일배움카드 훈련과정",
    "job_info": "커리어넷 직업백과",
    "job_info_work24": "고용24 직업정보",
    "training": "고용24 국민내일배움카드 훈련과정",
    "duties": "고용24 직무정보",
    "postings": "고용24 채용정보",
}

_cache: TtlCache[dict[str, Any]] = TtlCache("external", ttl_seconds=6 * 3600, max_size=512)

# 시·도 이름 → 주소에서 찾을 짧은 이름
REGION_SHORT = {
    "서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구", "인천광역시": "인천",
    "광주광역시": "광주", "대전광역시": "대전", "울산광역시": "울산", "세종특별자치시": "세종",
    "경기도": "경기", "강원특별자치도": "강원", "강원도": "강원", "충청북도": "충북", "충청남도": "충남",
    "전북특별자치도": "전북", "전라북도": "전북", "전라남도": "전남", "경상북도": "경북",
    "경상남도": "경남", "제주특별자치도": "제주",
}


class ExternalError(Exception):
    pass


def _get(url: str, params: dict[str, Any]) -> bytes:
    """바깥 API 호출. 키가 들어간 주소는 로그에 남기지 않는다"""
    query = urllib.parse.urlencode({k: v for k, v in params.items() if v not in (None, "")})
    req = urllib.request.Request(f"{url}?{query}", headers={"User-Agent": "onroad-api/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=get_settings().external_timeout_seconds) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        reason = f"HTTP {e.code} {_error_reason(e.read())}".strip()
        log.warning("external call failed: %s (%s)", url, reason)
        raise ExternalError(reason) from e
    except Exception as e:  # noqa: BLE001 - 바깥 서버 오류는 모두 같은 응답으로
        log.warning("external call failed: %s (%s)", url, type(e).__name__)
        raise ExternalError(str(type(e).__name__)) from e


def _error_reason(raw: bytes) -> str:
    """공공데이터포털 · 고용24 오류 응답에서 사유 코드만 꺼낸다 (키 값은 담기지 않는다)"""
    text = raw[:2000].decode("utf-8", "replace")
    for tag in ("returnAuthMsg", "resultMsg", "errMsg", "message"):
        m = re.search(rf"<{tag}>([^<]+)</{tag}>", text) or re.search(rf'"{tag}"\s*:\s*"([^"]+)"', text)
        if m:
            return m.group(1).strip()[:120]
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", text)).strip()[:80]


def _result(kind: str, items: list[dict[str, Any]] | None = None, *, available: bool = True,
            message: str | None = None, **extra: Any) -> dict[str, Any]:
    return {"available": available, "source": SOURCES[kind], "items": items or [], "message": message, **extra}


def _missing_key(kind: str) -> dict[str, Any]:
    return _result(kind, available=False, message="서버에 API 키가 설정되지 않았습니다.")


def _failed(kind: str, reason: str | None = None) -> dict[str, Any]:
    return _result(kind, available=False, message="정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.",
                   reason=reason)


def _cached(key: tuple, make) -> dict[str, Any]:
    try:
        return _cache.get_or_set(key, make)
    except ExternalError as e:
        return _failed(key[0], str(e) or None)


def _ymd(v: Any) -> str | None:
    s = re.sub(r"\D", "", str(v or ""))
    if len(s) >= 8:
        return f"{s[:4]}-{s[4:6]}-{s[6:8]}"
    return None


def _text(el: ET.Element | None, tag: str) -> str | None:
    if el is None:
        return None
    v = el.findtext(tag)
    v = v.strip() if v else None
    return v or None


def _int(v: Any) -> int | None:
    s = re.sub(r"[^\d]", "", str(v or ""))
    return int(s) if s else None


def region_short(region: str | None) -> str | None:
    if not region:
        return None
    region = region.strip()
    return REGION_SHORT.get(region, region[:2])


# ── 큐넷 시험일정 ────────────────────────────────────────────

GRADES = ("기술사", "기능장", "산업기사", "기능사", "기사")


def grade_of(cert: str) -> str | None:
    """자격증 이름 끝으로 국가기술자격 등급을 찾는다. 등급이 없으면 큐넷 정기시험 대상이 아니다"""
    name = cert.replace(" ", "")
    for g in GRADES:
        if name.endswith(g):
            return g
    return None


QNET_PAGE_SIZE = 50  # 큐넷 한 페이지 최대 50개 (넘으면 930 오류)
QNET_CODES_URL = "http://openapi.q-net.or.kr/api/service/rest/InquiryListNationalQualifcationSVC/getList"

_code_cache: TtlCache[dict[str, tuple[str, str, str]]] = TtlCache("qnet-codes", ttl_seconds=24 * 3600, max_size=2)


def _qnet_key() -> str:
    # 포털이 주는 키가 인코딩된 것(%2B 등)이든 아니든 같은 값으로 보낸다
    return urllib.parse.unquote(get_settings().qnet_service_key or "")


def _norm_cert(name: str) -> str:
    """종목명 비교용: 띄어쓰기 · 괄호 설명((검정형) 등) 없이"""
    return re.sub(r"\(.*?\)|\s", "", name)


def _qnet_codes() -> dict[str, tuple[str, str, str]]:
    """국가자격 종목 목록: 정리한 종목명 → (종목코드, 원래 종목명, 자격구분 T/S). 하루 동안 담아 둔다"""
    def make() -> dict[str, tuple[str, str, str]]:
        root = _xml(_get(QNET_CODES_URL, {"serviceKey": _qnet_key()}))
        codes: dict[str, tuple[str, str, str]] = {}
        for el in root.iter("item"):
            code, name = _text(el, "jmcd"), _text(el, "jmfldnm")
            if code and name:
                codes.setdefault(_norm_cert(name), (code, name, _text(el, "qualgbcd") or "T"))
        if not codes:
            raise ExternalError(_error_reason(ET.tostring(root)) or "no codes")
        return codes

    return _code_cache.get_or_set("all", make)


def find_cert_code(cert: str) -> tuple[str, str, str] | None:
    """자격증 이름 → (종목코드, 종목명, 자격구분). 이름이 같은 것 → 앞부분이 같은 것 순. 목록을 못 받으면 None"""
    try:
        codes = _qnet_codes()
    except ExternalError:
        return None
    key = _norm_cert(cert)
    if key in codes:
        return codes[key]
    starts = sorted(k for k in codes if k.startswith(key))
    return codes[starts[0]] if starts else None


def _qnet_page(year: int, page: int, qualgb: str = "T", jm_cd: str | None = None) -> tuple[list[dict[str, Any]], int]:
    raw = _get(QNET_URL, {
        "serviceKey": _qnet_key(), "numOfRows": QNET_PAGE_SIZE, "pageNo": page,
        "dataFormat": "json", "implYy": year, "qualgbCd": qualgb, "jmCd": jm_cd,
    })
    try:
        data = json.loads(raw)
    except ValueError as e:
        raise ExternalError(_error_reason(raw) or "bad json") from e
    header = data.get("header") or data.get("response", {}).get("header") or {}
    if header.get("resultCode") not in (None, "00", "0"):
        raise ExternalError(f"{header.get('resultCode')} {header.get('resultMsg') or ''}".strip())
    body = (data.get("body") or data.get("response", {}).get("body") or {})
    items = body.get("items") or []
    if isinstance(items, dict):
        items = items.get("item") or []
    if isinstance(items, dict):
        items = [items]
    return items, _int(body.get("totalCount")) or len(items)


def _qnet_rounds(year: int, qualgb: str = "T", jm_cd: str | None = None) -> list[dict[str, Any]]:
    """한 해 회차 전부. 응답이 날짜 순이 아니라서 끝까지 받은 뒤 정렬한다 (종목코드가 있으면 보통 한 페이지)"""
    items, total = _qnet_page(year, 1, qualgb, jm_cd)
    page = 1
    while len(items) < total and page < 6:
        page += 1
        more, _ = _qnet_page(year, page, qualgb, jm_cd)
        if not more:
            break
        items += more
    return items


def _round(it: dict[str, Any], fallback: str) -> dict[str, Any]:
    return {
        "name": str(it.get("description") or "").strip() or fallback,
        "year": _int(it.get("implYy")),
        "round": _int(it.get("implSeq")),
        "written_apply_start": _ymd(it.get("docRegStartDt")),
        "written_apply_end": _ymd(it.get("docRegEndDt")),
        "written_exam_start": _ymd(it.get("docExamStartDt")),
        "written_exam_end": _ymd(it.get("docExamEndDt")),
        "written_pass": _ymd(it.get("docPassDt")),
        "practical_apply_start": _ymd(it.get("pracRegStartDt")),
        "practical_apply_end": _ymd(it.get("pracRegEndDt")),
        "practical_exam_start": _ymd(it.get("pracExamStartDt")),
        "practical_exam_end": _ymd(it.get("pracExamEndDt")),
        "practical_pass": _ymd(it.get("pracPassDt")),
    }


_DATE_KEYS = ("written_apply_start", "written_apply_end", "written_exam_start", "written_exam_end", "written_pass",
              "practical_apply_start", "practical_apply_end", "practical_exam_start", "practical_exam_end",
              "practical_pass")


def _upcoming(rounds: list[dict[str, Any]], today: date, limit: int = 4) -> list[dict[str, Any]]:
    """끝난 회차는 빼고, 아직 오지 않은 날짜가 가장 이른 회차부터"""
    t = today.isoformat()

    def next_date(r: dict[str, Any]) -> str:
        upcoming = [r[k] for k in _DATE_KEYS if r[k] and r[k] >= t]
        return min(upcoming) if upcoming else "9999"

    alive = [r for r in rounds if next_date(r) != "9999"]
    return sorted(alive, key=next_date)[:limit]


def exam_schedule(cert: str, today: date) -> dict[str, Any]:
    if not get_settings().qnet_service_key:
        return _missing_key("exams")
    found = find_cert_code(cert)
    grade = grade_of(cert)

    if found:
        code, jm_name, qualgb = found

        def make_one() -> dict[str, Any]:
            rounds = [_round(it, jm_name) for year in (today.year, today.year + 1)
                      for it in _qnet_rounds(year, qualgb, code)]
            return _result("exams", _upcoming(rounds, today), grade=grade, cert=jm_name, code=code,
                           url="https://www.q-net.or.kr", note=None)

        return _cached(("exams", code, today.isoformat()), make_one)

    if grade is None:
        return _result("exams", message="큐넷 국가기술자격 정기시험 일정이 없는 자격증입니다. 시행 기관 공지를 확인해 주세요.",
                       grade=None)

    # 종목 목록을 못 받았을 때: 같은 등급(기능사 등) 전체 회차로 대신 보여 준다
    def make() -> dict[str, Any]:
        rounds: list[dict[str, Any]] = []
        for year in (today.year, today.year + 1):
            for it in _qnet_rounds(year):
                desc = str(it.get("description") or "")
                # "기사" 를 찾을 때 "산업기사" 가 섞이지 않게
                if grade == "기사" and ("산업기사" in desc or "기사" not in desc.replace("산업기사", "")):
                    continue
                if grade != "기사" and grade not in desc:
                    continue
                rounds.append(_round(it, f"{year}년 {grade}"))
        return _result("exams", _upcoming(rounds, today), grade=grade, cert=None, code=None,
                       url="https://www.q-net.or.kr",
                       note=f"{grade} 등급 전체의 회차입니다. 종목마다 시행 회차가 다를 수 있어 큐넷에서 확인해 주세요.")

    return _cached(("exams", grade, today.isoformat()), make)


# ── 고용24 훈련과정 ──────────────────────────────────────────

def trainings(keyword: str, region: str | None, today: date) -> dict[str, Any]:
    s = get_settings()
    if not s.work24_training_key:
        return _missing_key("trainings")
    keyword = keyword.strip()
    short = region_short(region)

    def make() -> dict[str, Any]:
        raw = _get(WORK24_TRAINING_URL, {
            "authKey": s.work24_training_key, "returnType": "XML", "outType": 1,
            "pageNum": 1, "pageSize": 100,
            "srchTraStDt": today.strftime("%Y%m%d"),
            "srchTraEndDt": (today + timedelta(days=183)).strftime("%Y%m%d"),
            "sort": "ASC", "sortCol": 2, "srchTraProcessNm": keyword,
        })
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as e:
            raise ExternalError("bad xml") from e
        items = []
        for el in root.iter("scn_list"):
            title = _text(el, "title")
            if not title:
                continue
            address = _text(el, "address")
            items.append({
                "id": _text(el, "trprId"),
                "round": _text(el, "trprDegr"),
                "institution_id": _text(el, "trainstCstId"),
                "title": title,
                "institution": _text(el, "subTitle"),
                "address": address,
                "phone": _text(el, "telNo"),
                "start": _ymd(_text(el, "traStartDate")),
                "end": _ymd(_text(el, "traEndDate")),
                # 본인부담액은 목록에 없다 (과정 소개 my_cost)
                "cost": _int(_text(el, "realMan")) or _int(_text(el, "courseMan")),
                "certificate": _text(el, "certificate"),
                "employment_rate": _text(el, "eiEmplRate6") or _text(el, "eiEmplRate3"),
                "url": _text(el, "titleLink"),
                "nearby": bool(short and address and short in address),
            })
        # 내 지역 과정을 먼저, 그다음 시작일 순
        items.sort(key=lambda x: (not x["nearby"], x["start"] or "9999"))
        return _result("trainings", items[:20], total=_int(root.findtext(".//scn_cnt")),
                       url="https://www.work24.go.kr")

    return _cached(("trainings", keyword, short, today.isoformat()), make)


# ── 커리어넷 직업정보 ────────────────────────────────────────

def _as_list(v: Any) -> list[Any]:
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def _strip_html(v: Any) -> str | None:
    if v is None:
        return None
    s = re.sub(r"</?(b|i|u|em|strong|span|font)\b[^>]*>", "", str(v), flags=re.I)  # 글자 꾸밈은 띄어쓰기 없이
    s = re.sub(r"<[^>]+>", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s or None


def _names(v: Any, key: str) -> list[str]:
    out = []
    for it in _as_list(v):
        s = _strip_html(it.get(key) if isinstance(it, dict) else it)
        if s and s not in out:
            out.append(s)
    return out


def _split_names(v: Any) -> list[str]:
    s = _strip_html(v)
    if not s:
        return []
    return [p.strip() for p in re.split(r"[,，]", s) if p.strip()]


def job_info(name: str) -> dict[str, Any]:
    """커리어넷 키가 있으면 커리어넷, 없으면 고용24 직업정보"""
    s = get_settings()
    if not s.careernet_api_key:
        if s.work24_job_info_key:
            return work24_job_info(name)
        return _missing_key("job_info")
    name = name.strip()

    def make() -> dict[str, Any]:
        raw = _get(CAREERNET_JOBS_URL, {"apiKey": s.careernet_api_key, "searchJobNm": name, "pageIndex": 1})
        try:
            jobs = _as_list(json.loads(raw).get("jobs"))
        except (ValueError, AttributeError) as e:
            raise ExternalError("bad json") from e
        if not jobs:
            return _result("job_info", message="커리어넷에 같은 이름의 직업이 없습니다.", job=None)
        pick = next((j for j in jobs if str(j.get("job_nm", "")).strip() == name), jobs[0])
        seq = pick.get("job_cd")
        raw = _get(CAREERNET_JOB_URL, {"apiKey": s.careernet_api_key, "seq": seq})
        try:
            d = json.loads(raw)
        except ValueError as e:
            raise ExternalError("bad json") from e
        base = d.get("baseInfo") or {}
        ready = d.get("jobReadyList") or {}
        job = {
            "id": str(seq),
            "name": _strip_html(base.get("job_nm")) or _strip_html(pick.get("job_nm")),
            "summary": _strip_html(pick.get("work")),
            "works": _names(d.get("workList"), "work"),
            "abilities": _names(d.get("abilityList"), "ability_name"),
            "aptitudes": _names(d.get("aptitudeList"), "aptitude"),
            "interests": _names(d.get("interestList"), "interest"),
            "certificates": _names(d.get("certiList"), "certi") or _split_names(base.get("rel_cert_nm")),
            "majors": _names(d.get("departList"), "depart_name"),
            "related_jobs": _split_names(base.get("rel_job_nm")),
            "prepare": {
                "recruit": _names(ready.get("recruit"), "recruit"),
                "certificate": _names(ready.get("certificate"), "certificate"),
                "training": _names(ready.get("training"), "training"),
                "curriculum": _names(ready.get("curriculum"), "curriculum"),
            },
            "forecast": _names(d.get("forecastList"), "forecast"),
            "wage": _strip_html(base.get("wage")),
            "wage_source": _strip_html(base.get("wage_source")),
            "satisfaction": _strip_html(base.get("satisfication")),
            "work_life_balance": _strip_html(base.get("wlb")),
            "orgs": [
                {"name": _strip_html(o.get("rel_org")), "url": _strip_html(o.get("rel_org_url"))}
                for o in _as_list(d.get("jobRelOrgList")) if isinstance(o, dict) and o.get("rel_org")
            ],
            "url": f"https://www.career.go.kr/cloud/w/job/view?seq={seq}",
        }
        return _result("job_info", job=job)

    return _cached(("job_info", name), make)


# ── 고용24 채용공고 ──────────────────────────────────────────

def postings(keyword: str, region: str | None) -> dict[str, Any]:
    s = get_settings()
    if not s.work24_jobs_key:
        return _missing_key("postings")
    keyword = keyword.strip()
    short = region_short(region)

    def make() -> dict[str, Any]:
        raw = _get(WORK24_JOBS_URL, {
            "authKey": s.work24_jobs_key, "callTp": "L", "returnType": "xml",
            "startPage": 1, "display": 100, "keyword": keyword,
        })
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as e:
            raise ExternalError("bad xml") from e
        items = []
        for el in root.iter("wanted"):
            title = _text(el, "title")
            if not title:
                continue
            reg = _text(el, "region")
            items.append({
                "id": _text(el, "wantedAuthNo"),
                "company": _text(el, "company"),
                "title": title,
                "pay_type": _text(el, "salTpNm"),
                "pay": _text(el, "sal"),
                "region": reg,
                "education": _text(el, "minEdubg"),
                "career": _text(el, "career"),
                "holiday": _text(el, "holidayTpNm"),
                "registered": _ymd(_text(el, "regDt")),
                "close": _text(el, "closeDt"),
                "url": _text(el, "wantedMobileInfoUrl") or _text(el, "wantedInfoUrl"),
                "nearby": bool(short and reg and short in reg),
            })
        # 같은 지역 안에서는 최근 등록 먼저
        near = sorted([i for i in items if i["nearby"]], key=lambda x: x["registered"] or "", reverse=True)
        far = sorted([i for i in items if not i["nearby"]], key=lambda x: x["registered"] or "", reverse=True)
        return _result("postings", (near + far)[:20], total=_int(root.findtext("total")),
                       url="https://www.work24.go.kr")

    return _cached(("postings", keyword, short), make)


# ── 고용24 직업정보 ─────────────────────────────────────────

def _leaf(root: ET.Element, tag: str) -> str | None:
    """같은 이름의 감싸는 태그가 있어도 값이 든 첫 태그를 찾는다 (예: <jobSum><jobSum>하는 일</jobSum>)"""
    for el in root.iter(tag):
        if len(el) == 0 and el.text and el.text.strip():
            return el.text.strip()
    return None


def _leaves(root: ET.Element, tag: str) -> list[str]:
    out: list[str] = []
    for el in root.iter(tag):
        if len(el) == 0 and el.text and el.text.strip() and el.text.strip() not in out:
            out.append(el.text.strip())
    return out


def _sentences(text: str | None) -> list[str]:
    """긴 설명을 줄 단위로: 줄바꿈 · 글머리(-, ·) · 마침표 기준"""
    if not text:
        return []
    parts = re.split(r"\n+|(?<=다\.)\s+|^\s*[-·]\s*", text, flags=re.M)
    return [p.strip(" -·") for p in parts if p and p.strip(" -·")]


def _xml(raw: bytes) -> ET.Element:
    try:
        return ET.fromstring(raw)
    except ET.ParseError as e:
        raise ExternalError("bad xml") from e


def _name_variants(name: str) -> list[str]:
    """직업명 검색어 후보: 원래 이름 → 끝의 사·원·가 등을 뗀 이름 → 띄어쓴 첫 낱말 → 네 글자 합성어의 앞 두 글자"""
    out = [name]
    compact = name.replace(" ", "")
    trimmed = re.sub(r"(사|원|가|자|직|인)$", "", compact)
    if len(trimmed) >= 2:
        out.append(trimmed)
    if " " in name.strip():
        out.append(name.split()[0])
    if trimmed != compact and len(trimmed) == 4:
        out.append(trimmed[:2])
    return list(dict.fromkeys(out))


def work24_job_info(name: str) -> dict[str, Any]:
    s = get_settings()
    if not s.work24_job_info_key:
        return _missing_key("job_info_work24")
    name = name.strip()
    key = s.work24_job_info_key

    def make() -> dict[str, Any]:
        jobs: list[tuple[str | None, str | None]] = []
        # "제과제빵사" 처럼 고용24 직업명과 다르면 짧게 줄여 다시 찾는다 (제과제빵 → 제과)
        for word in _name_variants(name):
            root = _xml(_get(WORK24_JOB_LIST_URL, {
                "authKey": key, "returnType": "XML", "target": "JOBCD", "srchType": "K", "keyword": word,
            }))
            jobs = [(_text(el, "jobCd"), _text(el, "jobNm")) for el in root.iter("jobList")]
            jobs = [j for j in jobs if j[0]]
            if jobs:
                break
        if not jobs:
            return _result("job_info_work24", message="고용24에 같은 이름의 직업이 없습니다.", job=None)
        code, job_name = next((j for j in jobs if (j[1] or "").replace(" ", "") == name.replace(" ", "")), jobs[0])
        base = {"authKey": key, "returnType": "XML", "target": "JOBDTL", "jobGb": 1, "jobCd": code}
        summary = _xml(_get(WORK24_JOB_SUMMARY_URL, {**base, "dtlGb": 1}))
        try:
            doing = _xml(_get(WORK24_JOB_DO_URL, {**base, "dtlGb": 2}))
        except ExternalError:
            doing = None  # 하는 일 상세가 실패해도 요약은 보여 준다
        works = _sentences(_leaf(doing, "execJob")) if doing is not None else []
        job = {
            "id": code,
            "name": job_name or name,
            "summary": _leaf(summary, "jobSum"),
            "works": works,
            "abilities": _sentences(_leaf(summary, "jobAbil")),
            "aptitudes": _sentences(_leaf(summary, "jobChr")),
            "interests": _sentences(_leaf(summary, "jobIntrst")),
            "certificates": _leaves(summary, "certNm"),
            "majors": _leaves(summary, "majorNm"),
            "related_jobs": [n for n in _leaves(summary, "jobNm") if n != job_name],
            "prepare": {"recruit": [], "certificate": [], "training": [], "curriculum": _sentences(_leaf(summary, "way"))},
            "forecast": _sentences(_leaf(summary, "jobProspect")),
            "wage": _leaf(summary, "sal"),
            "wage_source": "고용24 재직자 조사",
            "satisfaction": _leaf(summary, "jobSatis"),
            "work_life_balance": None,
            "orgs": [],
            "url": None,
        }
        return _result("job_info_work24", job=job)

    return _cached(("job_info_work24", name), make)


# ── 고용24 훈련과정 상세 ─────────────────────────────────────

def training_detail(course_id: str, round_: str, institution_id: str) -> dict[str, Any]:
    s = get_settings()
    if not s.work24_training_key:
        return _missing_key("training")

    def make() -> dict[str, Any]:
        root = _xml(_get(WORK24_TRAINING_DETAIL_URL, {
            "authKey": s.work24_training_key, "returnType": "XML", "outType": 2,
            "srchTrprId": course_id, "srchTrprDegr": round_, "srchTorgId": institution_id,
        }))
        base = root.find(".//inst_base_info")
        detail = root.find(".//inst_detail_info")
        if base is None and detail is None:
            return _result("training", message="과정 정보를 찾지 못했습니다.", course=None)

        def pick(tag: str) -> str | None:
            for el in (base, detail):
                v = _text(el, tag)
                if v:
                    return v
            return None

        addr = " ".join(x for x in (pick("addr1"), pick("addr2")) if x) or None
        course = {
            "id": pick("trprId") or course_id,
            "round": pick("trprDegr") or round_,
            "title": pick("trprNm"),
            "institution": pick("inoNm"),
            "address": addr,
            "homepage": pick("hpAddr"),
            "field": pick("govBusiNm") or pick("ncsNm"),
            "target": pick("trprTargetNm"),
            "days": _int(pick("trDcnt") or pick("totTraingDyct")),
            "hours": _int(pick("trtm") or pick("totTraingTime")),
            "cost": _int(pick("instPerTrco")),
            "support": _int(pick("perTrco")),
            "my_cost": _int(pick("tgcrGnrlTrneOwepAllt")),
            "grade": pick("torgParGrad"),
            "contact_phone": pick("trprChapTel"),
            "facilities": [n for n in _leaves(root, "trafcltyNm")][:6],
            "url": (
                "https://www.work24.go.kr/hr/a/a/3100/selectTracseDetl.do?"
                + urllib.parse.urlencode({"tracseId": course_id, "tracseTme": round_, "crseTracseSe": "C0061",
                                          "trainstCstmrId": institution_id})
            ),
        }
        return _result("training", course=course)

    return _cached(("training", course_id, round_, institution_id), make)


# ── 고용24 직무정보 (직무데이터사전) ─────────────────────────

def _skill_labels(v: Any) -> list[str]:
    """지식 · 기술 · 태도: [{'knwg_tchn_attd': 주소, 'knwg_tchn_attd_label': 이름}, …] 또는 그 문자열"""
    if isinstance(v, str):
        try:
            v = json.loads(v.replace("'", '"'))
        except ValueError:
            return []
    out: list[str] = []
    for it in _as_list(v):
        label = it.get("knwg_tchn_attd_label") if isinstance(it, dict) else None
        if label and label not in out:
            out.append(label.strip())
    return out[:8]


def duties(keyword: str) -> dict[str, Any]:
    s = get_settings()
    if not s.work24_duty_key:
        return _missing_key("duties")
    keyword = keyword.strip()

    def make() -> dict[str, Any]:
        raw = _get(WORK24_DUTY_URL, {"authKey": s.work24_duty_key, "word": keyword, "limit": 8, "returnType": "JSON"})
        try:
            data = json.loads(raw)
        except ValueError as e:
            raise ExternalError("bad json") from e
        if isinstance(data, dict) and data.get("message_cd") and not data.get("result"):
            return _result("duties", message="직무 정보를 찾지 못했습니다.")
        result = data.get("result") if isinstance(data, dict) else data
        rows: list[tuple[str | None, dict]] = []
        if isinstance(result, dict):
            rows = [(k, v) for k, v in result.items() if isinstance(v, dict)]
        elif isinstance(result, list):
            rows = [(None, v) for v in result if isinstance(v, dict)]
        items = []
        for unit, v in rows:
            items.append({
                "code": v.get("ablt_unit"),
                "unit": unit or v.get("job_sdvn"),
                "definition": _strip_html(v.get("ablt_def")),
                "field": v.get("job_sdvn"),
                "group": " > ".join(x for x in (v.get("job_lcfn"), v.get("job_mcn"), v.get("job_scfn")) if x) or None,
                "skills": _skill_labels(v.get("knwg_tchn_attd")),
            })
        return _result("duties", items, url="https://www.ncs.go.kr")

    return _cached(("duties", keyword), make)
