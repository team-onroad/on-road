"""변환문 자동 검사.

1. 핵심 조건 대조: 정책 정형 필드(대상·지원 내용·금액·기간·신청 방법·서류)에서 뽑은 사실이 변환문 어딘가
   (핵심 블록 또는 더 알아보기)에 있는지. 숫자는 값·단위·경계 단어(이상·이내·이후 등)까지 본다.
2. 단계 기준: 핵심 블록의 평균·최대 어절, 문장 수, 용어 등급
3. 말투: 모든 문장이 합니다체(~니다)로 끝나는지
4. 근거 없는 숫자, 자격 단정 표현 (RAG 와 같은 규칙)

데이터 담당의 검증 스크립트(scripts/eval/easy_text_verify.py)가 나오면 1번은 그 규칙으로 맞춘다.
"""

import re
from dataclasses import dataclass, field

from easytext.glossary import count_hard_terms, load_terms, tier_violations
from easytext.levels import TOLERANCE, Level
from rag.guard import has_assertion, unsupported_numbers

# 숫자 + 단위 (+ 경계 단어)
# "1세대"(가구)는 나이가 아니다 (G059)
_UNIT = r"만\s*원|원|세(?!대)|살|년|개월|월|일|주|회|%|명|시간|부"
_FACT_RE = re.compile(rf"(\d+(?:[.,]\d+)?)\s*({_UNIT})\s*(이상|이하|초과|미만|이내|이후|이전|부터|까지|전|후)?")
# 약식 연도 앞 따옴표: 정부24 원문은 ’ ' ‘ 말고 역따옴표(`24.2.9.)도 쓴다 (G027)
_QUOTE = "[’'‘`]"
_NOT_DATE_AFTER = r"(?!\d|\s*(?:백|천|만|억|원|명|개|회|%|세|살|년|시간))"
# 달 자리 숫자 뒤에 단위(백 · 만 · 원 …)가 오면 날짜가 아니다 ("'20년 5백만원" 을 "2020년 5월백만원" 으로 망가뜨리던 것)
_SHORT_DATE_RE = re.compile(
    rf"{_QUOTE}(\d{{2}})\s*[.년]\s*(\d{{1,2}}){_NOT_DATE_AFTER}\s*[.월]?(?:\s*(\d{{1,2}})\s*[.일]?)?"
)
# 달 없이 연도만: "'23년부터" → "2023년부터" (정부24 원문에 많음)
_SHORT_YEAR_RE = re.compile(rf"{_QUOTE}(\d{{2}})\s*년")
# 약식 연도 뒤 목록의 따옴표 없는 연도: "'20년 5백만원, 21년 10백만원, 22년 이후" (G012)
_SHORT_YEAR_LIST_RE = re.compile(rf"{_QUOTE}(\d{{2}})\s*년[^\n]*")
_LISTED_YEAR_RE = re.compile(r"(?<=,)(\s*)(\d{2})(\s*년)")
# 마침표 날짜: "2024.01.01" · "2026.04.20." → "2024년 1월 1일" (01 과 1 을 다른 숫자로 보던 것, G020 · G032 · G095)
_DOT_DATE_RE = re.compile(rf"(?<![\d.])((?:19|20)\d{{2}})\s*\.\s*(\d{{1,2}})(?:\s*\.\s*(\d{{1,2}}))?{_NOT_DATE_AFTER}\.?")
# 백만 단위 금액: "15백만원" → "1500만원"
_HUNDRED_MAN_RE = re.compile(r"(?<![\d,])(\d+)\s*백만")
# 천 원 단위 금액: "2.4천원" → "2400원", "2,000천원" → "200만원" (G073 · G095)
_THOUSAND_WON_RE = re.compile(r"(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)\s*천\s*원")
# 전화번호: "0318392936" 과 "031-839-2936" 을 같은 번호로 (G066)
_PHONE_RE = re.compile(r"(?<!\d)(0\d{1,2})\s*[-.)]\s*(\d{3,4})\s*[-.]\s*(\d{4})(?!\d)")
# 숫자 목록의 쉼표: "3, 6, 9, 12월" 이 쉼표를 지우면 "36912월" 이 되던 것 (G066). 천 단위 쉼표(1,000)는 그대로
_LIST_COMMA_RE = re.compile(r"(?<=\d)\s*,\s*(?=\d{1,2}(?!\d))")
# 경계 단어 → 같은 뜻으로 인정하는 표현
_BOUNDARY_OK = {
    "이상": ("이상",), "이하": ("이하",), "초과": ("초과",), "미만": ("미만",),
    "이내": ("이내", "안에", "까지"), "이후": ("이후", "부터"), "부터": ("부터", "이후"),
    "이전": ("이전", "전"), "전": ("전", "이전"), "까지": ("까지", "이내"), "후": ("후", "이후", "뒤"),
}
_PERIOD_WORDS = {"매월": ("매월", "매달", "한달에한번", "달마다"), "매년": ("매년", "해마다", "1년에한번")}
_CHANNELS = ("방문", "온라인", "복지로", "우편", "팩스", "행정복지센터")
_EXCLUSION_RE = re.compile(r"제외|지원\s*불가|받을\s*수\s*없|해당하지\s*않|대상이\s*아니")
_DOC_SUFFIXES = ("신청서", "확인서", "이수증", "수료증", "신분증", "사본", "위임장", "증명서", "증빙서류", "통장", "서약서")
# 문장 끝: 마침표 · 물음표 · 느낌표 뒤에 띄어쓰기나 줄 끝. 소수점("4.5")과 주소("youth-up.go.kr")에서는 자르지 않는다
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])(?=\s|$)")


