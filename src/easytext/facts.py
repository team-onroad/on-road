"""정책 원문 → 핵심 사실(짧은 키워드) 추출 → 정책 카드 요약.

AI 는 원문에서 대상·제외·지원·기간·신청·서류를 짧은 구절로 뽑기만 하고, 카드는 코드가 템플릿으로 만든다.
뽑은 구절은 원문과 대조해 검사한다 (원문에 없는 숫자, 빠진 원문 단위, 숫자·경계 단어·부정 표현 누락, 길이).
검사를 통과할 때까지 최대 3번 다시 뽑는다. 쉬운 말 변환(convert.py)은 이 결과를 빠지면 안 되는 사실 목록으로 쓴다.

실행 (src 폴더에서, 백엔드 가상환경 사용, .env 의 OPENAI_API_KEY 필요):
    backend\\.venv\\Scripts\\python -m easytext.facts P001
    backend\\.venv\\Scripts\\python -m easytext.facts --all            # policies.json 의 모든 정책 (원문이 그대로면 건너뜀)
    backend\\.venv\\Scripts\\python -m easytext.facts P001 --judge     # 다른 모델로 의미 2차 검수

결과: data/policy_facts/{policy_key}.json. "verified" 는 사람이 원문과 대조한 뒤 true 로 바꾼다.
"""

import argparse
import hashlib
import json
import re
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from easytext.checks import _EXCLUSION_RE, _META_WORDS, _number_facts, normalize
from easytext.convert import (
    POLICIES_PATH, Generate, _blocking, _judge, _judge_feedback, _openai_generate, _strip_parens, source_text,
    source_units,
)
from easytext.levels import get_level
from easytext.prompts import ELIGIBILITY_NOTICE
from rag.settings import PROJECT_ROOT

OUT_DIR = PROJECT_ROOT / "data" / "policy_facts"
MAX_RETRIES = 3
# 카드 한 줄 길이 상한 (공백 포함). 넘으면 둘로 나누게 한다. 서류·확인 필요는 카드에 안 나와서 조건을 다 담게 길게 둔다
MAX_CHARS = 45
MAX_CHARS_LONG = 70
_LONG_ITEMS = ("docs", "check")

# 항목 코드 → 카드 머리말. 순서 = 카드에 보이는 순서
ITEMS = {
    "target": "지원 대상",
    "exclusion": "받을 수 없는 경우",
    "benefit": "지원 내용",
    "period": "지원 기간",
    "apply": "신청 방법",
    "docs": "필요 서류",
    "check": "확인 필요",
}
# 검색 카드에 보여 줄 항목과 항목별 최대 줄 수 (서류·확인 필요는 상세 화면·체크리스트에서 본다)
CARD_LIMITS = {"target": 3, "exclusion": 2, "benefit": 2, "period": 1, "apply": 2}
CARD_ITEMS = tuple(CARD_LIMITS)
# 체크리스트 단계별로 보여 줄 항목. 실제로 할 일을 정하는 화면이라 key 와 개수 제한 없이 전부 보여 준다
CHECKLIST_ITEMS = {"target_check": ("target", "exclusion"), "condition_check": ("target", "exclusion"), "apply": ("apply",)}
# 원문 단위의 필드 → 그 단위를 인용해야 하는 항목. 문의처는 카드에 넣지 않으므로 인용하지 않아도 된다
_OPTIONAL_FIELDS = {"문의처"}

_NEG_RE = re.compile(r"불가|제외|중지|않|없|금지|못")
# 명사형이 아닌 끝 ("~한다", "~이며", "~경우를"): 문장을 그대로 옮기거나 중간에서 자른 것
_SENTENCE_END_RE = re.compile(r"(니다|[한된있없이]다|[하이]며|하고|[을를])$")

