"""정책 → 단계별 쉬운 말 변환문. 검사를 통과할 때까지 최대 3번 다시 변환한다.

실행 (src 폴더에서, 백엔드 가상환경 사용, .env 의 OPENAI_API_KEY 필요):
    backend\\.venv\\Scripts\\python -m easytext.convert P001                    # 기본 단계(L6)
    backend\\.venv\\Scripts\\python -m easytext.convert P001 --levels L5 L6 L7
    backend\\.venv\\Scripts\\python -m easytext.convert P001 --levels L6 --judge  # 다른 모델로 의미 2차 검수

결과: data/easy_text/{policy_key}.json (단계별 변환문·검사 결과·지표). "verified" 는 사람이 원문과 대조한 뒤 true 로 바꾼다.
"""

import argparse
import json
import re
from collections.abc import Callable
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from easytext.checks import _EXCLUSION_RE, _META_WORDS, check, metrics
from easytext.glossary import add_glosses, apply_rewrites, hints_for, term_links
from easytext.levels import DEFAULT_LEVEL, Level, get_level
from easytext.prompts import (
    CONVERT_SYSTEM, CONVERT_USER, ELIGIBILITY_NOTICE, FILL_CORE_USER, FILL_MISSING_USER, ITEM_ALIASES, ITEMS, JUDGE_SYSTEM, JUDGE_USER, RETRY_USER, level_instructions,
)
from rag.settings import PROJECT_ROOT

POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
OUT_DIR = PROJECT_ROOT / "data" / "easy_text"
MAX_RETRIES = 3

# messages → JSON dict
Generate = Callable[[list[dict]], dict]


def source_text(policy: dict) -> str:
    """변환 입력: 데이터 담당이 정리한 정형 필드. original_text(출처·쪽수 포함 원문)는 넣지 않는다."""
    docs = []
    for d in policy.get("required_docs") or []:
        if isinstance(d, dict):
            docs.append(f"- {d['name']}" + (f" (조건: {d['condition']})" if d.get("condition") else ""))
        else:
            docs.append(f"- {d}")
    parts = [
        ("정책명", policy.get("name")), ("담당 기관", policy.get("agency")), ("문의처", policy.get("contact")),
        ("지원 대상", policy.get("target_description")), ("지원 내용", policy.get("support_content")),
        ("지원 금액", policy.get("support_amount")), ("지원 기간", policy.get("support_period")),
        ("신청 방법", policy.get("apply_method")), ("필요 서류", "\n".join(docs) if docs else None),
    ]
    return "\n\n".join(f"[{k}]\n{v}" for k, v in parts if v)


_UNIT_FIELDS = (
    ("지원 대상", "target_description"), ("지원 내용", "support_content"), ("지원 금액", "support_amount"),
    ("지원 기간", "support_period"), ("신청 방법", "apply_method"),
)


# 한 문장에 조건이 여러 개면 조건마다 나눈다: "…사전신청하며, 가정위탁 예정자는 …", "① … 또는 ② …"
_CLAUSE_SPLIT_RE = re.compile(r"(?<=하며),\s*|(?<=이며),\s*|\s*(?=[②③④⑤])")


def _clauses(sentence: str) -> list[str]:
    parts = [re.sub(r"[,\s]*(또는)?\s*$", "", p).strip() for p in _CLAUSE_SPLIT_RE.split(sentence)]
    return [p for p in parts if len(p) >= 4]


_NEG_RE = re.compile(r"불가|제외|중지|않|없|금지|못")
# 인용 문장에서 인정하는 부정 표현 (쉬운 단계의 "멈춥니다", "안 됩니다" 포함)
_NEG_EASY_RE = re.compile(r"불가|제외|중지|않|없|금지|못|멈|끊|안\s?(?:됩|줍|받|나|돼)")
# 동작을 나타내는 용어는 쉬운 단계에서 풀어 쓰는 게 정상이라 단위별 확인에서 뺀다 ("18세 도달" → "18세가 된")
_VERBAL_TERMS = {"지급", "수급", "도달", "종결", "전일", "만기", "본인인증"}


def _subject_stem(text: str) -> str | None:
    """원문 단위의 주어 ("시설 보호종료 예정자는 …" → "예정자"). 누구에 대한 조건인지 빠지지 않게 확인한다."""
    m = re.match(r"^[^,]{0,30}?(\S{2,})(?:는|은)\s", text.strip())
    if not m:
        return None
    stem = re.sub(r"[^가-힣]", "", m.group(1))
    # 사람 주어만 본다 (예정자·보호종료자·대리인 등). "지급일은", "업무매뉴얼에는" 같은 말은 주어 확인에서 뺀다
    return stem if len(stem) >= 2 and stem.endswith(("자", "인", "원")) else None


def _strip_parens(text: str) -> str:
    while re.search(r"\([^()]*\)", text):
        text = re.sub(r"\([^()]*\)", " ", text)
    return text