_ACTORS = (
    ("시설종사자", ("시설종사자", "시설선생님", "시설에서일하는")),
    ("담당 공무원", ("담당공무원", "공무원")),
)
_META_WORDS = ("업무매뉴얼", "정보북", "표기", "복지로에는", "복지로와")


def _dot_date(m: re.Match) -> str:
    y, mo, d = m.group(1), int(m.group(2)), m.group(3)
    if not 1 <= mo <= 12 or (d and not 1 <= int(d) <= 31):
        return m.group(0)  # 날짜가 아닌 소수 등은 그대로
    return f"{y}년 {mo}월" + (f" {int(d)}일" if d else "")


def _listed_years(m: re.Match) -> str:
    first = int(m.group(1))

    def one(y: re.Match) -> str:
        yy = int(y.group(2))  # 앞 연도부터 10년 안만 연도로 본다 ("'23년부터 …, 10년 거주" 의 10년은 기간)
        return f"{y.group(1)}20{y.group(2)}{y.group(3)}" if first <= yy <= first + 10 else y.group(0)

    return _LISTED_YEAR_RE.sub(one, m.group(0))


def _thousand_won(m: re.Match) -> str:
    won = round(float(m.group(1).replace(",", "")) * 1000)
    return f"{won // 10000}만원" if won >= 10000 and won % 10000 == 0 else f"{won}원"


def expand_dates(text: str) -> str:
    """비교용으로 표기를 맞춘다: 약식 · 마침표 날짜('18.8. → 2018년 8월, 2024.01.01 → 2024년 1월 1일),
    백만 · 천 원 단위 금액, 전화번호(숫자만). 원문과 변환문 양쪽에 똑같이 쓴다."""
    def full_date(m: re.Match) -> str:
        y, mo, d = m.group(1), m.group(2), m.group(3)
        return f"20{y}년 {int(mo)}월" + (f" {int(d)}일" if d else "")

    text = _DOT_DATE_RE.sub(_dot_date, text)
    text = _SHORT_YEAR_LIST_RE.sub(_listed_years, text)
    text = _SHORT_YEAR_RE.sub(lambda m: f"20{m.group(1)}년", _SHORT_DATE_RE.sub(full_date, text))
    # "15백만원" = "1,500만 원": 변환문이 쉬운 단위로 바르게 바꾼 것을 '원문에 없는 숫자'로 보지 않게 (G012)
    text = _HUNDRED_MAN_RE.sub(lambda m: f"{int(m.group(1)) * 100}만", text)
    text = _THOUSAND_WON_RE.sub(_thousand_won, text)
    return _PHONE_RE.sub(lambda m: "".join(m.groups()), text)


