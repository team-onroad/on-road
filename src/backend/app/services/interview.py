"""문서 AI 대화형 작성 (docs/document-ai.md 3.32).

AI 가 빈 칸을 하나씩 물어보고, 답을 칸에 채운다. 다 채우면 검사하고 통과하면 바로 내보낸다(PDF·체크리스트).

- 이름·연락처·날짜·주소·체크박스 같은 짧은 칸은 규칙으로 정리해 바로 완료한다 (LLM 에 보내지 않음, api.md 1.6)
- 서술형 칸(신청 사유 등)은 답을 바탕으로 AI 가 문장을 쓰고, "좋아"라고 하면 완료, 아니면 그 말대로 고친다
- 미리 채운 값(프로필·다른 서류)은 "이대로 할까요?"로 확인한다
- 필수가 아닌 칸은 "해당 없음"으로 비워 두고 넘어가고, 필수 칸을 건너뛰면 맨 끝에 다시 묻는다
- 답 대신 질문("소득 기준이 뭐야?")이 오면 라우터로 상담 AI 정책 검색·고민 안내로 보낸다 (칸은 그대로)
"""

import re
from datetime import date

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.crisis import CRISIS_CONTACTS, CRISIS_MESSAGE, is_crisis
from app.doc_llm import DocLlm
from app.llm_client import call_llm
from app.rag_client import SearchPolicies
from app.schemas.chat import Contact
from app.schemas.writer import InterviewRequest, InterviewResponse, Question
from app.services import writer as w
from app.services.policies import search
from db.models import Document, DocumentField, User

CONFIRM_WORDS = ("네", "예", "응", "ㅇㅇ", "맞아", "맞아요", "맞습니다", "좋아", "좋아요", "그대로", "확인", "오케이", "ok", "괜찮아")
NOT_APPLICABLE_WORDS = ("해당없음", "해당없어", "없음", "없어", "없어요", "안씀", "안써", "비워", "빈칸")
LATER_WORDS = ("건너뛰", "넘어가", "나중에", "패스", "몰라", "모르겠")
QUESTION_WORDS = (
    "뭐야", "뭐예요", "뭔가요", "뭔데", "뭐지", "어떻게", "어떡", "어때", "언제까지", "무슨", "왜", "되나요", "돼?",
    "할까", "될까", "일까", "인가요", "알려줘", "알려주", "가르쳐", "궁금",
)
# 짧은 칸 답이 이 말로 끝나고 띄어쓰기가 있으면 대화 문장일 수 있다 → 바로 적지 않고 "이렇게 적을까요?"로 확인
_TALK_ENDINGS = ("어", "야", "지", "래", "까", "니", "네", "요", "해", "줘", "다", "음", "ㅋ", "ㅎ")
# 짧은 칸 답 끝의 말버릇은 떼고 적는다 (예: "OO아동복지원이요" → "OO아동복지원")
_TRAILING_RE = re.compile(r"\s*(이에요|예요|이요|입니다|이에용|요)[.!~]*$")

DONE_MESSAGE = "모든 칸을 채웠어요."
EXPORTED_MESSAGE = "모든 칸을 채우고 서류를 만들었어요. PDF로 받아 볼 수 있어요."
NOT_READY_MESSAGE = "다 채웠는데 고칠 칸이 있어요. 아래 내용을 확인해 주세요."

_DIGITS_RE = re.compile(r"\d+")
BRIEF_CHARS = 70


def _flat(text: str) -> str:
    return "".join(text.split()).lower().rstrip(".!~")


def _josa(word: str, with_final: str, without_final: str) -> str:
    """받침 있으면 with_final (을·은·이), 없으면 without_final (를·는·가)"""
    last = word.strip().rstrip(" *:)")[-1:] if word.strip() else ""
    if "가" <= last <= "힣":
        return with_final if (ord(last) - 0xAC00) % 28 else without_final
    return f"{with_final}({without_final})"


def _state(doc: Document) -> dict:
    s = dict(doc.interview or {})
    s.setdefault("current", None)
    s.setdefault("skipped", [])
    s.setdefault("pending", None)
    s.setdefault("confirm_text", None)  # {"field": 칸, "text": 적을까 물어본 답} (대화 문장 같은 짧은 칸 답)
    return s


