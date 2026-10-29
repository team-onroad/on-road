"""올린 양식(사진·PDF) → 문서 AI 작성 칸 (docs/document-ai.md "올린 양식").

기관에서 받은 종이 양식을 찍어 올리면 칸을 읽어 대화로 채우기·칸별로 쓰기를 할 수 있게 한다.
- PDF 에 글자가 있으면 글자만 LLM 에 보낸다. 사진·스캔 PDF 는 쪽 이미지를 보낸다 (최대 MAX_PAGES 쪽)
- 토큰·속도: 사진은 흑백 JPG, 긴 변 IMAGE_MAX_SIDE 로 다시 그린다 (위치 정보 EXIF 도 지워짐).
  LLM 에는 칸 이름·종류·짧은 안내만 받는다 (양식 전체 글자를 옮겨 적게 하지 않음). 올린 파일은 저장하지 않는다
- PDF 글자가 있으면 LLM 이 준 칸 이름이 그 글자에 없을 때 버린다 (지어낸 칸 방지)
- 주민등록번호·서명은 손으로 쓰는 칸, 계좌번호는 user 칸(LLM 에 보내지 않음)으로 정한다 (doc_spec 와 같은 원칙)
- 원본 위에 채울 자리(box)는 없다 → 내보내면 칸 이름·내용 표 PDF (원본 양식에 옮겨 적는다)
"""

import base64
import hashlib
import re
from pathlib import PurePath

import pymupdf

from app.doc_llm import DocLlm
from app.doc_spec import ESSAY_WORDS, _field, _guess_profile_key, _sensitive
from app.errors import AppError
from app.llm_client import call_llm

MAX_BYTES = 10 * 1024 * 1024
MAX_PAGES = 2
MIN_PDF_TEXT = 40  # 이보다 글자가 적으면 스캔 PDF 로 보고 쪽 이미지를 보낸다
IMAGE_MAX_SIDE = 1280  # 사진 긴 변 (픽셀). 칸 이름을 읽기에 충분하고 이미지 토큰이 적다
JPG_QUALITY = 70
MAX_FIELDS = 40

_INPUT_TYPES = {"text", "date", "phone", "address", "checkbox", "account", "id_number", "signature"}
_ID_SENSITIVE = ("주민등록번호", "외국인등록번호")
_SIGN_WORDS = ("서명", "(인)", "날인", "도장")
_OFFICE_WORDS = ("접수번호", "접수일", "처리결과", "담당자", "담당공무원", "확인자", "결재", "처리기간")
_SPACE_RE = re.compile(r"\s+")


def _flat(text: str) -> str:
    return _SPACE_RE.sub("", text)


def bad_file(message: str) -> AppError:
    return AppError(422, "UPLOAD_INVALID", message)


def unreadable() -> AppError:
    return AppError(422, "FORM_UNREADABLE", "양식에서 쓸 칸을 찾지 못했어요. 양식 전체가 밝고 반듯하게 나오게 다시 찍어 주세요.")


def _kind(data: bytes) -> str:
    if data.startswith(b"%PDF"):
        return "pdf"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data[4:12] in (b"ftypheic", b"ftypheix", b"ftypmif1", b"ftyphevc"):
        raise bad_file("HEIC 사진은 읽지 못해요. 카메라 설정에서 JPG로 찍거나, 앱에서 바로 찍어 주세요.")
    raise bad_file("사진(JPG·PNG)이나 PDF 파일만 올릴 수 있어요.")


def _page_image(page: pymupdf.Page) -> str:
    """흑백 JPG data URL (PNG 보다 몇 배 작아 보내기가 빠르다)"""
    longest = max(page.rect.width, page.rect.height) or 1
    zoom = min(IMAGE_MAX_SIDE / longest, 3.0)
    pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), colorspace=pymupdf.csGRAY, alpha=False)
    return "data:image/jpeg;base64," + base64.b64encode(pix.tobytes("jpg", jpg_quality=JPG_QUALITY)).decode()


def read_source(data: bytes) -> tuple[str | None, list[str]]:
    """(PDF 글자 또는 None, 쪽 이미지 data URL 목록)"""
    if not data:
        raise bad_file("빈 파일이에요.")
    if len(data) > MAX_BYTES:
        raise bad_file("파일이 너무 커요. 10MB 이하로 올려 주세요.")
    kind = _kind(data)
    try:
        doc = pymupdf.open(stream=data, filetype=kind)
    except Exception:
        raise bad_file("파일을 열지 못했어요. 다른 사진이나 PDF로 올려 주세요.") from None
    with doc:
        if kind == "pdf":
            pages = list(doc)[:MAX_PAGES]
            text = "\n".join(p.get_text() for p in pages).strip()
            if len(_flat(text)) >= MIN_PDF_TEXT:
                return text, []
            return None, [_page_image(p) for p in pages]
        # 사진: 다시 그려서 보낸다 (EXIF·위치 정보 없음, 크기 줄임)
        return None, [_page_image(doc[0])]


