"""올린 양식(사진·PDF) → 문서 AI 작성 칸 (docs/document-ai.md "올린 양식").

기관에서 받은 종이 양식을 찍어 올리면 칸을 읽어 대화로 채우기·칸별로 쓰기를 할 수 있게 한다.
- PDF 에 글자가 있으면 글자만 LLM 에 보낸다. 사진·스캔 PDF 는 쪽 이미지를 보낸다 (최대 MAX_PAGES 쪽)
- 토큰·속도: 사진은 흑백 JPG, 긴 변 IMAGE_MAX_SIDE 로 다시 그린다 (위치 정보 EXIF 도 지워짐).
  LLM 에는 칸 이름·종류·짧은 안내만 받는다 (양식 전체 글자를 옮겨 적게 하지 않음)
- PDF 글자가 있으면 LLM 이 준 칸 이름이 그 글자에 없을 때 버린다 (지어낸 칸 방지)
- 주민등록번호·서명은 손으로 쓰는 칸, 계좌번호는 user 칸(LLM 에 보내지 않음)으로 정한다 (doc_spec 와 같은 원칙)
- 글자가 있는 PDF · hwpx 는 원본 규격 바탕 PDF 와 값 자리(box)를 만들어 원본 양식 위에 채워 내보낸다 (app/form_layouts.py).
  사진 · 스캔 PDF · 예전 한글(hwp)은 값 자리를 알 수 없어 칸 이름·내용 표 PDF 로 내보낸다
- 같은 양식은 파일이 달라도(다시 받은 파일 등) 글자가 같으면 같은 문서로 본다 (source_id)
"""

import base64
import hashlib
import re
from pathlib import PurePath

import pymupdf

from app.doc_llm import DocLlm
from app.doc_spec import ESSAY_WORDS, _field, _guess_profile_key, _sensitive, is_other_person
from app.errors import AppError
from app.llm_client import call_llm

MAX_BYTES = 10 * 1024 * 1024
MAX_PAGES = 2
MIN_PDF_TEXT = 40  # 이보다 글자가 적으면 스캔 PDF 로 보고 쪽 이미지를 보낸다
IMAGE_MAX_SIDE = 1280  # 사진 긴 변 (픽셀). 칸 이름을 읽기에 충분하고 이미지 토큰이 적다
JPG_QUALITY = 70
MAX_FIELDS = 60  # 가족사항 · 경력처럼 줄이 반복되는 표가 있는 양식
# 칸 읽는 규칙 버전. 올리면 같은 파일이어도 이 값이 바뀌었으면 새 문서로 다시 읽는다
READ_VERSION = "v3"  # v3: 원본 규격 바탕 PDF · 값 자리, 글자 기준으로 같은 양식 판단

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


def kind_of(data: bytes) -> str:
    return _kind(data)


