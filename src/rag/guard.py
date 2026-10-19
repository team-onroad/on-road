"""생성된 답변의 환각 검사와 출처 표시 (계획서 7.3).

LLM 초안은 문장마다 근거 번호를 단다: {"sentences": [{"text": ..., "evidence": [1, 2]}], "missing": bool}.
문장 단위로 아래에 걸리면 그 문장을 버린다.
- 근거 번호가 없거나 범위 밖이다
- 그 문장이 단 근거에 없는 숫자(금액·기간·나이·날짜)가 들어 있다
- 사용자가 신청 가능하다·대상이다라고 단정한다
  ("온라인으로 신청할 수 있습니다" 같은 신청 방법 안내는 막지 않는다)
남은 문장이 없으면 근거 정책 이름만 알려주는 고정 문장으로 바꾼다.
남은 문장에는 "(출처: 복지로)" 처럼 근거 출처를 붙인다.
"""

import re
from dataclasses import dataclass

CHECK_NOTICE = "자세한 조건은 공식 원문에서 꼭 확인하시기 바랍니다."
MISSING_NOTICE = "질문하신 내용 중 일부는 제공된 자료에서 확인되지 않습니다. 담당 기관에 문의하시기 바랍니다."

_NUMBER_RE = re.compile(r"\d+(?:[.,]\d+)*")
_ASSERTION_RES = [
    re.compile(p)
    for p in (
        # 사용자를 가리키며 단정: "질문하신 분은 신청 가능합니다", "회원님은 지원 대상이에요"
        r"(분|님|당신|사용자)(은|는|도|이|께서)\s*.{0,15}?"
        r"(신청\s*가능|신청하실 수 있|받으실 수 있|대상(자)?(입니다|이에요|예요)|해당(합니다|돼요|됩니다))",
        # 자격을 단정: "자격이 됩니다", "자격이 있어요"
        r"자격(이|은)\s*(있습니다|있어요|됩니다|돼요|충분)",
    )
]
# LLM 이 문장 안에 직접 쓴 근거 표시 ("[1]", "(근거 2)", "(1, 3)") 는 지우고 출처 이름으로 다시 단다.
# evidence 칸을 빼먹고 이렇게만 쓴 경우도 있어, 표시 안의 번호도 근거 번호로 읽는다
_INLINE_CITE_RE = re.compile(r"\s*(\[\s*\d+(\s*,\s*\d+)*\s*\]|\(\s*(근거\s*)?\d+(\s*,\s*\d+)*\s*\))")


def _numbers(text: str) -> set[str]:
    return {n.replace(",", "") for n in _NUMBER_RE.findall(text)}


def unsupported_numbers(answer: str, evidence_text: str) -> set[str]:
    return _numbers(answer) - _numbers(evidence_text)


def has_assertion(answer: str) -> bool:
    return any(r.search(answer) for r in _ASSERTION_RES)


def source_label(hit: dict) -> str:
    """"B: 2026년 자립정보북 | 대표" → "2026년 자립정보북". 출처 머리말이 없으면 정책명."""
    source = hit.get("source") or ""
    if not source:
        return hit["policy_name"]
    name = source.split(":", 1)[-1].split("|")[0].split(",")[0].strip()
    return name or hit["policy_name"]


def fallback_answer(policy_names: list[str]) -> str:
    names = ", ".join(dict.fromkeys(policy_names))
    return f"질문과 관련된 정책으로 {names}이(가) 있습니다. 아래 근거 원문을 함께 확인하시기 바랍니다. {CHECK_NOTICE}"


@dataclass(frozen=True)
class Checked:
    answer: str
    used: list[int]  # 답변이 실제로 쓴 근거(hits) 위치. 0부터
    fallback: bool


def _valid_refs(evidence: object, n: int) -> list[int]:
    if not isinstance(evidence, list):
        return []
    refs = [e for e in evidence if isinstance(e, int) and not isinstance(e, bool) and 1 <= e <= n]
    return list(dict.fromkeys(refs))


def _check_sentence(sentence: object, hits: list[dict]) -> tuple[str, list[int]] | None:
    if not isinstance(sentence, dict) or not isinstance(sentence.get("text"), str):
        return None
    raw = sentence["text"]
    text = _INLINE_CITE_RE.sub("", raw).strip()
    inline = [int(n) for m in _INLINE_CITE_RE.finditer(raw) for n in re.findall(r"\d+", m.group())]
    evidence = sentence.get("evidence")
    refs = _valid_refs((evidence if isinstance(evidence, list) else []) + inline, len(hits))
    if not text or not refs:
        return None
    cited = [hits[r - 1] for r in refs]
    evidence_text = "\n".join([h["policy_name"] for h in cited] + [h["content"] for h in cited])
    if unsupported_numbers(text, evidence_text) or has_assertion(text):
        return None
    return text, [r - 1 for r in refs]


def finalize(draft: dict | None, hits: list[dict]) -> Checked:
    """문장별로 검사해 통과한 문장만 출처를 붙여 잇고, 끝에 확인 안내를 붙인다."""
    names = [h["policy_name"] for h in hits]
    sentences = draft.get("sentences") if isinstance(draft, dict) else None
    kept = [c for s in (sentences if isinstance(sentences, list) else []) if (c := _check_sentence(s, hits))]
    if not kept:
        return Checked(fallback_answer(names), list(range(len(hits))), fallback=True)

    parts = []
    used: list[int] = []
    for text, refs in kept:
        labels = ", ".join(dict.fromkeys(source_label(hits[r]) for r in refs))
        if text[-1] not in ".!?":
            text += "."
        parts.append(f"{text} (출처: {labels})")
        used.extend(refs)
    if draft.get("missing") is True:
        parts.append(MISSING_NOTICE)
    parts.append(CHECK_NOTICE)
    return Checked(" ".join(parts), sorted(set(used)), fallback=False)
