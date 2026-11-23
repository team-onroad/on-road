"""RAG 정책 검색 정량 평가 (README: "RAG 정량 지표(hallucination률, 정확도) 측정").

질문셋(data/eval/rag_questions.json)을 실제 검색 함수(PolicySearcher)에 그대로 넣고 잰다.
- 검색: 정답 정책이 근거 상위 5개 안에 있는지 (Hit@5), 처음 나온 순위 (MRR)
- 거절: 근거 없는 질문(날씨 · 주식 …)에 답하지 않는지, 답할 수 있는 질문을 잘못 거절하지 않는지
- 사실: 답변에 원문 핵심 사실(금액 · 기간 · 기관 …)이 들어 있는지
- 환각: AI 초안 문장 중 환각 검사(guard)에 걸려 버려진 비율, 최종 답변에 근거 없는 숫자 · 자격 단정이 남았는지
- 정책 섞임: 한 문장이 서로 다른 정책의 근거를 함께 쓴 비율 (rag-ai.md 보완 과제)
- 속도: 질문당 응답 시간 평균 · p95, 답변 대신 안내(fallback) 횟수

평가용 인덱스는 메모리에 따로 만든다 (data/chroma 는 건드리지 않음). 정책은 policies.json 의 실제 정책 (더미 D… 제외).

실행 (src 폴더에서, 백엔드 가상환경):
    backend\\.venv\\Scripts\\python -m rag.evaluate          # 실제 OpenAI (임베딩 + 답변. 30문항 20초 안팎(6개씩 동시에), 수십 원)
    backend\\.venv\\Scripts\\python -m rag.evaluate --mock   # API 없이 (가짜 임베딩 · 가짜 답변, 흐름 확인용)
결과: data/eval_results/rag_eval_{날짜}_{real|mock}.json · .md
"""

import argparse
import hashlib
import json
import math
import re
import statistics
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from datetime import date
from pathlib import Path

from rag.guard import CHECK_NOTICE, MISSING_NOTICE, _check_sentence, _valid_refs, has_assertion, unsupported_numbers
from rag.policy_search import PolicySearcher
from rag.settings import PROJECT_ROOT

QUESTIONS_PATH = PROJECT_ROOT / "data" / "eval" / "rag_questions.json"
POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
RESULTS_DIR = PROJECT_ROOT / "data" / "eval_results"
EMBED_CACHE_PATH = PROJECT_ROOT / "data" / "chroma" / "eval_embed_cache.json"  # 커밋하지 않음 (.gitignore)
TOP_K = 5
WORKERS = 6  # 동시에 묻는 질문 수 (OpenAI 요청 한도 안)
_SOURCE_TAG_RE = re.compile(r"\s*\(출처: [^)]*\)")


@dataclass
class Record:
    id: str
    type: str
    question: str
    status: str
    seconds: float
    retrieved_keys: list[str]
    hit: bool | None  # 정답 정책이 상위 5개 안 (no_evidence 질문은 None)
    rank: int | None  # 정답 정책이 처음 나온 순위 (1부터)
    evidence_keys: list[str]
    facts_expected: list[str]
    facts_found: list[str]
    draft_sentences: int = 0
    draft_rejected: int = 0  # 환각 검사에 걸려 버려진 초안 문장
    draft_mixed: int = 0  # 서로 다른 정책 근거를 함께 쓴 초안 문장
    final_unsupported: list[str] = field(default_factory=list)  # 최종 답변의 근거 없는 숫자 (0 이어야 함)
    final_assertion: bool = False  # 최종 답변의 자격 단정 (False 여야 함)
    fallback: bool = False
    answer: str | None = None
    related_keys: list[str] = field(default_factory=list)  # 답변엔 안 썼지만 뜻이 가까워 함께 보여 준 정책 (11/23)


class RecordingAnswer:
    """답변 함수를 감싸 AI 초안과 근거를 남긴다 (환각 · 섞임 측정용).
    질문을 동시에 물어도 섞이지 않게 스레드마다 따로 남긴다 (검색 함수는 부른 스레드에서 답변 함수를 부른다)"""

    def __init__(self, answer):
        self.answer = answer
        self._local = threading.local()

    @property
    def last(self) -> tuple[dict | None, list[dict]] | None:
        return getattr(self._local, "last", None)

    @last.setter
    def last(self, value) -> None:
        self._local.last = value

    def __call__(self, question, hits):
        self.last = (None, hits)
        draft = self.answer(question, hits)
        self.last = (draft, hits)
        return draft


def _flat(text: str) -> str:
    return re.sub(r"\s+", "", text)


