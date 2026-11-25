"""수집한 정책을 data/policies/policies.json 에 합친다 (policy_key 기준).

규칙 (data/policies/README.md 작성 기준을 지킨다)
- 사람이 쓴 정책(source_type 이 manual 인 것, 예: P001)은 절대 바꾸지 않는다. 자동 수집(api · crawler)한 것만 고친다
- 새 정책은 뒤에 붙인다. easy_text_verified=false 라 쉬운 말 검수 전에는 앱에 보이지 않는다
- 내용이 바뀐 정책은 내용을 새로 쓰고 easy_text_verified 를 false 로 되돌린다 (쉬운 말을 다시 확인할 때까지 숨김).
  easy_text 는 다시 고칠 때 참고하도록 남긴다
- 내용이 같으면 checked_at(확인일)만 오늘로 바꾼다 (원문과 다시 대조했으므로)
- 사람이 고친 값(is_active, easy_text …)은 내용이 같으면 그대로 둔다. 분야 · 지역 · 나이는 사람이 고쳤을 때만 지킨다
- 이번에 안 나온 api 정책은 지우지 않고 보고서로만 알린다
"""

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

# 원문에서 다시 만드는 내용. 이것이 달라지면 '바뀜'
CONTENT_KEYS = (
    "name", "agency", "contact", "target_description", "support_content", "support_amount", "apply_method",
    "required_docs", "apply_url", "source_url", "original_text",
)  # support_amount: 원문에서 뽑는 값이라 뽑는 규칙이 바뀌어도 새로 써야 한다
# 자동 수집 출처 (db-schema.md 4장 source_type). 이것만 수집기가 고친다
AUTO_SOURCES = ("api", "crawler")
# 사람이 확인 · 수정하는 값: 이미 있으면 내용이 바뀌어도 덮어쓰지 않는다
# (금액 support_amount 는 원문을 따라가야 해서 넣지 않는다. 수집기가 채우지 않는 support_period 는 남긴다)
KEEP_KEYS = ("age_min", "age_max", "support_period", "easy_text", "is_active")
# 수집기가 규칙으로 고르는 값: 사람이 고쳤을 때만 지키고, 안 고쳤으면 새 규칙을 따른다
# (수집기가 마지막으로 넣은 값을 data/collected/auto_values.json 에 남겨, 지금 값과 같으면 '사람이 안 고침')
# 나이(age_min · age_max)는 11/23 부터 원문에서 분명한 것만 뽑아 넣는다 (convert.pick_ages). 전에는 늘 비워 둬서
# 기록이 없는 정책은 사람이 안 고친 것으로 본다 (그때까지 모두 null 이었음)
AUTO_KEYS = ("category", "region", "age_min", "age_max")
_LEGACY_EMPTY_KEYS = ("age_min", "age_max")  # 11/23 전 수집기는 늘 null 로 둠


@dataclass
class MergeResult:
    policies: list[dict]
    added: list[str] = field(default_factory=list)
    changed: list[str] = field(default_factory=list)
    unchanged: list[str] = field(default_factory=list)
    manual_kept: list[str] = field(default_factory=list)  # 같은 키의 사람 데이터가 있어 건너뜀
    missing: list[str] = field(default_factory=list)  # 전에 수집했는데 이번에 없음
    relabeled: list[str] = field(default_factory=list)  # 내용은 같고 분야 · 지역 규칙만 바뀌어 새로 쓴 것
    auto_values: dict[str, dict] = field(default_factory=dict)  # 이번에 수집기가 고른 분야 · 지역 (다음 합치기용)


def _auto_fields(old: dict, new: dict, auto_prev: dict | None) -> dict:
    """분야 · 지역 · 나이: 사람이 고쳤으면(지금 값 ≠ 수집기가 전에 넣은 값) 지금 값, 아니면 새 규칙 값.
    전에 넣은 값 기록이 없으면(기록을 만들기 전 데이터) 분야 · 지역은 사람이 안 고친 것으로,
    나이는 값이 있으면 사람이 넣은 것으로 본다"""
    out = {}
    for k in AUTO_KEYS:
        if k not in old:
            continue
        if auto_prev is not None and k in auto_prev:
            human_edited = old[k] != auto_prev[k]
        else:  # 기록 없음: 분야 · 지역은 안 고친 것으로, 나이는 예전 수집기가 늘 비웠으니 값이 있으면 사람이 넣은 것
            human_edited = k in _LEGACY_EMPTY_KEYS and old[k] is not None
        out[k] = old[k] if human_edited else new[k]
    return out


