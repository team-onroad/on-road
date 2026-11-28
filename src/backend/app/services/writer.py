"""문서 AI: 항목별 공동 작성 (api.md 3.25~3.31).

- 칸마다 segments([{text, source}])로 저장한다. source=ai 구간은 앱이 AI 초안 색으로 보여 준다.
- 완료한 칸은 잠긴다 (AI 도우미·수정 불가, is_completed=false 로 다시 열 수 있음).
- 체크리스트는 대화 해석 없이, 모든 칸 완료 + 내보내기 때 연결된 서류 항목을 체크한다.
- 프로필 값·민감 칸 값은 LLM 에 보내지 않는다 (api.md 1.6).
"""

import base64
import binascii
import re
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

from db.models import Checklist, ChecklistItem, Document, DocumentField, Policy, User
from sqlalchemy import and_, delete, func, or_, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import ai_content, doc_export, doc_spec
from app.config import get_settings
from app import profile_signal
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
    DocumentUpload,
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
UPLOAD_NOTICE = "받은 양식과 칸이 맞는지 확인해 주세요. 다 쓰면 받은 양식 모양 그대로 PDF로 만들어요."
UPLOAD_TABLE_NOTICE = (
    "이 양식은 칸 위치를 읽지 못해 칸 이름과 내용을 정리한 PDF로 만들어요. "
    "사진·스캔본 대신 PDF나 한글(hwpx) 파일로 올리면 받은 양식 모양 그대로 만들어요."
)
AI_NOTICE = "색으로 표시된 문장은 내 상황에 맞게 고친 뒤 완료를 눌러 주세요."
HANDWRITE_NOTICE = "주민등록번호·서명 칸은 앱에 입력하지 않아요. 출력한 양식에 직접 써 주세요."
ACCOUNT_TIP = "통장에 적힌 계좌번호를 숫자와 -로 적어 주세요. 본인 명의 통장만 돼요. (계좌번호는 AI 에 보내지 않아요)"
CLEARED_NOTICE = "오래된 서류라 계좌번호·연락처·주소 같은 개인정보 칸은 비웠어요. 다시 쓰려면 그 칸만 채워 주세요."
# 오래되면 비우는 개인정보 칸: 프로필에서 가져온 칸(이름·생년월일·주소·연락처) + 계좌 · 전화 · 주소 칸
PERSONAL_INPUT_TYPES = {"account", "phone", "address"}
COUNSEL_MESSAGE = "이 화면에서는 서류 작성과 신청 관련 질문을 도와요. 다른 이야기는 AI 상담에서 편하게 해 주세요."
HANDOFF_MESSAGE = "서류 작성은 문서 도우미에서 칸마다 함께 써 볼게요."

_PLACEHOLDER_RE = re.compile(r"\[[^\[\]\n]{1,30}\]")
_DATE_RE = re.compile(r"^\d{4}[.\-/ ]\s?\d{1,2}[.\-/ ]\s?\d{1,2}\.?$|^\d{4}년\s?\d{1,2}월\s?\d{1,2}일$|^\d{8}$")
_PHONE_RE = re.compile(r"^0\d{1,2}-?\d{3,4}-?\d{4}$")
_ACCOUNT_RE = re.compile(r"^\d[\d-]*\d$")
_NUMBER_RE = re.compile(r"^\d[\d,]*(\.\d+)?\s*(원|만원|명|세|살|㎡|평|개월|년)?$")


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


def delete_document(db: Session, doc: Document) -> None:
    """칸(document_fields)은 FK CASCADE 로 같이 삭제"""
    db.execute(delete(Document).where(Document.id == doc.id))
    db.commit()


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


def _date_parts(text: str) -> tuple[int, int, int] | None:
    digits = re.findall(r"\d+", text)
    if len(digits) == 1 and len(digits[0]) == 8:
        digits = [digits[0][:4], digits[0][4:6], digits[0][6:]]
    if len(digits) != 3:
        return None
    return int(digits[0]), int(digits[1]), int(digits[2])


