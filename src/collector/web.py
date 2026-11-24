"""믿을 만한 기관 누리집에서 지원제도(정책 · 장학금 · 주거 · 취업 …)를 더 찾아 policies.json 형식으로 바꾼다.

정부24 API(collector.run)에 없는 제도를 넓게 찾기 위한 보조 수집기. 믿을 수 있게 하는 장치 (사람 확인 전에는 앱에 안 보임):
1. 기관 목록: trusted_sources.json 의 공식 누리집(go.kr · 공공기관 · 법정 모금기관 · 큰 복지재단)만.
   검색도 이 도메인 안에서만 하고, 받은 페이지 주소를 다시 확인한다 (다른 곳으로 넘어간 페이지는 버림)
2. 원문 대조: AI 가 뽑은 칸마다 페이지 원문 인용을 함께 받고, 인용이 원문에 그대로 없거나
   값의 숫자(금액 · 나이 · 날짜)가 원문에 없거나 값의 낱말 대부분이 원문에 없으면 그 칸을 버린다.
   필수 칸(정책명 · 지원대상 · 지원내용 · 신청방법)이 하나라도 버려지면 그 제도는 넣지 않는다
3. 지금 신청할 수 있는 것만: 신청 기간이 이미 끝났거나(날짜가 모두 오늘 전) AI 가 종료 · 안내만인 글로 보면 넣지 않는다
4. 겹침: 이미 있는 정책(정부24 수집 · 사람이 쓴 것)과 이름이 같으면 넣지 않는다
5. source_type "crawler", easy_text_verified false → 보고서를 보고 사람이 확인 · 쉬운 말 검수를 해야 앱에 보인다

실행 (src 폴더에서, 백엔드 가상환경. OPENAI_API_KEY 필요):
    backend\\.venv\\Scripts\\python -m collector.web                       # 기본 주제 전체를 검색
    backend\\.venv\\Scripts\\python -m collector.web 장학금 주거             # 주제 지정
    backend\\.venv\\Scripts\\python -m collector.web --urls https://...     # 검색 없이 이 페이지만
    backend\\.venv\\Scripts\\python -m collector.web --dry-run             # 파일은 그대로, 보고서만
"""

import argparse
import json
import re
import sys
import urllib.parse
from collections.abc import Callable, Iterable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import date
from html.parser import HTMLParser
from pathlib import Path

from collector.convert import (CONTACT_MAX, DOC_TEXT_MAX, NAME_MAX, Converted, _cut, normalize_amount, pick_category, pick_region,
                               policy_ages, relevance)
from collector.merge import (KeyMap, MergeResult, NameIndex, load_auto_values, merge, read_json, save_auto_values,
                             write_json)
from rag.settings import PROJECT_ROOT

TRUSTED_PATH = Path(__file__).resolve().parent / "trusted_sources.json"
POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
COLLECTED_DIR = PROJECT_ROOT / "data" / "collected"

# 자립준비청년 · 보호대상아동이 받을 수 있는 지원을 분야별로 찾는다
DEFAULT_TOPICS = ("자립 지원금·수당", "장학금", "주거 지원", "취업·직업훈련", "의료·심리 지원", "자산형성·금융")
# 웹 검색의 도메인 제한(filters.allowed_domains)은 gpt-4.1-mini 에서 안 됨 (400 오류, 2026-11 실제 확인) → gpt-5-mini
SEARCH_MODEL = "gpt-5-mini"
EXTRACT_MODEL = "gpt-4.1-mini"
PAGE_TEXT_MAX = 20000  # AI 에 보내는 페이지 글 (긴 페이지는 앞부분)
PAGE_TEXT_MIN = 200  # 이보다 짧으면 자바스크립트로 그리는 페이지로 보고 건너뜀
MAX_PAGES_PER_TOPIC = 8
PROGRAMS_PER_PAGE = 5
WORD_COVERAGE = 0.8  # 값의 낱말 중 원문에 있어야 하는 비율
# 동시에 하는 수 (OpenAI 요청 한도 안에서. 늘려도 비용은 같고 시간만 줄어든다)
SEARCH_WORKERS = 6
PAGE_WORKERS = 6

