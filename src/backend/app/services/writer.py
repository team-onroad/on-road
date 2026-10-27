"""문서 AI: 항목별 공동 작성 (api.md 3.25~3.31).

- 칸마다 segments([{text, source}])로 저장한다. source=ai 구간은 앱이 AI 초안 색으로 보여 준다.
- 완료한 칸은 잠긴다 (AI 도우미·수정 불가, is_completed=false 로 다시 열 수 있음).
- 체크리스트는 대화 해석 없이, 모든 칸 완료 + 내보내기 때 연결된 서류 항목을 체크한다.
- 프로필 값·민감 칸 값은 LLM 에 보내지 않는다 (api.md 1.6).
"""

import re
from datetime import date
from pathlib import Path

from db.models import Checklist, ChecklistItem, Document, DocumentField, Policy, User
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import doc_export, doc_spec
from app.config import get_settings
from app.crisis import CRISIS_CONTACTS, CRISIS_MESSAGE, is_crisis
from app.doc_llm import DocLlm
from app.errors import AppError
from app.ids import parse_id
from app.llm_client import call_llm
from app.rag_client import SearchPolicies
from app.rules import calc_age, calc_stage
from app.schemas.chat import Contact, Handoff, HandoffCandidate
from app.schemas.checklists import ItemToggle
from app.schemas.writer import (
    AssistRequest,
    AssistResponse,
    ChecklistUpdate,
    CheckResponse,
    DocField,
    DocProgress,
    DocumentCreate,
    DocumentList,
    DocumentResponse,
    DocumentSummary,
    ExportResponse,
    FieldResponse,
    FieldUpdate,
    Problem,
    Segment,
)
from app.services import checklists as checklist_service
from app.services.policies import VISIBLE, get_visible_policy, search

ASSIST_MAX_CHARS = 1000
OTHER_FIELDS_FOR_AI = 3
NOT_CHECKED_NOTICE = "아직 사람이 원본 양식과 대조하지 않은 칸 목록이에요. 공식 양식과 함께 확인해 주세요."
AI_NOTICE = "AI가 쓴 문장은 색으로 표시돼요. 내 상황에 맞게 고친 뒤 완료를 눌러 주세요."
HANDWRITE_NOTICE = "주민등록번호·계좌·서명 칸은 앱에 입력하지 않아요. 출력한 양식에 직접 써 주세요."
COUNSEL_MESSAGE = "이 화면에서는 서류 작성을 도와요. 고민 이야기는 AI 상담에서 편하게 해 주세요."
HANDOFF_MESSAGE = "서류 작성은 문서 도우미에서 칸마다 함께 써 볼게요."

_PLACEHOLDER_RE = re.compile(r"\[[^\[\]\n]{1,30}\]")
_DATE_RE = re.compile(r"^\d{4}[.\-/ ]\s?\d{1,2}[.\-/ ]\s?\d{1,2}\.?$|^\d{4}년\s?\d{1,2}월\s?\d{1,2}일$|^\d{8}$")
_PHONE_RE = re.compile(r"^0\d{1,2}-?\d{3,4}-?\d{4}$")


def document_not_found() -> AppError:
    return AppError(404, "DOCUMENT_NOT_FOUND", "작성 중인 서류를 찾을 수 없습니다.")


def field_not_found() -> AppError:
    return AppError(404, "FIELD_NOT_FOUND", "서류 칸을 찾을 수 없습니다.")


# ---------------- 조회 공통 ----------------


def get_owned(db: Session, user: User, document_id: str) -> Document:
    """없는 ID, 형식 오류, 다른 사용자 것 → 404 DOCUMENT_NOT_FOUND"""
    did = parse_id(document_id)
    doc = None
    if did is not None:
        doc = db.scalars(select(Document).where(Document.id == did, Document.user_id == user.id)).one_or_none()
    if doc is None:
        raise document_not_found()
    return doc


def _rows(db: Session, doc: Document) -> list[DocumentField]:
    return list(db.scalars(
        select(DocumentField).where(DocumentField.document_id == doc.id).order_by(DocumentField.sort_order)
    ))


def _specs(doc: Document) -> dict[str, dict]:
    return {f["field_id"]: f for f in doc.spec}


