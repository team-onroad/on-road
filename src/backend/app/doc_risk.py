"""서류 속 숨겨진 위험 찾기 (등기부등본 · 임대차 계약서 · 근로계약서).

규칙은 app/doc_risk_rules.json 에 우리가 정하고, AI 는 그 규칙대로 서류에서 값과 근거 문장을 뽑아 판정한다.
서버가 다시 확인하는 것:
- 숫자로 정할 수 있는 규칙(kind=calc)은 AI 판정을 버리고 뽑은 값으로 다시 계산한다 (최저임금, 빚 비율, 날짜)
- 다른 서류와 맞춰 보는 규칙(kind=match)은 사용자가 넣은 계약 정보(집주인 이름·주소)와 비교한다
- 근거 문장 없는 '위험'은 '확인 필요'로 낮추고, PDF 글자에 없는 근거 문장은 버린다 (지어낸 위험 방지)
- 설명·할 일·연습은 규칙 파일 문장만 쓴다 (AI 문장을 그대로 보여 주지 않음)
올린 파일과 결과는 저장하지 않는다. 근거 문장의 주민등록번호·계좌번호는 가린다.
"""

import json
import re
from dataclasses import dataclass
from datetime import date, timedelta
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.doc_llm import DocLlm
from app.form_upload import _flat, read_source
from app.llm_client import call_llm
from app.roleplay import get_scenarios

RULES_PATH = Path(__file__).resolve().parent / "doc_risk_rules.json"
DOC_TYPES = ("registry", "lease", "labor")
STATUSES = ("danger", "check", "ok", "unknown")
STATUS_ORDER = {"danger": 0, "check": 1, "unknown": 2, "ok": 3}  # 보여 줄 순서
EVIDENCE_MAX = 120
NOTICE = "서류에서 찾은 내용을 규칙에 맞춰 본 결과예요. 법률 상담은 아니어서, 큰돈이 오가면 공인중개사·고용센터 등에 꼭 다시 물어봐요."

DocType = Literal[DOC_TYPES]
Status = Literal[STATUSES]


class Rule(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    kind: Literal["match", "presence", "calc"]
    section: str | None = None
    title: str
    values: list[str] = Field(default_factory=list)
    condition: dict[str, str]
    explain: str
    action: str
    practice: str | None = None


class DocRules(BaseModel):
    model_config = ConfigDict(extra="forbid")

    label: str
    extract: list[str]
    rules: list[Rule]


class RuleFile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: str
    description: str
    statuses: dict[str, str]
    judge_rules: list[str]
    params: dict
    docs: dict[str, DocRules]
    sources: list[dict]


@lru_cache
def get_rules() -> RuleFile:
    """형식 오류 · 없는 연습 상황 · 서류 종류 누락이면 서버 시작 시 실패"""
    rf = RuleFile.model_validate_json(RULES_PATH.read_bytes())
    if set(rf.docs) != set(DOC_TYPES):
        raise ValueError("doc_risk_rules.json docs 는 registry · lease · labor")
    scenarios = get_scenarios()
    ids: set[str] = set()
    for d in rf.docs.values():
        for r in d.rules:
            if r.id in ids:
                raise ValueError(f"doc_risk_rules.json 규칙 id 중복: {r.id}")
            ids.add(r.id)
            if r.practice and r.practice not in scenarios:
                raise ValueError(f"doc_risk_rules.json {r.id}: 없는 연습 상황 {r.practice}")
    return rf


# ------------------------------------------------------------------ 값 다듬기

_NUM_RE = re.compile(r"\d[\d,]*")
_RRN_RE = re.compile(r"\d{6}\s*-\s*[1-8]\d{6}")
_ACCOUNT_RE = re.compile(r"\d{2,6}-\d{2,6}-\d{2,8}")


def money(v: object) -> int | None:
    """정수 · "1억 2천만 원" · "350,000,000" · "30만 원" → 원"""
    if isinstance(v, bool):
        return None
    if isinstance(v, int):
        return v if v >= 0 else None
    if isinstance(v, float):
        return int(v) if v >= 0 else None
    if not isinstance(v, str):
        return None
    s = v.replace(" ", "").replace("원", "")
    if not s:
        return None
    if re.fullmatch(r"[\d,]+", s):
        return int(s.replace(",", ""))
    total, rest = 0, s
    for unit, mul in (("억", 10**8), ("천만", 10**7), ("백만", 10**6), ("만", 10**4)):
        if unit in rest:
            head, rest = rest.split(unit, 1)
            n = _NUM_RE.search(head)
            total += (int(n.group().replace(",", "")) if n else 1) * mul
    n = _NUM_RE.search(rest)
    if n:
        total += int(n.group().replace(",", ""))
    return total or None


def day(v: object) -> date | None:
    if not isinstance(v, str):
        return None
    m = re.search(r"(\d{4})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})", v)
    if not m:
        return None
    try:
        return date(int(m[1]), int(m[2]), int(m[3]))
    except ValueError:
        return None


def number(v: object) -> float | None:
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        m = re.search(r"\d+(\.\d+)?", v)
        return float(m.group()) if m else None
    return None


def truthy(v: object) -> bool | None:
    """있음/없음 값: true · "있음" · 문장 → True, false · "없음" · "" → False, null → None"""
    if v is None:
        return None
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)):
        return v != 0
    if isinstance(v, (list, dict)):
        return bool(v)
    s = str(v).strip()
    if not s:
        return False
    return not any(w in s for w in ("없음", "없다", "미기재", "해당없음", "해당 없음"))


