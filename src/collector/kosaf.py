"""한국장학재단 학자금지원정보(대학생)에서 자립준비청년이 받을 수 있는 장학금을 모은다 (정책 키 S001 …).

데이터: 공공데이터포털 15028252 '한국장학재단_학자금지원정보(대학생)' — 한국장학재단 · 지자체 · 민간 장학재단 · 대학의 장학금
(운영기관, 상품명, 특정자격, 소득 · 성적 · 지역 기준, 지원내역, 제출서류, 모집 시작일 · 종료일). 매달 새 회분이 올라오고
지난 회분(2021년부터)도 API 로 남아 있다. 인증키는 정부24 와 같은 DATA_GO_KR_SERVICE_KEY (이 데이터도 활용신청 필요, 11/25 승인).

- 최신 회분에서 자립준비청년 관련 장학금만 고른다: 상품명 · 특정자격 · 소득기준 · 지원내역 · 선발방법에 자립준비 · 보호종료 ·
  아동복지시설 · 가정위탁 · 보호대상아동 · 소년소녀가정 · 그룹홈 등이 있는 것 (자격제한 칸은 '제외'일 수 있어 보지 않는다).
  대학원 전용은 뺀다 (앱 사용자는 대학 진학 · 재학). 1,944건 중 약 40건 (11/25)
- 정책 추천 · 검색에는 넣지 않고 진로 로드맵만 쓴다 (팀 결정 11/25): policies.json 이 아니라 data/scholarships.json 에 쓴다.
  그래서 DB 시드 · 검색 색인(rag.index) · 홈 추천 · 쉬운 말 변환에 들어가지 않는다.
  항목 형식은 정부24 정책과 같다 (나이 규칙 policy_ages · 서류 split_docs 그대로, 키만 S001 …: data/collected/kosaf_keys.json)
- 정부24 · 웹 정책에 이미 있는 장학금은 넣지 않는다 (이름 · 운영기관, 또는 운영기관 + 모집 시작일이 같으면)
- 모집 일정: 지난 회분을 모아 장학금마다 모집 회차를 쌓고, 다음 모집 예상 시기를 data/apply_schedule.json 에 쓴다
  (진로 로드맵의 '언제 무엇을 신청' 용. 지원정책은 매년 비슷한 때 나온다 — 회차가 하나뿐이면 '내년 같은 달 예상'으로 표시)

실행 (src 폴더에서, 백엔드 가상환경):
    backend\\.venv\\Scripts\\python -m collector.kosaf --dry-run     # 보고서만
    backend\\.venv\\Scripts\\python -m collector.kosaf               # scholarships.json · 모집 일정 · 보고서 저장
    backend\\.venv\\Scripts\\python -m collector.kosaf --no-history  # 지난 회분 없이 최신만 (빠름)
"""

import argparse
import json
import re
import sys
import urllib.parse
from collections.abc import Callable
from datetime import date
from pathlib import Path

from collector import gov24
from collector.convert import (AGENCY_MAX, CITY_REGIONS, NAME_MAX, NATIONWIDE, REGION_NAMES, Converted, _cut, _single_amount,
                               pick_category, policy_ages, split_docs, text)
from collector.merge import KeyMap, NameIndex, read_json, write_json
from collector.run import COLLECTED_DIR, POLICIES_PATH, CollectError
from collector.schedule import SCHEDULE_PATH, SCHOLARSHIPS_PATH, deadline_text, parse_rounds

DATASET = "15028252"
OAS_URL = f"https://infuser.odcloud.kr/oas/docs?namespace={DATASET}/v1"
API_BASE = "https://api.odcloud.kr/api"
PAGE_URL = f"https://www.data.go.kr/data/{DATASET}/fileData.do"
SOURCE_NAME = "한국장학재단 학자금지원정보"
PER_PAGE = 1000

GetJson = Callable[[str], dict]

# 자립준비청년 · 보호대상아동이 대상이거나 우대되는 장학금 (실제 데이터 11/25: 41건)
TARGET_RE = re.compile(r"자립\s*준비|보호\s*종료|아동\s*복지\s*시설|아동\s*양육\s*시설|가정\s*위탁|보호\s*대상\s*아동|"
                       r"시설\s*(?:퇴소|출신|보호)|퇴소\s*(?:아동|청년|대학생|자)|소년\s*소녀\s*가(?:정|장)|그룹홈|공동\s*생활\s*가정")