def _kind(data: bytes) -> str:
    if data.startswith(b"%PDF"):
        return "pdf"
    if data.startswith(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"):
        return "hwp"  # 예전 한글 (OLE 복합 문서)
    if data.startswith(b"PK\x03\x04"):
        return "hwpx"  # 한글 hwpx (zip)
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data[4:12] in (b"ftypheic", b"ftypheix", b"ftypmif1", b"ftyphevc"):
        raise bad_file("HEIC 사진은 읽지 못해요. 카메라 설정에서 JPG로 찍거나, 앱에서 바로 찍어 주세요.")
    raise bad_file("사진(JPG·PNG), PDF, 한글(hwp·hwpx) 파일만 올릴 수 있어요.")


# ---------------- 한글 파일: 글자만 꺼낸다 (칸 찾기는 PDF 글자와 같은 길로) ----------------

_HWP_PARA_TEXT = 67  # HWPTAG_BEGIN(16) + 51
# 한글 본문 안 제어 문자: 이 코드들은 뒤에 7글자(14바이트)를 더 차지한다
_HWP_EXTENDED = {1, 2, 3, 11, 12, 14, 15, 16, 17, 18, 21, 22, 23}
_HWP_INLINE = {4, 5, 6, 7, 8, 9, 19, 20}


def _hwp_para(raw: bytes) -> str:
    out, i, n = [], 0, len(raw) // 2
    while i < n:
        code = int.from_bytes(raw[2 * i: 2 * i + 2], "little")
        if code < 32:
            if code in _HWP_EXTENDED or code in _HWP_INLINE:
                i += 8
                continue
            if code in (10, 13):
                out.append("\n")
            elif code == 24:
                out.append("-")
            elif code in (30, 31):
                out.append(" ")
            i += 1
            continue
        out.append(chr(code))
        i += 1
    return "".join(out)


def hwp_text(data: bytes) -> str:
    """예전 한글(.hwp): BodyText/Section* 의 문단 글자 (표 칸 글자도 문단이라 함께 나온다)"""
    import olefile
    import zlib

    ole = olefile.OleFileIO(data)
    try:
        header = ole.openstream("FileHeader").read()
        flags = int.from_bytes(header[36:40], "little")
        if flags & 0x2:
            raise bad_file("암호가 걸린 한글 파일은 읽지 못해요. 암호를 풀고 다시 올려 주세요.")
        compressed = bool(flags & 0x1)
        sections = sorted(
            (e for e in ole.listdir() if len(e) == 2 and e[0] == "BodyText" and e[1].startswith("Section")),
            key=lambda e: int(e[1][7:] or 0),
        )
        paras: list[str] = []
        for entry in sections:
            body = ole.openstream(entry).read()
            if compressed:
                body = zlib.decompress(body, -15)
            pos = 0
            while pos + 4 <= len(body):
                head = int.from_bytes(body[pos: pos + 4], "little")
                tag, size = head & 0x3FF, (head >> 20) & 0xFFF
                pos += 4
                if size == 0xFFF:
                    size = int.from_bytes(body[pos: pos + 4], "little")
                    pos += 4
                if tag == _HWP_PARA_TEXT:
                    text = _hwp_para(body[pos: pos + size]).strip()
                    if text:
                        paras.append(text)
                pos += size
        return "\n".join(paras)
    finally:
        ole.close()


def hwpx_text(data: bytes) -> str:
    """한글 hwpx: Contents/section*.xml 의 글자 (문단 · 표 칸마다 줄을 나눈다)"""
    import io
    import zipfile
    from xml.etree import ElementTree

    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = sorted(
            (n for n in z.namelist() if re.fullmatch(r"Contents/section\d+\.xml", n)),
            key=lambda n: int(re.search(r"\d+", n.rsplit("/", 1)[-1]).group()),
        )
        lines: list[str] = []
        rows: list[str] = []
        for name in names:
            root = ElementTree.fromstring(z.read(name))
            for p in root.iter():
                if p.tag.rsplit("}", 1)[-1] != "p":
                    continue
                # 이 문단에 바로 딸린 글자만 (안쪽 표의 문단은 따로 돈다)
                text = "".join(
                    t.text or "" for run in p if run.tag.rsplit("}", 1)[-1] == "run"
                    for t in run if t.tag.rsplit("}", 1)[-1] == "t"
                ).strip()
                if text:
                    lines.append(text)
            for tbl in root.iter():
                if _tag(tbl) == "tbl":
                    rows.extend(_hwpx_rows(tbl))
                    rows.append("")
        table = "\n".join(rows).strip()
        return "\n".join(lines) + (f"\n\n[표 구조]\n{table}" if table else "")


def _tag(el) -> str:
    return el.tag.rsplit("}", 1)[-1]


def _hwpx_cell_text(tc) -> str:
    """칸 하나의 글자. 한 문단 안에서 쪼개진 글자(run)는 붙이고 문단끼리는 줄을 나눈다.
    칸 안에 또 표가 있으면 그 표는 따로 줄을 만든다 (여기선 뺀다)"""
    paras: list[str] = []

    def walk(el):
        for ch in el:
            tag = _tag(ch)
            if tag == "tbl":
                continue
            if tag == "p":
                paras.append("".join(t.text or "" for t in _own_texts(ch)))
            else:
                walk(ch)
    walk(tc)
    return _cell("\n".join(paras))


def _own_texts(p):
    """문단 p 의 글자 (안쪽 표 글자 제외)"""
    for ch in p:
        if _tag(ch) == "tbl":
            continue
        if _tag(ch) == "t":
            yield ch
        else:
            yield from _own_texts(ch)


def _hwpx_rows(tbl) -> list[str]:
    rows = []
    for tr in tbl:
        if _tag(tr) != "tr":
            continue
        cells = [x for x in (_hwpx_cell_text(tc) for tc in tr if _tag(tc) == "tc") if x]
        if cells:
            rows.append(" | ".join(cells))
    return rows


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
    if kind in ("hwp", "hwpx"):
        try:
            text = hwp_text(data) if kind == "hwp" else hwpx_text(data)
        except AppError:
            raise
        except Exception:
            raise bad_file("한글 파일을 열지 못했어요. 파일이 손상되지 않았는지 확인해 주세요.") from None
        text = text.strip()
        if len(_flat(text)) < MIN_PDF_TEXT:
            raise bad_file("한글 파일에서 글자를 찾지 못했어요. PDF로 저장해서 올려 주세요.")
        return text, []
    try:
        doc = pymupdf.open(stream=data, filetype=kind)
    except Exception:
        raise bad_file("파일을 열지 못했어요. 다른 사진이나 PDF로 올려 주세요.") from None
    with doc:
        if kind == "pdf":
            pages = list(doc)[:MAX_PAGES]
            text = "\n".join(p.get_text() for p in pages).strip()
            if len(_flat(text)) >= MIN_PDF_TEXT:
                tables = "\n".join(filter(None, (_table_lines(p) for p in pages)))
                return (f"{text}\n\n[표 구조]\n{tables}" if tables else text), []
            return None, [_page_image(p) for p in pages]
        # 사진: 다시 그려서 보낸다 (EXIF·위치 정보 없음, 크기 줄임)
        return None, [_page_image(doc[0])]


def _cell(text: str | None) -> str:
    """칸 글자 한 줄로. 세로로 한 글자씩 쓴 이름(신\n청\n인)은 붙인다"""
    lines = [x.strip() for x in (text or "").splitlines() if x.strip()]
    if lines and all(len(x) == 1 for x in lines):
        return "".join(lines)
    return " ".join(lines)


def _table_lines(page: "pymupdf.Page") -> str:
    """PDF 표를 줄 단위로 ("성명 | 생년월일 | 년 월 일"). 칸끼리 짝과 묶음(신청인 정보 | 대리인 정보)을 LLM 이 알게.
    표 전체 폭을 차지하는 한 칸 줄은 묶음 제목이라 [ ]로 감싼다"""
    try:
        found = page.find_tables()
    except Exception:  # 표 찾기가 실패해도 글자만으로 읽는다
        return ""
    out: list[str] = []
    for t in found.tables:
        width = (t.bbox[2] - t.bbox[0]) or 1
        for row in t.rows:
            cells = [(c, _cell(page.get_textbox(c))) for c in row.cells if c]
            filled = [(c, x) for c, x in cells if x]
            if not filled:
                continue
            if len(filled) == 1 and (filled[0][0][2] - filled[0][0][0]) > width * 0.6:
                out.append(f"[{filled[0][1]}]")
            else:
                out.append(" | ".join(x for _, x in filled))
        out.append("")
    return "\n".join(out).strip()


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
    """"년" "월" "일"로 나눠 온 칸을 날짜 칸 하나("년 월 일", date)로 합친다. 사용자는 2026.10.29 처럼 한 번에 쓴다.
    LLM 이 셋을 떨어뜨려 주거나 둘만 줘도 (예: 년·월·일 사이에 다른 칸) 첫 자리에 하나로 모은다"""
    def part(f: dict) -> bool:
        return _flat(str(f.get("label") or "")).strip(":*()[]") in _DATE_PARTS

    idx = [i for i, f in enumerate(raw) if part(f)]
    if len(idx) < 2:
        return raw
    out = [f for i, f in enumerate(raw) if i not in idx]
    out.insert(idx[0], {"label": "년 월 일", "type": "date", "guide": "신청하는 날짜예요"})
    return out


def to_fields(raw: list[dict], form_text: str | None) -> list[dict]:
    """LLM 칸 목록 → documents.spec 칸. 기관 기재란은 버리고, PDF 글자가 있으면 거기 없는 칸 이름도 버린다"""
    source = _flat(form_text) if form_text else None
    raw = _merge_date_parts(raw)
    fields: list[dict] = []
    seen: set[tuple[str, str]] = set()
    taken: set[str] = set()
    # 같은 칸 이름이 여러 묶음에 있으면 (신청인 성명 · 대리인 성명 · 가족 1 성명) 묶음 이름을 붙여 부른다
    label_count: dict[str, int] = {}
    for f in raw:
        k = _flat(" ".join(str(f.get("label") or "").split()))
        label_count[k] = label_count.get(k, 0) + 1
    for f in raw:
        label = " ".join(str(f.get("label") or "").split())[:60]
        flat = _flat(label)
        section = " ".join(str(f.get("section") or "").split())[:40] or None
        # "신청일" · "작성일" 은 양식에 "년 월 일" 줄로만 있는 경우가 많다
        dated = flat in _DATE_LABELS and source is not None and "년월일" in source
        if not label or (section, flat) in seen or (source is not None and not dated and not _in_source(label, source)):
            continue
        if any(w in flat for w in _OFFICE_WORDS) or (section and any(w in _flat(section) for w in _OFFICE_WORDS)):
            continue
        if not section and any(sec is None and fl == flat for sec, fl in seen):
            continue
        seen.add((section, flat))
        other_person = is_other_person({"label": label, "section": section})
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
        if source_kind == "user" and input_type in ("text", "date", "phone") and not other_person:
            profile_key = _guess_profile_key(label, taken)
            if profile_key:
                taken.add(profile_key)
                source_kind = "profile"
        essay = source_kind == "user" and input_type == "text" and any(w in label for w in ESSAY_WORDS)
        easy = f"{section} {label}" if section and label_count.get(flat, 0) > 1 else None
        fields.append(_field(
            field_id=f"f{len(fields) + 1}", label=label, easy_label=easy, section=section,
            guide=guide, example=example, multiline=essay, essay=essay,
            required=f.get("required") if isinstance(f.get("required"), bool) else None,
            input_type=input_type, value_source=source_kind, profile_key=profile_key, options=options,
        ))
        if len(fields) >= MAX_FIELDS:
            break
    return fields


def source_id(data: bytes, text: str | None = None) -> str:
    """같은 양식이면 같은 값 (쓰던 문서를 연다).
    글자를 꺼낸 양식(PDF · 한글)은 글자로 본다: 같은 양식을 다시 받은 파일처럼 바이트가 조금 달라도 같은 양식.
    사진 · 스캔본은 파일 그대로 본다"""
    basis = _flat(text).encode() if text and len(_flat(text)) >= MIN_PDF_TEXT else data
    return f"upload-{READ_VERSION}-" + hashlib.sha256(basis).hexdigest()[:20]


def read_form(
    data: bytes, file_name: str | None, doc_llm: DocLlm, source: tuple[str | None, list[str]] | None = None,
) -> tuple[str, list[dict]]:
    """(제목, 칸 목록). source 는 이미 꺼낸 (글자, 쪽 이미지) (없으면 여기서 꺼낸다)"""
    text, images = source if source is not None else read_source(data)
    out = call_llm("read_form", doc_llm.read_form, _check_read, text, images)
    fields = to_fields(out["fields"], text)
    if not fields:
        raise unreadable()
    title = " ".join(str(out.get("title") or "").split())
    if not title or (text and not _in_source(title, _flat(text))):
        stem = PurePath(file_name).stem if file_name else ""
        title = stem if stem and not re.fullmatch(r"(IMG|PXL|KakaoTalk|Screenshot|image|photo)[\w\- ]*", stem, re.I) else "올린 양식"
    return title[:100], fields