def normalize(text: str) -> str:
    """비교용: 표기를 맞추고(expand_dates), 살→세, 공백·쉼표 제거. 숫자 목록 쉼표는 '·' 로 남긴다."""
    text = _LIST_COMMA_RE.sub("·", expand_dates(text))
    return re.sub(r"\s+|,", "", text).replace("살", "세")


@dataclass(frozen=True)
class Fact:
    label: str  # 사람이 읽는 설명 (재변환 지적에 씀)
    needles: tuple[str, ...]  # 정규화한 변환문에 이 중 하나라도 있으면 통과
    count: int = 1  # 원문에서 다른 조건에 몇 번 쓰였는지 (변환문에도 min(count, 2)번 있어야 함)
    context: str = ""  # 이 사실이 나온 원문 문장 (재변환 지적에 함께 보여줌)


MAX_REQUIRED = 2


def _source_sentences(policy: dict) -> list[str]:
    """정형 필드를 문장으로 나눈다. 출처를 비교하는 메모 문장("업무매뉴얼은 ~", "복지로와 표기가 달라")은
    정책 조건이 아니라서 뺀다."""
    fields = [policy.get(k) or "" for k in
              ("target_description", "support_content", "support_amount", "support_period", "apply_method")]
    out = []
    for f in fields:
        budget = False
        for line in f.splitlines():  # 정부24 원문은 문장 대신 "○ · -" 줄로 쓴다
            # "○ 사업예산 :" 아래 "- 2026년 총 : …" 줄들은 받는 사람의 조건이 아니다 (G026)
            if not line.lstrip().startswith(("-", "*", "·")):
                budget = "예산" in line
            if budget:
                continue
            for sent in re.split(r"(?<=[.다])\s+", line):
                sent = sent.strip()
                if sent and not any(w in sent for w in _META_WORDS):
                    out.append(sent)
    return _drop_repeats(list(dict.fromkeys(out)))


def _repeat_key(sent: str) -> str:
    return re.sub(r"[^\w·]", "", normalize(sent))


def _drop_repeats(sents: list[str]) -> list[str]:
    """같은 내용이 여러 칸에 되풀이된 문장은 한 번만 센다. 원문에 두 번 나온 값을 변환문에도 두 번 쓰라고 하던 것:
    - 같은 문장 (지원금액 칸 = 지원내용 칸일 때 "30%")
    - 다른 문장 안에 통째로 들어 있음 (지원금액 칸 "월 50만원" ⊂ 지원내용 "지급내용: 월 50만원/인", G007 · G032 · G061)
    - 숫자 사실이 같고 글자가 거의 같음 (대상 칸 "… 참여 이력이 없는 청년" ≈ 지원내용 칸 "… 이력이 없는 청년", G023)
    다른 조건에 같은 값이 쓰인 것("일반 보호종료자는 5년 이내 · 조기 보호종료자는 18세부터 5년 이내")은 그대로 센다."""
    from difflib import SequenceMatcher

    keys = [_repeat_key(s) for s in sents]
    nums = [{f for f, *_ in _FACT_RE.findall(normalize(s))} for s in sents]
    keep = []
    for i, k in enumerate(keys):
        if not k:
            continue
        dup = False
        for j, other in enumerate(keys):
            if i == j or not other:
                continue
            inside = k in other and (len(k) < len(other) or j < i)  # 똑같으면 앞의 것만 남김
            similar = j < i and nums[i] == nums[j] and SequenceMatcher(None, k, other).ratio() >= 0.85
            if inside or similar:
                dup = True
                break
        if not dup:
            keep.append(sents[i])
    return keep