REQUIRED = ("name", "target_description", "support_content", "apply_method")
TEXT_FIELDS = ("name", "agency", "contact", "target_description", "support_content", "support_amount",
               "support_period", "apply_method", "deadline")

Search = Callable[[str, list[str]], list[str]]  # (주제, 도메인) → 페이지 주소
LoadPage = Callable[[str], tuple[str, str]]  # 주소 → (최종 주소, HTML)
Extract = Callable[[str, str], dict]  # (페이지 글, 주소) → {"programs": [...]}


class WebCollectError(RuntimeError):
    pass


# ---------------- 믿을 만한 기관 ----------------


@dataclass(frozen=True)
class Source:
    domain: str
    name: str | None
    type: str


def load_sources(path: Path = TRUSTED_PATH) -> list[Source]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return [Source(s["domain"].lower().strip("."), s.get("name"), s.get("type", "")) for s in data["sources"]]


def trusted_source(url: str, sources: list[Source]) -> Source | None:
    """주소의 호스트가 목록의 domain 이거나 그 하위면 가장 길게 맞는 기관. http(s) 만"""
    parsed = urllib.parse.urlparse(url)
    host = (parsed.hostname or "").lower()
    if parsed.scheme not in ("http", "https") or not host:
        return None
    matches = [s for s in sources if host == s.domain or host.endswith("." + s.domain)]
    return max(matches, key=lambda s: len(s.domain)) if matches else None


# ---------------- 페이지 글 ----------------