FACTS_SYSTEM = f"""너는 정부 지원 정책 원문에서 핵심 사실만 짧게 뽑는 편집자다. 결과는 정책 카드와 쉬운 말 변환의 기준이 된다.

[반드시 지킬 것]
1. [정책 원문]에 있는 내용만 쓴다. 없는 사실·숫자·예외를 만들지 않는다.
2. 사실 하나 = 짧은 명사형 구절 하나 ({MAX_CHARS}자 이내, docs·check 는 {MAX_CHARS_LONG}자 이내). "~입니다", "~한다" 같은 문장 끝을 쓰지 않는다.
   구절마다 혼자 읽어도 뜻이 통해야 한다. 괄호로 시작하는 조각("(2018년 8월 이후 보호종료)")을 따로 쓰지 말고 누구의 조건인지 함께 쓴다.
   (좋은 예: "보호종료 전 2년 이상 연속 보호", "월 50만 원, 매월 20일 지급", "만기·연장 종료: 종료 후 5년 이내")
3. 숫자·날짜·단위·경계 단어(이상·이하·초과·미만·이내·이후·이전·부터·까지)는 원문 값 그대로 쓴다.
   약식 날짜('18.8.)는 "2018년 8월"처럼 풀어 쓴다.
4. 함께 맞아야 하는 조건은 한 구절에 묶거나 "A: B" 형태로 누구의 조건인지 밝힌다. 같은 숫자가 다른 조건에 쓰이면 조건마다 따로 쓴다.
5. 길어서 글자 수를 넘으면 같은 원문 번호로 구절을 둘로 나눈다. 조건을 지워서 줄이지 않는다.
6. 누가 하는지(본인·대리인·시설종사자)와 조건부 서류의 조건은 괄호로 함께 쓴다 (예: "위임장 (대리 신청 시)").
7. 제외 조건·지급 중지는 "exclusion" 항목에 쓴다. 출처끼리 다르거나 담당 기관 확인이 필요한 내용은 "check" 항목에 쓴다.
8. 읽는 사람이 대상이라고 단정하지 않는다.
9. 출처끼리 비교하는 메모("업무매뉴얼에는 ~", "정보북은 ~")는 그대로 옮기지 않는다. 조건이면 조건만 쓰고 출처 이름은 뺀다.
   출처마다 표현이 다르면 조건을 각각 쓰고, 다르다는 사실은 "check" 에 "제외 범위 담당기관 확인 필요"처럼 짧게 쓴다.
10. 메뉴 경로("로그인 > 서비스 신청 > …")는 쓰지 않는다.

[항목] item 은 다음 코드 중 하나:
{chr(10).join(f"- {code}: {label}" for code, label in ITEMS.items())}

[카드 표시 key]
- 정책 카드에는 key 가 true 인 구절만 보인다. 처음 보는 사람이 "나도 해당될까, 얼마를, 어떻게"를 판단하는 데 가장 중요한 구절에만 붙인다.
- 항목별 key 최대 개수: {", ".join(f"{code} {n}개" for code, n in CARD_LIMITS.items())}. docs·check 에는 붙이지 않는다.
- key 구절은 숫자 조건(기간·나이·금액)이 들어 있는 완전한 조건을 우선한다. 조건 일부만 있는 구절은 key 로 고르지 않는다
  (나쁜 예: "18세 이후 만기 보호종료"만 key 로 두고 같은 사람의 "5년 이내"는 key 가 아님 → 둘을 한 구절로 묶어 key 로).
- target 의 key 는 누가(대상)와 가장 중요한 숫자 조건을, apply 의 key 는 대표 신청 경로(방문·온라인 등)를 담는다.
- benefit 의 key 에는 금액·주기를 넣는다. key 가 아닌 구절도 빠짐없이 출력한다 (쉬운 말 변환의 기준이 된다).

[원문 번호]
- [정책 원문]은 S1, S2, ... 번호가 붙은 단위로 주어진다. 구절마다 그 구절이 온 원문 번호를 src 로 단다.
- "(선택)"이 없는 번호는 반드시 한 구절 이상에서 인용한다. 인용한 번호의 숫자·조건·부정 표현(불가·제외·중지)은 빠짐없이 쓴다.

[출력 형식] JSON 하나만 출력한다.
{{"facts": [{{"item": "target", "text": "짧은 구절", "src": ["S1"], "key": true}}]}}"""

FACTS_USER = """[정책 원문]
{source}"""