def mask(text: str) -> str:
    return _ACCOUNT_RE.sub("***-***-***", _RRN_RE.sub("******-*******", text))


def _norm_name(s: str) -> str:
    return re.sub(r"[\s()·.,]|주식회사|\(주\)", "", s)


def _norm_addr(s: str) -> str:
    return re.sub(r"[\s,.\-]|특별시|광역시|특별자치시|특별자치도", "", s)


# ------------------------------------------------------------------ 판정


@dataclass
class Judged:
    status: str
    evidence: str | None
    explain: str


@dataclass
class Context:
    landlord_name: str | None = None
    address: str | None = None
    deposit: int | None = None
    market_price: int | None = None


def _min_wage(year: int) -> tuple[int, int]:
    table = {int(k): int(v) for k, v in get_rules().params["min_wage"].items()}
    known = [y for y in table if y <= year] or [min(table)]
    y = max(known)
    return y, table[y]


def _calc(rule: Rule, v: dict, ctx: Context, today: date, ai: Judged) -> Judged:
    """숫자로 정하는 규칙. 값이 없으면 AI 판정을 쓰되, 위험은 근거가 있을 때만"""
    p = get_rules().params
    if rule.id == "REG-04":
        price = ctx.market_price or money(v.get("market_price"))
        deposit = ctx.deposit or money(v.get("deposit"))
        debt = money(v.get("mortgage_max_total"))
        if debt is None and truthy(v.get("mortgages")) is False:
            debt = 0
        if not price:
            return Judged("check", ai.evidence, "집값(시세)을 넣으면 빚과 보증금이 얼마나 되는지 계산해 줘요.")
        if debt is None or deposit is None:
            return Judged("unknown", ai.evidence, "근저당 금액이나 보증금을 서류에서 찾지 못했어요.")
        ratio = (debt + deposit) / price
        pct = round(ratio * 100)
        if ratio > p["lease_debt_ratio_danger"]:
            return Judged("danger", ai.evidence, f"빚과 보증금이 집값의 {pct}%예요. 70%를 넘으면 위험해요.")
        return Judged("ok", ai.evidence, f"빚과 보증금이 집값의 {pct}%예요.")
    if rule.id == "REG-07":
        d = day(v.get("owner_changed_at"))
        if d is None:
            return ai
        days = (today - d).days
        if 0 <= days <= p["recent_owner_change_days"]:
            return Judged("check", ai.evidence, f"주인이 바뀐 지 {days}일밖에 안 됐어요.")
        return Judged("ok", ai.evidence, "주인이 바뀐 지 오래됐어요.")
    if rule.id == "REG-08":
        d = day(v.get("issued_at"))
        if d is None:
            return Judged("unknown", None, "등기부를 뗀 날짜를 찾지 못했어요.")
        days = (today - d).days
        if days > p["registry_fresh_days"]:
            return Judged("check", ai.evidence, f"{days}일 전에 뗀 등기부예요. 그사이 빚이 생겼을 수 있어요.")
        return Judged("ok", ai.evidence, "최근에 뗀 등기부예요.")
    if rule.id == "LEA-05":
        s, e = day(v.get("start_date")), day(v.get("end_date"))
        if s is None or e is None:
            return ai
        try:
            two_years = s.replace(year=s.year + 2)
        except ValueError:  # 2월 29일
            two_years = s.replace(year=s.year + 2, day=28)
        if e < two_years - timedelta(days=1):  # 12.1 ~ 2년 뒤 11.30 까지는 2년
            return Judged("check", ai.evidence, rule.explain)
        return Judged("ok", ai.evidence, "계약 기간이 2년 이상이에요.")
    if rule.id == "LAB-01":
        wage = money(v.get("hourly_wage"))
        if wage is None:
            return ai if ai.status != "danger" else Judged("check", ai.evidence, "시급이 적혀 있지 않아요. 시급으로 얼마인지 물어봐요.")
        start = day(v.get("contract_start")) or today
        year, minimum = _min_wage(start.year)
        if wage >= minimum:
            return Judged("ok", ai.evidence, f"{year}년 최저 시급 {minimum:,}원 이상이에요.")
        end = day(v.get("contract_end"))
        long_contract = end is not None and (end - start).days >= 365
        simple_job = any(w in str(v.get("job_type") or "") for w in ("단순", "청소", "경비", "배달", "포장"))
        if truthy(v.get("probation")) and long_contract and not simple_job and wage >= int(minimum * 0.9):
            return Judged("check", ai.evidence, f"수습 중이라 최저 시급의 90%({int(minimum * 0.9):,}원)까지는 될 수 있어요. 수습은 3개월까지예요.")
        return Judged("danger", ai.evidence, f"시급 {wage:,}원이 {year}년 최저 시급 {minimum:,}원보다 적어요.")
    if rule.id == "LAB-03":
        hours = number(v.get("work_hours_per_week"))
        holiday = truthy(v.get("weekly_holiday"))
        if hours is None:
            return ai
        if hours < p["weekly_holiday_hours"]:
            return Judged("ok", ai.evidence, "주 15시간보다 적게 일해서 주휴수당은 없어요.")
        if holiday:
            return Judged("ok", ai.evidence, "주휴일이 적혀 있어요.")
        return Judged("check", ai.evidence, f"주 {hours:g}시간이라 주휴수당 대상인데 적혀 있지 않아요.")
    return ai  # LAB-07 등은 AI 판정 + 근거 확인만