def _number_facts(sents: list[str]) -> list[Fact]:
    found: dict[tuple[str, ...], list] = {}
    for sent in sents:
        norm = normalize(sent)
        for fm in _FACT_RE.finditer(norm):
            # 사업 예산("○ 사업예산 : 2026년 총 659,900천원")은 받는 사람의 조건이 아니다 (G026)
            if "예산" in norm[max(0, fm.start() - 20): fm.start()]:
                continue
            num, unit, bound = fm.groups(default="")
            num = num.replace(",", "")
            unit = re.sub(r"\s+", "", unit).replace("살", "세")
            base = f"{num}{unit}"
            if bound:
                needles = tuple(f"{base}{b}" for b in _BOUNDARY_OK.get(bound, (bound,)))
                label = f"{base} {bound}"
            else:
                needles, label = (base,), base
            full = expand_dates(sent)
            m = re.search(rf"{re.escape(num)}\s*{re.escape(unit[0])}", full)
            i = m.start() if m else -1
            snippet = full[max(0, i - 35): i + 45] if i >= 0 else sent
            entry = found.setdefault(needles, [label, 0, snippet])
            entry[1] += 1
    return [Fact(label, needles, count, ctx) for needles, (label, count, ctx) in found.items()]


def _doc_key(name: str) -> str | None:
    cleaned = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", name.split(":")[0])
    if "사본" in cleaned:  # "통장 사본": '통장'은 받는 통장 이야기와 섞여서 '사본'으로 찾는다
        return "사본"
    for tok in cleaned.split():
        if tok.endswith(_DOC_SUFFIXES):
            return next(s for s in _DOC_SUFFIXES if tok.endswith(s))
    return None


def core_facts(policy: dict) -> list[Fact]:
    """정책 정형 필드에서 변환문이 꼭 담아야 할 사실. original_text(출처·쪽수 포함)는 쓰지 않는다."""
    sents = _source_sentences(policy)
    facts: dict[tuple[str, ...], Fact] = {f.needles: f for f in _number_facts(sents)}
    for word, needles in _PERIOD_WORDS.items():
        ctx = next((x for x in sents if word in normalize(x)), None)
        if ctx:
            facts.setdefault(needles, Fact(f"주기 '{word}'", needles, 1, ctx))
    for ch in _CHANNELS:
        if ch in (policy.get("apply_method") or ""):
            facts.setdefault((ch,), Fact(f"신청 방법 '{ch}'", (ch,)))
    # 신청 주체: 원문에 "시설종사자가 신청" 처럼 누가 하는지 나오면 변환문에도 그 사람이 있어야 한다
    for actor, needles in _ACTORS:
        if actor in (policy.get("apply_method") or ""):
            ctx = next((x for x in sents if actor in x), "")
            facts.setdefault(needles, Fact(f"신청 주체 '{actor}'", needles, 1, ctx))
    target = policy.get("target_description") or ""
    if _EXCLUSION_RE.search(target):
        needles = ("받을수없", "제외", "지원불가", "해당하지않")
        ctx = next((x for x in re.split(r"(?<=[.다])\s+", target) if _EXCLUSION_RE.search(x)), "")
        facts.setdefault(needles, Fact("받을 수 없는 경우", needles, 1, ctx))
    for doc in policy.get("required_docs") or []:
        name = doc["name"] if isinstance(doc, dict) else str(doc)
        key = _doc_key(name)
        if key:
            facts.setdefault((key,), Fact(f"서류 '{key}'", (key,), 1, name))
    return list(facts.values())


def _with_easy_words(needles: tuple[str, ...]) -> tuple[str, ...]:
    """용어 사전의 쉬운 말로 바꿔 쓴 것도 인정한다 (신청서 → 신청하는 종이)."""
    extra = []
    for t in load_terms():
        term = normalize(t.term)
        for n in needles:
            if n == term:
                extra += [normalize(t.easy), normalize(t.gloss)]
            elif term in n:  # 합성어: 증빙서류 → 증명하는 서류
                extra.append(normalize(t.easy))
    return needles + tuple(extra)


# 쉬운 말로 쓴 수량: "1회 지급" → "한 번 받습니다", "1일 2,400원" → "하루에 2,400원" (G027 · G037 · G073)
_NUMBER_WORDS = {"1회": ("한번",), "2회": ("두번",), "3회": ("세번",), "1일": ("하루",), "1개월": ("한달",), "1년": ("한해",)}