# 2차 검수(convert.JUDGE_SYSTEM)에 덧붙이는 말: 변환문이 문장이 아니라 키워드 목록이다
JUDGE_FACTS_NOTE = """

[이번 변환문] 문장이 아니라 원문에서 뽑은 짧은 키워드 목록이다. 다음은 줄임으로 보고 severity "low" 로 둔다:
괄호 속 예시·종류 목록을 상위어로 줄인 것("아동복지시설(아동양육시설·…)" → "아동복지시설"), 서류·기관 이름을 줄인 것, 조사·어미를 뺀 것.
조건·숫자·경계 단어·신청 주체·제외가 달라진 것은 그대로 "high" 다."""


def _keyword_judge(judge: Generate) -> Generate:
    def call(messages: list[dict]) -> dict:
        return judge([{**messages[0], "content": messages[0]["content"] + JUDGE_FACTS_NOTE}, *messages[1:]])

    return call


RETRY_USER = """앞의 결과가 검사를 통과하지 못했다. 아래 지적만 고쳐서 같은 JSON 형식으로 전체를 다시 출력한다.
지적되지 않은 구절은 그대로 둔다. 빠졌다고 지적된 내용은 지우지 말고 알맞은 항목에 더한다.
{feedback}"""


@dataclass
class FactCheck:
    format_problems: list[str] = field(default_factory=list)  # 항목 코드·원문 번호·빈 구절·괄호 조각·key 개수
    too_long: list[str] = field(default_factory=list)
    unsupported_numbers: list[str] = field(default_factory=list)  # 인용한 원문에 없는 숫자
    missing_units: list[str] = field(default_factory=list)  # 어느 구절도 인용하지 않은 필수 원문 단위
    unit_gaps: list[str] = field(default_factory=list)  # 인용은 했지만 그 단위의 숫자·부정 표현이 구절에 없음

    @property
    def passed(self) -> bool:
        return not (self.format_problems or self.too_long or self.unsupported_numbers or self.missing_units
                    or self.unit_gaps)

    def feedback(self) -> list[str]:
        out = list(self.format_problems)
        if self.too_long:
            out.append("글자 수가 넘는 구절. 같은 원문 번호로 둘로 나눌 것 (조건을 지우지 말 것):")
            out += [f"  · {t}" for t in self.too_long]
        if self.unsupported_numbers:
            out.append("인용한 원문에 없는 숫자. 원문 값으로 고치거나 지울 것:")
            out += [f"  · {t}" for t in self.unsupported_numbers]
        if self.missing_units:
            out.append("아무 구절도 인용하지 않은 원문 단위. 알맞은 항목에 구절을 더하고 src 에 번호를 달 것:")
            out += [f"  · {u}" for u in self.missing_units]
        if self.unit_gaps:
            out.append("원문 번호를 인용했지만 그 번호의 핵심 내용이 구절에 없다. 구절에 함께 쓰거나 구절을 더할 것:")
            out += [f"  · {g}" for g in self.unit_gaps]
        return out


def clean_facts(raw: object) -> list[dict]:
    """LLM 출력 → [{"item", "text", "src"}]. 형식이 틀린 항목은 check_facts 가 지적하도록 그대로 둔다."""
    facts = raw.get("facts") if isinstance(raw, dict) else None
    out = []
    for f in facts if isinstance(facts, list) else []:
        if not isinstance(f, dict):
            continue
        src = f.get("src") if isinstance(f.get("src"), list) else []
        out.append({
            "item": str(f.get("item") or "").strip(),
            "text": re.sub(r"\s+", " ", str(f.get("text") or "")).strip(),
            "src": [str(s).strip() for s in src if str(s).strip()],
            "key": f.get("key") is True,
        })
    return out


def _digits(text: str) -> set[str]:
    return set(re.findall(r"\d+", normalize(text)))