def _get_row(db: Session, doc: Document, field_id: str) -> tuple[DocumentField, dict]:
    spec = _specs(doc).get(field_id)
    row = db.scalars(
        select(DocumentField).where(DocumentField.document_id == doc.id, DocumentField.field_id == field_id)
    ).one_or_none()
    if spec is None or row is None:
        raise field_not_found()
    return row, spec


def _value(segments: list[dict]) -> str:
    return "".join(s["text"] for s in segments)


def _clean(segments: list[dict]) -> list[dict]:
    """빈 구간 버리고, 이웃한 같은 출처 구간은 합친다"""
    out: list[dict] = []
    for s in segments:
        if not s["text"]:
            continue
        if out and out[-1]["source"] == s["source"]:
            out[-1] = {"text": out[-1]["text"] + s["text"], "source": s["source"]}
        else:
            out.append({"text": s["text"], "source": s["source"]})
    return out


def _writable(spec: dict) -> bool:
    return spec["value_source"] in doc_spec.WRITABLE


def _field_warnings(spec: dict, value: str) -> list[str]:
    if not _writable(spec):
        return []
    warnings = [f"{p} 빈칸을 채워 주세요." for p in dict.fromkeys(_PLACEHOLDER_RE.findall(value))]
    text = value.strip()
    if text and spec["input_type"] == "date" and not _DATE_RE.match(text):
        warnings.append("날짜 형식을 확인해 주세요 (예: 2007.03.15).")
    if text and spec["input_type"] == "phone" and not _PHONE_RE.match(text):
        warnings.append("전화번호 형식을 확인해 주세요 (예: 010-1234-5678).")
    options = spec.get("options")
    if text and options and any(p.strip() not in options for p in text.split(",")):
        warnings.append(f"선택지 중에서 골라 주세요: {', '.join(options)}")
    return warnings


def _to_field(row: DocumentField, spec: dict) -> DocField:
    value = _value(row.segments)
    return DocField(
        **{k: spec[k] for k in (
            "field_id", "label", "easy_label", "section", "guide", "caution", "example", "outline",
            "multiline", "essay", "required", "input_type", "value_source",
        )},
        options=spec.get("options"),
        writable=_writable(spec),
        value=value,
        segments=[Segment(**s) for s in row.segments],
        tip=row.tip,
        reused_from=row.reused_from,
        is_completed=row.is_completed,
        completed_at=row.completed_at,
        can_undo=row.previous_segments is not None,
        warnings=_field_warnings(spec, value),
    )


def _progress(rows: list[DocumentField], specs: dict[str, dict]) -> tuple[DocProgress, str | None]:
    writable = [r for r in rows if _writable(specs[r.field_id])]
    done = sum(1 for r in writable if r.is_completed)
    nxt = next((r.field_id for r in writable if not r.is_completed), None)
    return DocProgress(completed=done, total=len(writable)), nxt


def _notices(doc: Document) -> list[str]:
    notices = [AI_NOTICE]
    if doc.source_type == "policy_form" and not doc_spec_checked(doc):
        notices.insert(0, NOT_CHECKED_NOTICE)
    if any(f["value_source"] == "handwrite" for f in doc.spec):
        notices.append(HANDWRITE_NOTICE)
    return notices


def doc_spec_checked(doc: Document) -> bool:
    return bool(doc.spec) and all(f.get("checked") for f in doc.spec)


def to_response(db: Session, doc: Document) -> DocumentResponse:
    rows = _rows(db, doc)
    specs = _specs(doc)
    progress, nxt = _progress(rows, specs)
    return DocumentResponse(
        document_id=doc.id,
        title=doc.title,
        source_type=doc.source_type,
        source_id=doc.source_id,
        policy_id=doc.policy_id,
        checklist_item_id=doc.checklist_item_id,
        status=doc.status,
        context=doc.context,
        progress=progress,
        next_field_id=nxt,
        fields=[_to_field(r, specs[r.field_id]) for r in rows],
        notices=_notices(doc),
        exported_at=doc.exported_at,
        created_at=doc.created_at,
        updated_at=doc.updated_at,
    )


def list_documents(db: Session, user: User) -> DocumentList:
    docs = db.scalars(
        select(Document).where(Document.user_id == user.id).order_by(Document.updated_at.desc(), Document.id.desc())
    )
    out = []
    for d in docs:
        progress, _ = _progress(_rows(db, d), _specs(d))
        out.append(DocumentSummary(
            document_id=d.id, title=d.title, source_type=d.source_type, source_id=d.source_id,
            policy_id=d.policy_id, status=d.status, progress=progress, updated_at=d.updated_at,
        ))
    return DocumentList(documents=out)


