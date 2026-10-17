"""AI 상담 채팅 (api.md 1.6, 3.15). 대화 내용은 저장·로그 안 함"""

from datetime import date

from sqlalchemy.orm import Session

from app.crisis import CRISIS_CONTACTS, CRISIS_MESSAGE, is_crisis
from app.llm_client import Llm, call_llm
from app.rag_client import SearchPolicies
from app.rules import calc_age, calc_d_day, calc_stage
from app.schemas.chat import ChatRequest, ChatResponse, Contact
from app.services.policies import search
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


def chat(db: Session, user: User, body: ChatRequest, today: date, llm: Llm, rag: SearchPolicies) -> ChatResponse:
    if is_crisis(body.message):
        return ChatResponse(
            type="crisis",
            message=CRISIS_MESSAGE,
            search=None,
            contacts=[Contact(**c) for c in CRISIS_CONTACTS],
        )

    history = [h.model_dump() for h in body.history]
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