def _todo(rows: list[DocumentField], specs: dict, skipped: list[str]) -> list[str]:
    """아직 완료하지 않은 쓰기 칸. 건너뛴 필수 칸은 맨 뒤로"""
    open_ids = [r.field_id for r in rows if w._writable(specs[r.field_id]) and not r.is_completed]
    return [f for f in open_ids if f not in skipped] + [f for f in open_ids if f in skipped]


def _question(row: DocumentField, spec: dict, pending: bool, confirm_text: str | None = None) -> Question:
    name = spec.get("easy_label") or spec["label"]
    value = w._value(row.segments).strip()
    can_skip = spec.get("required") is not True
    options = spec.get("options")
    if confirm_text is not None:
        text = (f"'{name}'에 '{confirm_text}'(이)라고 적을까요? 맞으면 '네', 아니면 칸에 쓸 내용을 다시 알려 주세요. "
                "궁금한 게 있으면 물어봐도 돼요.")
        return Question(
            field_id=row.field_id, label=spec["label"], text=text, input_type=spec.get("input_type", "text"),
            options=None, current_value=confirm_text, awaiting_confirm=True, can_skip=can_skip,
        )
    if pending and value:
        text = f"이렇게 써 봤어요.\n\n{value}\n\n괜찮으면 '좋아', 고칠 점이 있으면 말해 주세요."
        confirm = True
    elif value and spec.get("profile_key") != "region":
        text = f"'{name}'{_josa(name, '은', '는')} '{value}'(으)로 할까요? 맞으면 '네', 아니면 바르게 알려 주세요."
        confirm = True
    else:
        confirm = False
        box = spec.get("box") or {}
        if box.get("how") == "confirm":
            items = "\n".join(f"- {_brief(item)}" for item in box.get("items") or [])
            text = f"유의사항 {len(box.get('items') or [])}가지를 읽고 확인해야 해요.\n{items}\n다 읽었으면 '확인'이라고 해 주세요."
        elif options:
            text = f"'{name}'에서 골라 주세요: {', '.join(options)}"
        elif spec.get("essay"):
            outline = spec.get("outline")
            hint = f" ({' → '.join(outline)} 순서로 말해 주면 좋아요.)" if outline else ""
            text = f"'{name}'에 쓸 내용을 편하게 말해 주세요. 제가 문장으로 정리해 드릴게요.{hint}"
        elif spec.get("input_type") == "date":
            text = f"'{name}'{_josa(name, '을', '를')} 알려 주세요. (예: 2026.10.27)"
        else:
            text = f"'{name}'{_josa(name, '을', '를')} 알려 주세요."
            if spec.get("guide"):
                text += f" {spec['guide']}"
        if value:  # 시/도만 미리 채운 주소
            text += f" (지금은 '{value}'만 있어요.)"
    if can_skip and not confirm:
        text += " 해당 없으면 '해당 없음'이라고 해 주세요."
    return Question(
        field_id=row.field_id, label=spec["label"], text=text, input_type=spec.get("input_type", "text"),
        options=options, current_value=value or None, awaiting_confirm=confirm, can_skip=can_skip,
    )