# ---------------- 만들기 ----------------


def _resolve_source(db: Session, body: DocumentCreate) -> tuple[str, str, list[dict], int | None]:
    """(source_id, 제목, 칸 목록, policy_id)"""
    if body.source_type == "template":
        try:
            title, fields = doc_spec.template_spec(body.source_id)
        except KeyError:
            raise AppError(404, "FORM_NOT_FOUND", "서류 양식을 찾을 수 없습니다.") from None
        policy_id = get_visible_policy(db, str(body.policy_id)).id if body.policy_id is not None else None
        return body.source_id, title, fields, policy_id
    policy = get_visible_policy(db, str(body.policy_id))
    found = doc_spec.policy_form_spec(policy.policy_key)
    if found is None:
        raise AppError(404, "FORM_GUIDE_NOT_FOUND", "이 정책은 아직 신청서 작성 도우미가 준비되지 않았습니다.")
    form_id, title, fields = found
    return form_id, title, fields, policy.id


def _checklist_item(db: Session, user: User, item_id: int | None) -> ChecklistItem | None:
    """사용자 체크리스트의 서류 항목만 연결할 수 있다 (아니면 404 ITEM_NOT_FOUND)"""
    if item_id is None:
        return None
    item = db.scalars(
        select(ChecklistItem)
        .join(Checklist, Checklist.id == ChecklistItem.checklist_id)
        .where(ChecklistItem.id == item_id, Checklist.user_id == user.id, ChecklistItem.item_type == "document")
    ).one_or_none()
    if item is None:
        raise checklist_service.item_not_found()
    return item


def _reusable(db: Session, user: User, exclude_id: int | None) -> dict[str, tuple[str, list[dict]]]:
    """다른 서류에서 완료한 칸: label_key → (서류 제목, segments). 최근 서류 우선"""
    q = (
        select(Document, DocumentField)
        .join(DocumentField, DocumentField.document_id == Document.id)
        .where(Document.user_id == user.id, DocumentField.is_completed.is_(True))
        .order_by(Document.updated_at.desc(), Document.id.desc())
    )
    if exclude_id is not None:
        q = q.where(Document.id != exclude_id)
    out: dict[str, tuple[str, list[dict]]] = {}
    for doc, row in db.execute(q).all():
        spec = _specs(doc).get(row.field_id)
        if spec is None or not _writable(spec) or not _value(row.segments).strip():
            continue
        out.setdefault(doc_spec.label_key(spec["label"]), (doc.title, row.segments))
    return out


def _initial(spec: dict, user: User, reusable: dict) -> tuple[list[dict], str | None]:
    if not _writable(spec):
        return [], None
    if spec["value_source"] == "profile":
        v = doc_spec.profile_value(spec["profile_key"], user.name, user.birth_date, user.region, user.phone)
        if v:
            return [{"text": v, "source": "profile"}], None
    hit = reusable.get(doc_spec.label_key(spec["label"]))
    if hit:
        title, segments = hit
        return [{"text": _value(segments), "source": "reused"}], title
    return [], None


def create(db: Session, user: User, body: DocumentCreate) -> tuple[Document, bool]:
    """같은 양식은 사용자마다 하나 (get-or-create). 기존 문서면 체크리스트 항목·context 만 새 값으로 바꾼다"""
    source_id, title, fields, policy_id = _resolve_source(db, body)
    item = _checklist_item(db, user, body.checklist_item_id)
    existing = db.scalars(select(Document).where(
        Document.user_id == user.id, Document.source_type == body.source_type, Document.source_id == source_id,
    )).one_or_none()
    if existing is not None:
        changed = False
        if item is not None and existing.checklist_item_id != item.id:
            existing.checklist_item_id = item.id
            changed = True
        if body.context and existing.context != body.context:
            existing.context = body.context
            changed = True
        if changed:
            db.commit()
            db.refresh(existing)
        return existing, False

    doc = Document(
        user_id=user.id, source_type=body.source_type, source_id=source_id, title=title, policy_id=policy_id,
        checklist_item_id=item.id if item else None, context=body.context, spec=fields,
    )
    db.add(doc)
    db.flush()
    reusable = _reusable(db, user, doc.id)
    for i, spec in enumerate(fields):
        segments, reused_from = _initial(spec, user, reusable)
        db.add(DocumentField(
            document_id=doc.id, field_id=spec["field_id"], sort_order=i, segments=segments, reused_from=reused_from,
        ))
    db.commit()
    db.refresh(doc)
    return doc, True