def _cited_texts(draft: dict, level: Level) -> dict[str, str]:
    """원문 번호 → 그 번호를 인용한 문장들 (안전한 표현 바꾸기를 적용한 뒤)."""
    out: dict[str, str] = {}
    for part in ("core", "more"):
        for b in (draft.get(part) or []) if isinstance(draft, dict) else []:
            for sent in (b.get("sentences") or []) if isinstance(b, dict) else []:
                if isinstance(sent, dict) and isinstance(sent.get("src"), list):
                    text = apply_rewrites(_sentence_text(sent), level.gloss_tier)
                    for i in sent["src"]:
                        out[str(i).strip()] = out.get(str(i).strip(), "") + " " + text
    return out


def _term_variants(term) -> set[str]:
    """용어를 찾을 때 인정하는 표현: 원래 용어, 쉬운 말, 풀이, 안전한 바꿔 쓰기 결과 (앞 4글자로 비교)."""
    from easytext.checks import normalize

    raw = {term.term, term.easy, term.gloss, *(new for _, new in term.rewrites)}
    out = {normalize(x)[:4] for x in raw if x and "풀어 씀" not in x}
    # 여러 단어로 된 용어는 핵심 첫 단어만 있어도 인정 ("연장 보호" → "연장")
    words = term.term.split()
    if len(words) > 1 and len(words[0]) >= 2:
        out.add(normalize(words[0]))
    return out


def unit_content_gaps(units: list[dict], draft: dict, level: Level) -> list[str]:
    """인용한 문장 안에 그 원문 단위의 핵심 요소가 있는지 본다 (인용만 하고 내용을 빼는 것 방지).

    핵심 요소: 숫자·경계 단어(출처 메모 단위 제외), 용어 사전에 있는 용어(쉬운 말 인정), 부정 표현(불가·제외·중지 등).
    """
    from easytext.checks import _META_WORDS, _number_facts, normalize
    from easytext.glossary import find_terms

    cited = _cited_texts(draft, level)
    gaps = []
    for u in units:
        if not u["required"] or u["id"] not in cited:
            continue
        text, got = u["text"], normalize(cited[u["id"]])
        body = _strip_parens(text)  # 용어·부정 표현은 괄호 밖 본문만 (괄호 속 메뉴 경로·보충 조건 제외)
        lacking = []
        if not any(w in text for w in _META_WORDS):  # 숫자는 괄호 안("2018년 8월 이후")도 본다
            lacking += [f.label for f in _number_facts([text])
                        if f.label != "1부" and not any(n in got for n in f.needles)]  # 서류 1부는 세부
        for t in {t.term: t for t, _ in find_terms(body) if t.tier >= 2 and t.term not in _VERBAL_TERMS}.values():
            if not any(v and v in got for v in _term_variants(t)):
                lacking.append(t.term)
        subject = _subject_stem(body)
        if subject and normalize(subject)[:3] not in got:
            lacking.append(f"주어 '{subject}'")
        if _NEG_RE.search(body) and not _NEG_EASY_RE.search(cited[u["id"]]):
            lacking.append("부정 표현(불가·제외·중지 등)")
        if lacking:
            gaps.append(f'{u["id"]}: {", ".join(dict.fromkeys(lacking))} — 원문: "{text[:100]}"')
    return gaps


def source_units(policy: dict) -> list[dict]:
    """원문을 번호 붙은 단위(S1, S2, ...)로 나눈다. 변환문 문장은 어느 단위에서 왔는지 번호를 단다.

    required=False: 출처끼리 비교하는 메모 문장 (안 써도 됨). 나머지는 변환문 어디선가 꼭 인용돼야 한다.
    """
    units = []

    def add(field: str, text: str, required: bool = True):
        units.append({"id": f"S{len(units) + 1}", "field": field, "text": text.strip(), "required": required})

    for label, key in _UNIT_FIELDS:
        for sent in (c for x in re.split(r"(?<=[.다])\s+", policy.get(key) or "") for c in _clauses(x)):
            if sent.strip():
                # 출처 메모가 섞여 있어도 제외 조건이 들어 있으면 필수
                add(label, sent, not any(w in sent for w in _META_WORDS) or bool(_EXCLUSION_RE.search(sent)))
    for d in policy.get("required_docs") or []:
        if isinstance(d, dict):
            add("필요 서류", d["name"] + (f" (조건: {d['condition']})" if d.get("condition") else ""))
        else:
            add("필요 서류", str(d))
    if policy.get("contact"):
        add("문의처", policy["contact"])
    return units


def units_text(policy: dict, units: list[dict]) -> str:
    head = f"[정책명] {policy.get('name')}\n[담당 기관] {policy.get('agency')}"
    lines = [f"{u['id']}{'' if u['required'] else ' (선택)'} [{u['field']}] {u['text']}" for u in units]
    return head + "\n\n" + "\n".join(lines)


def cited_ids(draft: dict) -> set[str]:
    out: set[str] = set()
    for part in ("core", "more"):
        for b in draft.get(part) or [] if isinstance(draft, dict) else []:
            for sent in (b.get("sentences") or []) if isinstance(b, dict) else []:
                if isinstance(sent, dict) and isinstance(sent.get("src"), list):
                    out.update(str(x).strip() for x in sent["src"])
    return out


def _sentence_text(sent: object) -> str:
    if isinstance(sent, dict):
        sent = sent.get("text")
    return sent.strip() if isinstance(sent, str) else ""