def _match(rule: Rule, v: dict, ctx: Context, ai: Judged) -> Judged:
    if rule.id == "REG-01":
        owners = v.get("owner_names")
        names = [str(n) for n in (owners if isinstance(owners, list) else [owners]) if n]
        if not ctx.landlord_name:
            return Judged("unknown", None, "계약서의 집주인 이름을 넣으면 등기부 주인과 맞는지 봐 줘요.")
        if not names:
            return Judged("unknown", None, "등기부에서 주인 이름을 찾지 못했어요.")
        if any(_norm_name(ctx.landlord_name) == _norm_name(n) for n in names):
            return Judged("ok", None, "등기부 주인과 계약하는 사람이 같아요.")
        return Judged("danger", None, rule.explain)
    if rule.id == "REG-06":
        addr = v.get("address")
        if not ctx.address:
            return Judged("unknown", None, "계약서 주소를 넣으면 등기부 주소와 맞는지 봐 줘요.")
        if not addr:
            return Judged("unknown", None, "등기부에서 주소를 찾지 못했어요.")
        a, b = _norm_addr(str(addr)), _norm_addr(ctx.address)
        if a and b and (a in b or b in a):
            return Judged("ok", None, "계약서와 등기부 주소가 같아요.")
        return Judged("danger", None, rule.explain)
    return ai  # LEA-01 (계좌 명의) 처럼 한 서류 안에서 맞춰 보는 규칙은 AI 판정 + 근거 확인