# ---------------- 칸 수정·완료·되돌리기 ----------------


def _touch(doc: Document) -> None:
    doc.updated_at = func.now()


def _field_response(db: Session, doc: Document, row: DocumentField, spec: dict) -> FieldResponse:
    db.refresh(row)
    db.refresh(doc)
    progress, nxt = _progress(_rows(db, doc), _specs(doc))
    return FieldResponse(field=_to_field(row, spec), progress=progress, next_field_id=nxt, status=doc.status)


def field_completed() -> AppError:
    return AppError(409, "FIELD_COMPLETED", "완료한 칸이에요. 고치려면 먼저 다시 열어 주세요.")


def field_not_writable() -> AppError:
    return AppError(422, "FIELD_NOT_WRITABLE", "이 칸은 앱에서 쓰지 않아요. 양식에 직접 작성해 주세요.")


def update_field(db: Session, doc: Document, field_id: str, body: FieldUpdate) -> FieldResponse:
    row, spec = _get_row(db, doc, field_id)
    if not _writable(spec):
        raise field_not_writable()
    sent = body.model_fields_set
    writes = bool({"value", "segments"} & sent)
    reopen = "is_completed" in sent and body.is_completed is False
    if row.is_completed and writes and not reopen:
        raise field_completed()

    if reopen and row.is_completed:
        row.is_completed = False
        row.completed_at = None
        doc.status = "writing"
    if writes:
        new = [{"text": body.value, "source": "user"}] if "value" in sent else [s.model_dump() for s in body.segments]
        new = _clean(new)
        if new != row.segments:
            row.previous_segments = row.segments
            row.segments = new
            row.reused_from = row.reused_from if all(s["source"] == "reused" for s in new) and new else None
    if body.is_completed is True and not row.is_completed:
        row.is_completed = True
        row.completed_at = func.now()
    row.updated_at = func.now()
    _touch(doc)
    db.commit()
    return _field_response(db, doc, row, spec)


def undo(db: Session, doc: Document, field_id: str) -> FieldResponse:
    row, spec = _get_row(db, doc, field_id)
    if not _writable(spec):
        raise field_not_writable()
    if row.is_completed:
        raise field_completed()
    if row.previous_segments is None:
        raise AppError(409, "NOTHING_TO_UNDO", "되돌릴 내용이 없어요.")
    row.segments, row.previous_segments = row.previous_segments, None
    row.updated_at = func.now()
    _touch(doc)
    db.commit()
    return _field_response(db, doc, row, spec)


# ---------------- AI 도우미 (라우터 포함) ----------------


def _ctx(user: User, today: date) -> tuple[str, str]:
    return calc_stage(calc_age(user.birth_date, today), user.status), user.status


def _other_fields(db: Session, doc: Document, field_id: str) -> list[dict]:
    """같은 서류의 다른 서술형 칸만 (프로필·민감·짧은 칸 값은 보내지 않음)"""
    specs = _specs(doc)
    out = []
    for r in _rows(db, doc):
        spec = specs[r.field_id]
        if r.field_id == field_id or spec["value_source"] != "user" or not spec["essay"]:
            continue
        text = _value(r.segments).strip()
        if text:
            out.append({"label": spec["label"], "text": text})
    return out[:OTHER_FIELDS_FOR_AI]


def _check_route(v: object) -> bool:
    return isinstance(v, dict) and v.get("route") in ("field", "policy", "counsel")


def _check_assist(v: object) -> bool:
    return (
        isinstance(v, dict) and v.get("action") in ("tip", "outline", "draft", "revise")
        and isinstance(v.get("text"), str) and bool(v["text"].strip())
    )