def merge(current: list[dict], collected: list[dict], *, full_run: bool = False,
          auto_prev: dict[str, dict] | None = None) -> MergeResult:
    """full_run: 키워드 전체로 돌린 수집이면 이번에 안 나온 api 정책을 missing 으로 알린다.
    auto_prev: policy_key → 수집기가 전에 넣은 {category, region} (사람이 고쳤는지 가리는 데 씀)"""
    auto_prev = auto_prev or {}
    by_key = {p["policy_key"]: i for i, p in enumerate(current)}
    out = [dict(p) for p in current]
    res = MergeResult(out)
    seen: set[str] = set()
    for new in collected:
        key = new["policy_key"]
        seen.add(key)
        res.auto_values[key] = {k: new[k] for k in AUTO_KEYS if k in new}
        if key not in by_key:
            out.append(new)
            res.added.append(key)
            continue
        old = out[by_key[key]]
        if old.get("source_type") not in AUTO_SOURCES:
            res.manual_kept.append(key)
            res.auto_values.pop(key, None)
            continue
        auto = _auto_fields(old, new, auto_prev.get(key))
        if all(old.get(k) == new.get(k) for k in CONTENT_KEYS):
            old["checked_at"] = new["checked_at"]
            if any(old.get(k) != v for k, v in auto.items()):
                old.update(auto)  # 내용은 같고 규칙만 바뀜: 분야 · 지역만 새로 (검수 상태는 그대로)
                res.relabeled.append(key)
            res.unchanged.append(key)
            continue
        changed_keys = {k for k in CONTENT_KEYS if old.get(k) != new.get(k)}
        if changed_keys == {"support_amount"}:
            # 원문은 그대로고 금액을 뽑는 규칙만 바뀜: 금액 칸만 새로 (쉬운 말은 원문에서 만든 것이라 검수 상태는 그대로)
            old["support_amount"] = new["support_amount"]
            old["checked_at"] = new["checked_at"]
            old.update(auto)
            res.relabeled.append(key)
            res.unchanged.append(key)
            continue
        merged = {**new, **{k: old[k] for k in KEEP_KEYS if k in old}, **auto, "easy_text_verified": False}
        out[by_key[key]] = {k: merged[k] for k in [*new, *(k for k in merged if k not in new)]}
        res.changed.append(key)
    if full_run:
        res.missing = [p["policy_key"] for p in current if p.get("source_type") == "api" and p["policy_key"] not in seen]
    return res


def name_key(name: str) -> str:
    """정책 이름 비교용. 괄호 안 · 띄어쓰기 · 문장부호 · 대소문자를 무시한다
    ("자립준비청년(보호종료아동) 자립수당 지급" = "자립준비청년 자립수당 지급")"""
    name = re.sub(r"\([^()]*\)|\[[^\[\]]*\]", "", name)
    return re.sub(r"[\s ·ㆍ,.\"'“”‘’:;~\-–—]", "", name).lower()


_SIGUNGU_RE = re.compile(r"\S+(시|군|구)$")


def agency_key(agency: str | None) -> str:
    """기관 비교용: 부서는 빼고 기관까지 ("보건복지부 청년정책팀" → 보건복지부, "서울특별시 서초구 복지과" → 서울특별시서초구)"""
    parts = (agency or "").split()
    if not parts:
        return ""
    return parts[0] + parts[1] if len(parts) > 1 and _SIGUNGU_RE.match(parts[1]) else parts[0]


class NameIndex:
    """(이름, 기관) → policy_key. 이미 있는 정책(사람이 쓴 P001 · 다른 수집기)과 같은 제도면 넣지 않을 때 쓴다.
    이름이 같아도 기관이 다르면 다른 제도다 (시 · 군 · 구마다 같은 이름의 '자립정착금 지원'이 따로 있다)"""

    def __init__(self, policies: list[dict]):
        self.known = {(name_key(p["name"]), agency_key(p.get("agency"))): p["policy_key"] for p in policies}

    def duplicate_of(self, name: str, agency: str | None, my_key: str | None) -> str | None:
        """같은 제도의 다른 정책 key. 내가 전에 넣은 정책(my_key)을 다시 수집한 것은 겹침이 아니다"""
        existing = self.known.get((name_key(name), agency_key(agency)))
        return existing if existing and existing != my_key else None

    def add(self, name: str, agency: str | None, key: str) -> None:
        self.known[(name_key(name), agency_key(agency))] = key


# ---------------- 파일 ----------------


def read_json(path: Path, default):
    if not path.exists():
        return default
    raw = path.read_text(encoding="utf-8-sig").strip()
    return json.loads(raw) if raw else default


def write_json(path: Path, data) -> None:
    """기존 파일의 줄바꿈(CRLF/LF)과 끝 줄바꿈 유무를 유지해 쓴다 (바뀐 줄만 diff 에 나오게)"""
    newline, trailing = "\n", "\n"
    if path.exists():
        old = path.read_bytes()
        if b"\r\n" in old[:4096]:
            newline = "\r\n"
        if old and not old.endswith(b"\n"):
            trailing = ""
    body = json.dumps(data, ensure_ascii=False, indent=2) + trailing
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline=newline) as f:
        f.write(body)


def load_auto_values(collected_dir: Path) -> dict[str, dict]:
    """수집기가 전에 넣은 분야 · 지역 (정부24 · 웹 수집이 한 파일을 같이 쓴다)"""
    return read_json(collected_dir / "auto_values.json", {})


def save_auto_values(collected_dir: Path, prev: dict[str, dict], res: "MergeResult") -> None:
    write_json(collected_dir / "auto_values.json", dict(sorted({**prev, **res.auto_values}.items())))


class KeyMap:
    """수집 대상 ID → policy_key (정부24 G001 …, 웹 W001 …). 한 번 정한 키는 바꾸지 않는다 (README: 키 유지)"""

    def __init__(self, path: Path, prefix: str = "G"):
        self.path = path
        self.PREFIX = prefix
        self.keys: dict[str, str] = read_json(path, {})

    def key_for(self, service_id: str, taken: set[str]) -> str:
        if service_id not in self.keys:
            used = set(self.keys.values()) | taken
            n = 1
            while f"{self.PREFIX}{n:03d}" in used:
                n += 1
            self.keys[service_id] = f"{self.PREFIX}{n:03d}"
        return self.keys[service_id]

    def save(self) -> None:
        write_json(self.path, dict(sorted(self.keys.items(), key=lambda kv: kv[1])))
