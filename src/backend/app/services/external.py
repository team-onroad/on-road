"""진로 외부 정보: 큐넷 시험일정 · 고용24 훈련과정 · 커리어넷 직업정보 · 고용24 채용공고

- 키는 서버 환경변수로만 받는다 (app/config.py). 키가 없으면 available=false
- 바깥 서버가 느리거나 실패해도 앱 화면이 멈추지 않게 짧은 제한시간, 실패는 available=false + message
- 같은 질의는 6시간 동안 메모리 캐시 (실패한 응답은 담지 않는다)
"""

from __future__ import annotations

import json
import logging
import re
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
WORK24_JOBS_URL = "https://www.work24.go.kr/cm/openApi/call/wk/callOpenApiSvcInfo210L01.do"

SOURCES = {
    "exams": "큐넷 국가자격 시험일정",
    "trainings": "고용24 국민내일배움카드 훈련과정",
    "job_info": "커리어넷 직업백과",
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
    except Exception as e:  # noqa: BLE001 - 바깥 서버 오류는 모두 같은 응답으로
        log.warning("external call failed: %s (%s)", url, type(e).__name__)
        raise ExternalError(str(type(e).__name__)) from e


def _result(kind: str, items: list[dict[str, Any]] | None = None, *, available: bool = True,
            message: str | None = None, **extra: Any) -> dict[str, Any]:
    return {"available": available, "source": SOURCES[kind], "items": items or [], "message": message, **extra}


def _missing_key(kind: str) -> dict[str, Any]:
    return _result(kind, available=False, message="서버에 API 키가 설정되지 않았습니다.")


def _failed(kind: str) -> dict[str, Any]:
    return _result(kind, available=False, message="정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")


def _cached(key: tuple, make) -> dict[str, Any]:
    try:
        return _cache.get_or_set(key, make)
    except ExternalError:
        return _failed(key[0])


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


def _qnet_rounds(year: int) -> list[dict[str, Any]]:
    s = get_settings()
    raw = _get(QNET_URL, {
        # 포털이 주는 키가 인코딩된 것(%2B 등)이든 아니든 같은 값으로 보낸다
        "serviceKey": urllib.parse.unquote(s.qnet_service_key or ""), "numOfRows": 100, "pageNo": 1,
        "dataFormat": "json", "implYy": year, "qualgbCd": "T",
    })
    try:
        data = json.loads(raw)
    except ValueError as e:
        raise ExternalError("bad json") from e
    body = (data.get("body") or data.get("response", {}).get("body") or {})
    items = body.get("items") or []
    if isinstance(items, dict):
        items = items.get("item") or []
    if isinstance(items, dict):
        items = [items]
    return items


def exam_schedule(cert: str, today: date) -> dict[str, Any]:
    if not get_settings().qnet_service_key:
        return _missing_key("exams")
    grade = grade_of(cert)
    if grade is None:
        return _result("exams", message="큐넷 국가기술자격 정기시험 일정이 없는 자격증입니다. 시행 기관 공지를 확인해 주세요.",
                       grade=None)

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
                r = {
                    "name": desc.strip() or f"{year}년 {grade}",
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
                last = r["practical_pass"] or r["practical_exam_end"] or r["written_exam_end"]
                if last and last < today.isoformat():
                    continue
                rounds.append(r)
        rounds.sort(key=lambda r: r["written_apply_start"] or r["practical_apply_start"] or "9999")
        return _result("exams", rounds[:6], grade=grade, url="https://www.q-net.or.kr")

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
                "title": title,
                "institution": _text(el, "subTitle"),
                "address": address,
                "phone": _text(el, "telNo"),
                "start": _ymd(_text(el, "traStartDate")),
                "end": _ymd(_text(el, "traEndDate")),
                "cost": _int(_text(el, "courseMan")),
                "my_cost": _int(_text(el, "realMan")),
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
    s = re.sub(r"<[^>]+>", " ", str(v))
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
    s = get_settings()
    if not s.careernet_api_key:
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