# 대상 칸 (여기에 있으면 자립준비청년이 받을 수 있는 장학금) · 우대 칸 (선발방법의 "우대 · 가점"만 있는 일반 장학금)
TARGET_FIELDS = ("상품명", "특정자격 상세내용", "소득기준 상세내용", "지원내역 상세내용")
PREFER_FIELDS = ("선발방법 상세내용",)
WHERE = TARGET_FIELDS + PREFER_FIELDS
# 대학구분은 띄어쓰기 없이 붙어 온다 ("4년제(5~6년제포함)기술대학원격대학일반대학원전문대(2~3년제)전문대학원")
# → '전문대학원' · '기술대학'+'원격대학'이 학부로 잘못 잡히지 않게 학부 표시만 고른다
_UNDERGRAD_RE = re.compile(r"4년제|전문대\(|원격대학|기술대학(?!원(?!격))|제한없음|학점은행|특정대학")
# 이름만으로는 어느 장학금인지 모르는 상품명 → 앞에 운영기관을 붙인다 ("장학금" → "(사)우리 장학금")
_GENERIC_NAME_RE = re.compile(r"^(?:(?:일반|특별|복지|희망|우수|대학생|대학|이사장|총장|기관|추천)\s*)*장학(?:금|생)?(?:\s*지원)?$")
_LEGAL_PREFIX_RE = re.compile(r"^\s*(?:[(（]\s*(?:재|사|주)\s*[)）]|재단법인|사단법인)\s*")
_NONE_WORDS = ("해당없음", "해당 없음", "없음", "-")

# 군 이름 → 시/도 (지역거주 조건 · 운영기관이 "강진군 …"일 때. 정부24 표 CITY_REGIONS 는 시만 있다.
# 고성군은 강원 · 경남 둘 다라 뺀다)
_COUNTIES = {
    "부산": "기장", "대구": "달성 군위", "인천": "강화 옹진", "울산": "울주",
    "경기": "가평 양평 연천",
    "강원": "홍천 횡성 영월 평창 정선 철원 화천 양구 인제 양양",
    "충북": "보은 옥천 영동 증평 진천 괴산 음성 단양",
    "충남": "금산 부여 서천 청양 홍성 예산 태안",
    "전북": "완주 진안 무주 장수 임실 순창 고창 부안",
    "전남": "담양 곡성 구례 고흥 보성 화순 장흥 강진 해남 영암 무안 함평 영광 장성 완도 진도 신안",
    "경북": "의성 청송 영양 영덕 청도 고령 성주 칠곡 예천 봉화 울진 울릉",
    "경남": "의령 함안 창녕 남해 하동 산청 함양 거창 합천",
}
COUNTY_REGIONS = tuple((f"{n}군", code) for code, names in _COUNTIES.items() for n in names.split())
PLACE_NAMES = REGION_NAMES + tuple(CITY_REGIONS) + COUNTY_REGIONS


def _s(row: dict, k: str) -> str:
    v = text(row, k)
    return "" if v is None or v.strip() in _NONE_WORDS else v


# ---------------- API ----------------


def _version_date(summary: str) -> str | None:
    """회분 이름의 날짜 → YYYY-MM-DD ("…_20261110", "…_10/12/2021")"""
    if m := re.search(r"(\d{4})(\d{2})(\d{2})\s*$", summary):
        return f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
    if m := re.search(r"(\d{1,2})/(\d{1,2})/(\d{4})\s*$", summary):
        return f"{m.group(3)}-{int(m.group(1)):02d}-{int(m.group(2)):02d}"
    return None


def list_versions(get_json: GetJson, cache: Path | None = None) -> list[tuple[str, str]]:
    """[(API 경로, 회분 날짜)] 오래된 것부터. 매달 새 회분이 생기므로 API 명세에서 읽는다 (경로를 코드에 박지 않음).
    명세 서버(infuser.odcloud.kr)가 멈추는 날이 있어(11/25 503) 받은 목록을 cache 에 남기고, 못 받으면 그것을 쓴다"""
    try:
        doc = gov24.get_with_retry(get_json, OAS_URL)
    except Exception as e:
        saved = read_json(cache, None) if cache else None
        if not saved:
            raise CollectError(f"학자금지원정보 API 명세를 받지 못함 ({type(e).__name__}: {str(e)[:80]}), 저장한 회분 목록도 없음") from None
        print(f"[kosaf] API 명세를 받지 못해 저장한 회분 목록을 씀 ({type(e).__name__})", file=sys.stderr)
        return [tuple(v) for v in saved]
    out = []
    for path, ops in (doc.get("paths") or {}).items():
        d = _version_date(((ops.get("get") or {}).get("summary")) or "")
        if d:
            out.append((path, d))
    if not out:
        raise CollectError("학자금지원정보 API 명세에서 회분을 찾지 못함")
    out = sorted(out, key=lambda x: x[1])
    if cache:
        write_json(cache, [list(v) for v in out])
    return out