def _normalize(spec: dict, answer: str, today: date) -> tuple[str | None, str | None]:
    """(칸에 넣을 값, 다시 물을 때 안내). 값이 None 이면 다시 묻는다"""
    flat = _flat(answer)
    options = spec.get("options")
    if (spec.get("box") or {}).get("how") == "confirm":
        if flat in CONFIRM_WORDS or "확인" in flat or "읽었" in flat:
            return options[0] if options else "확인", None
        return None, "유의사항을 읽고 '확인'이라고 해 주세요. 궁금한 항목이 있으면 물어봐도 돼요."
    if options:
        chosen = [o for o in options if _flat(o) in flat]
        # 긴 선택지가 짧은 선택지를 품으면 긴 것만 (예: "기타" vs "기타사유")
        chosen = [o for o in chosen if not any(o != c and _flat(o) in _flat(c) for c in chosen)]
        if not chosen:
            return None, f"선택지 중에서 골라 주세요: {', '.join(options)}"
        return ", ".join(chosen), None
    kind = spec.get("input_type")
    if kind == "date":
        if "오늘" in flat:
            return today.strftime("%Y.%m.%d"), None
        nums = _DIGITS_RE.findall(answer)
        if len(nums) == 1 and len(nums[0]) == 8:
            nums = [nums[0][:4], nums[0][4:6], nums[0][6:]]
        if len(nums) >= 3 and len(nums[0]) == 4:
            y, m, d = int(nums[0]), int(nums[1]), int(nums[2])
            try:
                return date(y, m, d).strftime("%Y.%m.%d"), None
            except ValueError:
                pass
        return None, "날짜를 다시 알려 주세요. (예: 2026.10.27)"
    if kind == "account":
        digits = "".join(_DIGITS_RE.findall(answer))
        if 10 <= len(digits) <= 16:
            kept = re.sub(r"[^\d-]", "", answer).strip("-")
            return (kept if "".join(_DIGITS_RE.findall(kept)) == digits else digits), None
        return None, "계좌번호를 다시 알려 주세요. 숫자 10~16자리예요. (예: 123-456-789012)"
    if kind == "phone":
        digits = "".join(_DIGITS_RE.findall(answer))
        if 9 <= len(digits) <= 11 and digits.startswith("0"):
            from app.doc_spec import format_phone

            return format_phone(digits) if len(digits) >= 10 else f"{digits[:2]}-{digits[2:5]}-{digits[5:]}", None
        return None, "전화번호를 다시 알려 주세요. (예: 010-1234-5678)"
    return answer.strip(), None


def _brief(text: str, limit: int = BRIEF_CHARS) -> str:
    """유의사항 항목 앞부분 (첫 문장, 길면 줄임)"""
    text = " ".join(text.split())
    first = re.split(r"(?<=[다요]\.)\s", text, maxsplit=1)[0]
    return first if len(first) <= limit else first[:limit].rstrip() + "…"


def _handwrite_note(specs: dict) -> str:
    """내보낸 뒤 손으로 써야 할 것 (주민등록번호 등 앱에 받지 않는 칸, 서명)"""
    labels = []
    for spec in specs.values():
        if spec.get("value_source") == "handwrite":
            name = " ".join(spec["label"].replace(":", "").split())
            if name not in labels:
                labels.append(name)
    # 서명란 이름 칸 ("신청인 성명: ____ (서명 또는 인)", formguide/layout.py _signature)
    signs = any(spec.get("profile_key") == "name" and "신청인" in spec["label"] and "성명" in _flat(spec["label"])
                for spec in specs.values())
    parts = []
    if labels:
        parts.append(f"출력한 뒤 {', '.join(labels)} 칸은 직접 써 주세요.")
    if signs:
        parts.append("이름 옆 (서명 또는 인) 자리에 서명하거나 도장을 찍어 주세요.")
    return " ".join(parts)


def _is_question(text: str) -> bool:
    flat = _flat(text)
    return "?" in text or "？" in text or any(q in flat for q in QUESTION_WORDS)


def _talk_like(text: str) -> bool:
    """짧은 칸 답인데 대화 문장처럼 보이는지 (띄어쓰기가 있고 말끝이 대화체). 이름·주소·기관명은 보통 아니다"""
    t = text.strip().rstrip(".!~ ")
    return " " in t and t.endswith(_TALK_ENDINGS)


def _short_value(text: str) -> str:
    return _TRAILING_RE.sub("", text.strip()) or text.strip()


def _save(db: Session, doc: Document, state: dict) -> None:
    doc.interview = dict(state)
    w._touch(doc)
    db.commit()


def _complete(row: DocumentField, segments: list[dict] | None = None) -> None:
    if segments is not None:
        new = w._clean(segments)
        if new != row.segments:
            row.previous_segments = row.segments
            row.segments = new
            row.reused_from = None
    row.is_completed = True
    row.completed_at = func.now()
    row.updated_at = func.now()