def _check_read(v: object) -> bool:
    return isinstance(v, dict) and isinstance(v.get("fields"), list)


def _in_source(label: str, source: str) -> bool:
    flat = _flat(label).rstrip(":*")
    if flat and flat in source:
        return True
    words = [w for w in re.split(r"[\s/()·,]+", label) if w]
    return len(words) >= 2 and all(_flat(w) in source for w in words)


_DATE_PARTS = ("년", "월", "일")
# 날짜 칸으로 볼 칸 이름 (붙여 쓴 이름). 신청·작성 날짜는 대화로 채우기에서 오늘 날짜로 미리 채운다
_DATE_LABELS = ("년월일", "신청일", "신청일자", "신청날짜", "작성일", "작성일자", "제출일", "생년월일", "보호종료일", "입주희망일")


def _merge_date_parts(raw: list[dict]) -> list[dict]:
    """"년" "월" "일"로 나눠 온 칸을 날짜 칸 하나("년 월 일", date)로 합친다. 사용자는 2026.10.29 처럼 한 번에 쓴다"""
    out: list[dict] = []
    i = 0
    while i < len(raw):
        labels = [_flat(str(f.get("label") or "")) for f in raw[i:i + 3]]
        if labels == list(_DATE_PARTS):
            out.append({"label": "년 월 일", "type": "date", "guide": "신청하는 날짜예요"})
            i += 3
            continue
        out.append(raw[i])
        i += 1
    return out


def to_fields(raw: list[dict], form_text: str | None) -> list[dict]:
    """LLM 칸 목록 → documents.spec 칸. 기관 기재란은 버리고, PDF 글자가 있으면 거기 없는 칸 이름도 버린다"""
    source = _flat(form_text) if form_text else None
    raw = _merge_date_parts(raw)
    fields: list[dict] = []
    seen: set[str] = set()
    taken: set[str] = set()
    for f in raw:
        label = " ".join(str(f.get("label") or "").split())[:60]
        flat = _flat(label)
        if not label or flat in seen or (source is not None and not _in_source(label, source)):
            continue
        if any(w in flat for w in _OFFICE_WORDS):
            continue
        seen.add(flat)
        kind = f.get("type") or f.get("input_type")
        input_type = kind if kind in _INPUT_TYPES else "text"
        if flat.rstrip(":*") in _DATE_LABELS or (flat.endswith("일") and "년" in flat and "월" in flat):
            input_type = "date"  # "년 월 일"을 따로 받지 않고 날짜 하나로 (2026.10.29)
        if "계좌" in flat:
            input_type = "account"
        options = [str(o).strip() for o in f.get("options") or [] if str(o).strip()] if input_type == "checkbox" else None
        if input_type == "checkbox" and not options:
            input_type = "text"
        guide = " ".join(str(f.get("guide") or "").split())[:200] or f"'{label}' 칸에 들어갈 내용을 적어요."
        example = " ".join(str(f.get("example") or "").split())[:80] or None
        if input_type == "account":
            source_kind, example = "user", None
        elif input_type in ("id_number", "signature") or any(w in flat for w in _ID_SENSITIVE + _SIGN_WORDS) or _sensitive(label):
            source_kind, input_type = "handwrite", ("signature" if any(w in flat for w in _SIGN_WORDS) else "id_number")
        else:
            source_kind = "user"
        profile_key = None
        if source_kind == "user" and input_type in ("text", "date", "phone"):
            profile_key = _guess_profile_key(label, taken)
            if profile_key:
                taken.add(profile_key)
                source_kind = "profile"
        essay = source_kind == "user" and input_type == "text" and any(w in label for w in ESSAY_WORDS)
        fields.append(_field(
            field_id=f"f{len(fields) + 1}", label=label, section=(str(f.get("section")).strip()[:40] or None)
            if f.get("section") else None,
            guide=guide, example=example, multiline=essay, essay=essay,
            required=f.get("required") if isinstance(f.get("required"), bool) else None,
            input_type=input_type, value_source=source_kind, profile_key=profile_key, options=options,
        ))
        if len(fields) >= MAX_FIELDS:
            break
    return fields


def source_id(data: bytes) -> str:
    """같은 파일을 다시 올리면 같은 값 (쓰던 문서를 연다)"""
    return "upload-" + hashlib.sha256(data).hexdigest()[:24]


def read_form(data: bytes, file_name: str | None, doc_llm: DocLlm) -> tuple[str, list[dict]]:
    """(제목, 칸 목록)"""
    text, images = read_source(data)
    out = call_llm("read_form", doc_llm.read_form, _check_read, text, images)
    fields = to_fields(out["fields"], text)
    if not fields:
        raise unreadable()
    title = " ".join(str(out.get("title") or "").split())
    if not title or (text and not _in_source(title, _flat(text))):
        stem = PurePath(file_name).stem if file_name else ""
        title = stem if stem and not re.fullmatch(r"(IMG|PXL|KakaoTalk|Screenshot|image|photo)[\w\- ]*", stem, re.I) else "올린 양식"
    return title[:100], fields