def _date_problems(spec: dict, text: str, today: date | None = None) -> list[str]:
    """형식은 맞는데 없는 날짜(2007.02.30 · 13월)이거나, 생년월일이 오늘보다 뒤"""
    today = today or date.today()
    parts = _date_parts(text)
    if parts is None:
        return []
    try:
        day = date(*parts)
    except ValueError:
        return ["없는 날짜예요. 월은 1~12, 일은 그 달의 날 수 안에서 적어 주세요."]
    label = (spec.get("label") or "").replace(" ", "")
    if "생년월일" in label and day > today:
        return ["생년월일이 오늘보다 뒤예요. 연도를 확인해 주세요."]
    if day.year < 1900 or day.year > today.year + 30:
        return ["연도를 확인해 주세요."]
    return []


def _number_problems(spec: dict, text: str) -> list[str]:
    """나이 · 연도처럼 범위가 정해진 숫자 칸"""
    label = (spec.get("label") or "").replace(" ", "")
    n = float(re.sub(r"[^\d.]", "", text) or 0)
    if ("나이" in label or "연령" in label) and not 0 < n < 120:
        return ["나이를 확인해 주세요. 숫자로 적어요 (예: 20)."]
    if label.endswith("연도") and not 1900 <= n <= date.today().year + 30:
        return ["연도를 네 자리로 적어 주세요 (예: 2027)."]
    return []


def _field_warnings(spec: dict, value: str) -> list[str]:
    if not _writable(spec):
        return []
    warnings = [f"{p} 빈칸을 채워 주세요." for p in dict.fromkeys(_PLACEHOLDER_RE.findall(value))]
    text = value.strip()
    if text and spec["input_type"] == "date" and not _DATE_RE.match(text):
        warnings.append("날짜 형식을 확인해 주세요. 연.월.일 순서로 적어요 (예: 2007.03.15).")
    elif text and spec["input_type"] == "date":
        warnings += _date_problems(spec, text)
    if text and spec["input_type"] == "number" and _NUMBER_RE.match(text):
        warnings += _number_problems(spec, text)
    if text and spec["input_type"] == "phone" and not _PHONE_RE.match(text):
        warnings.append("전화번호 형식을 확인해 주세요. 0으로 시작하는 숫자 9~11자리예요 (예: 010-1234-5678).")
    if text and spec["input_type"] == "account":
        digits = re.sub(r"\D", "", text)
        if not _ACCOUNT_RE.match(text):
            warnings.append("계좌번호는 숫자와 - 만 적어 주세요 (예: 110-123-456789).")
        elif not 10 <= len(digits) <= 16:
            warnings.append(f"계좌번호 자리 수를 확인해 주세요. 지금 {len(digits)}자리예요 (보통 10~16자리).")
    if text and spec["input_type"] == "number" and not _NUMBER_RE.match(text):
        example = spec.get("example") or "500000"
        warnings.append(f"숫자로 적어 주세요. 쉼표는 써도 되고 글자는 빼요 (예: {example}).")
    options = spec.get("options")
    if text and options and any(p.strip() not in options for p in text.split(",")):
        warnings.append(f"선택지 중에서 골라 주세요: {', '.join(options)}")
    elif text and not options and spec["input_type"] not in ("date", "phone", "account", "checkbox", "number"):
        # 이름 · 주소 · 이메일 형식, 아무렇게나 친 글자, 너무 짧은 글 칸 (answer_extract.problem)
        from app import answer_extract

        reason = answer_extract.problem(spec, text)
        if reason:
            warnings.append(reason)
    return warnings


def confirm_items(spec: dict) -> list[str] | None:
    """유의사항 확인 칸의 항목 전문 (줄임 없이)"""
    box = spec.get("box") or {}
    if box.get("how") != "confirm" or not box.get("items"):
        return None
    return [" ".join(item.split()) for item in box["items"]]