def load_questions(path: Path = QUESTIONS_PATH) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8"))["questions"]


def load_policies(path: Path = POLICIES_PATH) -> list[dict]:
    """실제 정책만 (더미 D… 는 평가에서 뺀다)"""
    items = json.loads(path.read_text(encoding="utf-8-sig"))
    return [p for p in items if not p["policy_key"].startswith("D")]


def evaluate_one(searcher: PolicySearcher, recorder: RecordingAnswer, q: dict, keys: list[str]) -> Record:
    recorder.last = None
    t0 = time.perf_counter()
    out = searcher(q["question"], keys, top_k=TOP_K)
    seconds = time.perf_counter() - t0
    hits = searcher.retrieve(q["question"], keys, TOP_K)  # 임베딩은 기억해 둔 것을 써서 추가 비용 없음
    retrieved = list(dict.fromkeys(h["policy_key"] for h in hits))
    expect = q.get("expect_keys") or []
    rank = next((i + 1 for i, k in enumerate(retrieved) if k in expect), None) if expect else None
    answer = out.get("answer") or ""
    facts = q.get("expect_facts") or []
    rec = Record(
        id=q["id"], type=q["type"], question=q["question"], status=out["status"], seconds=round(seconds, 2),
        retrieved_keys=retrieved, hit=(rank is not None) if expect else None, rank=rank,
        evidence_keys=list(dict.fromkeys(e["policy_key"] for e in out["evidences"] if not e.get("related"))),
        related_keys=[e["policy_key"] for e in out["evidences"] if e.get("related")],
        facts_expected=facts, facts_found=[f for f in facts if _flat(f) in _flat(answer)],
        fallback=bool(out.get("fallback")), answer=out.get("answer"),
    )
    if recorder.last and isinstance(recorder.last[0], dict):
        draft, used_hits = recorder.last
        sents = draft.get("sentences") if isinstance(draft.get("sentences"), list) else []
        rec.draft_sentences = len(sents)
        for s in sents:
            if _check_sentence(s, used_hits) is None:
                rec.draft_rejected += 1
                continue
            refs = _valid_refs(s.get("evidence") if isinstance(s, dict) else [], len(used_hits))
            if len({used_hits[r - 1]["policy_key"] for r in refs}) > 1:
                rec.draft_mixed += 1
    if answer:
        body = _SOURCE_TAG_RE.sub("", answer).replace(CHECK_NOTICE, "").replace(MISSING_NOTICE, "")
        evidence_text = "\n".join(e["content"] for e in out["evidences"] if not e.get("related"))  # 답변 근거만
        rec.final_unsupported = sorted(unsupported_numbers(body, evidence_text))
        rec.final_assertion = has_assertion(body)
    return rec


def _ratio(a: int, b: int) -> float | None:
    return round(a / b, 3) if b else None


def summarize(records: list[Record]) -> dict:
    ask = [r for r in records if r.type in ("answerable", "boundary")]
    none = [r for r in records if r.type == "no_evidence"]
    with_facts = [r for r in ask if r.facts_expected and r.status == "answered"]
    secs = sorted(r.seconds for r in records)
    p95 = secs[min(len(secs) - 1, math.ceil(0.95 * len(secs)) - 1)] if secs else None
    draft = sum(r.draft_sentences for r in records)
    return {
        "질문 수": {"전체": len(records), "답할 수 있음": sum(r.type == "answerable" for r in records),
                  "조건 경계": sum(r.type == "boundary" for r in records), "근거 없음": len(none)},
        "검색": {
            "Hit@5 (정답 정책이 근거 상위 5개 안)": _ratio(sum(bool(r.hit) for r in ask), len(ask)),
            "MRR (정답 정책 순위의 역수 평균)": round(sum(1 / r.rank for r in ask if r.rank) / len(ask), 3) if ask else None,
        },
        "거절": {
            "근거 없는 질문을 거절한 비율": _ratio(sum(r.status == "no_evidence" for r in none), len(none)),
            "답할 수 있는 질문을 잘못 거절한 비율": _ratio(sum(r.status == "no_evidence" for r in ask), len(ask)),
        },
        "사실": {
            "답변에 원문 핵심 사실이 들어간 비율": _ratio(sum(len(r.facts_found) for r in with_facts),
                                              sum(len(r.facts_expected) for r in with_facts)),
        },
        "환각": {
            "AI 초안 문장 수": draft,
            "환각 검사에 걸려 버려진 초안 문장 비율": _ratio(sum(r.draft_rejected for r in records), draft),
            "최종 답변에 근거 없는 숫자가 남은 질문 수": sum(bool(r.final_unsupported) for r in records),
            "최종 답변에 자격 단정이 남은 질문 수": sum(r.final_assertion for r in records),
        },
        "정책 섞임": {
            "서로 다른 정책 근거를 함께 쓴 초안 문장 비율": _ratio(sum(r.draft_mixed for r in records), draft),
        },
        "비슷한 정책": {
            "비슷한 정책 카드가 붙은 질문 수": sum(bool(r.related_keys) for r in records),
            "근거 없는 질문에 붙은 수 (0 이어야 함)": sum(bool(r.related_keys) for r in none),
            "질문당 비슷한 정책 수 (붙은 질문 평균)": round(statistics.mean(len(r.related_keys) for r in records if r.related_keys), 2)
            if any(r.related_keys for r in records) else None,
        },
        "속도": {
            "평균 응답 초": round(statistics.mean(secs), 2) if secs else None,
            "p95 응답 초": p95,
            "답변 대신 안내(fallback) 횟수": sum(r.fallback for r in records),
        },
    }


