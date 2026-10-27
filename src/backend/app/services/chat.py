"""AI 상담 채팅 (api.md 1.6, 3.15). 대화 내용은 저장·로그 안 함"""

from datetime import date

from sqlalchemy.orm import Session

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
    """라우터: 위기(규칙) → 서류 작성 요청이면 문서 AI 로 핸드오프 → 정책 질문(RAG) / 고민 상담"""
    if is_crisis(body.message):
        return ChatResponse(
            type="crisis",
            message=CRISIS_MESSAGE,
            search=None,
            contacts=[Contact(**c) for c in CRISIS_CONTACTS],
        )

    history = [h.model_dump() for h in body.history]
    routed = call_llm("route_message", doc_llm.route_message, _check_route, body.message, history, {"screen": "chat"})
    if routed["route"] == "document":
        return ChatResponse(
            type="document", message=HANDOFF_MESSAGE, search=None, contacts=[],
            handoff=handoff_for(db, user, body.message, routed.get("doc_hint"), today),
        )

    intent = call_llm(
        "classify_intent", llm.classify_intent, lambda v: v in ("policy", "counsel"), body.message, history
    )
    if intent == "policy":
        return ChatResponse(
            type="policy", message=None, search=search(db, user, body.message, today, rag), contacts=[]
        )

    reply = call_llm(
        "counsel_reply", llm.counsel_reply, _is_text, body.message, history, _profile(user, today)
    )
    return ChatResponse(type="counsel", message=reply, search=None, contacts=[])