def _to_field(row: DocumentField, spec: dict) -> DocField:
    value = _value(row.segments)
    return DocField(
        **{k: spec[k] for k in (
            "field_id", "label", "easy_label", "section", "guide", "caution", "example", "outline",
            "multiline", "essay", "required", "input_type", "value_source",
        )},
        options=spec.get("options"),
        items=confirm_items(spec),
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
    if doc.source_type == "policy_form" and (meta := ai_content.form_meta(form_id_of(doc))) and meta.get("substitute"):
        if meta.get("notice"):  # 공식 양식이 공개되지 않아 대신 쓰는 공통 양식 (자립정착금 신청서 → 사회보장급여 신청서)
            notices.insert(0, meta["notice"])
    if doc.source_type == "upload":
        notices.insert(0, UPLOAD_NOTICE if any(f.get("box") for f in doc.spec) else UPLOAD_TABLE_NOTICE)
    if any(f["value_source"] == "handwrite" for f in doc.spec):
        notices.append(HANDWRITE_NOTICE)
    if doc.personal_cleared_at is not None:
        notices.insert(0, CLEARED_NOTICE)
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


def _uncheck_linked_item(db: Session, doc: Document) -> None:
    if doc.checklist_item_id is None:
        return
    item = db.get(ChecklistItem, doc.checklist_item_id)
    cl = db.get(Checklist, item.checklist_id) if item is not None else None
    if item is not None and cl is not None and cl.user_id == doc.user_id and item.is_checked:
        checklist_service._apply(item, ItemToggle(is_checked=False))
        cl.updated_at = func.now()


def list_documents(db: Session, user: User) -> DocumentList:
    clear_stale(db, user)
    docs = db.scalars(
        select(Document).where(Document.user_id == user.id).order_by(Document.updated_at.desc(), Document.id.desc())
    )
    out = []
    for d in docs:
        progress, _ = _progress(_rows(db, d), _specs(d))
        out.append(DocumentSummary(
            document_id=d.id, title=d.title, source_type=d.source_type, source_id=d.source_id,
            policy_id=d.policy_id, checklist_item_id=d.checklist_item_id, status=d.status, progress=progress,
            updated_at=d.updated_at,
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
    if body.policy_id is None:
        # 공통 양식 (퇴소 신청서 · 보호기간 연장 신청서 등): 정책 없이 사용자마다 하나
        common = doc_spec.common_form_spec(body.form_id)
        if common is None:
            raise AppError(404, "FORM_NOT_FOUND", "서류 양식을 찾을 수 없습니다.")
        form_id, title, fields = common
        return f"{form_id}{FORM_SCOPE_SEP}u", title, fields, None
    policy = get_visible_policy(db, str(body.policy_id))
    found = doc_spec.policy_form_spec(policy.policy_key, body.form_id)
    if found is None and body.form_id is not None:
        raise AppError(404, "FORM_NOT_FOUND", "이 정책에 쓰는 서류 양식이 아닙니다.")
    if found is None:
        # 정책 자체 신청서가 없으면 연결표의 첫 양식 (정책 상세 '신청서 쓰기'를 form_id 없이 누른 예전 앱)
        linked = ai_content.policy_forms(policy.policy_key)
        if linked:
            return _resolve_source(db, body.model_copy(update={"form_id": linked[0]["form_id"]}))
        raise AppError(404, "FORM_GUIDE_NOT_FOUND", "이 정책은 아직 신청서 작성 도우미가 준비되지 않았습니다.")
    form_id, title, fields = found
    own = ai_content.find_form(policy.policy_key)
    if body.form_id is not None and (own is None or own.form_id != form_id):
        # 연결표 양식은 여러 정책 · 여러 서류(1차 · 2차 사용계획서)에 같이 쓰인다 → 서류 항목(없으면 정책)마다 따로 저장
        scope = f"i{body.checklist_item_id}" if body.checklist_item_id is not None else f"p{policy.id}"
        return f"{form_id}{FORM_SCOPE_SEP}{scope}", title, fields, policy.id
    return form_id, title, fields, policy.id


FORM_SCOPE_SEP = "@"


def form_id_of(doc: Document) -> str:
    """문서가 쓰는 서버 양식 ID (source_id 의 '@항목' 앞부분)"""
    return doc.source_id.split(FORM_SCOPE_SEP, 1)[0]


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
        key = doc_spec.reuse_key(spec) if spec is not None else None
        if key is None or not _writable(spec) or not _value(row.segments).strip():
            continue
        out.setdefault(key, (doc.title, row.segments))
    return out


def _initial(spec: dict, user: User, reusable: dict) -> tuple[list[dict], str | None]:
    if not _writable(spec):
        return [], None
    if spec["value_source"] == "profile":
        v = doc_spec.profile_value(spec["profile_key"], user.name, user.birth_date, user.region, user.phone)
        if v:
            return [{"text": v, "source": "profile"}], None
    key = doc_spec.reuse_key(spec)
    hit = None if spec.get("input_type") == "date" or key is None else reusable.get(key)  # 날짜는 서류마다 다르다
    if hit:
        title, segments = hit
        return [{"text": _value(segments), "source": "reused"}], title
    return [], None


def create(db: Session, user: User, body: DocumentCreate) -> tuple[Document, bool]:
    """같은 양식은 사용자마다 하나 (get-or-create). 기존 문서면 체크리스트 항목·context 만 새 값으로 바꾼다"""
    clear_stale(db, user)  # 오래된 서류의 개인정보를 새 서류로 가져오지 않게 먼저 비운다
    source_id, title, fields, policy_id = _resolve_source(db, body)
    item = _checklist_item(db, user, body.checklist_item_id)
    return _get_or_create(db, user, body.source_type, source_id, title, fields, policy_id, item, body.context)


def upload(db: Session, user: User, body: DocumentUpload, doc_llm) -> tuple[Document, bool]:
    """올린 양식(사진·PDF·한글) → 문서. 같은 양식을 다시 올리면 쓰던 문서를 연다 (LLM 을 다시 부르지 않음).
    글자가 있는 PDF · hwpx 는 원본 규격 바탕 PDF 와 값 자리를 함께 저장해 원본 양식 위에 채워 내보낸다"""
    from app import form_layouts, form_upload

    item = _checklist_item(db, user, body.checklist_item_id)
    policy_id = get_visible_policy(db, str(body.policy_id)).id if body.policy_id is not None else None
    try:
        data = base64.b64decode(body.file_base64, validate=True)
    except (binascii.Error, ValueError):
        raise form_upload.bad_file("파일을 읽지 못했어요. 다시 올려 주세요.") from None
    source = form_upload.read_source(data)
    source_id = form_upload.source_id(data, source[0])
    clear_stale(db, user)
    existing = _existing(db, user, "upload", source_id)
    if existing is not None:
        return _get_or_create(db, user, "upload", source_id, existing.title, existing.spec, policy_id, item, None)
    title, fields = form_upload.read_form(data, body.file_name, doc_llm, source)
    pdf = form_layouts.base_pdf(data, form_upload.kind_of(data))
    # 표가 있는 양식은 표 구조로 항목 칸 · 값 칸을 나눠 칸 목록을 다시 만든다. 표가 없으면 칸 이름으로 자리를 찾는다
    boxed = (form_layouts.from_structure(fields, pdf) or form_layouts.attach_boxes(fields, pdf)) if pdf else None
    form_pdf = base64.b64encode(pdf).decode() if boxed else None
    return _get_or_create(
        db, user, "upload", source_id, title, boxed or fields, policy_id, item, None, form_pdf=form_pdf,
    )


def _existing(db: Session, user: User, source_type: str, source_id: str) -> Document | None:
    return db.scalars(select(Document).where(
        Document.user_id == user.id, Document.source_type == source_type, Document.source_id == source_id,
    )).one_or_none()


def _get_or_create(
    db: Session, user: User, source_type: str, source_id: str, title: str, fields: list[dict],
    policy_id: int | None, item: ChecklistItem | None, context: str | None, *, retried: bool = False,
    form_pdf: str | None = None,
) -> tuple[Document, bool]:
    existing = _existing(db, user, source_type, source_id)
    if existing is not None:
        changed = sync_layout(db, existing)  # 양식 검수로 고친 칸 자리 · 새로 찾은 칸을 이미 만든 서류에도
        fill_from_others(db, user, existing)  # 다른 서류에서 그사이 쓴 내 정보로 빈 칸 채우기
        if item is not None and existing.checklist_item_id != item.id:
            existing.checklist_item_id = item.id
            changed = True
        if context and existing.context != context:
            existing.context = context
            changed = True
        if changed:
            db.commit()
            db.refresh(existing)
        return existing, False

    doc = Document(
        user_id=user.id, source_type=source_type, source_id=source_id, title=title, policy_id=policy_id,
        checklist_item_id=item.id if item else None, context=context, spec=fields, form_pdf=form_pdf,
    )
    try:
        with db.begin_nested():  # 이 INSERT 만 되돌릴 수 있게 (SAVEPOINT)
            db.add(doc)
            db.flush()
    except IntegrityError:
        # 같은 양식을 거의 동시에 두 번 열면 (두 번 누름 · 앱 재시도) 다른 요청이 먼저 만든다.
        # 예전에는 여기서 500 이 났다 → 먼저 만들어진 문서를 연다
        if retried:
            raise
        return _get_or_create(
            db, user, source_type, source_id, title, fields, policy_id, item, context, retried=True, form_pdf=form_pdf,
        )
    reusable = _reusable(db, user, doc.id)
    for i, spec in enumerate(fields):
        segments, reused_from = _initial(spec, user, reusable)
        db.add(DocumentField(
            document_id=doc.id, field_id=spec["field_id"], sort_order=i, segments=segments, reused_from=reused_from,
        ))
    db.commit()
    db.refresh(doc)
    return doc, True


LAYOUT_KEYS = ("box", "essay", "multiline")  # 양식 검수로 바뀌는 칸 모양 (칸 이름 · 안내 · 값은 그대로)


def sync_layout(db: Session, doc: Document, *, add_fields: bool = True) -> bool:
    """정책 신청서: 지금 양식 파일(data/forms)의 칸 자리(box) · 글 칸 여부를 서류에 맞춘다.
    서류를 만들 때 칸 목록을 복사해 두므로, 양식 검수로 고친 자리가 이미 만든 서류에는 안 들어가던 문제.
    쓴 값 · 완료 여부는 그대로. 내보내기 전 서류에는 양식에 새로 생긴 칸(빠졌던 작성일 등)도 빈 칸으로 더한다.
    바뀌었으면 True (커밋은 부르는 쪽)"""
    if doc.source_type != "policy_form":
        return False
    form = ai_content.load_form(form_id_of(doc))
    if form is None:
        return False
    fresh = doc_spec.from_form_spec(form)
    by_id = {f["field_id"]: f for f in fresh}
    changed = False
    spec = []
    for f in doc.spec:
        g = by_id.get(f["field_id"])
        fix = {k: g.get(k) for k in LAYOUT_KEYS if g is not None and g.get(k) != f.get(k)}
        if fix:
            f = {**f, **fix}
            changed = True
        spec.append(f)
    have = {f["field_id"] for f in spec}
    added = [g for g in fresh if g["field_id"] not in have] if add_fields and doc.status != "exported" else []
    if added:
        checked = all(f.get("checked") for f in doc.spec) if doc.spec else False
        order = len(spec)
        for i, g in enumerate(added):
            spec.append({**g, "checked": checked})
            db.add(DocumentField(document_id=doc.id, field_id=g["field_id"], sort_order=order + i, segments=[]))
        changed = True
    if changed:
        doc.spec = spec
    return changed


def fill_from_others(db: Session, user: User, doc: Document) -> int:
    """서류를 열 때: 아직 비어 있고 완료하지 않은 내 칸을 다른 서류에서 완료한 같은 칸 값으로 채운다.
    이미 쓴 칸 · 다른 사람 칸 · 날짜 칸은 건드리지 않는다. 채운 칸 수"""
    if doc.status == "exported":
        return 0
    specs = _specs(doc)
    empty = [r for r in _rows(db, doc)
             if not r.is_completed and not _value(r.segments).strip() and _writable(specs[r.field_id])
             and specs[r.field_id].get("input_type") != "date" and doc_spec.reuse_key(specs[r.field_id]) is not None]
    if not empty:
        return 0
    reusable = _reusable(db, user, doc.id)
    filled = 0
    for r in empty:
        hit = reusable.get(doc_spec.reuse_key(specs[r.field_id]))
        if hit:
            title, segments = hit
            r.segments = [{"text": _value(segments), "source": "reused"}]
            r.reused_from = title
            filled += 1
    if filled:
        db.commit()
    return filled


# ---------------- 내 정보가 바뀌면 서류 칸에도 ----------------

PROFILE_KEYS = ("name", "birth_date", "region", "phone")


def profile_snapshot(user: User) -> dict[str, str | None]:
    """서류 칸에 들어가는 형태의 내 정보 (이름 · 생년월일 · 지역 · 전화)"""
    return {k: doc_spec.profile_value(k, user.name, user.birth_date, user.region, user.phone) for k in PROFILE_KEYS}


def sync_profile(db: Session, user: User, before: dict[str, str | None]) -> int:
    """내 정보(이름 · 생년월일 · 지역 · 전화)가 바뀌면 그 정보를 쓰는 모든 서류 칸을 새 값으로 바꾼다.

    - 바꾸는 칸: 그 정보를 쓰는 칸(profile_key) 중 비어 있거나, 내 정보에서 채웠거나, 예전 값과 같은 칸.
      사용자가 일부러 다르게 적은 칸(예: 대리인 이름)은 두지 않는다
    - 예전 값은 되돌리기(previous_segments)에 남긴다. 완료 여부는 그대로
    - 개인정보 칸을 비운 오래된 서류는 건드리지 않는다. 바꾼 칸 수를 돌려준다
    """
    after = profile_snapshot(user)
    changed = {k: v for k, v in after.items() if v and v != before.get(k)}
    if not changed:
        return 0
    count = 0
    docs = db.scalars(select(Document).where(Document.user_id == user.id, Document.personal_cleared_at.is_(None)))
    for doc in docs:
        specs = _specs(doc)
        touched = False
        for row in _rows(db, doc):
            spec = specs.get(row.field_id)
            key = spec.get("profile_key") if spec else None
            if key not in changed or not _writable(spec):
                continue
            current = _value(row.segments).strip()
            from_profile = all(seg.get("source") in ("profile", "reused") for seg in row.segments)
            if current and not from_profile and current != (before.get(key) or ""):
                continue
            if current == changed[key]:
                continue
            row.previous_segments = row.segments
            row.segments = [{"text": changed[key], "source": "profile"}]
            touched = True
            count += 1
        if touched:
            _touch(doc)
    db.commit()
    return count


# ---------------- 오래된 서류: 개인정보 칸만 비우기 ----------------


def _personal(spec: dict) -> bool:
    return (
        spec["value_source"] == "profile"
        or spec.get("profile_key") is not None
        or spec.get("input_type") in PERSONAL_INPUT_TYPES
    )


def clear_stale(db: Session, user: User | None = None, now: datetime | None = None) -> int:
    """내보낸 지 오래됐거나(기본 90일) 쓰다 둔 지 오래된(기본 180일) 서류는 지우지 않고 개인정보 칸만 비운다.

    - 쓴 문장(자기소개 · 사유 등)과 서류 목록 · 체크리스트 체크는 그대로 남는다
    - 비운 칸은 완료가 풀린다. 서류를 다시 열면 맨 위에 비웠다는 안내가 나온다
    - user 가 없으면 모든 사용자 (db/clear_old_documents.py). 비운 서류 수를 돌려준다
    """
    settings = get_settings()
    now = now or datetime.now(UTC)
    exported_before = now - timedelta(days=settings.doc_keep_exported_days)
    writing_before = now - timedelta(days=settings.doc_keep_writing_days)
    q = select(Document).where(
        Document.personal_cleared_at.is_(None),
        or_(
            and_(Document.status == "exported", Document.exported_at < exported_before),
            and_(Document.status == "writing", Document.updated_at < writing_before),
        ),
    )
    if user is not None:
        q = q.where(Document.user_id == user.id)
    docs = list(db.scalars(q))
    for doc in docs:
        specs = _specs(doc)
        for row in _rows(db, doc):
            spec = specs.get(row.field_id)
            if spec is None or not _personal(spec) or not (row.segments or row.previous_segments):
                continue
            row.segments = []
            row.previous_segments = None
            row.reused_from = None
            row.is_completed = False
            row.completed_at = None
        # updated_at 은 그대로 둔다 (사용자가 고친 것이 아니라서 목록 순서가 바뀌지 않게)
        db.execute(
            update(Document)
            .where(Document.id == doc.id)
            .values(personal_cleared_at=func.now(), updated_at=Document.updated_at)
        )
    if docs:
        db.commit()
        for doc in docs:
            db.refresh(doc)
    return len(docs)


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
        if doc.status == "exported":
            _uncheck_linked_item(db, doc)  # 내보낸 서류를 다시 고치면 체크리스트 체크도 풀린다
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


_REQ_WORDS = re.compile(r"(?:적어|써|넣어|채워|입력해|해)\s*(?:줘|주세요|줄래|주라)")


def _echoes_request(text: str, prompt: str) -> bool:
    """AI 가 칸 값 대신 부탁 문장을 그대로 돌려줬는지 ("내 이름으로 적어줘" → "내 이름으로 적어줘")"""
    flat_t, flat_p = "".join(text.split()), "".join(prompt.split())
    return bool(flat_t) and (flat_t == flat_p or (flat_p in flat_t and bool(_REQ_WORDS.search(text))))


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
    res = _assist(db, user, doc, body, today, doc_llm, rag)
    if res.route != "crisis":
        res.profile_changes = profile_signal.detect(body.prompt, user, today)
    return res


def _assist(
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

    if spec.get("input_type") == "account":  # 계좌번호 칸은 AI 를 쓰지 않는다 (값·요청을 LLM 에 보내지 않음)
        row.tip = ACCOUNT_TIP
        db.commit()
        resp = _field_response(db, doc, row, spec)
        return AssistResponse(
            route="field", action="tip", field=resp.field, progress=resp.progress, message=None, search=None, contacts=[],
        )

    # "내 이름으로 적어줘" 같은 부탁: 그 말을 칸에 적지 않고 앱에 있는 내 정보를 넣는다 (LLM 을 부르지 않음)
    from app import answer_extract

    ref = answer_extract.self_reference(body.prompt)
    if ref is None and spec.get("input_type") == "date" and answer_extract.today_request(body.prompt):
        ref = "today"
    if ref is not None:
        mine, why = ((today.strftime("%Y.%m.%d"), None) if ref == "today"
                     else answer_extract.self_value(ref, spec, user.name, user.birth_date, user.phone))
        if mine or why:
            if mine:
                row.previous_segments = row.segments
                row.segments = [{"text": mine, "source": "profile"}]
                row.reused_from = None
            else:
                row.tip = why
            row.updated_at = func.now()
            _touch(doc)
            db.commit()
            resp = _field_response(db, doc, row, spec)
            return AssistResponse(
                route="field", action="draft" if mine else "tip", field=resp.field, progress=resp.progress,
                message=None, search=None, contacts=[],
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
    if action in ("draft", "revise") and _echoes_request(text, body.prompt):
        # 부탁 문장("내 주소로 써 줘")을 그대로 칸에 적지 않는다: 무엇을 알려 주면 되는지 안내로 바꾼다
        action = "tip"
        example = f" (예: {spec['example']})" if spec.get("example") else ""
        text = f"'{spec.get('easy_label') or spec['label']}'에 넣을 내용을 직접 알려 주세요.{example}"
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
        label = spec.get("easy_label") or spec["label"]  # 같은 이름 칸이 여럿이면 "대리인 성명"처럼
        where = {"section": spec.get("section") or None, "how": spec.get("guide") or None,
                 "example": _example_of(spec)}
        if not r.is_completed and not value.strip():
            problems.append(Problem(field_id=r.field_id, label=label, reason="required_empty" if spec["required"] else "not_completed",
                                    message=f"'{label}' 칸이 비어 있어요. 내용을 적고 완료를 눌러 주세요.", **where))
            continue
        if not r.is_completed:
            problems.append(Problem(field_id=r.field_id, label=label, reason="not_completed",
                                    message=f"'{label}' 칸에 적은 내용을 확인하고 완료를 눌러 주세요.", **where))
            continue
        if spec["required"] and not value.strip():
            problems.append(Problem(field_id=r.field_id, label=label, reason="required_empty",
                                    message=f"'{label}' 칸은 꼭 적어야 해요. 비워 둔 채 완료했어요.", **where))
            continue
        for w in _field_warnings(spec, value):
            reason = "placeholder_left" if w.endswith("빈칸을 채워 주세요.") else "invalid_format"  # 형식·선택지
            problems.append(Problem(field_id=r.field_id, label=label, reason=reason, message=f"'{label}': {w}", **where))
    return CheckResponse(ready=not problems, problems=problems, warnings=_consistency(db, user, doc))


_TYPE_EXAMPLES = {"date": "2007.03.15", "phone": "010-1234-5678", "account": "110-123-456789"}


def _example_of(spec: dict) -> str | None:
    """칸의 바른 예시: 양식에 적힌 예시 → 선택지 → 날짜 · 전화 · 계좌 기본 예시"""
    if spec.get("example"):
        return spec["example"]
    if spec.get("options"):
        return spec["options"][0]
    return _TYPE_EXAMPLES.get(spec.get("input_type") or "")


def _consistency(db: Session, user: User, doc: Document) -> list[str]:
    """다른 서류에서 완료한 같은 칸과 값이 다르면 알려 준다 (내보내기는 막지 않음)"""
    mine = {}
    specs = _specs(doc)
    for r in _rows(db, doc):
        spec = specs[r.field_id]
        key = doc_spec.reuse_key(spec)
        if key is not None and _writable(spec) and _value(r.segments).strip():
            mine.setdefault(key, (spec["label"], " ".join(_value(r.segments).split())))
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


def original_pdf(doc: Document) -> Path | bytes | None:
    """원본 양식 바탕 PDF. 정책 신청서는 formguide 가 저장한 data/forms/{form_id}.pdf,
    올린 양식은 올릴 때 만든 바탕 PDF (documents.form_pdf). 앱 양식은 None"""
    if doc.source_type == "upload":
        if not doc.form_pdf:
            return None
        try:
            return base64.b64decode(doc.form_pdf)
        except (binascii.Error, ValueError):
            return None
    if doc.source_type != "policy_form":
        return None
    path = get_settings().forms_dir / f"{form_id_of(doc)}.pdf"
    return path if path.is_file() else None


def pdf_layout(doc: Document) -> str:
    """"original": 원본 양식 위에 채움 / "table": 칸 이름·내용 표"""
    rows = [(f, "") for f in doc.spec]
    return "original" if doc_export.has_layout(original_pdf(doc), rows) else "table"


def file_rows(db: Session, doc: Document) -> list[tuple[dict, str]]:
    if doc.status != "exported":
        raise AppError(409, "NOT_EXPORTED", "먼저 검사를 마치고 내보내기를 해 주세요.")
    if sync_layout(db, doc, add_fields=False):  # 고친 칸 자리로 다시 그린다 (값은 그대로)
        db.commit()
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
