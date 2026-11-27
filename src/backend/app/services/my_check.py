"""상담 정책 답변 끝에 붙는 '내 조건으로 보면' (규칙, AI 없음).

답변 문장(RAG)은 정책 설명이라, 이 사람이 실제로 받을 수 있는지 · 더 나은 방법이 있는지는 따로 알려 준다.
정책마다 한 줄, 아는 것만 쓴다 (모르는 조건은 '확인해 보세요'로):
- 지금 받고 있음 (saved_policies receiving)
- 나이 · 지역 판정 (match · needs_check), 가족 나이로 보는 정책, 퇴소해야 받는 정책
- 앱이 판정하지 못하는 대상 조건 (카드 요약의 '지원 대상' 구절, 예: 서초구 1년 이상 거주)
- 들어야 하는 의무교육과 이수 여부 (3.37)
- 같은 종류 중 더 유리한 정책 (로드맵 3.32 와 같은 기준)
"""

import re
from datetime import date

from sqlalchemy.orm import Session

from app import ai_content
from app.schemas.policies import SearchResult
from app.services.credentials import EDUCATIONS, done_educations, required_education
from app.services.saved_policies import receiving
from db.models import Policy, User

TITLE = "[내 조건으로 보면]"
MAX_POLICIES = 3
MAX_CONDITIONS = 2
# 카드 요약 '지원 대상' 구절에서 앱이 이미 판정한 부분(나이 · 보호종료 · 대상 이름)은 지우고 남는 조건만 묻는다.
# "서초구 1년 이상 거주 자립준비청년(보호종료아동)" → "서초구 1년 이상 거주"
_STRIP = (
    re.compile(r"\([^)]*\)"),
    re.compile(r"(아동복지시설|가정위탁|아동양육시설|청소년쉼터)?\s*(퇴소|보호\s*종료)\s*(후|예정|된|한)?\s*(\d+\s*년\s*(이내|이상|미만)?)?"),
    re.compile(r"만\s*\d+\s*세\s*(이상|이하|미만|초과)?|\d+\s*세\s*(이상|이하|미만|초과)"),
    re.compile(r"자립\s*준비\s*청년|보호종료아동"),
    re.compile(r"\s(청년|아동|중인|인)$"),
)


def short_name(name: str) -> str:
    """'서리풀 희망사다리 프로젝트 자립준비청년(보호종료아동) 생활보조수당 지원' → '서리풀 생활보조수당 지원'"""
    n = re.sub(r"\([^)]*\)", " ", name)
    m = re.match(r"\s*(\S+)\s.*?프로젝트\s+(.*)", n)
    if m:
        n = f"{m.group(1)} {m.group(2)}"
    n = re.sub(r"자립준비청년\s*", "", n)
    return re.sub(r"\s+", " ", n).strip()


def _josa(word: str, pair: tuple[str, str]) -> str:
    c = word.strip()[-1:] if word.strip() else ""
    if "가" <= c <= "힣":
        return pair[0] if (ord(c) - 0xAC00) % 28 else pair[1]
    return pair[1]


def _better_by_policy(roadmap) -> dict[int, str]:
    """{정책 id: 같은 종류 중 더 유리한 정책 이름}. 로드맵이 고른 것이 아닌 정책만"""
    out: dict[int, str] = {}
    for item in (i for g in roadmap.stages for i in g.items):
        for a in item.alternatives:
            out[a.policy_id] = short_name(item.name) + (f"({item.amount_text})" if item.amount_text else "")
    return out


def clean_condition(text: str) -> str:
    c = text
    for pattern in _STRIP:
        c = pattern.sub(" ", c)
    c = re.sub(r"\s+", " ", c).strip(" ,.·")
    c = re.sub(r"(에서|중인|인)$", "", c).strip()
    return c if len(c.replace(" ", "")) >= 3 else ""


def _conditions(policy: Policy) -> list[str]:
    """앱이 판정하지 못하는 대상 조건 (카드 요약 구절에서 나이 · 보호종료처럼 이미 본 것은 지운다)"""
    out: list[str] = []
    for item in ai_content.policy_section(policy.policy_key, "target"):
        c = clean_condition(item)
        if c and c not in out:
            out.append(c)
    return out[:MAX_CONDITIONS]


def _family_word(user: User) -> str:
    rel = {m.relation for m in getattr(user, "family", None) or []}
    return {frozenset({"sibling"}): "동생", frozenset({"child"}): "자녀"}.get(frozenset(rel), "동생 · 자녀")


def lines(db: Session, user: User, results: list[SearchResult], policies: dict[int, Policy], today: date,
          roadmap=None) -> list[str]:
    """roadmap: 이미 만든 맞춤 로드맵 (상담 plan 과 같이 쓰면 한 번만 만든다). 없으면 여기서 만든다"""
    main = [r for r in results if not r.related][:MAX_POLICIES]
    if not main:
        return []
    got = receiving(db, user)
    done = done_educations(db, user)
    if roadmap is None:
        from app.services.roadmap import build_roadmap

        roadmap = build_roadmap(db, user, today)
    better = _better_by_policy(roadmap)
    out = []
    for r in main:
        p = policies.get(r.policy_id)
        name = short_name(r.name)
        if r.policy_id in got:
            out.append(f"- {name}: 지금 받고 있는 지원이에요.")
            continue
        parts: list[str] = []
        if r.for_family:
            parts.append(f"{_family_word(user)} 나이로 보는 정책이에요")
        elif r.eligibility.status == "match":
            parts.append("나이 · 지역 조건은 맞아요")
        else:
            parts.append("나이나 지역 조건을 더 확인해야 해요")
        if r.eligibility.leave == "after_leave":
            parts.append("보호가 끝난 뒤 신청할 수 있어요")
        if p is not None and not r.for_family:
            cond = _conditions(p)
            if cond:
                parts.append(", ".join(cond) + "인지 확인해 보세요")
            code = required_education(p)
            if code and code not in done:
                parts.append(f"{EDUCATIONS[code][0]}을 먼저 들어야 해요")
        if r.policy_id in better:
            b = better[r.policy_id]
            parts.append(f"같은 종류 중에는 {b}{_josa(b.split('(')[0], ('이', '가'))} 더 유리해요")
        out.append(f"- {name}: " + ". ".join(parts) + ".")
    return [TITLE, *out] if out else []


def append_to(answer: str | None, note: list[str]) -> str | None:
    if not answer or not note:
        return answer
    return answer.rstrip() + "\n\n" + "\n".join(note)