def _blocks(raw: object) -> list[tuple[str, list[str]]]:
    """LLM 출력의 블록 목록을 항목 순서대로 정리. 모르는 항목 이름은 '확인 필요' 앞에 둔다."""
    blocks: dict[str, list[str]] = {}
    for b in raw if isinstance(raw, list) else []:
        if not isinstance(b, dict):
            continue
        item = str(b.get("item") or "").strip() or "확인 필요"
        item = ITEM_ALIASES.get(item, item)
        sents = [t for t in (_sentence_text(x) for x in b.get("sentences") or []) if t]
        if sents:
            blocks.setdefault(item, []).extend(sents)
    order = {name: i for i, name in enumerate(ITEMS)}
    return sorted(blocks.items(), key=lambda kv: order.get(kv[0], len(ITEMS) - 1.5))


def _paragraph(item: str, sents: list[str]) -> str:
    """항목 하나 = 문단 하나. 번호 목록은 줄을 바꾼다."""
    lines: list[str] = []
    for s in sents:
        if s[:1].isdigit() or not lines or lines[-1][:1].isdigit():
            lines.append(s)
        else:
            lines[-1] += " " + s
    return f"[{item}]\n" + "\n".join(lines)


# core 가 길면 이 순서로 항목을 통째로 더 알아보기로 옮긴다 (내용은 그대로, 위치만 바뀜)
_MOVE_ORDER = ("확인 필요", "문의", "기간", "받을 수 없는 경우")
# core 에 꼭 있어야 하는 항목 (처음 읽는 사람이 바로 알아야 할 것)
CORE_REQUIRED_ITEMS = ("받는 것", "받는 사람", "신청 방법", "서류")


def _fit_core(core_b, more_b, max_sentences: int):
    from easytext.checks import sentences

    def count(blocks):
        return sum(len(sentences("\n".join(s))) for _, s in blocks)

    core_b, more_b = list(core_b), list(more_b)
    for item in _MOVE_ORDER:
        if count(core_b) <= max_sentences:
            break
        moved = [b for b in core_b if b[0] == item]
        if not moved:
            continue
        core_b = [b for b in core_b if b[0] != item]
        rest = [b for b in more_b if b[0] == item]
        merged = (item, moved[0][1] + (rest[0][1] if rest else []))
        more_b = [merged] + [b for b in more_b if b[0] != item]
    # 그래도 길면: 항목마다 앞 2문장만 core 에 두고, 나머지는 more 의 같은 항목 앞쪽으로 옮긴다
    # (문자열 하나에 여러 문장이 있을 수 있어, 문장 수를 다시 세며 가장 긴 항목의 마지막 조각부터 옮긴다)
    # 한 문자열에 문장이 여러 개면 문장 단위로 쪼개 둬야 끝까지 옮길 수 있다
    core_b = [(i, [p for x in s for p in ([x] if x[:1].isdigit() else _SPLIT_RE.split(x))]) for i, s in core_b]
    while count(core_b) > max_sentences:
        longest = max(core_b, key=lambda b: len(sentences("\n".join(b[1]))))
        if len(longest[1]) <= 1:
            break
        item, piece = longest[0], longest[1].pop()
        rest = next((s for i, s in more_b if i == item), [])
        more_b = [(item, [piece] + rest)] + [b for b in more_b if b[0] != item]
    order = {name: i for i, name in enumerate(ITEMS)}
    more_b.sort(key=lambda kv: order.get(kv[0], len(ITEMS) - 1.5))
    return core_b, more_b


def _with_notice(more_b):
    """확인 필요 항목 끝에 자격 단정 금지 고정 문장을 붙인다 (이미 있으면 그대로)."""
    more_b = list(more_b)
    for idx, (item, sents) in enumerate(more_b):
        if item == "확인 필요":
            if ELIGIBILITY_NOTICE not in sents:
                more_b[idx] = (item, sents + [ELIGIBILITY_NOTICE])
            return more_b
    return more_b + [("확인 필요", [ELIGIBILITY_NOTICE])]


def _copy_missing_core(core_b, more_b):
    """core 에 꼭 있어야 할 항목이 끝내 없으면, more 의 같은 항목 첫 두 문장을 core 에 복사한다 (이미 검사받는 내용)."""
    have = {i for i, _ in core_b}
    out = list(core_b)
    for item in CORE_REQUIRED_ITEMS:
        if item in have:
            continue
        src = next((s for i, s in more_b if i == item), None)
        if src:
            out.append((item, src[:2]))
    order = {name: i for i, name in enumerate(ITEMS)}
    return sorted(out, key=lambda kv: order.get(kv[0], len(ITEMS) - 1.5))


_SPLIT_RE = re.compile(r"(?<=[.!?])\s+(?=\S)")
# 앞 문장을 가리키는 시작 말 (앞 문장 없이 혼자 있으면 뜻이 끊김)
_REFERS_BACK_RE = re.compile(r"^(이 경우|이때|이는|그 경우|그때|이런 경우|예를 들면)")


