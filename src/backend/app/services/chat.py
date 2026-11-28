"""AI 상담 채팅 (api.md 1.6, 3.15). 대화 내용은 저장·로그 안 함"""

import logging
import queue
import re
import threading
import time
from collections.abc import Callable, Iterator
from concurrent.futures import ThreadPoolExecutor
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import pii, profile_signal, sse
from app.errors import AppError
from app.crisis import CRISIS_CONTACTS, CRISIS_MESSAGE, is_crisis
from app.doc_llm import DocLlm
from app.llm_client import Llm, ai_unavailable, call_llm
from app.rag_client import SearchPolicies
from app.rules import calc_age, calc_d_day, calc_stage
from app.schemas.chat import ChatRequest, ChatResponse, Contact
from app.money import normalize_amounts
from app.services import my_check
from app.services.policies import search
from app.services.roadmap import build_roadmap, is_broad_question, personal_plan
from app.services.writer import HANDOFF_MESSAGE, handoff_for
from db.models import Policy, User

logger = logging.getLogger(__name__)

# ---------------- 규칙으로 먼저 판단 (뻔한 말은 AI 에게 묻지 않는다) ----------------
# 서류를 '쓰려는' 요청에는 쓰기 낱말이 꼭 들어간다. 없으면 서류 쪽이 아니다 (있으면 AI 가 판단)
_WRITE_WORDS = ("써", "쓰", "작성", "적어", "적을", "채워", "채우")
# 공식 정보를 찾아야 하는 질문 (정책 검색)
_POLICY_WORDS = (
    "수당", "지원금", "지원제도", "정책", "제도", "혜택", "장학", "대출", "보조금", "바우처",
    "임대주택", "전세임대", "매입임대", "보증금지원", "월세지원", "주거지원", "자격", "신청방법", "신청기간", "받을수",
    # 정책 이름 · 제도 낱말 (질문 칩이 모두 규칙으로 바로 정책 검색에 가게)
    "지원", "급여", "정착금", "등록금", "교육비", "의료비", "서리풀", "디딤씨앗", "적립", "매칭", "수혜",
    "전담기관", "확인서", "발급", "사전신청",
)
# 마음 · 고민 이야기
_FEELING_WORDS = (
    "힘들", "우울", "외로", "불안", "걱정", "무서", "속상", "짜증", "슬프", "슬퍼", "지쳐", "지친", "화나", "막막",
    "고민", "답답", "서운", "눈물", "울고", "울었", "자신없", "자신이없", "스트레스",
)
_GREETING = re.compile(r"^(안녕|하이|고마워|고맙|감사|ㅎㅇ|반가)")
# 앞 대화에 이어지는 짧은 요청 ("더 쉽게") 은 앞 대화를 봐야 해서 AI 가 판단한다
_FOLLOW_UP = ("더쉽게", "쉽게", "다시", "무슨말", "무슨뜻", "그게뭐", "그럼", "그러면")


def _quick_route(message: str) -> str | None:
    """서류 쓰기 요청이 아닌 게 분명하면 "other", 아니면 None (AI 가 판단)"""
    flat = message.replace(" ", "")
    return None if any(w in flat for w in _WRITE_WORDS) else "other"


def _quick_intent(message: str, history: list[dict]) -> str | None:
    """정책 질문 · 상담이 분명하면 그 값, 애매하면 None (AI 가 판단)"""
    flat = message.replace(" ", "")
    policy = any(w in flat.replace("자격증", "") for w in _POLICY_WORDS)  # 자격증 공부는 진로 이야기
    # 짧은 말은 앞 대화에 이어지는 말일 수 있다 (정책 낱말이 있으면 그대로 정책 질문)
    if history and ((len(flat) <= 12 and not policy) or any(w in flat for w in _FOLLOW_UP)):
        return None
    feeling = any(w in flat for w in _FEELING_WORDS)
    if policy and not feeling:
        return "policy"
    if feeling and not policy:
        return "counsel"
    if not history and _GREETING.match(flat) and len(flat) <= 12:
        return "counsel"
    return None


def _profile(user: User, today: date) -> dict:
    # LLM 에는 이름·전화번호·생년월일 안 보냄 (api.md 1.6)
    return {
        "stage": calc_stage(calc_age(user.birth_date, today), user.status),
        "status": user.status,
        "d_day": calc_d_day(user.d_date, today),
    }


def _is_text(v: object) -> bool:
    return isinstance(v, str) and bool(v.strip())


def _check_route(v: object) -> bool:
    return isinstance(v, dict) and v.get("route") in ("document", "other")


