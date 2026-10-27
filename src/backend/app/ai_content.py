"""AI 담당 결과물(JSON 파일) 연결 지점.

- 쉬운 말 단계별 변환: data/easy_text/{policy_key}.json  (src/easytext/ 결과)
- 신청서 작성 항목:    data/forms/{form_id}.json          (src/formguide/ 결과)
- 정책 카드 요약:      data/policy_facts/{policy_key}.json (src/easytext/facts.py 결과)

파일은 요청마다 다시 읽지 않고 수정 시각이 바뀌면 다시 읽는다 (변환 결과를 서버 재시작 없이 반영).
"""

import json
import logging
import sys
from datetime import date
from pathlib import Path

from app.config import PROJECT_ROOT, get_settings

logger = logging.getLogger(__name__)

_SRC = str(PROJECT_ROOT / "src")
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)

# 순수 파이썬 모듈만 불러온다 (openai·chromadb 불필요)
from easytext.levels import DEFAULT_LEVEL, LEVELS, recommend_level  # noqa: E402
from formguide.guide import make_guide  # noqa: E402
from formguide.schema import FormSpec  # noqa: E402

MORE_MARKER = "[더 알아보기]"

_cache: dict[Path, tuple[float, object]] = {}


def _load_json(path: Path) -> object | None:
    try:
        mtime = path.stat().st_mtime
    except FileNotFoundError:
        return None
    hit = _cache.get(path)
    if hit and hit[0] == mtime:
        return hit[1]
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        logger.exception("JSON 읽기 실패: %s", path)
        return None
    _cache[path] = (mtime, data)
    return data


# ---------------- 쉬운 말 ----------------


def easy_levels(policy_key: str) -> dict[str, dict]:
    """자동 검사를 통과한 단계만 L1~L7 순서로. 파일이 없으면 빈 dict."""
    data = _load_json(get_settings().easy_text_dir / f"{policy_key}.json")
    if not isinstance(data, dict):
        return {}
    levels = data.get("levels") or {}
    return {code: levels[code] for code in LEVELS if isinstance(levels.get(code), dict) and levels[code].get("passed")}


def level_name(code: str) -> str:
    return LEVELS[code].name


def pick_level(available: list[str], requested: str | None, age: int | None) -> str:
    """요청 단계 > 나이 추천 단계 > 기본(L6). 없는 단계면 가장 가까운 단계."""
    want = requested or (recommend_level(age) if age is not None else DEFAULT_LEVEL)
    if want in available:
        return want
    order = list(LEVELS)
    idx = order.index(want)
    return min(available, key=lambda c: (abs(order.index(c) - idx), order.index(c)))


def split_more(level: dict) -> tuple[str, str | None]:
    """(핵심 요약, 더 알아보기). core_text 가 있으면 그대로 쓴다."""
    text: str = level.get("text") or ""
    core = level.get("core_text")
    head, sep, tail = text.partition(MORE_MARKER)
    more = tail.strip() if sep else None
    return (core or head).strip(), (more or None)


def recommended_for(age: int | None) -> str:
    return recommend_level(age) if age is not None else DEFAULT_LEVEL


# ---------------- 정책 카드 요약 ----------------


def _policy_facts(policy_key: str) -> dict | None:
    """자동 검사를 통과한 핵심 사실 결과. 없거나 통과 못 했으면 None."""
    data = _load_json(get_settings().policy_facts_dir / f"{policy_key}.json")
    return data if isinstance(data, dict) and data.get("passed") else None


def _summary(data: dict, raw_sections: object) -> dict | None:
    sections = [
        {"label": s["label"], "items": [i for i in s.get("items") or [] if isinstance(i, str) and i]}
        for s in raw_sections if isinstance(s, dict) and s.get("label")
    ] if isinstance(raw_sections, list) else []
    sections = [s for s in sections if s["items"]]
    if not sections:
        return None
    return {"sections": sections, "notice": data.get("notice"), "verified": bool(data.get("verified"))}


def policy_summary(policy_key: str) -> dict | None:
    """검색 카드 요약 {"sections", "notice", "verified"}. 없으면 None."""
    data = _policy_facts(policy_key)
    return _summary(data, data.get("card")) if data else None


def checklist_summary(policy_key: str, step_key: str) -> dict | None:
    """체크리스트 단계(target_check·condition_check·apply)별 요약. 형식은 policy_summary 와 같다."""
    data = _policy_facts(policy_key)
    checklist = data.get("checklist") if data else None
    return _summary(data, checklist.get(step_key)) if isinstance(checklist, dict) else None


def doc_short_labels(policy_key: str) -> dict[str, str]:
    """서류 원래 이름 → 짧은 이름. 없으면 빈 dict."""
    data = _policy_facts(policy_key)
    labels = data.get("doc_labels") if data else None
    if not isinstance(labels, dict):
        return {}
    return {k: v for k, v in labels.items() if isinstance(k, str) and isinstance(v, str) and v}


# ---------------- 신청서 작성 도우미 ----------------


def find_form(policy_key: str) -> FormSpec | None:
    """policy_key 가 같은 양식 중 사람이 확인한(checked) 것 우선, 그다음 form_id 순."""
    forms_dir = get_settings().forms_dir
    if not forms_dir.is_dir():
        return None
    specs: list[FormSpec] = []
    for path in sorted(forms_dir.glob("*.json")):
        if path.name.endswith((".source.json", ".extracted.json")):  # extracted: AI 추출 원본 (좌표 넣기 전)
            continue
        data = _load_json(path)
        if not isinstance(data, dict) or data.get("policy_key") != policy_key:
            continue
        try:
            specs.append(FormSpec.model_validate(data))
        except ValueError:
            logger.warning("양식 형식 오류로 건너뜀: %s", path)
    if not specs:
        return None
    return sorted(specs, key=lambda s: (not s.checked, s.form_id))[0]


def form_guide(spec: FormSpec, profile: dict | None) -> dict:
    return make_guide(spec, profile)


def profile_of(name: str, birth_date: date, region: str, phone: str | None) -> dict:
    return {"name": name, "birth_date": birth_date, "region": region, "phone": phone}
