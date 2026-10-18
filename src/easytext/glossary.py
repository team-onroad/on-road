"""용어 사전 (T2~T4). 단계별 허용 등급 검사와 전문용어 감소율 계산에 쓴다."""

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

GLOSSARY_PATH = Path(__file__).resolve().parent / "glossary.json"


@dataclass(frozen=True)
class Term:
    term: str
    tier: int
    easy: str
    gloss: str
    rewrites: tuple[tuple[str, str], ...] = ()  # 쉬운 단계에서 코드가 바꿔 쓰는 표현 쌍


@lru_cache
def load_terms(path: Path = GLOSSARY_PATH) -> tuple[Term, ...]:
    data = json.loads(path.read_text(encoding="utf-8"))
    # 긴 용어부터 찾아야 "아동복지심의위원회" 안의 "아동복지"류가 따로 잡히지 않는다
    terms = (Term(**{**t, "rewrites": tuple(tuple(r) for r in t.get("rewrites", []))}) for t in data["terms"])
    return tuple(sorted(terms, key=lambda t: -len(t.term)))


def _without_parens(text: str) -> str:
    """괄호 풀이 안의 용어는 세지 않는다. 겹친 괄호도 모두 뺀다."""
    while re.search(r"\([^()]*\)", text):
        text = re.sub(r"\([^()]*\)", " ", text)
    return text


def find_terms(text: str, terms: tuple[Term, ...] | None = None) -> list[tuple[Term, int]]:
    """(용어, 위치) 목록. 긴 용어가 차지한 자리는 짧은 용어로 다시 세지 않는다."""
    terms = terms if terms is not None else load_terms()
    taken: list[tuple[int, int]] = []
    found: list[tuple[Term, int]] = []
    for t in terms:
        for m in re.finditer(re.escape(t.term), text):
            s, e = m.span()
            if any(s < te and ts < e for ts, te in taken):
                continue
            taken.append((s, e))
            found.append((t, s))
    return sorted(found, key=lambda x: x[1])


def count_hard_terms(text: str, min_tier: int = 3) -> int:
    """T3 이상 용어 수 (괄호 풀이 안 제외). 전문용어 감소율에 쓴다."""
    return sum(1 for t, _ in find_terms(_without_parens(text)) if t.tier >= min_tier)


def _inside_paren(text: str, pos: int) -> bool:
    return text[:pos].count("(") > text[:pos].count(")")


def tier_violations(text: str, plain_tier: int, gloss_tier: int) -> list[str]:
    """단계 기준을 어긴 용어.

    - plain_tier 이하: 그냥 써도 됨
    - plain_tier 초과 ~ gloss_tier 이하: 처음 나올 때 바로 뒤에 괄호 풀이가 있어야 함
    - gloss_tier 초과: 쉬운 말로 바꿔 써야 함 (괄호 풀이가 있어도 위반)
    """
    out: list[str] = []
    seen: set[str] = set()
    plain = _without_parens(text)
    first_glossed = {}
    for t, pos in find_terms(text):
        if t.term in first_glossed or _inside_paren(text, pos):
            continue  # 괄호 안 등장은 "처음 나옴"으로 보지 않는다
        first_glossed[t.term] = text[pos + len(t.term): pos + len(t.term) + 2].lstrip().startswith("(")
    for t, _ in find_terms(plain):
        if t.term in seen or t.tier <= plain_tier:
            continue
        seen.add(t.term)
        if t.tier > gloss_tier:
            out.append(f"{t.term}(T{t.tier}): 쉬운 말로 바꿔야 함 (예: {t.easy})")
        elif not first_glossed.get(t.term):
            out.append(f"{t.term}(T{t.tier}): 처음 나올 때 괄호 풀이 필요 (예: {t.term}({t.gloss}))")
    return out


def hints_for(source_text: str, level_plain: int, level_gloss: int) -> list[str]:
    """프롬프트에 넣을 용어 안내: 원문에 나온 어려운 용어를 이 단계에서 어떻게 쓸지."""
    hints = []
    for t in {t.term: t for t, _ in find_terms(source_text)}.values():
        if t.tier <= level_plain:
            continue
        if t.tier > level_gloss:
            ex = "; ".join(f"{a} → {b}" for a, b in t.rewrites[:3])
            hints.append(f"- '{t.term}': 문장에 맞게 쉬운 말로 풀어 쓴다 (뜻: {t.easy})" + (f". 예: {ex}" if ex else "")
                         + ". 명사 뒤에 그대로 끼워 넣지 않는다")
        else:
            hints.append(f"- '{t.term}': 처음 나올 때 '{t.term}({t.gloss})'처럼 풀이를 붙인다")
    return hints


def add_glosses(blocks: list[list[str]], plain_tier: int, gloss_tier: int) -> list[list[str]]:
    """풀이가 필요한 용어(plain_tier 초과 ~ gloss_tier 이하)의 첫 등장에 괄호 풀이를 자동으로 붙인다.

    괄호만 덧붙이므로 문장 뜻·문법을 바꾸지 않는다. blocks 는 화면 순서(core → more)의 문장 목록들.
    """
    done: set[str] = set()
    out = []
    for sents in blocks:
        new_sents = []
        for sent in sents:
            for t, pos in reversed(find_terms(sent)):
                if t.term in done or not (plain_tier < t.tier <= gloss_tier):
                    continue
                end = pos + len(t.term)
                if _inside_paren(sent, pos) or sent[end:end + 2].lstrip().startswith("("):
                    continue
                sent = f"{sent[:end]}({t.gloss}){sent[end:]}"
            for t, pos in find_terms(sent):
                if not _inside_paren(sent, pos):
                    done.add(t.term)
            new_sents.append(sent)
        out.append(new_sents)
    return out


def apply_rewrites(text: str, gloss_tier: int) -> str:
    """바꿔 써야 하는 용어(gloss_tier 초과) 중 안전한 표현 쌍이 있는 것은 코드가 바꾼다. 긴 표현부터 바꾼다.

    바꾼 결과 안의 말("…통장(압류방지통장)")이 다시 바뀌지 않게, 먼저 자리표시로 바꾼 뒤 마지막에 펼친다.
    괄호 안(원래 이름을 함께 쓴 자리)의 용어는 바꾸지 않는다: "…통장(압류방지통장)"이 두 번 풀리는 것 방지.
    """
    pairs = [p for t in load_terms() if t.tier > gloss_tier for p in t.rewrites]
    slots: list[str] = []
    for old, new in sorted(pairs, key=lambda p: -len(p[0])):
        hits = [m.start() for m in re.finditer(re.escape(old), text) if not _inside_paren(text, m.start())]
        if not hits:
            continue
        token = f"⦃{len(slots)}⦄"
        slots.append(new)
        for start in reversed(hits):
            text = text[:start] + token + text[start + len(old):]
    for i, new in enumerate(slots):
        text = text.replace(f"⦃{i}⦄", new)
    return text


def term_links(text: str, link_tier: int) -> list[dict]:
    """앱이 사전 풀이로 보여줄 용어 목록: 변환문에 나온 link_tier 이상 용어 (괄호 안 원래 이름 포함), 처음 나온 순서.

    앱은 이 용어들을 강조 표시하고, 누르면 gloss(풀이)를 보여준다. 문장 안에는 괄호 풀이를 넣지 않는다.
    """
    seen: dict[str, dict] = {}
    for t, _ in find_terms(text):
        if t.tier < link_tier:
            continue
        if t.term not in seen:
            seen[t.term] = {"term": t.term, "tier": t.tier, "gloss": t.gloss, "count": 0}
        seen[t.term]["count"] += 1
    return list(seen.values())