def _dedupe(core_b, more_b):
    """같은 문장이 두 번 나오지 않게 한다: 각 항목 안의 반복, 그리고 core 와 똑같은 문장이 more 의 같은 항목에 또 있는 것.

    번호 목록 줄은 그대로 두고, 일반 문장은 문장 단위로 비교한다 (공백 차이 무시).
    """
    def key(s: str) -> str:
        return re.sub(r"\s+", "", s)

    def clean(sents: list[str], seen: set[str]) -> list[str]:
        out = []
        for s in sents:
            parts = [s] if s[:1].isdigit() else _SPLIT_RE.split(s)
            kept = []
            for k, p in enumerate(parts):
                nxt = parts[k + 1] if k + 1 < len(parts) else ""
                # 다음 문장이 "이 경우·이때"로 앞 문장을 가리키면, 중복이어도 앞 문장을 남긴다 (뜻이 끊기지 않게)
                if key(p) not in seen or _REFERS_BACK_RE.match(nxt):
                    seen.add(key(p))
                    kept.append(p)
            if kept:
                out.append(" ".join(kept))
        return out

    core_seen: dict[str, set[str]] = {}
    new_core = []
    for item, sents in core_b:
        seen = core_seen.setdefault(item, set())
        new_core.append((item, clean(sents, seen)))
    new_more = []
    for item, sents in more_b:
        seen = set(core_seen.get(item, set()))
        kept = clean(sents, seen)
        if kept:
            new_more.append((item, kept))
    return [b for b in new_core if b[1]], new_more


# 번호 목록 줄 ("1. …", "2) …"). "18세 이후…"처럼 숫자로 시작하는 문장과 구분한다
_NUMBERED_RE = re.compile(r"^\d+[.)]\s")
# 빠져도 뜻이 그대로인 말 (문장 끝·일반 명사). 이 말만 다르면 같은 내용으로 본다
_GENERIC_STEMS = {"사람", "경우", "있습", "합니", "입니", "됩니", "때는", "돈은"}


def _stems(s: str) -> set[str]:
    """비교용 낱말: 어절 앞 두 글자 (조사·어미 차이 무시). 괄호 안 조건도 포함한다."""
    words = re.findall(r"[0-9]+|[가-힣A-Za-z]+", s)
    return {w if w.isdigit() else w[:2] for w in words if w.isdigit() or len(w) >= 2}


def _covered_by(a: str, b: str) -> bool:
    """a 의 내용(숫자·낱말·부정 표현)이 모두 b 에 있으면 a 는 b 의 반복이다."""
    if bool(_NEG_EASY_RE.search(a)) != bool(_NEG_EASY_RE.search(b)):  # "할 수 있다"와 "할 수 없다"는 다른 내용
        return False
    sa, sb = _stems(a), _stems(b)
    nums = {w for w in sa if w.isdigit()}
    return bool(sa) and nums <= sb and not (sa - sb - _GENERIC_STEMS) and len(sb) >= len(sa)


def _drop_covered(blocks):
    """같은 항목 안에서 표현만 다르고 내용이 다른 문장에 다 들어 있는 문장을 뺀다 (더 자세한 문장을 남긴다).

    번호 목록 줄과, 다음 문장이 "이 경우·이때"로 가리키는 문장은 남긴다.
    """
    out = []
    for item, sents in blocks:
        listed = lambda x: bool(_NUMBERED_RE.match(x))
        parts = [(k, p) for k, s in enumerate(sents) for p in ([s] if listed(s) else _SPLIT_RE.split(s))]
        drop: set[int] = set()
        for n, (k, p) in enumerate(parts):
            nxt = parts[n + 1][1] if n + 1 < len(parts) and parts[n + 1][0] == k else ""
            if listed(p) or _REFERS_BACK_RE.match(nxt):
                continue
            for m, (_, q) in enumerate(parts):
                if m == n or m in drop or listed(q) or not _covered_by(p, q):
                    continue
                # 서로 같은 내용이면 뒤에 나온 쪽을 뺀다
                if _covered_by(q, p) and m > n:
                    continue
                drop.add(n)
                break
        rebuilt = []
        for k in range(len(sents)):
            kept = [p for n, (kk, p) in enumerate(parts) if kk == k and n not in drop]
            if kept:
                rebuilt.append(" ".join(kept))
        if rebuilt:
            out.append((item, rebuilt))
    return out


def _drop_unconditional(core_b, more_b, policy: dict | None):
    """조건부 서류를 조건 없이 말한 문장은, 같은 서류를 조건과 함께 말한 문장이 따로 있으면 뺀다 (중복 + 범위 오해 제거).

    조건이 있는 문장이 하나도 없으면 빼지 않는다 (그때는 검사가 지적해 다시 변환한다).
    """
    from easytext.checks import doc_conditions, normalize

    if not policy:
        return core_b, more_b
    conds = doc_conditions(policy)
    pieces = [_SPLIT_RE.split(x) for _, sents in core_b + more_b for x in sents]
    flat = [normalize(p) for ps in pieces for p in ps]

    def droppable(p: str) -> bool:
        n = normalize(p)
        for dc in conds:
            if dc.mentioned(n) and not dc.satisfied(n) and any(dc.mentioned(f) and dc.satisfied(f) for f in flat):
                return True
        return False

    def clean(blocks):
        out = []
        for item, sents in blocks:
            kept = []
            for x in sents:
                parts = [p for p in ([x] if x[:1].isdigit() else _SPLIT_RE.split(x)) if not droppable(p)]
                if parts:
                    kept.append(" ".join(parts))
            if kept:
                out.append((item, kept))
        return out

    return clean(core_b), clean(more_b)