class _TextParser(HTMLParser):
    SKIP = {"script", "style", "noscript", "svg", "head", "template"}
    BLOCK = {"p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "dt", "dd", "section", "article",
             "table", "ul", "ol", "th", "td"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self._skip += 1
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in self.SKIP and self._skip:
            self._skip -= 1
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self._skip:
            self.parts.append(data)


def html_to_text(html: str) -> str:
    p = _TextParser()
    p.feed(html)
    lines = (" ".join(ln.split()) for ln in "".join(p.parts).split("\n"))
    return "\n".join(ln for ln in lines if ln)


# ---------------- 원문 대조 ----------------

_NUM_RE = re.compile(r"\d+(?:[.,]\d+)*")
_WORD_RE = re.compile(r"[0-9A-Za-z가-힣]{2,}")
_DATE_RE = re.compile(r"(20\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})")


def _flat(s: str) -> str:
    return re.sub(r"[\s ·ㆍ,.()\[\]\"'“”‘’:;~\-–—]", "", s)


def _numbers(s: str) -> set[str]:
    return {n.replace(",", "") for n in _NUM_RE.findall(s)}


def grounded(value: str, quote: str | None, page: str) -> str | None:
    """값이 원문에 근거하면 None, 아니면 버린 이유"""
    flat_page = _flat(page)
    if not quote or _flat(quote) not in flat_page:
        return "인용이 원문에 없음"
    if extra := _numbers(value) - _numbers(page):
        return f"원문에 없는 숫자 {sorted(extra)}"
    words = _WORD_RE.findall(value)
    if words:
        # 조사가 붙은 낱말("청년이")도 원문의 낱말("청년")로 인정
        hit = sum(1 for w in words if w in page or (len(w) > 2 and w[:-1] in page))
        if hit / len(words) < WORD_COVERAGE:
            return "값의 낱말 대부분이 원문에 없음 (AI 가 바꿔 쓴 말)"
    return None


def ended(deadline: str | None, today: date) -> bool:
    """신청기한의 날짜가 모두 오늘보다 전이면 끝남. 날짜가 없으면 ("상시", "예산 소진 시") 끝나지 않은 것으로"""
    dates = []
    for y, m, d in _DATE_RE.findall(deadline or ""):
        try:
            dates.append(date(int(y), int(m), int(d)))
        except ValueError:
            continue
    return bool(dates) and max(dates) < today


# ---------------- 한 제도 → 정책 ----------------


@dataclass
class PageResult:
    url: str
    source: Source | None
    converted: list[Converted] = field(default_factory=list)
    error: str | None = None


def to_policy(program: dict, page: str, url: str, source: Source, today: date) -> Converted:
    """AI 가 뽑은 제도 하나를 원문과 대조해 정책으로. policy_key 는 부른 쪽이 정한다"""
    quotes = program.get("quotes") if isinstance(program.get("quotes"), dict) else {}
    values: dict[str, str] = {}
    notes: list[str] = [f"출처: {source.name or urllib.parse.urlparse(url).hostname} ({source.type}) {url}"]
    for k in TEXT_FIELDS:
        v = program.get(k)
        if not isinstance(v, str) or not v.strip():
            continue
        v = "\n".join(ln.strip() for ln in v.strip().splitlines() if ln.strip())
        why = grounded(v, quotes.get(k) if isinstance(quotes.get(k), str) else None, page)
        if why and grounded(v, v, page) is None:
            why = None  # 인용은 틀렸어도 값 자체가 원문에 그대로 있으면 근거가 있다
        if why:
            notes.append(f"{k} 버림: {why}")
        else:
            values[k] = v
    sid = f"{url}#{program.get('name') or ''}"
    if program.get("is_open") is False:
        return Converted(None, sid, skip_reason=f"'{program.get('name')}' 신청 종료 · 안내만인 글")
    missing = [k for k in REQUIRED if k not in values]
    if missing:
        return Converted(None, sid, notes, skip_reason=f"'{program.get('name')}' 원문으로 확인 안 된 필수 칸 {missing}")
    if _flat(values["name"]) not in _flat(page):
        return Converted(None, sid, notes, skip_reason=f"'{values['name']}' 정책명이 원문에 그대로 없음")
    if ended(values.get("deadline"), today):
        return Converted(None, sid, notes, skip_reason=f"'{values['name']}' 신청 기간 끝남 ({values['deadline']})")
    # 정부24 와 같은 대상 고르기 (자립준비청년 · 보호대상아동이 직접 받는 제도만)
    relevant, why_relevant = relevance({"서비스명": values["name"], "지원대상": values["target_description"]})
    if not relevant:
        return Converted(None, sid, notes, skip_reason=f"{values['name']}: {why_relevant}", off_target=True)
    notes.insert(1, f"대상: {why_relevant}")

    agency = values.get("agency") or source.name or (urllib.parse.urlparse(url).hostname or "")
    row = {"서비스명": values["name"], "지원내용": values["support_content"], "소관기관명": agency}
    category, why = pick_category(row)
    notes.append(f"분야 {category} ({why})")
    region, region_note = pick_region(row)
    age_min, age_max, age_note = policy_ages(values["name"], values["target_description"], values["support_content"])
    if age_note:
        notes.append(age_note)  # 정부24 와 같은 나이 규칙 (원문 나이 → 없으면 대상 종류, 11/23)
    if region_note:
        notes.append(region_note)
    elif region == "전국" and source.name is None:
        # go.kr 일반 누리집(시 · 군 · 구청 등)인데 기관명에서 시/도를 못 찾음 → 전국으로 보이면 안 되니 확인
        notes.append(f"지자체 누리집({urllib.parse.urlparse(url).hostname})으로 보이는데 지역을 못 찾음 → region 확인 필요 (지금은 전국)")

    docs = []
    for d in program.get("required_docs") or []:
        if not isinstance(d, dict) or not isinstance(d.get("name"), str) or not d["name"].strip():
            continue
        if grounded(d["name"], d["name"], page):  # 서류 이름은 원문 그대로여야 한다
            notes.append(f"서류 버림: '{d['name'][:30]}' 원문에 없음")
            continue
        cond = d.get("condition") if isinstance(d.get("condition"), str) and d["condition"].strip() else None
        if cond and grounded(cond, cond, page):
            cond = None
        doc = {"name": _cut(d["name"].strip(), DOC_TEXT_MAX), "condition": _cut(cond, DOC_TEXT_MAX)}
        if doc not in docs:
            docs.append(doc)
    if not docs:
        notes.append("신청서류가 원문에서 확인되지 않음 → 서류 준비 단계가 비어 보임")

    apply_method = values["apply_method"]
    if values.get("deadline"):
        apply_method = f"{apply_method}\n신청기한: {values['deadline']}"
    apply_url = program.get("apply_url")
    if not (isinstance(apply_url, str) and trusted_source(apply_url, [source]) and apply_url in page + url):
        apply_url = None  # 신청 주소도 같은 기관 · 원문에 있는 것만

    sections = [("정책명", "name"), ("담당부처", "agency"), ("지원대상", "target_description"),
                ("지원내용", "support_content"), ("지원금액", "support_amount"), ("지원기간", "support_period"),
                ("신청방법", "apply_method"), ("신청기한", "deadline"), ("문의처", "contact")]
    original = [f"[출처 A: {source.name or urllib.parse.urlparse(url).hostname}]", url]
    for header, k in sections:
        if values.get(k):
            original += [f"[{header}]", values[k]]
    if docs:
        original += ["[신청서류]", *(d["name"] + (f" ({d['condition']})" if d["condition"] else "") for d in docs)]

    policy = {
        "policy_key": "",
        "name": _cut(values["name"], NAME_MAX),
        "agency": _cut(agency, 100),
        "contact": _cut(values.get("contact"), CONTACT_MAX),
        "category": category,
        "region": region,
        "age_min": age_min,
        "age_max": age_max,
        "target_description": values["target_description"],
        "support_content": values["support_content"],
        "support_amount": normalize_amount(values.get("support_amount")),
        "support_period": values.get("support_period"),
        "apply_method": apply_method,
        "required_docs": docs,
        "apply_url": apply_url,
        "source_url": url,
        "original_text": "\n".join(original),
        "easy_text": None,
        "easy_text_verified": False,
        "source_type": "crawler",
        "checked_at": today.isoformat(),
    }
    return Converted(policy, sid, notes)


# ---------------- 실제 검색 · 페이지 · AI (테스트에서는 가짜로 바꾼다) ----------------

EXTRACT_SYSTEM = """공식 기관 누리집 페이지 글에서 자립준비청년(보호종료아동) · 보호대상아동 · 청년이 신청할 수 있는 지원제도를 뽑는다.
규칙
- 페이지 글에 적힌 말을 그대로 옮긴다. 요약 · 바꿔 쓰기 · 추측 금지. 글에 없는 칸은 비운다 (null)
- 칸마다 quotes 에 그 값을 뒷받침하는 원문 한 구절을 글자 그대로 넣는다 (40자 안팎)
- 지원제도 안내가 아닌 글(뉴스 · 행사 후기 · 결과 발표 · 목록 페이지)이면 programs 는 []
- 신청이 끝났다고 적혀 있거나 지난 공고면 is_open false
- 최대 5개
JSON: {"programs": [{"name": "제도 이름", "agency": "운영 기관", "contact": "문의처", "target_description": "지원대상",
"support_content": "지원내용", "support_amount": "금액", "support_period": "기간", "apply_method": "신청방법",
"deadline": "신청기한", "apply_url": "신청 주소", "required_docs": [{"name": "서류", "condition": "필요한 경우"}],
"is_open": true, "quotes": {"name": "원문 구절", "target_description": "원문 구절", "...": "..."}}]}"""

SEARCH_PROMPT = """자립준비청년(보호종료아동)·보호대상아동이 지금 신청할 수 있는 '{topic}' 지원제도의 공식 안내 페이지를 찾아라.
뉴스 · 블로그가 아닌 운영 기관의 안내 · 공고 페이지만. 제도마다 한 페이지.
JSON 만: {{"pages": [{{"title": "제도 이름", "url": "https://..."}}]}}"""


# 웹 검색 도메인 제한(allowed_domains)에 한 번에 넣는 수. 기관이 이보다 많으면 나눠서 검색한다
# (전에는 앞 20곳만 넣어 21번째부터는 아무 표시 없이 검색에서 빠졌다)
DOMAINS_PER_SEARCH = 20


def openai_search(topic: str, domains: list[str]) -> list[str]:
    from rag.openai_client import _client

    urls: list[str] = []
    for i in range(0, len(domains), DOMAINS_PER_SEARCH):
        resp = _client().with_options(timeout=90.0, max_retries=1).responses.create(
            model=SEARCH_MODEL,
            tools=[{"type": "web_search", "filters": {"allowed_domains": domains[i:i + DOMAINS_PER_SEARCH]}}],
            input=SEARCH_PROMPT.format(topic=topic),
        )
        for item in getattr(resp, "output", None) or []:
            for c in getattr(item, "content", None) or []:
                for a in getattr(c, "annotations", None) or []:
                    if getattr(a, "type", "") == "url_citation" and getattr(a, "url", None):
                        urls.append(a.url)
        urls += re.findall(r"https?://[^\s\"'<>)\]]+", getattr(resp, "output_text", "") or "")
    # 검색 결과 주소에 붙는 추적 꼬리 (?utm_source=openai) 떼기
    return list(dict.fromkeys(re.sub(r"[?&]utm_source=openai$", "", u) for u in urls))


def default_load_page(url: str) -> tuple[str, str]:
    import urllib.request

    from formguide.fetch import USER_AGENT
    from formguide.notice import MAX_PAGE_BYTES, _decode

    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.geturl(), _decode(resp.read(MAX_PAGE_BYTES), resp.headers.get("Content-Type"))


def openai_extract(page: str, url: str) -> dict:
    from rag.openai_client import _client

    resp = _client().with_options(timeout=60.0, max_retries=1).chat.completions.create(
        model=EXTRACT_MODEL,
        temperature=0,
        max_tokens=4000,
        response_format={"type": "json_object"},
        messages=[{"role": "system", "content": EXTRACT_SYSTEM},
                  {"role": "user", "content": f"[페이지 주소]\n{url}\n\n[페이지 글]\n{page[:PAGE_TEXT_MAX]}"}],
    )
    try:
        return json.loads(resp.choices[0].message.content or "{}")
    except json.JSONDecodeError:
        return {"programs": []}


# ---------------- 전체 ----------------


def read_page(url: str, sources: list[Source], today: date, load: LoadPage, extract: Extract) -> PageResult:
    source = trusted_source(url, sources)
    if source is None:
        return PageResult(url, None, error="믿을 만한 기관 목록에 없는 주소")
    try:
        final_url, html = load(url)
    except Exception as e:
        return PageResult(url, source, error=f"페이지를 못 읽음 ({type(e).__name__})")
    final_source = trusted_source(final_url, sources)
    if final_source is None:
        return PageResult(url, source, error=f"목록 밖 주소로 넘어감 ({urllib.parse.urlparse(final_url).hostname})")
    page = html_to_text(html)
    if len(page) < PAGE_TEXT_MIN:
        return PageResult(final_url, final_source, error="페이지 글이 너무 적음 (자바스크립트로 그리는 페이지일 수 있음)")
    try:
        programs = extract(page, final_url).get("programs") or []
    except Exception as e:
        return PageResult(final_url, final_source, error=f"AI 추출 실패 ({type(e).__name__})")
    res = PageResult(final_url, final_source)
    for program in [p for p in programs if isinstance(p, dict)][:PROGRAMS_PER_PAGE]:
        res.converted.append(to_policy(program, page, final_url, final_source, today))
    return res


def report(res: MergeResult, pages: list[PageResult], dupes: list[str], today: date,
           search_errors: list[str] | None = None) -> str:
    from collector.run import report as base_report

    converted = [c for p in pages for c in p.converted]
    failed = [f"- {p.url}: {p.error}" for p in pages if p.error]
    parts = [
        base_report(res, converted, today, source="믿을 만한 기관 웹 수집", dupes=dupes),
        f"## 읽지 못한 페이지 ({len(failed)})", *(failed or ["- 없음"]), "",
    ]
    if search_errors:
        parts += [f"## 검색하지 못한 주제 ({len(search_errors)})", *(f"- {e}" for e in search_errors), ""]
    return "\n".join(parts)


def collect(topics: Iterable[str] | None, urls: list[str] | None, *, today: date,
            search: Search = openai_search, load: LoadPage = default_load_page, extract: Extract = openai_extract,
            policies_path: Path = POLICIES_PATH, collected_dir: Path = COLLECTED_DIR,
            sources: list[Source] | None = None, dry_run: bool = False,
            output_path: Path | None = None) -> tuple[MergeResult, str]:
    """policies_path: 정책 파일 (기본 data/policies/policies.json 에 바로 합침). output_path 를 주면 따로 저장하고 policies_path 는 읽기만"""
    from collector.run import CollectError, validate

    # 기본은 팀 policies.json 에 바로 합친다 (사람이 쓴 정책은 바꾸지 않음). output_path 를 주면 따로 저장하고 팀 파일은 읽기만
    output_path = output_path or policies_path

    sources = sources or load_sources()
    search_errors: list[str] = []
    if urls:
        candidates = list(dict.fromkeys(urls))
    else:
        domains = [s.domain for s in sources]
        topic_list = list(topics or DEFAULT_TOPICS)

        def search_one(topic: str) -> list[str] | Exception:
            try:
                return [u for u in search(topic, domains) if trusted_source(u, sources)][:MAX_PAGES_PER_TOPIC]
            except Exception as e:  # 한 주제 검색이 실패해도 나머지는 계속한다
                return e

        # 주제마다 웹 검색이 30초~1분 걸려 동시에 한다 (결과 순서는 주제 순서 그대로)
        with ThreadPoolExecutor(max_workers=max(1, min(len(topic_list), SEARCH_WORKERS))) as pool:
            found_all = list(pool.map(search_one, topic_list))
        candidates = []
        for topic, found in zip(topic_list, found_all):
            if isinstance(found, Exception):
                search_errors.append(f"{topic}: {type(found).__name__}")
            else:
                candidates += found
        candidates = list(dict.fromkeys(candidates))
        if search_errors and not candidates:
            raise WebCollectError("모든 주제 검색 실패: " + ", ".join(search_errors))

    # 페이지 받기 · AI 칸 뽑기도 동시에 (순서는 그대로 → policy_key 가 실행마다 같게 매겨짐)
    with ThreadPoolExecutor(max_workers=PAGE_WORKERS) as pool:
        pages = list(pool.map(lambda u: read_page(u, sources, today, load, extract), candidates))

    base: list[dict] = [] if output_path == policies_path else read_json(policies_path, [])  # 따로 저장할 때만: 팀 정책 (읽기만)
    current: list[dict] = read_json(output_path, [])  # 전에 수집한 정책 (정부24 G · 웹 W)
    names = NameIndex(base + current)
    keymap = KeyMap(collected_dir / "web_keys.json", prefix="W")
    taken = {p["policy_key"] for p in base + current}
    collected, dupes = [], []
    for page in pages:
        kept = []
        for conv in page.converted:
            if conv.policy is not None:
                if same := names.duplicate_of(conv.policy["name"], conv.policy["agency"],
                                              keymap.keys.get(conv.service_id)):
                    dupes.append(f"{conv.policy['name']} · {conv.policy['agency']} (= {same})")  # 정부24 · 사람이 쓴 같은 제도
                    continue
                conv.policy["policy_key"] = keymap.key_for(conv.service_id, taken)
                names.add(conv.policy["name"], conv.policy["agency"], conv.policy["policy_key"])
                collected.append(conv.policy)
            kept.append(conv)
        page.converted = kept

    auto_prev = load_auto_values(collected_dir)
    res = merge(current, collected, auto_prev=auto_prev)
    try:
        validate(base + res.policies)  # 팀 정책과 합쳐도 시드 검사 통과
    except CollectError as e:
        raise WebCollectError(str(e)) from None
    text = report(res, pages, dupes, today, search_errors)
    if not dry_run:
        write_json(output_path, res.policies)  # 팀 파일은 쓰지 않는다
        keymap.save()
        save_auto_values(collected_dir, auto_prev, res)
        (collected_dir / "web_report.md").write_text(text, encoding="utf-8")
    return res, text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="믿을 만한 기관 누리집에서 지원제도를 찾아 policies.json 에 넣기")
    parser.add_argument("topics", nargs="*", help=f"찾을 주제 (없으면 기본: {', '.join(DEFAULT_TOPICS)})")
    parser.add_argument("--urls", nargs="+", metavar="주소", help="검색 없이 이 페이지만 (목록 안 기관만)")
    parser.add_argument("--dry-run", action="store_true", help="파일은 그대로 두고 보고서만 출력")
    args = parser.parse_args(argv)
    try:
        res, text = collect(args.topics, args.urls, today=date.today(), dry_run=args.dry_run)
    except WebCollectError as e:
        print(e, file=sys.stderr)
        return 1
    print(text)
    if not args.dry_run:
        print(f"\n저장: {POLICIES_PATH} (새로 {len(res.added)} · 바뀜 {len(res.changed)}), "
              f"보고서 {COLLECTED_DIR / 'web_report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