def _fmt(name: str, value) -> str:
    """비율 · Hit 은 백분율로, 나머지는 그대로"""
    if value is None:
        return "-"
    if isinstance(value, float) and ("비율" in name or "Hit" in name):
        return f"{value:.1%}"
    return str(value)


def to_markdown(summary: dict, records: list[Record], meta: dict) -> str:
    lines = [f"# RAG 정책 검색 평가 ({meta['date']}, {meta['mode']})", "",
             f"- 질문셋: `{meta['questions']}` · 정책 {meta['policies']}개 (더미 제외) · 근거 상위 {TOP_K}개",
             f"- 모델: 임베딩 `{meta.get('embedding_model', '-')}` · 답변 `{meta.get('chat_model', '-')}`", ""]
    for group, items in summary.items():
        lines += [f"## {group}", "", "| 항목 | 값 |", "|---|---|"]
        lines += [f"| {k} | {_fmt(k, v)} |" for k, v in items.items()]
        lines.append("")
    lines += ["## 문항별", "", "| ID | 유형 | 질문 | 결과 | 정답 순위 | 사실 | 걸린 초안 | 초 |", "|---|---|---|---|---|---|---|---|"]
    for r in records:
        fact = f"{len(r.facts_found)}/{len(r.facts_expected)}" if r.facts_expected else "-"
        lines.append(f"| {r.id} | {r.type} | {r.question} | {r.status}{' (대신 안내)' if r.fallback else ''} | "
                     f"{r.rank or ('-' if r.hit is None else '없음')} | {fact} | {r.draft_rejected}/{r.draft_sentences} | {r.seconds} |")
    return "\n".join(lines) + "\n"


# ---------------- 검색기 만들기 ----------------


def _fake_embed(texts: list[str]) -> list[list[float]]:
    """글자 2-gram 해시 벡터 (API 없이 흐름 확인용. 테스트와 같은 방식)"""
    out = []
    for t in texts:
        v = [0.0] * 256
        for tok in re.findall(r"[0-9A-Za-z가-힣]+", t):
            for i in range(max(1, len(tok) - 1)):
                v[sum(map(ord, tok[i:i + 2])) % 256] += 1.0
        n = math.sqrt(sum(x * x for x in v)) or 1.0
        out.append([x / n for x in v])
    return out


def _fake_answer(question: str, hits: list[dict]) -> dict:
    first = hits[0]["content"].splitlines()
    text = next((ln for ln in first if ln and not ln.startswith("[")), first[0])
    return {"sentences": [{"text": text[:120], "evidence": [1]}], "missing": False}


def build_searcher(policies: list[dict], mock: bool) -> tuple[PolicySearcher, RecordingAnswer, dict]:
    import chromadb

    from rag.index import index_policies
    from rag.settings import get_rag_settings
    from rag.store import open_collection

    client = chromadb.EphemeralClient(settings=chromadb.Settings(anonymized_telemetry=False))
    col = open_collection(client, f"eval-{uuid.uuid4().hex[:8]}")
    s = get_rag_settings()
    if mock:
        index_policies(col, policies, _fake_embed)
        recorder = RecordingAnswer(_fake_answer)
        searcher = PolicySearcher(col, _fake_embed, recorder, 1.0, 1.0)
        meta = {"embedding_model": "가짜(2-gram)", "chat_model": "가짜"}
    else:
        from rag.openai_client import embed, embed_query, generate_answer

        cached = CachedEmbed(embed, EMBED_CACHE_PATH, s.rag_embedding_model)
        index_policies(col, policies, cached)
        cached.save()
        recorder = RecordingAnswer(generate_answer)
        searcher = PolicySearcher(col, embed_query, recorder, s.rag_max_distance, s.rag_keyword_max_distance)
        meta = {"embedding_model": s.rag_embedding_model, "chat_model": s.rag_chat_model,
                "embedding_reused": f"{cached.reused}/{cached.reused + cached.made}"}
    return searcher, recorder, meta