def _ai_judged(rule: Rule, raw: dict | None, source: str | None) -> Judged:
    status = raw.get("status") if raw else None
    status = status if status in STATUSES else "unknown"
    ev = raw.get("evidence") if raw else None
    evidence = " ".join(ev.split())[:EVIDENCE_MAX] if isinstance(ev, str) and ev.strip() else None
    if evidence and source is not None and _flat(evidence) not in source:
        evidence = None  # PDF 글자에 없는 문장은 지어낸 근거
    if status == "danger" and evidence is None:
        status = "check"
    explain = rule.explain if status in ("danger", "check") else (
        "서류에서 찾을 수 없어요." if status == "unknown" else "괜찮아요."
    )
    return Judged(status, evidence, explain)


def judge(doc_type: str, out: dict, ctx: Context, today: date, form_text: str | None) -> dict:
    rf = get_rules()
    doc = rf.docs[doc_type]
    values = out.get("values") if isinstance(out.get("values"), dict) else {}
    by_id = {f.get("rule_id"): f for f in out.get("findings") or [] if isinstance(f, dict)}
    source = _flat(form_text) if form_text else None
    scenarios = get_scenarios()
    findings = []
    for rule in doc.rules:
        ai = _ai_judged(rule, by_id.get(rule.id), source)
        if rule.kind == "calc":
            j = _calc(rule, values, ctx, today, ai)
        elif rule.kind == "match":
            j = _match(rule, values, ctx, ai)
        else:
            j = ai
        practice = rule.practice if j.status in ("danger", "check") else None
        findings.append({
            "rule_id": rule.id,
            "title": rule.title,
            "status": j.status,
            "evidence": mask(j.evidence) if j.evidence else None,
            "explain": j.explain,
            "action": rule.action if j.status in ("danger", "check", "unknown") else None,
            "practice": {"scenario_id": practice, "title": scenarios[practice].title} if practice else None,
        })
    findings.sort(key=lambda f: STATUS_ORDER[f["status"]])  # 같은 단계 안에서는 규칙 순서
    counts = {s: sum(1 for f in findings if f["status"] == s) for s in STATUSES}
    level = "danger" if counts["danger"] else ("check" if counts["check"] or counts["unknown"] else "ok")
    parts = []
    if counts["danger"]:
        parts.append(f"위험한 부분 {counts['danger']}개")
    if counts["check"] + counts["unknown"]:
        parts.append(f"확인할 부분 {counts['check'] + counts['unknown']}개")
    headline = (", ".join(parts) + "가 있어요") if parts else "규칙에 걸리는 부분이 없어요"
    return {
        "doc_type": doc_type,
        "label": doc.label,
        "rules_version": rf.version,
        "summary": {"level": level, "headline": headline, "counts": counts},
        "findings": findings,
        "notice": NOTICE,
    }


def prompt_rules(doc_type: str) -> dict:
    """AI 에 넘길 규칙 (설명·할 일 문장은 빼고 판정에 필요한 것만)"""
    rf = get_rules()
    doc = rf.docs[doc_type]
    return {
        "doc_type": doc_type,
        "label": doc.label,
        "extract": doc.extract,
        "judge_rules": rf.judge_rules,
        "rules": [{"id": r.id, "title": r.title, "section": r.section, "condition": r.condition} for r in doc.rules],
    }


def _check_read(v: object) -> bool:
    return isinstance(v, dict) and isinstance(v.get("findings", []), list) and isinstance(v.get("values", {}), dict)


def check(data: bytes, doc_type: str, ctx: Context, today: date, doc_llm: DocLlm) -> dict:
    text, images = read_source(data)
    out = call_llm("read_risk", doc_llm.read_risk, _check_read, prompt_rules(doc_type), text, images)
    return judge(doc_type, out, ctx, today, text)


def rules_json(doc_type: str) -> str:
    return json.dumps(prompt_rules(doc_type), ensure_ascii=False)