def _essay_draft(
    db: Session, user: User, doc: Document, row: DocumentField, spec: dict, answer: str, revise: bool,
    today: date, doc_llm: DocLlm,
) -> None:
    """서술형 칸: 사용자가 말한 내용만으로 AI 가 문장을 쓴다 (revise 면 지금 초안을 그 말대로 고친다)"""
    stage, status = w._ctx(user, today)
    current = w._value(row.segments)
    prompt = answer if revise else f"이 칸에 들어갈 문장을 써 줘. 내가 넣고 싶은 내용: {answer}"
    req = {
        "document_title": doc.title,
        "field": {k: spec[k] for k in ("label", "guide", "caution", "outline", "example", "essay", "multiline")},
        "current_text": current if revise else "",
        "prompt": prompt,
        "stage": stage,
        "status": status,
        "context": doc.context,
        "other_fields": w._other_fields(db, doc, row.field_id),
        "max_chars": w.ASSIST_MAX_CHARS,
        "force_action": "revise" if revise else "draft",
    }
    out = call_llm("assist_field", doc_llm.assist_field, w._check_assist, req)
    text = out["text"].strip()[: w.ASSIST_MAX_CHARS]
    if out["action"] == "tip":  # 고치지 못하고 안내만 온 경우 (예: 빈칸을 먼저 채워 달라) 초안은 그대로
        row.tip = text
        return
    row.previous_segments = row.segments
    row.segments = [{"text": text, "source": "ai"}]
    row.reused_from = None
    row.updated_at = func.now()


def _response(
    db: Session, user: User, doc: Document, state: dict, *, route: str = "field", message: str | None = None,
    field_row: DocumentField | None = None, search_result=None, contacts=None,
) -> InterviewResponse:
    db.refresh(doc)
    rows = w._rows(db, doc)
    specs = w._specs(doc)
    progress, _ = w._progress(rows, specs)
    todo = _todo(rows, specs, state["skipped"])
    field = None
    if field_row is not None:
        db.refresh(field_row)
        field = w._to_field(field_row, specs[field_row.field_id])

    question, export, check = None, None, None
    if todo:
        current = state["current"] if state["current"] in todo else todo[0]
        if state["current"] != current:
            state["current"], state["pending"] = current, None
            _save(db, doc, state)
        row = next(r for r in rows if r.field_id == current)
        confirm = state["confirm_text"] if (state["confirm_text"] or {}).get("field") == current else None
        question = _question(row, specs[current], state["pending"] == current, confirm and confirm["text"])
    elif route == "field":
        result = w.check(db, user, doc)
        if result.ready:
            export = w.export(db, user, doc)
            done = f"{EXPORTED_MESSAGE} {_handwrite_note(specs)}".strip()
            message = f"{message} {done}".strip() if message else done
        else:
            check = result
            message = f"{message} {NOT_READY_MESSAGE}".strip() if message else NOT_READY_MESSAGE
    return InterviewResponse(
        route=route, message=message, field=field, question=question, progress=progress, done=not todo,
        export=export, check=check, search=search_result, contacts=contacts or [],
    )