class CachedEmbed:
    """정책 조각 임베딩을 디스크에 남겨 다음 평가 때 다시 쓴다 (내용 · 모델이 같으면 OpenAI 를 다시 부르지 않음).
    파일은 data/chroma/ 아래라 커밋하지 않는다. 실행마다 99개 정책 · 약 160조각을 다시 임베딩하던 것"""

    def __init__(self, embed, path: Path, model: str):
        self.embed, self.path, self.model = embed, path, model
        try:
            self.data: dict[str, list[float]] = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        except (OSError, json.JSONDecodeError):
            self.data = {}  # 깨진 파일은 무시하고 새로 만든다
        self.reused = self.made = 0

    def _key(self, text: str) -> str:
        return hashlib.sha1(f"{self.model}\n{text}".encode("utf-8")).hexdigest()

    def __call__(self, texts: list[str]) -> list[list[float]]:
        missing = [t for t in dict.fromkeys(texts) if self._key(t) not in self.data]
        if missing:
            for t, v in zip(missing, self.embed(missing)):
                self.data[self._key(t)] = v
        self.made += len(missing)
        self.reused += len(texts) - len(missing)
        return [self.data[self._key(t)] for t in texts]

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.data), encoding="utf-8")


def unique_base(out_dir: Path, name: str) -> Path:
    """같은 이름 결과가 있으면 _2, _3 … 을 붙인다 (하루에 여러 번 돌려도 앞 결과를 덮지 않게)"""
    base, n = out_dir / name, 2
    while base.with_suffix(".json").exists() or base.with_suffix(".md").exists():
        base, n = out_dir / f"{name}_{n}", n + 1
    return base


def run(mock: bool = False, questions_path: Path = QUESTIONS_PATH, policies_path: Path = POLICIES_PATH,
        out_dir: Path = RESULTS_DIR, today: date | None = None, workers: int = WORKERS,
        label: str | None = None) -> tuple[dict, list[Record], Path]:
    today = today or date.today()
    questions = load_questions(questions_path)
    policies = load_policies(policies_path)
    keys = [p["policy_key"] for p in policies]
    searcher, recorder, meta = build_searcher(policies, mock)
    # 질문을 몇 개씩 동시에 묻는다 (하나씩이면 30문항 약 90초 → 6개씩이면 20초 안팎). 결과 순서는 질문 순서 그대로
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        records = list(pool.map(lambda q: evaluate_one(searcher, recorder, q, keys), questions))
    summary = summarize(records)
    mode = "mock" if mock else "real"
    meta.update(date=today.isoformat(), mode=mode, policies=len(policies),
                questions=questions_path.relative_to(PROJECT_ROOT).as_posix() if questions_path.is_relative_to(PROJECT_ROOT) else str(questions_path))
    out_dir.mkdir(parents=True, exist_ok=True)
    tag = re.sub(r"[^0-9A-Za-z가-힣_.-]+", "-", label).strip("-") if label else ""
    base = unique_base(out_dir, f"rag_eval_{today.strftime('%Y%m%d')}_{mode}" + (f"_{tag}" if tag else ""))
    meta["label"] = label
    base.with_suffix(".json").write_text(
        json.dumps({"meta": meta, "summary": summary, "records": [asdict(r) for r in records]}, ensure_ascii=False, indent=2),
        encoding="utf-8")
    base.with_suffix(".md").write_text(to_markdown(summary, records, meta), encoding="utf-8")
    return summary, records, base


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="RAG 정책 검색 정량 평가")
    parser.add_argument("--mock", action="store_true", help="API 없이 가짜 임베딩 · 가짜 답변으로 (흐름 확인용)")
    parser.add_argument("--questions", type=Path, default=QUESTIONS_PATH)
    parser.add_argument("--workers", type=int, default=WORKERS,
                        help=f"동시에 묻는 질문 수 (기본 {WORKERS}. 1 이면 하나씩 — 응답 시간을 서버 한 명 기준으로 잴 때)")
    parser.add_argument("--label", help="결과 파일 이름표 (예: gpt-4.1-mini). 같은 날 같은 이름이면 _2 를 붙인다")
    args = parser.parse_args(argv)
    summary, _, base = run(mock=args.mock, questions_path=args.questions, workers=args.workers, label=args.label)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n저장: {base}.json · .md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