def _with_number_words(needles: tuple[str, ...]) -> tuple[str, ...]:
    extra = []
    for n in needles:
        m = re.match(r"\d+[^\d]+?(?=이상|이하|초과|미만|이내|이후|이전|부터|까지|전|후|안에|뒤|$)", n)
        for word in _NUMBER_WORDS.get(m.group(0) if m else "", ()):
            extra.append(word + n[m.end():])
    return needles + tuple(extra)


def _missing(policy: dict, converted: str) -> list[Fact]:
    norm = normalize(converted)
    out = []
    for f in core_facts(policy):
        found = sum(norm.count(n) for n in _with_number_words(_with_easy_words(f.needles)))
        if found < min(f.count, MAX_REQUIRED):
            out.append(f)
    return out


# 서류 조건 → 변환문에서 그 서류와 같은 문장에 있어야 하는 말 (쉬운 말 포함)
# 범위를 좁히는 조건만 본다. "방문 신청 시 준비"처럼 준비 안내인 조건은 보지 않는다
_DOC_CONDITION_WORDS = (
    ("대리", ("대리", "대신")), ("압류", ("압류", "실패")), ("요청", ("요청",)),
)
_DOC_CONDITION_PREFIX = (("우편", ("우편", "팩스")), ("팩스", ("우편", "팩스")))


@dataclass(frozen=True)
class DocCondition:
    key: str  # 서류를 찾는 말 (정규화)
    easy: tuple[str, ...]  # 용어 사전의 쉬운 말 (정규화)
    distinct: tuple[str, ...]  # 쉬운 말로 찾을 때 함께 있어야 하는 그 서류만의 말 (예: 관계, 신청인)
    need: tuple[tuple[str, ...], ...]  # 같은 문장에 있어야 하는 조건 말
    condition: str  # 원문 조건

    def mentioned(self, sent_norm: str) -> bool:
        if self.key in sent_norm:
            # "증빙서류"처럼 짧은 이름은 다른 서류("방문 제한 사유 증빙 서류")와 겹친다 → 그 서류만의 말도 있어야 한다
            return not self.distinct or any(d in sent_norm for d in self.distinct)
        # 쉬운 말("증명하는 서류")은 여러 서류에 겹치므로 그 서류만의 말도 있어야 같은 서류로 본다
        return any(e in sent_norm for e in self.easy) and any(d in sent_norm for d in self.distinct)

    def satisfied(self, sent_norm: str) -> bool:
        return any(any(w in sent_norm for w in words) for words in self.need)


_GENERIC_TOKENS = ("서류", "해당", "등", "1부", "1종", "중")


def doc_conditions(policy: dict) -> list[DocCondition]:
    docs = [d for d in policy.get("required_docs") or [] if isinstance(d, dict)]
    keys = [_doc_key(d["name"]) for d in docs]
    out = []
    for doc, key in zip(docs, keys):
        cond = doc.get("condition") or ""
        if not cond or not key or keys.count(key) > 1:  # 같은 이름(확인서 등)이 여러 서류면 구분할 수 없어 건너뜀
            continue
        need = [w for trigger, w in _DOC_CONDITION_WORDS if trigger in cond]
        need += [w for trigger, w in _DOC_CONDITION_PREFIX if cond.startswith(trigger)]
        if not need:
            continue
        nkey = normalize(key)
        easy = tuple(x for x in _with_easy_words((nkey,)) if x != nkey)
        cleaned = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", doc["name"].split(":")[0])
        distinct = tuple(normalize(t)[:3] for t in cleaned.split()
                         if len(t) >= 2 and key not in t and t not in _GENERIC_TOKENS)
        out.append(DocCondition(nkey, easy, distinct, tuple(need), cond))
    return out