def assist(
    db: Session, user: User, doc: Document, body: AssistRequest, today: date, doc_llm: DocLlm, rag: SearchPolicies,
) -> AssistResponse:
    row, spec = _get_row(db, doc, body.field_id)
    if not _writable(spec):
        raise field_not_writable()
    if row.is_completed:
        raise field_completed()

    if is_crisis(body.prompt):
        return AssistResponse(
            route="crisis", action=None, field=None, progress=None, message=CRISIS_MESSAGE, search=None,
            contacts=[Contact(**c) for c in CRISIS_CONTACTS],
        )

    context = {"screen": "document", "document_title": doc.title, "field_label": spec["label"]}
    routed = call_llm("route_message", doc_llm.route_message, _check_route, body.prompt, [], context)
    if routed["route"] == "policy":
        return AssistResponse(
            route="policy", action=None, field=None, progress=None, message=None,
            search=search(db, user, body.prompt, today, rag), contacts=[],
        )
    if routed["route"] == "counsel":
        return AssistResponse(
            route="counsel", action=None, field=None, progress=None, message=COUNSEL_MESSAGE, search=None, contacts=[],
        )

    stage, status = _ctx(user, today)
    current = _value(row.segments)
    req = {
        "document_title": doc.title,
        "field": {k: spec[k] for k in ("label", "guide", "caution", "outline", "example", "essay", "multiline")},
        # 프로필로 채운 값은 보내지 않는다 (이름·생년월일·연락처)
        "current_text": "" if all(s["source"] == "profile" for s in row.segments) else current,
        "prompt": body.prompt,
        "stage": stage,
        "status": status,
        "context": doc.context,
        "other_fields": _other_fields(db, doc, body.field_id),
        "max_chars": ASSIST_MAX_CHARS,
    }
    out = call_llm("assist_field", doc_llm.assist_field, _check_assist, req)
    action, text = out["action"], out["text"].strip()[:ASSIST_MAX_CHARS]
    if action == "revise" and not current.strip():
        action = "draft"

    if action == "tip":
        row.tip = text
    else:
        if action == "revise" or body.mode == "replace" or not current.strip():
            new = [{"text": text, "source": "ai"}]
        else:
            sep = "" if current.endswith(("\n", " ")) else ("\n" if spec["multiline"] or spec["essay"] else " ")
            new = row.segments + [{"text": sep + text, "source": "ai"}]
        new = _clean(new)
        if sum(len(s["text"]) for s in new) > 3000:
            raise AppError(422, "VALUE_TOO_LONG", "칸 내용이 너무 길어요. 덮어쓰기로 다시 요청해 주세요.")
        row.previous_segments = row.segments
        row.segments = new
        row.reused_from = None
    row.updated_at = func.now()
    _touch(doc)
    db.commit()
    resp = _field_response(db, doc, row, spec)
    return AssistResponse(
        route="field", action=action, field=resp.field, progress=resp.progress, message=None, search=None, contacts=[],
    )


# ---------------- 검사·내보내기 ----------------


def check(db: Session, user: User, doc: Document) -> CheckResponse:
    specs = _specs(doc)
    problems: list[Problem] = []
    for r in _rows(db, doc):
        spec = specs[r.field_id]
        if not _writable(spec):
            continue
        value = _value(r.segments)
        label = spec["label"]
        if not r.is_completed:
            problems.append(Problem(field_id=r.field_id, label=label, reason="not_completed",
                                    message=f"'{label}' 칸을 완료하지 않았어요."))
            continue
        if spec["required"] and not value.strip():
            problems.append(Problem(field_id=r.field_id, label=label, reason="required_empty",
                                    message=f"'{label}' 칸이 비어 있어요."))
            continue
        for w in _field_warnings(spec, value):
            reason = "placeholder_left" if w.endswith("빈칸을 채워 주세요.") else "invalid_format"  # 형식·선택지
            problems.append(Problem(field_id=r.field_id, label=label, reason=reason, message=f"'{label}': {w}"))
    return CheckResponse(ready=not problems, problems=problems, warnings=_consistency(db, user, doc))


def _consistency(db: Session, user: User, doc: Document) -> list[str]:
    """다른 서류에서 완료한 같은 칸과 값이 다르면 알려 준다 (내보내기는 막지 않음)"""
    mine = {}
    specs = _specs(doc)
    for r in _rows(db, doc):
        spec = specs[r.field_id]
        if _writable(spec) and _value(r.segments).strip():
            mine[doc_spec.label_key(spec["label"])] = (spec["label"], " ".join(_value(r.segments).split()))
    warnings = []
    for key, (title, segments) in _reusable(db, user, doc.id).items():
        if key in mine and mine[key][1] != " ".join(_value(segments).split()):
            warnings.append(f"다른 서류 「{title}」의 '{mine[key][0]}'와 내용이 달라요. 맞는지 확인해 주세요.")
    return warnings