def fetch_rows(path: str, get_json: GetJson, key: str) -> list[dict]:
    rows: list[dict] = []
    page = 1
    while True:
        url = f"{API_BASE}{path}?" + urllib.parse.urlencode({"page": page, "perPage": PER_PAGE, "serviceKey": key})
        data = gov24.get_with_retry(get_json, url)
        got = [r for r in data.get("data") or [] if isinstance(r, dict)]
        rows += got
        if len(got) < PER_PAGE or len(rows) >= int(data.get("totalCount") or 0):
            return rows
        page += 1


# ---------------- 고르기 · 변환 ----------------


def row_id(row: dict) -> str:
    """회분이 달라도 같은 장학금으로 묶는 이름: 운영기관 + 상품명 (띄어쓰기 · 기호 무시)"""
    return re.sub(r"[\W_]", "", f"{_LEGAL_PREFIX_RE.sub('', row.get('운영기관명') or '')}|{row.get('상품명') or ''}")


def _match(row: dict) -> tuple[re.Match | None, bool]:
    """(걸린 말, 우대만인지). 대상 칸에 없고 선발방법에만 있으면 '우대' (일반 장학금인데 자립준비청년 가점)"""
    for fields, prefer in ((TARGET_FIELDS, False), (PREFER_FIELDS, True)):
        if m := TARGET_RE.search(" ".join(_s(row, k) for k in fields)):
            return m, prefer
    return None, False


def _agency_key(agency: str | None) -> str:
    return re.sub(r"[\W_]", "", _LEGAL_PREFIX_RE.sub("", agency or ""))


def relevance(row: dict) -> tuple[bool, str]:
    m, prefer = _match(row)
    if not m:
        return False, "자립준비청년 · 보호대상아동 관련 자격이 없음"
    school = _s(row, "대학구분")
    if (school or "대학원" in (row.get("상품명") or "")) and not _UNDERGRAD_RE.search(school):
        return False, "대학원 전용"
    return True, f"'{m.group(0)}'" + (" (선발 때 우대)" if prefer else "")


def prefer_line(row: dict) -> str | None:
    """우대만인 장학금: 선발방법에서 자립준비청년이 든 문장 ("우대사항 : … 자립준비청년 …")"""
    m, prefer = _match(row)
    if not m or not prefer:
        return None
    for part in re.split(r"[○※\n]", _s(row, "선발방법 상세내용")):
        if TARGET_RE.search(part):
            return part.strip(" :")
    return None


def display_name(row: dict) -> str:
    name = " ".join((row.get("상품명") or "").split())
    agency = _LEGAL_PREFIX_RE.sub("", row.get("운영기관명") or "").strip()
    if _GENERIC_NAME_RE.match(name) or len(name) <= 4:
        return f"{agency} {name}".strip()
    return name


def pick_region(row: dict) -> tuple[str, str | None]:
    """(지역 코드, 확인할 것). 지역거주 조건 → 운영기관 이름 순으로 시/도를 찾는다. 조건이 없으면 전국"""
    cond = _s(row, "지역거주여부 상세내용")
    agency = _LEGAL_PREFIX_RE.sub("", row.get("운영기관명") or "")
    kind = row.get("운영기관구분") or ""
    for hay in (cond, agency):
        hits = [(hay.find(n), c) for n, c in PLACE_NAMES if n in hay]
        if hits:
            code = min(hits)[1]
            local = "지자체" in kind or re.search(r"(시|군|구)(?:\s|에|민|청|$)", cond or agency)
            return code, (f"시·군·구 거주 조건 → 앱은 '{code}' 전체에 보임 (조건 확인 필요)" if local else None)
    if cond or "지자체" in kind:
        return NATIONWIDE, f"지역 조건이 있는데 시/도를 못 찾음 ('{(cond or agency)[:40]}') → 지역 확인 필요"
    return NATIONWIDE, None