def chat(
    db: Session, user: User, body: ChatRequest, today: date, llm: Llm, rag: SearchPolicies, doc_llm: DocLlm,
) -> ChatResponse:
    """라우터: 위기(규칙) → 서류 작성 요청이면 문서 AI 로 핸드오프 → 정책 질문(RAG) / 고민 상담.
    위기가 아니면 대화에서 찾은 프로필 변경 후보(profile_changes)를 같이 준다"""
    if is_crisis(body.message):
        return ChatResponse(
            type="crisis",
            message=CRISIS_MESSAGE,
            search=None,
            contacts=[Contact(**c) for c in CRISIS_CONTACTS],
        )
    res = _answer(db, user, body, today, llm, rag, doc_llm)
    res.profile_changes = profile_signal.detect(body.message, user, today)
    return res


def _route(
    db: Session, user: User, body: ChatRequest, today: date, llm: Llm, rag: SearchPolicies, doc_llm: DocLlm,
    on_stage: Callable[[str], None] = lambda _stage: None,
) -> ChatResponse | None:
    """서류 넘기기 · 정책 검색이면 그 응답, 고민 상담이면 None (답은 따로 만든다).
    뻔한 말은 규칙으로 바로 정하고, 애매한 것만 AI 에게 묻는다.
    서류 쪽인지(route)와 정책·상담 중 무엇인지(intent)는 서로 상관없는 질문이라 둘 다 물을 때는 동시에 묻는다."""
    history = [h.model_dump() for h in body.history]
    route = _quick_route(body.message)
    intent = _quick_intent(body.message, history)
    if intent != "counsel" and (route is None or intent is None):
        on_stage("route")
        # AI 에게 묻는 동안 정책 검색용 질문 임베딩을 미리 만든다 (정책 질문으로 판단되면 바로 검색, 실제 RAG 만)
        prefetch = getattr(rag, "prefetch", None)
        if prefetch is not None:
            # 검색과 같이 개인정보를 가린 문장으로 (OpenAI 로 나가는 글자는 모두 가린다 · 기억해 둔 임베딩이 검색 때 그대로 쓰이게)
            prefetch(pii.mask(body.message))
    with ThreadPoolExecutor(max_workers=2) as pool:
        routed_job = None if route else pool.submit(
            call_llm, "route_message", doc_llm.route_message, _check_route, body.message, history, {"screen": "chat"}
        )
        intent_job = None if intent else pool.submit(
            call_llm, "classify_intent", llm.classify_intent, lambda v: v in ("policy", "counsel"), body.message, history
        )
        routed = routed_job.result() if routed_job else {"route": route, "doc_hint": None}
        if routed["route"] == "document":
            if intent_job:
                intent_job.cancel()  # 서류로 넘기면 intent 는 쓰지 않는다 (이미 시작했으면 끝나도 결과는 버림)
            on_stage("document")
            return ChatResponse(
                type="document", message=HANDOFF_MESSAGE, search=None, contacts=[],
                handoff=handoff_for(db, user, body.message, routed.get("doc_hint"), today),
            )
        intent = intent_job.result() if intent_job else intent
    if intent == "policy":
        on_stage("search")
        found = search(db, user, body.message, today, rag)
        roadmap = None
        if found.answer:
            on_stage("check")
            found.answer = normalize_amounts(found.answer)  # "300천원" → "30만 원" (앱 표기와 같게)
            # 답변 끝에 이 사람 기준 판정 · 더 유리한 방법 (규칙). answer 글에 붙여서 응답 형식은 그대로.
            # 덧붙이기가 실패해도 답변은 그대로 나간다
            try:
                roadmap = build_roadmap(db, user, today)
                ids = [r.policy_id for r in found.results]
                by_id = {p.id: p for p in db.scalars(select(Policy).where(Policy.id.in_(ids)))} if ids else {}
                note = my_check.lines(db, user, found.results, by_id, today, roadmap)
                found.answer = my_check.append_to(found.answer, note)
            except Exception as e:  # noqa: BLE001
                logger.error("내 조건 덧붙이기 실패: %s", type(e).__name__)
        plan = personal_plan(db, user, today, roadmap) if is_broad_question(body.message) else None
        return ChatResponse(type="policy", message=None, search=found, contacts=[], plan=plan)
    return None


def _answer(
    db: Session, user: User, body: ChatRequest, today: date, llm: Llm, rag: SearchPolicies, doc_llm: DocLlm,
) -> ChatResponse:
    routed = _route(db, user, body, today, llm, rag, doc_llm)
    if routed is not None:
        return routed
    history = [h.model_dump() for h in body.history]
    reply = call_llm(
        "counsel_reply", llm.counsel_reply, _is_text, body.message, history, _profile(user, today)
    )
    return ChatResponse(type="counsel", message=reply, search=None, contacts=[])


# ---------------- 스트리밍 (POST /chat/stream) ----------------