def _attach_conditions(core_b, more_b, policy: dict | None, level: Level):
    """조건부 서류를 조건 없이 말한 문장에 원문 조건을 괄호로 붙인다 ("위임장이 필요합니다(조건: 대리 신청 시).").

    원문 조건을 그대로(쉬운 표현 쌍만 적용) 붙이므로 뜻이 바뀌지 않는다. core 에 있던 문장은 길어지므로
    more 의 같은 항목 앞쪽으로 옮긴다.
    """
    from easytext.checks import doc_conditions, normalize

    if not policy:
        return core_b, more_b
    conds = doc_conditions(policy)

    postal_limited = any("제한" in x for x in re.split(r"(?<=[.다])\s+", policy.get("apply_method") or "")
                         if re.search(r"우편|팩스", x))

    def fix(p: str) -> tuple[str, bool]:
        n = normalize(p)
        # 우편·팩스 신청은 "직접 방문이 제한될 때"만 가능 → 조건이 없으면 붙인다
        bare = re.sub(r"\([^()]*\)", "", re.sub(r"\([^()]*\)", "", p))
        if postal_limited and re.search(r"우편|팩스", p) and bare.strip(" .") and not re.search(r"제한|어려|못", p):
            body = p.rstrip()
            end = body[-1] if body[-1:] in ".!?" else "."
            body = body[:-1] if body[-1:] in ".!?" else body
            return f"{body}(직접 방문이 제한될 때){end}", True
        for dc in conds:
            if dc.mentioned(n) and not dc.satisfied(n):
                body = p.rstrip()
                end = body[-1] if body[-1:] in ".!?" else "."
                body = body[:-1] if body[-1:] in ".!?" else body
                return f"{body}(조건: {apply_rewrites(dc.condition, level.gloss_tier)}){end}", True
        return p, False

    def split(x: str) -> list[str]:
        return [x] if x[:1].isdigit() else _SPLIT_RE.split(x)

    new_core, moved = [], []
    for item, sents in core_b:
        kept = []
        for x in sents:
            stay = []
            for pt in split(x):
                fixed, changed = fix(pt)
                (moved.append((item, fixed)) if changed else stay.append(pt))
            if stay:
                kept.append(" ".join(stay))
        if kept:
            new_core.append((item, kept))
    new_more = [(item, [" ".join(fix(pt)[0] for pt in split(x)) for x in sents]) for item, sents in more_b]
    for item, pt in moved:
        idx = next((k for k, (i, _) in enumerate(new_more) if i == item), None)
        if idx is None:
            new_more.append((item, [pt]))
        else:
            new_more[idx] = (item, [pt] + new_more[idx][1])
    order = {name: i for i, name in enumerate(ITEMS)}
    new_more.sort(key=lambda kv: order.get(kv[0], len(ITEMS) - 1.5))
    return _drop_orphan_conditions(new_core, conds), _drop_orphan_conditions(new_more, conds)


# 서류를 가리키는 말. 이런 말이 없는 "…할 때 필요합니다." 문장은 무엇이 필요한지 알 수 없다
_DOC_NOUN_RE = re.compile(r"서류|증명서|확인서|사본|신분증|신청서|위임장|이수증|수료증")  # "통장"은 압류방지통장 조건에도 나와서 제외


def _drop_orphan_conditions(blocks, conds):
    """서류 이름 없이 조건만 있는 문장("…실패할 때 필요합니다.")은, 그 조건이 이미 서류 문장에 붙어 있으면 뺀다."""
    from easytext.checks import normalize

    all_parts = [p for _, sents in blocks for x in sents for p in _SPLIT_RE.split(x)]
    covered = [dc for dc in conds
               if any(dc.mentioned(normalize(p)) and dc.satisfied(normalize(p)) for p in all_parts)]

    def orphan(p: str) -> bool:
        n = normalize(p)
        body = re.sub(r"\([^()]*\)", "", p)
        return (bool(re.search(r"(필요합니다|제출합니다)[.!?]?$", p.strip()))
                and not _DOC_NOUN_RE.search(body)
                and any(dc.satisfied(n) for dc in covered))

    out = []
    for item, sents in blocks:
        kept = []
        for x in sents:
            parts = [x] if x[:1].isdigit() else [p for p in _SPLIT_RE.split(x) if not orphan(p)]
            if parts:
                kept.append(" ".join(parts))
        if kept:
            out.append((item, kept))
    return out


# 문장 뒤에 따로 떨어진 조건 괄호 "…필요합니다. (대리 신청 시)" → "…필요합니다(대리 신청 시)."
_TRAILING_PAREN_RE = re.compile(r"([.!?])\s*(\((?:[^()]|\([^()]*\))*\))(?=\s|$)")