def _period(row: dict) -> str | None:
    s, e = (row.get("모집시작일") or "").strip(), (row.get("모집종료일") or "").strip()
    return f"{s} ~ {e}" if s or e else None


def build_original_text(row: dict, name: str, source_url: str) -> str:
    sections = (("정책명", name), ("운영기관", row.get("운영기관명")), ("운영기관구분", row.get("운영기관구분")),
                ("지원유형", " · ".join(x for x in (row.get("상품구분"), row.get("학자금유형구분")) if x and x not in _NONE_WORDS)),
                ("지원대상", _target(row)), ("지원내용", bulleted(_s(row, "지원내역 상세내용"))),
                ("선발방법", bulleted(_s(row, "선발방법 상세내용"))), ("선발인원", bulleted(_s(row, "선발인원 상세내용"))),
                ("신청기한", _period(row)), ("신청서류", bulleted(_s(row, "제출서류 상세내용"))),
                ("자격제한", bulleted(_s(row, "자격제한 상세내용"))))
    parts = [f"[출처 A: {SOURCE_NAME}]", source_url]
    for header, v in sections:
        if v:
            parts += [f"[{header}]", v]
    return "\n".join(parts)


# 대학구분 · 학년구분 · 학과구분은 값이 띄어쓰기 없이 붙어 온다 ("대학2학기대학3학기", "공학계열교육계열")
_SCHOOL_TOKEN_RE = re.compile(r"(?<=[^\s(,])(?=대학\d|대학신입생|(?:공학|교육|사회|예체능|의약|인문|자연)계열|제한없음|전문대|"
                              r"원격대학|일반대학원|학점은행|해외대학|특정대학)")


def _items(v: str) -> list[str]:
    return [x.strip(" ○") for x in re.split(r"(?=[○※])", v or "") if x.strip(" ○")]


def bulleted(v: str) -> str:
    """상세내용은 줄바꿈 없이 "○ 가○ 나※ 다"로 온다 → "- 가\n- 나\n※ 다" (항목이 하나면 그대로)"""
    items = _items(v)
    if len(items) <= 1:
        return items[0] if items else ""
    return "\n".join(x if x.startswith("※") else f"- {x}" for x in items)


def _labeled(label: str, v: str) -> str:
    items = _items(v)
    return f"{label}: {items[0]}" if len(items) == 1 else f"{label}:\n{bulleted(v)}"


def _target(row: dict) -> str:
    lines = []
    for label, k in (("자격", "특정자격 상세내용"), ("소득", "소득기준 상세내용"), ("성적", "성적기준 상세내용"),
                     ("지역", "지역거주여부 상세내용")):
        if v := _s(row, k):
            lines.append(_labeled(label, v))
    if v := prefer_line(row):
        lines.append(f"우대: {v}")
    school = " · ".join(_SCHOOL_TOKEN_RE.sub(", ", x) for x in (_s(row, "대학구분"), _s(row, "학년구분"), _s(row, "학과구분")) if x)
    if school:
        lines.append(f"대학: {school}")
    if v := _s(row, "자격제한 상세내용"):
        lines.append(_labeled("받을 수 없는 경우", v))
    return "\n".join(lines)


def doc_text(raw: str) -> str:
    """제출서류는 줄바꿈 없이 "○ 신청서○ 재학증명서※ 자세한 …"로 온다 → 글머리표마다 줄을 나눠 split_docs 에 넘긴다"""
    return re.sub(r"\s*(?=[○●□■▶※])", "\n", raw).strip()