def export(db: Session, user: User, doc: Document) -> ExportResponse:
    result = check(db, user, doc)
    if not result.ready:
        raise AppError(
            422, "EXPORT_NOT_READY", f"완료하지 않았거나 고칠 칸이 {len(result.problems)}개 있어요. 검사 결과를 확인해 주세요.",
        )
    if doc.status != "exported":
        doc.status = "exported"
        doc.exported_at = func.now()
        _touch(doc)

    checklist = None
    if doc.checklist_item_id is not None:
        item = db.get(ChecklistItem, doc.checklist_item_id)
        cl = db.get(Checklist, item.checklist_id) if item is not None else None
        if item is not None and cl is not None and cl.user_id == user.id:
            if checklist_service._apply(item, ItemToggle(is_checked=True)):
                cl.updated_at = func.now()
            checklist = ChecklistUpdate(checklist_id=cl.id, item_id=item.id, label=item.label, is_checked=True)
    db.commit()
    db.refresh(doc)
    base = f"/api/documents/{doc.id}/file"
    return ExportResponse(
        document=to_response(db, doc),
        checklist=checklist,
        warnings=result.warnings,
        files={"pdf": f"{base}?format=pdf", "docx": f"{base}?format=docx"},
        pdf_layout=pdf_layout(doc),
    )


def original_pdf(doc: Document) -> Path | None:
    """정책 신청서의 원본 양식 PDF (formguide 가 data/forms/{form_id}.pdf 로 저장). 앱 양식은 None"""
    if doc.source_type != "policy_form":
        return None
    path = get_settings().forms_dir / f"{doc.source_id}.pdf"
    return path if path.is_file() else None


def pdf_layout(doc: Document) -> str:
    """"original": 원본 양식 위에 채움 / "table": 칸 이름·내용 표"""
    rows = [(f, "") for f in doc.spec]
    return "original" if doc_export.has_layout(original_pdf(doc), rows) else "table"


def file_rows(db: Session, doc: Document) -> list[tuple[dict, str]]:
    if doc.status != "exported":
        raise AppError(409, "NOT_EXPORTED", "먼저 검사를 마치고 내보내기를 해 주세요.")
    specs = _specs(doc)
    return [(specs[r.field_id], _value(r.segments)) for r in _rows(db, doc)]


# ---------------- 상담 AI → 문서 AI 핸드오프 ----------------


# 정책 이름에 흔히 붙어 혼자로는 정책을 가리키지 못하는 낱말
_GENERIC_WORDS = {"자립준비청년", "보호종료아동", "청년", "지원", "지급", "사업", "신청", "제도", "정책", "서비스", "프로그램"}


def _policy_mentioned(name: str, flat_message: str) -> bool:
    """정책 이름 전체, 또는 흔한 낱말을 뺀 핵심 낱말(예: "자립준비청년 자립수당 지급" → "자립수당")이 메시지에 있으면"""
    if name.replace(" ", "") in flat_message:
        return True
    words = [w for w in re.split(r"[\s()·,/]+", name) if len(w) >= 2 and w not in _GENERIC_WORDS]
    return any(w in flat_message for w in words)


def handoff_for(db: Session, user: User, message: str, hint: str | None, today: date) -> Handoff:
    text = f"{message} {hint or ''}"
    flat = text.replace(" ", "")
    stage = calc_stage(calc_age(user.birth_date, today), user.status)
    candidates: list[HandoffCandidate] = []
    for p in db.scalars(select(Policy).where(VISIBLE).order_by(Policy.id)):
        if _policy_mentioned(p.name, flat) and doc_spec.policy_form_spec(p.policy_key) is not None:
            candidates.append(HandoffCandidate(source_type="policy_form", source_id=None, policy_id=p.id, title=p.name))
    templates = doc_spec.template_candidates(text, stage)
    if not candidates and not templates:
        templates = doc_spec.stage_templates(stage)
    candidates += [
        HandoffCandidate(source_type="template", source_id=tid, policy_id=None, title=name) for tid, name in templates
    ]
    return Handoff(context=message[:500], candidates=candidates)