def _tidy(text: str) -> str:
    """뜻을 바꾸지 않는 문장 다듬기: 떨어진 조건 괄호를 앞 문장에 붙이고, "…하세요." 를 합니다체로 바꾼다."""
    text = _TRAILING_PAREN_RE.sub(r"\2\1", text)
    return re.sub(r"하세요(?=[.!?]|$)", "하시기 바랍니다", text)


def render(draft: dict, level: Level | None = None, policy: dict | None = None) -> tuple[str, str]:
    """(핵심 블록, 전체). 항목마다 문단을 나누고, 더 알아보기는 머리말 뒤에 붙인다.

    level 을 주면 안전한 표현 바꾸기·core 맞추기·중복 제거 등 뜻을 바꾸지 않는 정리를 한다.
    용어 풀이는 문장에 넣지 않고 결과의 "terms" 목록으로 따로 내보낸다 (앱이 사전 풀이로 보여줌).
    """
    core_b, more_b = _blocks(draft.get("core")), _blocks(draft.get("more"))
    if level is not None:
        rw = lambda blocks: [(i, [apply_rewrites(_tidy(x), level.gloss_tier) for x in sents]) for i, sents in blocks]
        core_b, more_b = rw(core_b), rw(more_b)
        core_b, more_b = _drop_unconditional(core_b, more_b, policy)
        core_b, more_b = _attach_conditions(core_b, more_b, policy, level)
        core_b = _copy_missing_core(core_b, more_b)
        core_b, more_b = _fit_core(core_b, more_b, level.core_sentences[1])
        core_b, more_b = _dedupe(core_b, more_b)
        core_b, more_b = _drop_covered(core_b), _drop_covered(more_b)
        more_b = _with_notice(more_b)
        glossed = add_glosses([s for _, s in core_b + more_b], level.plain_tier, level.gloss_tier)
        n = len(core_b)
        core_b = [(i, g) for (i, _), g in zip(core_b, glossed[:n])]
        more_b = [(i, g) for (i, _), g in zip(more_b, glossed[n:])]
    core = "\n\n".join(_paragraph(i, s) for i, s in core_b)
    more = "\n\n".join(_paragraph(i, s) for i, s in more_b)
    full = core + (f"\n\n[더 알아보기]\n\n{more}" if more else "")
    return core, full


def _claimed_missing_but_present(original: str, full: str, level: Level) -> bool:
    """2차 검수가 "빠졌다"고 한 원문의 조건마다 핵심 요소(숫자·용어·부정 표현)가 변환문 전체에 있으면 오탐으로 본다."""
    units = [{"id": f"J{i}", "field": "", "required": True, "text": c}
             for i, c in enumerate(c for s in re.split(r"(?<=[.다])\s+", original) for c in _clauses(s))]
    if not units:
        return False
    whole = {"more": [{"item": "확인", "sentences": [{"text": full, "src": [u["id"] for u in units]}]}]}
    return unit_content_gaps(units, whole, level) == []


def _judge(judge: Generate, src: str, full: str, level: Level | None = None) -> list[dict]:
    verdict = judge([
        {"role": "system", "content": JUDGE_SYSTEM},
        {"role": "user", "content": JUDGE_USER.format(source=src, converted=full)},
    ])
    issues = verdict.get("issues") if isinstance(verdict, dict) else None
    out = []
    for i in issues if isinstance(issues, list) else []:
        if not isinstance(i, dict):
            continue
        # "빠졌다"면서 변환문의 해당 문장을 찾아 왔다면 빠진 게 아니다 (표현 지적)
        # 빠진 내용은 원문 번호 인용(coverage)으로 코드가 확인한다. 2차 검수는 뜻이 바뀐 것만 본다
        if i.get("type") != "changed":
            continue
        # "빠졌다"는 지적인데 그 조건의 핵심 요소가 변환문 어딘가(더 알아보기 포함)에 다 있으면 오탐
        reason = str(i.get("reason") or "")
        if level is not None and any(w in reason for w in ("빠", "누락", "없음", "없습니다"))                 and _claimed_missing_but_present(str(i.get("original") or ""), full, level):
            continue
        # "다소 모호하다"처럼 표현만 지적하고 구체적인 변화(빠짐·다름·범위)가 없으면 사소한 지적으로 본다
        if any(w in reason for w in ("다소", "모호", "명확하지", "어색")) and not any(
                w in reason for w in ("빠", "누락", "다르", "반대", "잘못", "넓어", "좁아", "바뀌", "변경")):
            i = {**i, "severity": "low"}
        # 메뉴 경로("로그인 > 서비스 신청 > …") 생략은 세부 안내라 통과를 막지 않는다
        if ">" in str(i.get("original") or ""):
            i = {**i, "severity": "low"}
        # 출처끼리 비교하는 메모("업무매뉴얼 표기가 달라")는 정책 조건이 아니다
        if any(w in str(i.get("original") or "") for w in _META_WORDS):
            continue
        out.append(i)
    return out


def _blocking(issues: list[dict]) -> list[dict]:
    """통과를 막는 지적: severity 가 high 이거나 없는 것 (없으면 안전하게 high 로 본다)."""
    return [i for i in issues if str(i.get("severity") or "high").lower() != "low"]