def to_policy(row: dict, policy_key: str, today: date) -> Converted:
    sid = row_id(row)
    ok, why = relevance(row)
    if not ok:
        return Converted(None, sid, skip_reason=f"{row.get('상품명')}: {why}", off_target=True)
    name = display_name(row)
    target, content = _target(row), bulleted(_s(row, "지원내역 상세내용"))
    if not target or not content:
        return Converted(None, sid, skip_reason=f"{name}: 지원대상 · 지원내역이 원문에 없음")
    notes = [f"대상: {why}"]
    agency = _cut(_LEGAL_PREFIX_RE.sub("", row.get("운영기관명") or "").strip() or SOURCE_NAME, AGENCY_MAX)
    category, cwhy = pick_category({"서비스명": name, "지원내용": content})
    notes.append(f"분야 {category} ({cwhy})")
    region, region_note = pick_region(row)
    if region_note:
        notes.append(region_note)
    age_min, age_max, age_note = policy_ages(name, target, content)
    if age_note:
        notes.append(age_note)
    home = _s(row, "홈페이지 주소")
    home = home if home.startswith(("http://", "https://")) else None
    apply_lines = [bulleted(_s(row, "선발방법 상세내용")) or "운영기관 공고에 따라 신청"]
    if p := _period(row):
        apply_lines.append(f"신청기한: {p}")
    if v := bulleted(_s(row, "추천필요여부 상세내용")):
        apply_lines.append(f"추천: {v}")
    if home:
        apply_lines.append(f"공고: {home}")
    docs, doc_notes = split_docs(doc_text(_s(row, "제출서류 상세내용")))
    notes += doc_notes
    if not docs:
        notes.append("제출서류가 원문에 없음 → 서류 준비 단계가 비어 보임")
    source_url = home or PAGE_URL
    policy = {
        "policy_key": policy_key,
        "name": _cut(name, NAME_MAX),
        "agency": agency,
        "contact": None,
        "category": category,
        "region": region,
        "age_min": age_min,
        "age_max": age_max,
        "target_description": target,
        "support_content": content,
        "support_amount": _single_amount(content),
        "support_period": None,
        "apply_method": "\n".join(apply_lines),
        "required_docs": docs,
        "apply_url": home,
        "source_url": source_url,
        "original_text": build_original_text(row, name, source_url),
        "easy_text": None,
        "easy_text_verified": False,
        "source_type": "api",
        "checked_at": today.isoformat(),
    }
    return Converted(policy, sid, notes)


# ---------------- 모집 일정 ----------------


def _d(s: str | None) -> date | None:
    try:
        return date.fromisoformat((s or "").strip()[:10])
    except ValueError:
        return None


def schedule_for(rounds: list[tuple[str, str]], today: date) -> dict:
    """모집 회차들 → {rounds, months, status, next_start, next_end, basis}.
    지금 모집 중 · 곧 모집이면 그 회차, 지났으면 가장 최근 회차의 1년 뒤로 예상 (지원정책은 매년 비슷한 때 나온다)"""
    rs = sorted({(s, e) for s, e in rounds if _d(s) or _d(e)})
    out: dict = {"rounds": [list(r) for r in rs]}
    if not rs:
        return {**out, "status": "unknown"}
    out["months"] = sorted({_d(s).month for s, _ in rs if _d(s)})
    last_s, last_e = _d(rs[-1][0]), _d(rs[-1][1])
    if last_e and last_e >= today:
        out.update(status="open" if not last_s or last_s <= today else "upcoming",
                   next_start=rs[-1][0], next_end=rs[-1][1], basis="공고된 회차")
        return out
    # 최근 1년 안의 회차마다 (1년에 두 번 · 학기마다 뽑는 장학금) 1년씩 옮겨 오늘 뒤에 오는 첫 회차 → 가장 이른 것이 다음 모집
    last = last_s or last_e
    # 해마다 같은 회차(작년 8월 · 올해 8월)는 하나로: 최근 회차부터 보고 45일 안에 이미 있으면 건너뛴다
    expected: list[tuple[date, date]] = []
    for s, e in reversed(rs):
        bs, be = _d(s) or _d(e), _d(e) or _d(s)
        if (last - bs).days >= 365:
            continue
        years = 1
        while _plus_years(be, years) < today:
            years += 1
        ns, ne = _plus_years(bs, years), _plus_years(be, years)
        if all(abs((ns - x).days) > 45 for x, _ in expected):
            expected.append((ns, ne))
    expected = [(s.isoformat(), e.isoformat()) for s, e in sorted(expected)]
    seen = sorted({_d(s).year for s, _ in rs if _d(s)})
    base = last_s or last_e
    basis = (f"{', '.join(map(str, seen))}년 {'·'.join(f'{m}월' for m in out['months'])} 모집 → 해마다 비슷한 때로 예상"
             if len(seen) > 1 else f"{seen[0] if seen else base.year}년 {base.month}월 모집 → 해마다 비슷한 때로 예상")
    out.update(status="expected", next_start=expected[0][0], next_end=expected[0][1], basis=basis,
               expected_rounds=[list(r) for r in expected])
    return out