def check_facts(facts: list[dict], units: list[dict]) -> FactCheck:
    by_id = {u["id"]: u for u in units}
    r = FactCheck()
    cited: dict[str, list[dict]] = {}
    for f in facts:
        label = f'"{f["text"]}"'
        if f["item"] not in ITEMS:
            r.format_problems.append(f"{label}: 항목 코드 '{f['item']}' 는 없다. {', '.join(ITEMS)} 중에서 고를 것")
        if not f["text"]:
            r.format_problems.append("빈 구절이 있다. 지울 것")
            continue
        unknown = [s for s in f["src"] if s not in by_id]
        if not f["src"] or unknown:
            r.format_problems.append(f"{label}: 원문 번호(src)가 없거나 없는 번호다 ({', '.join(unknown) or '없음'})")
        limit = MAX_CHARS_LONG if f["item"] in _LONG_ITEMS else MAX_CHARS
        if len(f["text"]) > limit:
            r.too_long.append(f"{label} ({len(f['text'])}자, {limit}자 이내)")
        if any(w in f["text"] for w in _META_WORDS):
            r.format_problems.append(f"{label}: 출처 이름·출처 비교 메모를 뺄 것. 조건만 짧게 쓸 것")
        if ">" in f["text"]:
            r.format_problems.append(f"{label}: 메뉴 경로는 쓰지 않는다")
        if _SENTENCE_END_RE.search(f["text"].rstrip(" .")):
            r.format_problems.append(f"{label}: 명사형 구절로 끝낼 것 (문장 끝·중간에서 자른 말 금지)")
        if f["text"].startswith(("(", "[", "※")) and not f["text"].startswith("[택"):
            r.format_problems.append(f"{label}: 괄호·기호로 시작하는 조각이다. 누구의 조건인지 함께 써서 혼자 읽어도 통하게 할 것")
        source = " ".join(by_id[s]["text"] for s in f["src"] if s in by_id)
        extra = sorted(_digits(f["text"]) - _digits(source), key=int)
        if source and extra:
            r.unsupported_numbers.append(f"{label}: {', '.join(extra)}")
        for s in f["src"]:
            cited.setdefault(s, []).append(f)

    for code, n in CARD_LIMITS.items():
        keys = [f for f in facts if f["item"] == code and f.get("key")]
        if len(keys) > n:
            r.format_problems.append(f"{code} 의 key 가 {len(keys)}개다. 가장 중요한 {n}개에만 key 를 붙일 것")
    for u in units:
        if not u["required"] or u["field"] in _OPTIONAL_FIELDS:
            continue
        got = cited.get(u["id"])
        if not got:
            r.missing_units.append(f'{u["id"]} [{u["field"]}] "{u["text"][:120]}"')
            continue
        text = u["text"]
        joined = normalize(" ".join(f["text"] for f in got))
        lacking = []
        if not any(w in text for w in _META_WORDS):  # 출처 메모 단위의 숫자는 조건이 아니다
            lacking += [f.label for f in _number_facts([text])
                        if f.label != "1부" and not any(n in joined for n in f.needles)]
        body = _strip_parens(text)  # 괄호 속 보충(서류 조건 등)의 부정 표현은 본문 기준에서 뺀다
        if _NEG_RE.search(body) and not (
                any(f["item"] in ("exclusion", "check") for f in got) or any(_NEG_RE.search(f["text"]) for f in got)):
            lacking.append("부정 표현(불가·제외·중지 등) 또는 exclusion 항목")
        # 출처끼리 다른 제외 조건도 check 로만 넘기지 않는다 (조건 자체가 사라짐). 각각 exclusion 에 쓰게 한다
        if _EXCLUSION_RE.search(body) and not any(f["item"] == "exclusion" for f in got):
            lacking.append("제외 조건은 exclusion 항목에 (출처마다 다르면 각각)")
        if lacking:
            r.unit_gaps.append(f'{u["id"]}: {", ".join(dict.fromkeys(lacking))} — 원문: "{text[:100]}"')
    return r


def card(facts: list[dict]) -> list[dict]:
    """검색 카드용 요약: [{"label": "지원 대상", "items": [...]}]. key 구절만, 항목별 최대 개수까지.
    key 가 하나도 없는 항목은 앞에서부터 최대 개수만큼 쓴다. 같은 구절은 한 번만."""
    out = []
    for code, n in CARD_LIMITS.items():
        rows = [f for f in facts if f["item"] == code and f["text"]]
        keyed = [f for f in rows if f.get("key")]
        items = list(dict.fromkeys(f["text"] for f in (keyed or rows)))[:n]
        if items:
            out.append({"item": code, "label": ITEMS[code], "items": items})
    return out


def sections(facts: list[dict], codes: tuple[str, ...]) -> list[dict]:
    """항목별 구절 전부: [{"item", "label", "items"}]. 같은 구절은 한 번만."""
    out = []
    for code in codes:
        items = list(dict.fromkeys(f["text"] for f in facts if f["item"] == code and f["text"]))
        if items:
            out.append({"item": code, "label": ITEMS[code], "items": items})
    return out