def _judge_feedback(issues: list[dict]) -> list[str]:
    return [
        f"뜻이 바뀌거나 빠짐: 원문 \"{i.get('original', '')}\" → 변환문 \"{i.get('converted', '')}\" ({i.get('reason', '')}). "
        "원문 뜻 그대로 고칠 것"
        for i in issues
    ]


def _fill_core(draft: dict, level: Level, generate: Generate, src: str) -> dict:
    """core 에 꼭 있어야 할 항목이 없으면, more 를 바탕으로 그 항목만 짧게 요약하는 좁은 요청을 한 번 더 한다."""
    if not isinstance(draft, dict):
        return draft
    have = {item for item, _ in _blocks(draft.get("core"))}
    lacking = [i for i in CORE_REQUIRED_ITEMS if i not in have]
    if not lacking:
        return draft
    extra = generate([
        {"role": "system", "content": CONVERT_SYSTEM},
        {"role": "user", "content": FILL_CORE_USER.format(
            level_block=level_instructions(level, hints_for(src, level.plain_tier, level.gloss_tier)),
            items=", ".join(lacking), more=json.dumps(draft.get("more") or [], ensure_ascii=False))},
    ])
    added = [b for b in (extra.get("core") if isinstance(extra, dict) else None) or []
             if isinstance(b, dict) and ITEM_ALIASES.get(str(b.get("item")), b.get("item")) in lacking]
    return {**draft, "core": list(draft.get("core") or []) + added}


def _fill_missing(draft: dict, level: Level, generate: Generate, src: str, units: list[dict]) -> dict:
    """빠진 원문 단위(인용 안 됨)와 내용이 일부 빠진 단위만 골라 따로 바꿔 more 에 더한다.

    쉬운 단계는 전체를 다시 쓰면 다른 곳이 또 빠지기 쉬워, 빠진 부분만 좁게 요청한다.
    중복은 render 의 반복 정리가 뺀다 (더 자세한 문장을 남김).
    """
    if not isinstance(draft, dict):
        return draft
    cited = cited_ids(draft)
    gap_ids = {g.split(":", 1)[0] for g in unit_content_gaps(units, draft, level)}
    todo = [u for u in units if u["required"] and (u["id"] not in cited or u["id"] in gap_ids)]
    if not todo:
        return draft
    extra = generate([
        {"role": "system", "content": CONVERT_SYSTEM},
        {"role": "user", "content": FILL_MISSING_USER.format(
            level_block=level_instructions(level, hints_for(src, level.plain_tier, level.gloss_tier)),
            items=", ".join(ITEMS), units="\n".join(f'[{u["id"]}] ({u["field"]}) {u["text"]}' for u in todo))},
    ])
    wanted = {u["id"] for u in todo}
    added = []
    for b in (extra.get("more") if isinstance(extra, dict) else None) or []:
        if not isinstance(b, dict):
            continue
        item = ITEM_ALIASES.get(str(b.get("item")), b.get("item"))
        # 요청한 원문 번호를 단 문장만 받는다 (엉뚱한 내용이 끼어드는 것 방지)
        sents = [s for s in b.get("sentences") or [] if isinstance(s, dict) and set(s.get("src") or []) & wanted]
        if item in ITEMS and sents:
            added.append({"item": item, "sentences": sents})
    return {**draft, "more": list(draft.get("more") or []) + added}


def convert_level(policy: dict, level: Level, generate: Generate, judge: Generate | None = None) -> dict:
    """자동 검사 → (judge 가 있으면) 다른 모델의 의미 검수까지 통과해야 passed.

    judge 는 자동 검사를 통과한 결과에만 부른다 (비용). 지적은 재변환 지적 사항으로 넘긴다.
    """
    src = source_text(policy)
    units = source_units(policy)
    messages = [
        {"role": "system", "content": CONVERT_SYSTEM},
        {"role": "user", "content": CONVERT_USER.format(
            level_block=level_instructions(level, hints_for(src, level.plain_tier, level.gloss_tier)),
            source=units_text(policy, units))},
    ]
    attempts = []
    outputs: list[tuple[str, str]] = []
    judge_issues: list[dict] | None = None
    for _ in range(1 + MAX_RETRIES):
        draft = _fill_missing(_fill_core(generate(messages), level, generate, src), level, generate, src, units)
        core, full = render(draft if isinstance(draft, dict) else {}, level, policy)
        result = check(policy, core, full, level, src)
        core_items = {line[1:-1] for line in core.splitlines() if line.startswith("[") and line.endswith("]")}
        lacking = [i for i in CORE_REQUIRED_ITEMS if i not in core_items]
        if lacking:
            result.level_problems.append(
                "core 에 " + ", ".join(f"'{i}'" for i in lacking) + " 항목이 없다. 각 항목을 core 에 1~3문장으로 요약할 것 "
                "(같은 원문 번호를 more 에서 또 인용해도 됨, more 의 자세한 내용은 그대로 둘 것)"
            )
        cited = cited_ids(draft)
        result.missing_units = [f'{u["id"]} [{u["field"]}] "{u["text"][:120]}"'
                                for u in units if u["required"] and u["id"] not in cited]
        result.unit_gaps = unit_content_gaps(units, draft if isinstance(draft, dict) else {}, level)
        attempts.append(result)
        outputs.append((core, full))
        feedback = result.feedback()
        if result.passed and judge is not None:
            judge_issues = _judge(judge, src, full, level)
            feedback = _judge_feedback(_blocking(judge_issues))
        if not feedback:
            break
        messages += [
            {"role": "assistant", "content": json.dumps(draft, ensure_ascii=False)},
            {"role": "user", "content": RETRY_USER.format(feedback="\n".join(f"- {f}" for f in feedback))},
        ]

    # 끝까지 통과하지 못하면 마지막이 아니라 지적이 가장 적은 시도를 낸다 (재시도가 더 나빠지는 경우가 있다)
    best = len(attempts) - 1 if attempts[-1].passed else min(range(len(attempts)), key=lambda k: len(attempts[k].feedback()))
    final = attempts[best]
    core, full = outputs[best]
    judged_ok = judge is None or (final.passed and judge_issues is not None and not _blocking(judge_issues))
    return {
        "level": level.code,
        "level_name": level.name,
        "text": full,
        "core_text": core,
        "passed": final.passed and judged_ok,
        "auto_checks_passed": final.passed,
        "attempts": len(attempts),
        "checks": asdict(final),
        # 지표는 재변환 전 첫 결과 기준 (설계 문서: 누락률은 첫 결과로 잰다)
        "first_attempt_missing": attempts[0].missing_facts,
        "metrics": metrics(src, core),
        "judge_issues": judge_issues if final.passed and best == len(attempts) - 1 else None,
        # 앱이 강조 표시하고, 누르면 풀이를 보여줄 용어 (사람이 확정한 용어 사전 기준)
        "terms": term_links(full, level.link_tier),
        "verified": False,
    }