def _plus_years(d: date, n: int) -> date:
    try:
        return d.replace(year=d.year + n)
    except ValueError:  # 2월 29일
        return d.replace(year=d.year + n, day=28)


def collect_history(versions: list[tuple[str, str]], get_json: GetJson, key: str, cache_dir: Path) -> dict[str, list[tuple[str, str]]]:
    """회분마다 자립준비청년 관련 장학금의 모집 기간을 모은다 → {row_id: [(시작, 종료) …]}.
    지난 회분은 바뀌지 않으므로 받은 회분은 cache_dir 에 남겨 다음에는 API 를 부르지 않는다"""
    rounds: dict[str, list[tuple[str, str]]] = {}
    for path, d in versions:
        cache = cache_dir / f"{d}.json"
        saved = read_json(cache, None)
        if saved is None:
            try:
                saved = [_round_of(r) for r in fetch_rows(path, get_json, key) if relevance(r)[0]]
            except Exception as e:  # 오래된 회분 하나가 안 되어도 나머지로 일정을 만든다
                print(f"[kosaf] {d} 회분 건너뜀: {type(e).__name__}", file=sys.stderr)
                continue
            write_json(cache, saved)
        for item in saved:
            sid, s, e = _round_of(item) if isinstance(item, dict) else item  # dict: 예전 저장 형식 (행 전체)
            rounds.setdefault(sid, []).append((s, e))
    return rounds


def _round_of(row: dict) -> list[str]:
    """회분 저장본에는 [장학금 id, 모집 시작일, 종료일]만 남긴다 (행 전체는 회분 하나 약 60KB × 73)"""
    return [row_id(row), (row.get("모집시작일") or "").strip(), (row.get("모집종료일") or "").strip()]


# ---------------- 수집 ----------------


def collect(*, today: date, get_json: GetJson = gov24._default_get_json, key: str | None = None,
            policies_path: Path = POLICIES_PATH, scholarships_path: Path = SCHOLARSHIPS_PATH,
            collected_dir: Path = COLLECTED_DIR, schedule_path: Path = SCHEDULE_PATH,
            dry_run: bool = False, history: bool = True) -> tuple[list[Converted], dict, str]:
    """(변환 결과, 모집 일정, 보고서). policies.json 은 읽기만 한다 (이미 있는 장학금 거르기)"""
    if key is None:
        from formguide.search import _service_key

        key = _service_key()
    versions = list_versions(get_json, collected_dir / "kosaf" / "versions.json")
    latest_path, latest_date = versions[-1]
    rows = fetch_rows(latest_path, get_json, key)

    current = [p for p in read_json(policies_path, []) if not p["policy_key"].startswith("S")]
    keymap = KeyMap(collected_dir / "kosaf_keys.json", prefix="S")
    taken: set[str] = set()
    names = NameIndex(current)
    # 정부24 · 웹에 이름만 다르게 이미 있는 장학금: 운영기관 + 모집 시작일이 같으면 같은 것
    # ("목포인재육성재단 · 2026-04-20": 정부24 '자립 지원 장학금' = 여기 '아동복지시설 퇴소대학생 자립지원')
    by_round = {(_agency_key(p.get("agency")), s): p["policy_key"] for p in current for s, _ in parse_rounds(deadline_text(p))}
    converted: list[Converted] = []
    dupes: list[str] = []
    for r in rows:
        if not relevance(r)[0]:
            continue
        sid = row_id(r)
        conv = to_policy(r, keymap.keys.get(sid, ""), today)
        if conv.policy is None:
            converted.append(conv)
            continue
        same = names.duplicate_of(conv.policy["name"], conv.policy["agency"], keymap.keys.get(sid))
        same = same or by_round.get((_agency_key(conv.policy["agency"]), (r.get("모집시작일") or "").strip()))
        if same:
            dupes.append(f"{conv.policy['name']} · {conv.policy['agency']} (= {same})")
            continue
        conv.policy["policy_key"] = keymap.key_for(sid, taken)
        names.add(conv.policy["name"], conv.policy["agency"], conv.policy["policy_key"])
        converted.append(conv)

    scholarships = sorted((_scholarship(c.policy) for c in converted if c.policy), key=lambda x: x["policy_key"])

    rounds = collect_history(versions, get_json, key, collected_dir / "kosaf" / "versions") if history else {}
    for r in rows:  # 최신 회분은 꼭 넣는다 (history 를 끈 때도)
        if relevance(r)[0]:
            rounds.setdefault(row_id(r), []).append(((r.get("모집시작일") or "").strip(), (r.get("모집종료일") or "").strip()))
    schedule = read_json(schedule_path, {})
    for c in converted:
        if c.policy:
            schedule[c.policy["policy_key"]] = {"name": c.policy["name"], "source": SOURCE_NAME,
                                                **schedule_for(rounds.get(c.service_id, []), today)}

    text_ = report(converted, schedule, dupes, today, latest_date, len(rows), len(versions) if history else 1)
    if not dry_run:
        write_json(scholarships_path, scholarships)  # 최신 회분이 곧 목록 (지난 모집도 남아 있어 통째로 다시 쓴다)
        keymap.save()
        write_json(schedule_path, dict(sorted(schedule.items())))
        write_json(collected_dir / "kosaf" / "latest.json", [r for r in rows if relevance(r)[0]])
        (collected_dir / "kosaf_report.md").write_text(text_, encoding="utf-8")
        from collector import schedule as apply_schedule

        # 언제 단계 · 시기 문구는 정책 전체(정부24 · 웹 포함)와 같은 규칙으로 (collector.schedule)
        apply_schedule.update(today, policies_path=policies_path, scholarships_path=scholarships_path,
                              schedule_path=schedule_path, history_path=collected_dir / "schedule_history.json")
    return converted, schedule, text_


