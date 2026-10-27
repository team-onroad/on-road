"""LLM 이 만든 항목 초안을 양식 원문과 대조해 고친다 (환각·개인정보 방지).

- 칸 이름(label)이 원문에 없으면 그 항목을 버린다.
- 근거(evidence)가 원문에 없으면 근거만 지운다.
- 주민등록번호·계좌·서명 같은 칸은 민감 항목으로 바꾸고, 예시를 지우고, 손으로 쓰게 한다.
- 접수·담당자 칸은 공무원 기재란으로 바꾼다.
걸러진 내용은 warnings 에 남겨 사람이 확인하게 한다.
"""

import re

from pydantic import ValidationError

from formguide.extract import FormText, normalize
from formguide.schema import FormField

SENSITIVE_TYPES = {"account", "id_number", "signature"}
_SENSITIVE_WORDS = ("주민등록번호", "외국인등록번호", "계좌", "서명", "(인)", "날인", "도장", "비밀번호", "카드번호")
_SENSITIVE_TYPE_BY_WORD = {"주민등록번호": "id_number", "외국인등록번호": "id_number", "계좌": "account",
                           "서명": "signature", "(인)": "signature", "날인": "signature", "도장": "signature"}
_OFFICE_WORDS = ("접수번호", "접수일", "처리결과", "담당자", "담당공무원", "확인자", "결재")
_ID_RE = re.compile(r"[^a-z0-9_]+")
GUIDE_MAX = 200


def _slug(text: str, fallback: str) -> str:
    s = _ID_RE.sub("_", text.lower()).strip("_")
    return s[:40] or fallback


def _loose(text: str) -> str:
    """표 칸 구분자(|)까지 지운 비교용 글자"""
    return normalize(text).replace("|", "")


_PART_RE = re.compile(r"[|>/,·]+")


def _label_in(label: str, source: str) -> bool:
    """칸 이름이 원문에 있는지. "대리인 전화:"처럼 구역 이름을 붙인 칸 이름은 낱말이 모두 원문에 있으면 인정"""
    if normalize(label) in source:
        return True
    words = label.split()
    return len(words) >= 2 and all(normalize(w) in source for w in words)


def _evidence_in(evidence: str, loose_source: str) -> bool:
    if _loose(evidence) in loose_source:
        return True
    # 표 칸이 띄어 놓인 원문을 한 줄로 이어 쓴 근거 (예: "대상 아동 성명")도 낱말이 모두 원문에 있으면 인정
    parts = [_loose(p) for p in _PART_RE.split(evidence.replace(" ", "|")) if _loose(p)]
    return bool(parts) and all(p in loose_source for p in parts)


def check_fields(raw_fields: object, form: FormText) -> tuple[list[FormField], list[str]]:
    source = form.normalized()
    loose_source = _loose(form.as_prompt_text())
    fields: list[FormField] = []
    warnings: list[str] = []
    seen: set[str] = set()

    for i, raw in enumerate(raw_fields if isinstance(raw_fields, list) else []):
        try:
            f = FormField.model_validate(raw)
        except ValidationError as e:
            warnings.append(f"항목 {i + 1}: 형식 오류로 버림 ({e.error_count()}건)")
            continue

        if not f.label.strip() or not _label_in(f.label, source):
            warnings.append(f"'{f.label}': 양식 원문에 없는 칸 이름이라 버림")
            continue

        updates: dict = {}
        if f.evidence and not _evidence_in(f.evidence, loose_source):
            warnings.append(f"'{f.label}': 근거 문장이 원문과 달라 근거를 지움 (근거: {f.evidence[:40]!r})")
            updates["evidence"] = None

        norm_label = normalize(f.label)
        hit = next((w for w in _SENSITIVE_WORDS if normalize(w) in norm_label), None)
        if hit or f.input_type in SENSITIVE_TYPES or f.sensitive:
            updates.update(sensitive=True, value_source="handwrite", example=None, profile_key=None)
            if hit and f.input_type not in SENSITIVE_TYPES:
                updates["input_type"] = _SENSITIVE_TYPE_BY_WORD.get(hit, f.input_type)
        elif any(normalize(w) in norm_label for w in _OFFICE_WORDS):
            updates.update(value_source="office", example=None, profile_key=None)

        source_kind = updates.get("value_source", f.value_source)
        if source_kind == "profile" and f.profile_key is None:
            updates["value_source"] = "user"
        if source_kind != "profile":
            updates["profile_key"] = None

        if len(f.guide) > GUIDE_MAX:
            updates["guide"] = f.guide[:GUIDE_MAX].rstrip() + "…"

        fid = _slug(f.id, f"field_{i + 1}")
        while fid in seen:
            fid += "_2"
        seen.add(fid)
        updates["id"] = fid
        fields.append(f.model_copy(update=updates))

    if not fields:
        warnings.append("남은 항목이 없습니다. 양식 PDF 가 표 양식인지, 글자가 뽑히는지 확인하세요.")
    return fields, warnings


def check_notes(raw_notes: object, form: FormText) -> tuple[list[str], list[str]]:
    source = form.normalized()
    notes, warnings = [], []
    for n in raw_notes if isinstance(raw_notes, list) else []:
        if not isinstance(n, str) or not n.strip():
            continue
        # 유의사항은 요약일 수 있어, 숫자만 원문에 있는지 본다 (근거 없는 기한·금액 방지)
        if all(d in source for d in re.findall(r"\d+", n)):
            notes.append(n.strip())
        else:
            warnings.append(f"유의사항 '{n[:30]}…': 원문에 없는 숫자가 있어 버림")
    return notes, warnings