def _openai_generate(model: str) -> Generate:
    from rag.openai_client import RagFormatError, _client

    def generate(messages: list[dict]) -> dict:
        resp = _client().chat.completions.create(
            model=model, temperature=0.2, max_tokens=2500,
            response_format={"type": "json_object"}, messages=messages,
        )
        try:
            return json.loads(resp.choices[0].message.content or "")
        except json.JSONDecodeError:
            raise RagFormatError("LLM 출력이 JSON 이 아닙니다.") from None

    return generate


def find_policy(policy_key: str, path: Path = POLICIES_PATH) -> dict:
    text = path.read_text(encoding="utf-8").strip()
    for p in json.loads(text) if text else []:
        if p.get("policy_key") == policy_key:
            return p
    raise SystemExit(f"{path} 에 {policy_key} 가 없습니다.")


def save(policy: dict, results: list[dict], model: str, out_dir: Path = OUT_DIR) -> Path:
    """단계별 결과를 합쳐 저장. 이미 있는 다른 단계 결과는 남긴다."""
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{policy['policy_key']}.json"
    data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"levels": {}}
    data.update(policy_key=policy["policy_key"], name=policy["name"])
    for r in results:
        data["levels"][r["level"]] = {
            **r, "model": model, "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def main() -> None:
    from rag.settings import get_rag_settings

    parser = argparse.ArgumentParser(description="정책 쉬운 말 변환 (L1~L7)")
    parser.add_argument("policy_key")
    parser.add_argument("--levels", nargs="+", default=[DEFAULT_LEVEL])
    parser.add_argument("--file", type=Path, default=POLICIES_PATH)
    parser.add_argument("--judge", action="store_true", help="다른 모델로 의미 보존 2차 검수")
    args = parser.parse_args()

    s = get_rag_settings()
    policy = find_policy(args.policy_key, args.file)
    generate = _openai_generate(s.easytext_model)
    judge = _openai_generate(s.easytext_judge_model) if args.judge else None

    results = []
    for code in args.levels:
        level = get_level(code)
        r = convert_level(policy, level, generate, judge)
        results.append(r)
        c, m = r["checks"], r["metrics"]
        print(f"\n===== {level.code} · {level.name}: {'통과' if r['passed'] else '실패'} (시도 {r['attempts']}번)")
        print(f"핵심 블록 {c['core_sentences']}문장, 평균 {c['avg_eojeol']}어절, 최대 {c['max_eojeol']}어절")
        print(f"전문용어 감소율 {m['term_reduction']}, 문장길이 감소율 {m['length_reduction']}")
        for k in ("missing_facts", "missing_units", "unit_gaps", "condition_gaps", "unsupported_numbers", "tier_violations",
                  "style_violations", "level_problems"):
            if c[k]:
                print(f"[{k}] {c[k]}")
        if r["judge_issues"]:
            for i in r["judge_issues"]:
                sev = str(i.get("severity") or "high").lower()
                tag = "2차 검수 지적" if sev != "low" else "2차 검수 참고(사람 확인)"
                print(f"[{tag}] {i.get('reason')} | 원문: {i.get('original')} | 변환문: {i.get('converted')}")
        if r["terms"]:
            print("[사전 풀이 연결] " + ", ".join(t["term"] for t in r["terms"]))
        print("-" * 40)
        print(r["text"])
    print(f"\n저장: {save(policy, results, s.easytext_model)}")
    print("원문과 대조한 뒤 맞으면 해당 단계의 \"verified\" 를 true 로 바꿔 주세요.")


if __name__ == "__main__":
    main()