def doc_condition_gaps(policy: dict, converted: str) -> list[str]:
    """조건부 서류인데, 그 서류를 말한 문장에 조건이 없으면 범위가 넓어진다 ("위임장이 필요합니다" ← 대리 신청 시)."""
    sents = sentences(converted)
    gaps = []
    for dc in doc_conditions(policy):
        # 그 서류를 말한 문장마다 조건이 있어야 한다 (한 곳에만 있으면 다른 문장은 범위가 넓게 읽힘)
        bad = [x for x in sents if dc.mentioned(normalize(x)) and not dc.satisfied(normalize(x))]
        for x in bad[:2]:
            gaps.append(f"\"{x}\" — 이 서류는 조건부다. 같은 문장에 조건을 쓸 것 (원문 조건: \"{dc.condition}\")")
    return gaps


_POSTAL_RE = re.compile(r"우편|팩스")
_POSTAL_LIMIT_WORDS = ("제한", "어려", "못", "할수없")


def channel_condition_gaps(policy: dict, converted: str) -> list[str]:
    """원문이 "직접 방문이 제한될 때" 우편·팩스 신청을 허용하면, 변환문에서 우편·팩스를 말한 문장마다 그 조건이 있어야 한다."""
    apply = policy.get("apply_method") or ""
    src = [x for x in re.split(r"(?<=[.다])\s+", apply) if _POSTAL_RE.search(x)]
    if not any("제한" in x for x in src):
        return []
    bad = [x for x in sentences(converted)
           if _POSTAL_RE.search(x) and not any(w in normalize(x) for w in _POSTAL_LIMIT_WORDS)]
    return [f"우편·팩스를 말한 문장에 '직접 방문이 제한될 때' 조건이 없다: \"{x}\"" for x in bad]


def missing_facts(policy: dict, converted: str) -> list[str]:
    return [f.label for f in _missing(policy, converted)]


def missing_details(policy: dict, converted: str) -> list[str]:
    """재변환 지적용: 빠진 사실 + 그 사실이 나온 원문 문장."""
    out = []
    for f in _missing(policy, converted):
        need = min(f.count, MAX_REQUIRED)
        times = f" (원문에서 서로 다른 조건에 {f.count}번 쓰임 → 각 조건마다 써야 함)" if need > 1 else ""
        ctx = f' — 원문: "…{f.context[:100]}…"' if f.context else ""
        out.append(f"{f.label}{times}{ctx}")
    return out


def sentences(text: str) -> list[str]:
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line or (line.startswith("[") and line.endswith("]")):
            continue  # 항목 머리말
        for s in _SENTENCE_SPLIT_RE.split(line):
            s = re.sub(r"^(\d+\.(?!\d)|[-•①②③④⑤⑥⑦⑧⑨])\s*", "", s.strip()).strip()
            # 번호만 남은 조각, 닫는 괄호 같은 글자 없는 조각은 버림
            if not s or re.fullmatch(r"\d+\.?", s) or not re.search(r"[가-힣A-Za-z]", s):
                continue
            if re.fullmatch(r"\([^()]*\)\.?", s):  # 앞 문장에 붙은 괄호 보충(메뉴 경로 등)은 문장으로 세지 않음
                continue
            out.append(s)
    return out


def style_violations(text: str) -> list[str]:
    """합니다체(~니다)로 끝나지 않는 문장. "받는 것: ..." 같은 머리말 뒤 문장도 본다."""
    bad = []
    for s in sentences(text):
        body = s.rstrip(".!? ")
        while re.search(r"\([^()]*\)", body):  # 괄호 보충은 (겹친 괄호도) 빼고 본다
            body = re.sub(r"\s*\([^()]*\)", "", body)
        body = body.rstrip(".!? ")
        if not re.search(r"(니다|니까)$", body):
            bad.append(s)
    return bad