def doc_labels(policy: dict, facts: list[dict]) -> dict[str, str]:
    """서류 원래 이름 → 짧은 이름. 원문 서류 하나만 인용한 docs 구절만 쓴다 (여러 서류를 묶은 구절은 서류마다 나눌 수 없음)."""
    names = [d["name"] if isinstance(d, dict) else str(d) for d in policy.get("required_docs") or []]
    doc_units = [u for u in source_units(policy) if u["field"] == "필요 서류"]  # required_docs 순서와 같다
    by_unit = {u["id"]: name for u, name in zip(doc_units, names)}
    out: dict[str, str] = {}
    for f in facts:
        if f["item"] == "docs" and f["text"] and len(f["src"]) == 1 and f["src"][0] in by_unit:
            out.setdefault(by_unit[f["src"][0]], f["text"])
    return out


def derive(policy: dict, facts: list[dict]) -> dict:
    """사실 목록으로 만드는 화면용 결과 (AI 호출 없음): 검색 카드, 체크리스트 단계별 요약, 서류 짧은 이름."""
    return {
        "card": card(facts),
        "checklist": {step: sections(facts, codes) for step, codes in CHECKLIST_ITEMS.items()},
        "doc_labels": doc_labels(policy, facts),
    }


def facts_text(facts: list[dict]) -> str:
    """사람·2차 검수가 읽는 형태: 항목별 목록."""
    blocks = []
    for code, label in ITEMS.items():
        items = [f["text"] for f in facts if f["item"] == code]
        if items:
            blocks.append(f"[{label}]\n" + "\n".join(f"- {t}" for t in items))
    return "\n\n".join(blocks)


def source_hash(policy: dict) -> str:
    """추출 입력(정형 필드)이 바뀌었는지 보는 값. 같으면 --all 에서 다시 뽑지 않는다."""
    units = source_units(policy)
    raw = json.dumps([(u["field"], u["text"]) for u in units], ensure_ascii=False)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def extract(policy: dict, generate: Generate, judge: Generate | None = None) -> dict:
    """자동 검사 → (judge 가 있으면) 다른 모델의 의미 검수까지 통과해야 passed."""
    units = source_units(policy)
    messages = [
        {"role": "system", "content": FACTS_SYSTEM},
        {"role": "user", "content": FACTS_USER.format(
            source=f"[정책명] {policy.get('name')}\n[담당 기관] {policy.get('agency')}\n\n" + "\n".join(
                f"{u['id']}{'' if u['required'] else ' (선택)'} [{u['field']}] {u['text']}" for u in units))},
    ]
    attempts: list[tuple[list[dict], FactCheck]] = []
    judge_issues: list[dict] | None = None
    for _ in range(1 + MAX_RETRIES):
        raw = generate(messages)
        facts = clean_facts(raw)
        result = check_facts(facts, units)
        attempts.append((facts, result))
        feedback = result.feedback()
        if result.passed and judge is not None:
            # 2차 검수는 L7(원래 용어 그대로) 기준으로 오탐을 거른다
            judge_issues = _judge(_keyword_judge(judge), source_text(policy), facts_text(facts), get_level("L7"))
            feedback = _judge_feedback(_blocking(judge_issues))
        if not feedback:
            break
        messages += [
            {"role": "assistant", "content": json.dumps(raw, ensure_ascii=False)},
            {"role": "user", "content": RETRY_USER.format(feedback="\n".join(f"- {f}" for f in feedback))},
        ]

    best = len(attempts) - 1 if attempts[-1][1].passed else min(
        range(len(attempts)), key=lambda k: len(attempts[k][1].feedback()))
    facts, result = attempts[best]
    judged_ok = judge is None or (result.passed and judge_issues is not None and not _blocking(judge_issues))
    return {
        "policy_key": policy["policy_key"],
        "name": policy.get("name"),
        "facts": facts,
        **derive(policy, facts),
        "notice": ELIGIBILITY_NOTICE,
        "passed": result.passed and judged_ok,
        "auto_checks_passed": result.passed,
        "attempts": len(attempts),
        "checks": asdict(result),
        "judge_issues": judge_issues if result.passed and best == len(attempts) - 1 else None,
        "source_hash": source_hash(policy),
        "source_checked_at": policy.get("checked_at"),
        "verified": False,
    }


