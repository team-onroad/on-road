"""AI 상담 채팅 (api.md 1.6, 3.15). 대화 내용은 저장·로그 안 함"""

from concurrent.futures import ThreadPoolExecutor
from datetime import date

from sqlalchemy.orm import Session

from app import profile_signal
from app.crisis import CRISIS_CONTACTS, CRISIS_MESSAGE, is_crisis
from app.doc_llm import DocLlm
from app.llm_client import Llm, call_llm
from app.rag_client import SearchPolicies
from app.rules import calc_age, calc_d_day, calc_stage
from app.schemas.chat import ChatRequest, ChatResponse, Contact
from app.services.policies import search
from app.services.writer import HANDOFF_MESSAGE, handoff_for
from db.models import User


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


def _answer(
    db: Session, user: User, body: ChatRequest, today: date, llm: Llm, rag: SearchPolicies, doc_llm: DocLlm,
) -> ChatResponse:
    history = [h.model_dump() for h in body.history]
    # 서류 쪽인지(route)와 정책·상담 중 무엇인지(intent)는 서로 상관없는 질문이라 AI 에게 동시에 묻는다.
    # 예전에는 차례로 물어서 답을 받기까지 AI 왕복이 한 번 더 걸렸다. 결과와 오류 처리는 예전과 같다.
    with ThreadPoolExecutor(max_workers=2) as pool:
        routed_job = pool.submit(
            call_llm, "route_message", doc_llm.route_message, _check_route, body.message, history, {"screen": "chat"}
        )
        intent_job = pool.submit(
            call_llm, "classify_intent", llm.classify_intent, lambda v: v in ("policy", "counsel"), body.message, history
        )
        routed = routed_job.result()
        if routed["route"] == "document":
            intent_job.cancel()  # 서류로 넘기면 intent 는 쓰지 않는다 (이미 시작했으면 끝나도 결과는 버림)
            return ChatResponse(
                type="document", message=HANDOFF_MESSAGE, search=None, contacts=[],
                handoff=handoff_for(db, user, body.message, routed.get("doc_hint"), today),
            )
        intent = intent_job.result()
    if intent == "policy":
        return ChatResponse(
            type="policy", message=None, search=search(db, user, body.message, today, rag), contacts=[]
        )

    reply = call_llm(
        "counsel_reply", llm.counsel_reply, _is_text, body.message, history, _profile(user, today)
    )
    return ChatResponse(type="counsel", message=reply, search=None, contacts=[])