def interview(
    db: Session, user: User, doc: Document, body: InterviewRequest, today: date, doc_llm: DocLlm, rag: SearchPolicies,
) -> InterviewResponse:
    state = _state(doc)
    if body.message is None:
        return _response(db, user, doc, state)

    msg = body.message
    if is_crisis(msg):
        return _response(db, user, doc, state, route="crisis", message=CRISIS_MESSAGE,
                         contacts=[Contact(**c) for c in CRISIS_CONTACTS])

    rows = w._rows(db, doc)
    specs = w._specs(doc)
    todo = _todo(rows, specs, state["skipped"])
    if not todo:
        return _response(db, user, doc, state)
    current = state["current"] if state["current"] in todo else todo[0]
    row = next(r for r in rows if r.field_id == current)
    spec = specs[current]
    state["current"] = current

    if _is_question(msg) and spec.get("input_type") != "account":  # 계좌번호 답은 LLM 에 보내지 않는다
        context = {"screen": "document", "document_title": doc.title, "field_label": spec["label"]}
        routed = call_llm("route_message", doc_llm.route_message, w._check_route, msg, [], context)
        if routed["route"] == "policy":
            return _response(db, user, doc, state, route="policy", search_result=search(db, user, msg, today, rag))
        if routed["route"] == "counsel":
            return _response(db, user, doc, state, route="counsel", message=w.COUNSEL_MESSAGE)
        # 칸에 대한 질문 ("여기엔 뭘 써?") → 안내만 하고 같은 칸을 다시 묻는다
        tip = spec.get("guide") or "이 칸에 들어갈 내용을 알려 주세요."
        if spec.get("outline"):
            tip += f" ({' → '.join(spec['outline'])})"
        return _response(db, user, doc, state, message=tip)

    flat = _flat(msg)
    value = w._value(row.segments).strip()
    pending = state["pending"] == current

    # "이렇게 적을까요?" 확인 중: "네"면 그 답을 적고, 아니면 이번 말을 새 답으로 본다
    asked = state["confirm_text"] if (state["confirm_text"] or {}).get("field") == current else None
    state["confirm_text"] = None
    if asked is not None and flat in CONFIRM_WORDS:
        if current in state["skipped"]:
            state["skipped"].remove(current)
        _complete(row, [{"text": asked["text"], "source": "user"}])
        state["pending"] = None
        _save(db, doc, state)
        return _response(db, user, doc, state, message="적었어요.", field_row=row)

    # 확인: AI 초안·미리 채운 값을 그대로
    if value and flat in CONFIRM_WORDS and (pending or spec.get("profile_key") != "region"):
        _complete(row)
        state["pending"] = None
        _save(db, doc, state)
        return _response(db, user, doc, state, message="확인했어요.", field_row=row)

    # 물어본 칸에 값이 없는데 "네"만 온 경우 → 값으로 넣지 않고 다시 묻는다
    if (not value or spec.get("profile_key") == "region") and not pending and flat in CONFIRM_WORDS and not (spec.get("box") or {}).get("how") == "confirm":
        name = spec.get("easy_label") or spec["label"]
        return _response(db, user, doc, state, message=f"'{name}'에 쓸 내용을 알려 주세요.")

    # 해당 없음 (필수가 아닌 칸) / 나중에 (필수 칸)
    if flat in NOT_APPLICABLE_WORDS or any(flat.startswith(x) for x in LATER_WORDS):
        if spec.get("required") is not True and flat in NOT_APPLICABLE_WORDS:
            _complete(row, [])
            state["pending"] = None
            _save(db, doc, state)
            return _response(db, user, doc, state, message="비워 둘게요.", field_row=row)
        if current not in state["skipped"]:
            state["skipped"].append(current)
        state["current"], state["pending"] = None, None
        _save(db, doc, state)
        return _response(db, user, doc, state, message="나중에 다시 물어볼게요.")

    # 서술형: AI 가 문장으로 쓰고 확인을 기다린다
    if spec.get("essay"):
        _essay_draft(db, user, doc, row, spec, msg, pending, today, doc_llm)
        state["pending"] = current
        _save(db, doc, state)
        return _response(db, user, doc, state, field_row=row)

    # 짧은 칸: 규칙으로 정리해 바로 완료
    normalized, retry = _normalize(spec, msg, today)
    if normalized is None:
        _save(db, doc, state)
        return _response(db, user, doc, state, message=retry)
    free_text = not spec.get("options") and spec.get("input_type") not in ("date", "phone", "account")
    if free_text:
        normalized = _short_value(normalized)
        if _talk_like(msg):
            # 대화 문장 같은 답 (예: "오늘 날씨 좋다"): 바로 적지 않고 확인 (LLM 에 보내지 않음)
            state["confirm_text"] = {"field": current, "text": normalized}
            _save(db, doc, state)
            return _response(db, user, doc, state)
    if current in state["skipped"]:
        state["skipped"].remove(current)
    _complete(row, [{"text": normalized, "source": "user"}])
    state["pending"] = None
    _save(db, doc, state)
    return _response(db, user, doc, state, message="적었어요.", field_row=row)