@dataclass
class CheckResult:
    missing_facts: list[str] = field(default_factory=list)
    missing_details: list[str] = field(default_factory=list)
    missing_units: list[str] = field(default_factory=list)  # 어느 문장도 인용하지 않은 필수 원문 단위
    unit_gaps: list[str] = field(default_factory=list)  # 인용은 했지만 그 단위의 핵심 요소가 인용 문장에 없음
    condition_gaps: list[str] = field(default_factory=list)  # 조건이 빠져 범위가 넓어진 서류
    unsupported_numbers: list[str] = field(default_factory=list)
    tier_violations: list[str] = field(default_factory=list)
    style_violations: list[str] = field(default_factory=list)
    assertion: bool = False
    avg_eojeol: float = 0.0
    max_eojeol: int = 0
    core_sentences: int = 0
    level_problems: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not (self.missing_facts or self.missing_units or self.unit_gaps or self.condition_gaps or self.unsupported_numbers or self.tier_violations
                    or self.style_violations or self.assertion or self.level_problems)

    def feedback(self) -> list[str]:
        """재변환 때 LLM 에 줄 지적 사항."""
        out = []
        if self.missing_facts:
            out.append("빠진 핵심 조건. 값·경계 단어를 그대로 넣을 것 (core 가 길어지면 more 에 넣을 것):")
            out += [f"  · {d}" for d in self.missing_details]
        if self.missing_units:
            out.append("아무 문장도 인용하지 않은 원문 단위. 내용을 알맞은 항목에 넣고 src 에 번호를 달 것:")
            out += [f"  · {u}" for u in self.missing_units]
        if self.unit_gaps:
            out.append("원문 번호를 인용했지만 그 번호의 핵심 내용이 인용한 문장에 없다. 그 문장에 함께 쓸 것:")
            out += [f"  · {g}" for g in self.unit_gaps]
        out += [f"{g}. 같은 문장에 조건을 함께 쓸 것" for g in self.condition_gaps]
        if self.unsupported_numbers:
            out.append("원문에 없는 숫자(지울 것): " + ", ".join(self.unsupported_numbers))
        out += [f"용어: {v}" for v in self.tier_violations]
        if self.style_violations:
            out.append("합니다체로 끝나지 않은 문장: " + " / ".join(self.style_violations[:5]))
        if self.assertion:
            out.append("사용자의 자격·신청 가능 여부를 단정하는 표현을 지울 것")
        out += self.level_problems
        return out


_REFERS_BACK_RE = re.compile(r"^(이 경우|이때|이는|그 경우|그때|이런 경우|예를 들면)")


def dangling_sentences(text: str) -> list[str]:
    """앞 문장을 가리키는 문장("이 경우 …", "예를 들면 …")이 문단 첫 문장이면 무엇을 가리키는지 알 수 없다.
    서류 항목의 "예를 들면 …" 문장은 무엇의 예시인지 흐려지므로 대상 문장과 합치게 한다."""
    problems = []
    item = ""
    for para in re.split(r"\n\s*\n", text):
        lines = [l.strip() for l in para.splitlines() if l.strip()]
        if lines and lines[0].startswith("[") and lines[0].endswith("]"):
            item, lines = lines[0][1:-1], lines[1:]
        sents = sentences("\n".join(lines))
        if sents and _REFERS_BACK_RE.match(sents[0]):
            problems.append(f'"{sents[0]}" — 문단 첫 문장이 앞 문장을 가리킨다. 가리키는 대상을 문장 안에 직접 쓸 것')
        if item == "서류":
            problems += [f'"{x}" — 예시는 대상 서류와 같은 문장 괄호 안에 쓸 것 (예: "…증명하는 서류(재학증명서 등)가 필요합니다")'
                         for x in sents[1:] if x.startswith("예를 들면")]
    return problems