_POLICY_ONLY = ("easy_text", "easy_text_verified", "source_type", "is_active")


def _scholarship(policy: dict) -> dict:
    """정책 형식에서 노출 · 쉬운 말 칸을 뺀다 (장학금은 앱에 정책으로 노출하지 않는다)"""
    return {k: v for k, v in policy.items() if k not in _POLICY_ONLY}


def report(converted: list[Converted], schedule: dict, dupes: list[str], today: date, version: str, total: int,
           versions: int) -> str:
    ok = [c for c in converted if c.policy]
    status = {"open": "모집 중", "upcoming": "곧 모집", "expected": "예상", "unknown": "모름"}
    lines = [f"# 장학금 수집 보고서 ({today.isoformat()}, {SOURCE_NAME} {version} 회분)", "",
             f"최신 회분 {total}건 중 자립준비청년 관련 {len(ok)}건 · 모집 일정은 회분 {versions}개로 정리", "",
             "`data/scholarships.json` (진로 로드맵용). 정책 추천 · 검색에는 넣지 않습니다.", "",
             "| 키 | 장학금 | 운영기관 | 지역 | 다음 모집 | 근거 |", "|---|---|---|---|---|---|"]
    for c in ok:
        p, s = c.policy, schedule.get(c.policy["policy_key"], {})
        nxt = f"{status.get(s.get('status'), '-')} {s.get('next_start', '')}~{s.get('next_end', '')}".strip()
        lines.append(f"| {p['policy_key']} | {p['name'][:28]} | {p['agency'][:16]} | {p['region']} | {nxt} | {s.get('basis', '')} |")
    lines += ["", "## 확인할 것"]
    for c in ok:
        extra = [n for n in c.notes if not n.startswith(("대상:", "분야"))]
        if extra:
            lines.append(f"- **{c.policy['policy_key']}** {c.policy['name'][:28]}: " + " · ".join(extra))
    skipped = [c for c in converted if not c.policy]
    if skipped or dupes:
        lines += ["", "## 넣지 않음"] + [f"- {c.skip_reason}" for c in skipped] + [f"- 이미 있는 정책과 같음: {d}" for d in dupes]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="한국장학재단 학자금지원정보 → 자립준비청년 장학금 수집 · 모집 일정")
    parser.add_argument("--dry-run", action="store_true", help="파일은 그대로, 보고서만 출력")
    parser.add_argument("--no-history", action="store_true", help="지난 회분 없이 최신 회분만 (모집 일정은 한 회차로)")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        converted, _, text_ = collect(today=date.today(), dry_run=args.dry_run, history=not args.no_history)
    except (CollectError, gov24.SearchError) as e:
        print(e, file=sys.stderr)
        return 1
    print(text_)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