# 지금 하는 일 (status 이벤트). 앱은 '답변 준비 중' 대신 이 글을 보여 준다
STATUS_TEXT = {
    "route": "질문을 살펴보는 중",
    "search": "관련 정책을 찾는 중",
    "read": "정책 원문을 읽는 중",
    "write": "답을 정리하는 중",
    "check": "내 조건과 맞춰 보는 중",
    "document": "알맞은 서류를 찾는 중",
    "counsel": "답을 생각하는 중",
}
# 정책 검색(RAG)은 한 번의 호출 안에서 원문 찾기 → 답 쓰기를 하므로, 그 안의 단계는 걸린 시간으로 넘긴다
_TIMED_NEXT = {"search": ("read", 1.2), "read": ("write", 2.0)}
_POLL = 0.2


def status(stage: str) -> str:
    return sse.event("status", {"stage": stage, "text": STATUS_TEXT[stage]})


def chat_stream(
    db: Session, user: User, body: ChatRequest, today: date, llm: Llm, rag: SearchPolicies, doc_llm: DocLlm,
) -> Iterator[str]:
    """지금 하는 일을 status 로 먼저 보내고 (질문 살펴보기 → 정책 찾기 → 원문 읽기 → 답 정리 → 내 조건 맞춰 보기),
    위기 · 서류 넘기기 · 정책 검색은 done 하나로 끝낸다.
    고민 상담만 문장이 나오는 대로 delta 로 보내고, 마지막에 done (보통 응답과 같은 모양).
    답을 만들다 실패하면 error (정책 검색 오류 RAG_UNAVAILABLE 도 여기로)"""
    if is_crisis(body.message):
        return iter([sse.event("done", chat(db, user, body, today, llm, rag, doc_llm))])
    return _stream(db, user, body, today, llm, rag, doc_llm)


def _stream(
    db: Session, user: User, body: ChatRequest, today: date, llm: Llm, rag: SearchPolicies, doc_llm: DocLlm,
) -> Iterator[str]:
    stages: queue.Queue[str | None] = queue.Queue()
    box: dict = {}

    def work() -> None:
        # 세션은 이 스레드만 쓴다 (스트림 쪽은 기다리기만)
        try:
            box["changes"] = profile_signal.detect(body.message, user, today)
            box["routed"] = _route(db, user, body, today, llm, rag, doc_llm, on_stage=stages.put)
        except BaseException as e:  # noqa: BLE001 — 아래에서 error 이벤트로
            box["error"] = e
        finally:
            stages.put(None)

    worker = threading.Thread(target=work, name="chat-route", daemon=True)
    worker.start()
    current, since = None, time.monotonic()
    while True:
        try:
            stage = stages.get(timeout=_POLL)
        except queue.Empty:
            nxt = _TIMED_NEXT.get(current)
            if nxt and time.monotonic() - since >= nxt[1]:
                current, since = nxt[0], time.monotonic()
                yield status(current)
            continue
        if stage is None:
            break
        if stage != current:
            current, since = stage, time.monotonic()
            yield status(stage)
    worker.join()

    error = box.get("error")
    if error is not None:
        if isinstance(error, AppError):
            yield sse.error(error)
        else:  # 대화 내용은 로그에 남기지 않는다
            logger.error("상담 답변 오류: %s", type(error).__name__)
            yield sse.error(ai_unavailable())
        return
    changes = box["changes"]
    routed = box["routed"]
    if routed is not None:
        routed.profile_changes = changes
        yield sse.event("done", routed)
        return

    yield status("counsel")
    history = [h.model_dump() for h in body.history]
    try:
        for ev in _counsel_events(llm, body.message, history, _profile(user, today)):
            if "delta" in ev:
                yield sse.delta(ev["delta"])
            else:
                res = ChatResponse(
                    type="counsel", message=ev["final"], search=None, contacts=[], profile_changes=changes,
                )
                yield sse.event("done", res, replace=bool(ev.get("replace")))
    except Exception as e:  # 대화 내용은 로그에 남기지 않는다
        logger.error("LLM 함수 오류: counsel_stream %s", type(e).__name__)
        yield sse.error(ai_unavailable())


def _counsel_events(llm: Llm, message: str, history: list[dict], profile: dict) -> Iterator[dict]:
    """스트리밍 함수가 없으면(테스트 가짜 등) 보통 답변을 한 번에 보낸다. 형식이 틀리면 오류"""
    if llm.counsel_stream is None:
        reply = llm.counsel_reply(message, history, profile)
        if not _is_text(reply):
            raise ValueError("빈 답변")
        yield {"delta": reply}
        yield {"final": reply, "replace": False}
        return
    final = False
    for ev in llm.counsel_stream(message, history, profile):
        if "delta" in ev and _is_text(ev["delta"]):
            yield ev
        elif _is_text(ev.get("final")):
            final = True
            yield ev
    if not final:
        raise ValueError("마지막 답이 없음")