def check(policy: dict, core_text: str, full_text: str, level: Level, source_text: str) -> CheckResult:
    core = sentences(core_text)
    lengths = [len(s.split()) for s in core] or [0]
    r = CheckResult(
        missing_facts=missing_facts(policy, full_text),
        missing_details=missing_details(policy, full_text),
        condition_gaps=doc_condition_gaps(policy, full_text) + channel_condition_gaps(policy, full_text),
        # 목록 번호("1.")는 빼고, 공백은 살려서 숫자를 센다 ("2. 18세" 가 "2.18" 로 붙지 않게)
        unsupported_numbers=sorted(unsupported_numbers(expand_dates(" ".join(sentences(full_text))),
                                                       expand_dates(source_text))),
        tier_violations=tier_violations(full_text, level.plain_tier, level.gloss_tier),
        style_violations=style_violations(full_text),
        assertion=has_assertion(full_text),
        avg_eojeol=round(sum(lengths) / len(lengths), 1),
        max_eojeol=max(lengths),
        core_sentences=len(core),
    )
    if r.avg_eojeol > level.avg_eojeol * TOLERANCE:
        r.level_problems.append(f"핵심 블록 문장이 길다: 평균 {r.avg_eojeol}어절 (기준 {level.avg_eojeol})")
    if r.max_eojeol > level.max_eojeol * TOLERANCE:
        long = [x for x in core if len(x.split()) > level.max_eojeol * TOLERANCE]
        r.level_problems.append(
            f"core 에 {level.max_eojeol}어절보다 긴 문장이 있다. 둘로 나눌 것: " + " / ".join(f'"{x}"' for x in long[:3])
        )
    lo, hi = level.core_sentences
    # 하한은 느슨하게 (기준 하한의 절반, 최소 3문장): 너무 짧아 core 가 비는 것만 막는다
    r.level_problems += dangling_sentences(full_text)
    # 원문 내용이 아주 적으면(예: W003 의료비 — 185자, 핵심 사실 1개) 그보다 많이 쓰라고 할 수 없다
    # → 하한을 핵심 사실 수에 맞춰 낮춘다 (P001 처럼 사실이 많으면 그대로)
    lower = min(max(3, lo // 2), max(3, len(core_facts(policy))))
    if r.core_sentences < lower:
        r.level_problems.append(
            f"core 가 {r.core_sentences}문장으로 너무 짧다 (기준 {lo}~{hi}). 받는 것·받는 사람의 가장 중요한 조건·"
            "신청 방법 한 줄·꼭 필요한 서류를 core 에도 간단히 쓸 것 (more 의 자세한 내용은 그대로 둘 것)"
        )
    if r.core_sentences > hi * TOLERANCE:
        r.level_problems.append(
            f"core 문장이 {r.core_sentences}개로 많다. core 는 {hi}문장 이하로 줄이고, "
            "세부 조건·예외·절차·서류 목록은 more 로 옮길 것 (빼지 말고 옮길 것)"
        )
    return r


def _density(terms: int, text: str) -> float | None:
    """어려운 용어 수 / 1,000자 (공백 빼고). 요약해서 글이 짧아진 효과를 빼고 '글이 쉬워졌는지'를 본다"""
    chars = len(re.sub(r"\s", "", text))
    return round(terms * 1000 / chars, 1) if chars else None


def metrics(source_text: str, core_text: str) -> dict:
    """발표 지표: 전문용어 감소율 · 밀도, 문장길이 감소율 (핵심 블록 기준).

    감소율(1 - 변환 용어 수 / 원문 용어 수)은 핵심 블록이 원문보다 짧은 만큼 저절로 올라간다
    (P001: 원문 1,863자 → 핵심 404자). 용어를 쉬운 말로 바꿨는지는 밀도(1,000자당 용어 수)로 함께 본다."""
    src_terms, out_terms = count_hard_terms(source_text), count_hard_terms(core_text)
    src_len = [len(s.split()) for s in sentences(source_text)] or [0]
    out_len = [len(s.split()) for s in sentences(core_text)] or [0]
    src_avg, out_avg = sum(src_len) / len(src_len), sum(out_len) / len(out_len)
    return {
        "hard_terms_source": src_terms,
        "hard_terms_converted": out_terms,
        "term_reduction": round(1 - out_terms / src_terms, 3) if src_terms else None,
        "term_density_source": _density(src_terms, source_text),
        "term_density_converted": _density(out_terms, core_text),
        # T4: L1~L5 에서 쉬운 말로 바꿔 써야 하는 용어 (T3 은 모든 단계에서 그대로 쓰고 사전 풀이 연결)
        "t4_terms_source": count_hard_terms(source_text, min_tier=4),
        "t4_terms_converted": count_hard_terms(core_text, min_tier=4),
        "avg_eojeol_source": round(src_avg, 1),
        "avg_eojeol_converted": round(out_avg, 1),
        "length_reduction": round(1 - out_avg / src_avg, 3) if src_avg else None,
    }