def load(policy_key: str, out_dir: Path = OUT_DIR) -> dict | None:
    path = out_dir / f"{policy_key}.json"
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def passed_facts(policy: dict, out_dir: Path = OUT_DIR) -> list[dict] | None:
    """쉬운 말 변환이 기준으로 쓸 사실 목록. 검사를 통과했고 원문이 그 뒤로 바뀌지 않았을 때만."""
    data = load(policy["policy_key"], out_dir)
    if not data or not data.get("passed") or data.get("source_hash") != source_hash(policy):
        return None
    return data.get("facts") or None


def save(result: dict, model: str, out_dir: Path = OUT_DIR) -> Path:
    """결과 저장. 원문이 그대로이고 사람이 확인한(verified) 결과는 덮어써도 verified 를 지우지 않는다."""
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{result['policy_key']}.json"
    old = load(result["policy_key"], out_dir) or {}
    keep_verified = old.get("verified") and old.get("source_hash") == result["source_hash"] and old.get(
        "facts") == result["facts"]
    data = {**result, "verified": bool(keep_verified), "model": model,
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def rebuild(policy: dict, out_dir: Path = OUT_DIR) -> Path | None:
    """저장된 사실로 화면용 결과만 다시 만든다. 사실·검사 결과·verified 는 그대로 둔다."""
    data = load(policy["policy_key"], out_dir)
    if not data or not isinstance(data.get("facts"), list):
        return None
    path = out_dir / f"{policy['policy_key']}.json"
    path.write_text(json.dumps({**data, **derive(policy, data["facts"])}, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def _policies(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8").strip()
    return json.loads(text) if text else []


def main() -> None:
    from rag.settings import get_rag_settings

    parser = argparse.ArgumentParser(description="정책 핵심 사실 추출·카드 요약")
    parser.add_argument("policy_keys", nargs="*")
    parser.add_argument("--all", action="store_true", help="policies.json 의 모든 정책")
    parser.add_argument("--force", action="store_true", help="원문이 그대로여도 다시 뽑기")
    parser.add_argument("--file", type=Path, default=POLICIES_PATH)
    parser.add_argument("--judge", action="store_true", help="다른 모델로 의미 보존 2차 검수")
    parser.add_argument("--rebuild", action="store_true", help="AI 호출 없이 저장된 사실로 카드·체크리스트 요약만 다시 만들기")
    args = parser.parse_args()
    if not args.all and not args.policy_keys:
        parser.error("정책 키를 주거나 --all 을 쓰세요.")

    policies = _policies(args.file)
    if not args.all:
        wanted = set(args.policy_keys)
        missing = wanted - {p.get("policy_key") for p in policies}
        if missing:
            raise SystemExit(f"{args.file} 에 없는 정책: {', '.join(sorted(missing))}")
        policies = [p for p in policies if p.get("policy_key") in wanted]

    if args.rebuild:
        for policy in policies:
            print(f"{policy['policy_key']}: " + (f"다시 만듦 → {p}" if (p := rebuild(policy)) else "저장된 결과 없음"))
        return

    s = get_rag_settings()
    generate = _openai_generate(s.easytext_model)
    judge = _openai_generate(s.easytext_judge_model) if args.judge else None
    for policy in policies:
        old = load(policy["policy_key"])
        if args.all and not args.force and old and old.get("passed") and old.get("source_hash") == source_hash(policy):
            print(f"{policy['policy_key']}: 원문이 그대로라 건너뜀")
            continue
        r = extract(policy, generate, judge)
        print(f"\n===== {policy['policy_key']} {policy.get('name')}: {'통과' if r['passed'] else '실패'} (시도 {r['attempts']}번)")
        for line in FactCheck(**r["checks"]).feedback():
            print(line)
        for i in r["judge_issues"] or []:
            print(f"[2차 검수] {i.get('reason')} | 원문: {i.get('original')} | 구절: {i.get('converted')}")
        print(facts_text(r["facts"]))
        print(f"저장: {save(r, s.easytext_model)}")
    print("\n원문과 대조한 뒤 맞으면 \"verified\" 를 true 로 바꿔 주세요.")


if __name__ == "__main__":
    main()
