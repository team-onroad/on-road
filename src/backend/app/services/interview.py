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

from app import answer_extract, profile_signal
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

DONE_MESSAGE = "모든 칸을 채웠어요."
EXPORTED_MESSAGE = "모든 칸을 채우고 서류를 만들었어요. PDF로 받아 볼 수 있어요."
NOT_READY_MESSAGE = "다 채웠는데 고칠 칸이 있어요. 아래 내용을 확인해 주세요."

_DIGITS_RE = re.compile(r"\d+")


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
    s.setdefault("proxy", None)  # 대리 신청인지 (None 이면 아직 안 물어봄)
    return s


# 대리인 칸: 대리 신청인지 한 번 묻고, 아니면 대리인 칸을 모두 비운 채 넘어간다
PROXY_QUESTION = "다른 사람(보호자·후견인 등)이 대신 신청하나요? 직접 신청하면 '아니요'라고 해 주세요."
PROXY_RETRY = "대신 신청해 주는 사람이 있으면 '예', 직접 신청하면 '아니요'라고 해 주세요."
PROXY_TIP = "대리 신청은 본인 대신 보호자나 후견인 같은 다른 사람이 신청하는 거예요. 직접 신청하면 '아니요'라고 해 주세요."
_PROXY_NO = ("아니", "아뇨", "없", "직접", "혼자", "본인", "제가", "내가", "ㄴㄴ", "노")
_PROXY_YES = ("있", "대신", "대리", "보호자", "후견인", "부모", "선생님")


# 신청(작성) 날짜 칸: 오늘 날짜를 미리 넣고 "이 날짜로 할까요?"로 확인만 받는다
_APPLY_DATE_LABELS = ("년월일", "신청일", "신청일자", "신청날짜", "작성일", "작성일자", "작성날짜", "제출일", "제출일자")


def _is_apply_date(field_id: str, spec: dict) -> bool:
    if spec.get("input_type") != "date":
        return False
    label = _flat(spec["label"]).rstrip(":*")
    return label in _APPLY_DATE_LABELS or field_id in ("application_date", "apply_date", "write_date", "submit_date")


def fill_today(db: Session, rows: list[DocumentField], specs: dict, today: date) -> None:
    """아직 확인 안 한 신청 날짜 칸을 오늘 날짜로 (며칠 지나 다시 열어도 그날 날짜로 바뀐다)"""
    value = today.strftime("%Y.%m.%d")
    changed = False
    for r in rows:
        if r.is_completed or not _is_apply_date(r.field_id, specs[r.field_id]):
            continue
        if w._value(r.segments).strip() != value:
            r.segments = [{"text": value, "source": "profile"}]
            r.updated_at = func.now()
            changed = True
    if changed:
        db.commit()


def _is_proxy(field_id: str, spec: dict) -> bool:
    """대리인 칸: 정책 양식은 proxy_ 아이디, 올린 양식은 칸 이름이나 묶음(section)에 '대리인'"""
    return (field_id.startswith("proxy_") or _flat(spec["label"]).startswith("대리인")
            or "대리인" in (spec.get("section") or ""))


_REPEAT_RE = re.compile(r"^(.*?)\s*(\d+)$")  # "가족 2" → ("가족", 2)


def _skip_repeats(rows: list[DocumentField], specs: dict, current: str, state: dict) -> str | None:
    """반복 줄(가족 1 · 가족 2 …)의 첫 칸에서 '없어요' → 그 줄과 뒤 줄을 모두 비운다. 비운 묶음 이름, 아니면 None.
    이미 그 줄에 하나라도 적었으면 이 칸만 비운다 (직업만 없는 경우 등)"""
    m = _REPEAT_RE.match(specs[current].get("section") or "")
    if not m:
        return None
    base, n = m.group(1), int(m.group(2))

    def nth(fid: str) -> int | None:
        mm = _REPEAT_RE.match(specs[fid].get("section") or "")
        return int(mm.group(2)) if mm and mm.group(1) == base else None

    if any(nth(r.field_id) == n and r.is_completed and w._value(r.segments).strip() for r in rows):
        return None
    for r in rows:
        k = nth(r.field_id)
        if k is not None and k >= n and not r.is_completed and specs[r.field_id].get("required") is not True:
            _complete(r, [])
            if r.field_id in state["skipped"]:
                state["skipped"].remove(r.field_id)
    return base


def _proxy_gate(state: dict, field_id: str, spec: dict) -> bool:
    """지금 칸이 대리인 칸인데 대리 신청인지 아직 안 물어봤는지"""
    return state.get("proxy") is None and _is_proxy(field_id, spec)


def _proxy_answer(message: str) -> bool | None:
    flat = _flat(message)
    if any(w in flat for w in _PROXY_NO) or flat in NOT_APPLICABLE_WORDS:
        return False
    if flat in CONFIRM_WORDS or any(w in flat for w in _PROXY_YES):
        return True
    return None


def _todo(rows: list[DocumentField], specs: dict, skipped: list[str]) -> list[str]:
    """아직 완료하지 않은 쓰기 칸. 건너뛴 필수 칸은 맨 뒤로"""
    open_ids = [r.field_id for r in rows if w._writable(specs[r.field_id]) and not r.is_completed]
    return [f for f in open_ids if f not in skipped] + [f for f in open_ids if f in skipped]


def _question(row: DocumentField, spec: dict, pending: bool, confirm_text: str | None = None) -> Question:
    name = "신청 날짜" if _is_apply_date(row.field_id, spec) else (spec.get("easy_label") or spec["label"])
    value = w._value(row.segments).strip()
    can_skip = spec.get("required") is not True
    options = spec.get("options")
    example = None
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
            # 항목 전문은 items 로 (앱이 항목마다 체크박스로 보여 준다)
            text = (f"유의사항 {len(box.get('items') or [])}가지를 끝까지 읽고 하나씩 체크해 주세요.\n"
                    "다 체크하면 '모두 확인했어요'를 눌러 주세요.")
        elif options:
            text = f"'{name}'에서 골라 주세요: {', '.join(options)}"
        elif spec.get("essay"):
            outline = spec.get("outline")
            hint = f" ({' → '.join(outline)} 순서로 말해 주면 좋아요.)" if outline else ""
            text = f"'{name}'에 쓸 내용을 편하게 말해 주세요. 제가 문장으로 정리해 드릴게요.{hint}"
        elif spec.get("input_type") == "date":
            text = f"'{name}'{_josa(name, '을', '를')} 알려 주세요."
            example = "(예: 2026.10.27)"
        else:
            text = f"'{name}'{_josa(name, '을', '를')} 알려 주세요."
            if spec.get("guide"):
                text += f" {spec['guide']}"
        if value:  # 시/도만 미리 채운 주소
            text += f" (지금은 '{value}'만 있어요.)"
    if can_skip and not confirm:
        text += " 해당 없으면 '해당 없음'이라고 해 주세요."
    if example:  # 예시는 맨 끝에 (안내 문장 사이에 끼면 읽기 어렵다)
        text += f" {example}"
    return Question(
        field_id=row.field_id, label=spec["label"], text=text, input_type=spec.get("input_type", "text"),
        options=options, items=w.confirm_items(spec), current_value=value or None, awaiting_confirm=confirm,
        can_skip=can_skip,
    )


# 음성으로 말한 숫자 ("이천육년 삼월 오일", "공일공 일이삼사 …")
_KO_DIGIT = {"공": 0, "영": 0, "빵": 0, "일": 1, "이": 2, "삼": 3, "사": 4, "오": 5, "육": 6, "륙": 6, "칠": 7, "팔": 8, "구": 9}
_KO_UNIT = {"십": 10, "백": 100, "천": 1000}


def _ko_number(word: str) -> int | None:
    """'이천육' → 2006, '십이' → 12, '삼' → 3. 숫자면 그대로"""
    if word.isdigit():
        return int(word)
    total, cur = 0, 0
    for ch in word:
        if ch in _KO_DIGIT:
            cur = _KO_DIGIT[ch]
        elif ch in _KO_UNIT:
            total += (cur or 1) * _KO_UNIT[ch]
            cur = 0
        else:
            return None
    return total + cur if word else None


def _spoken_digits(text: str) -> str:
    """'공일공 일이삼사 오육칠팔' → 01012345678 (한 글자씩 읽은 번호만)"""
    chars = [c for c in text if not c.isspace() and c not in "-."]
    run = "".join(str(_KO_DIGIT[c]) for c in chars if c in _KO_DIGIT)
    return run if len(run) >= 9 and len(run) >= len(chars) - 3 else ""


_DATE_PART_RE = re.compile(r"([0-9]+|[공영일이삼사오육륙칠팔구십백천]+)\s*(년|월|일)")


def _spoken_date(text: str, today: date) -> str | None:
    """'2006년 3월 5일', '06년 3월 5일', '3월 5일 2006년', '이천육년 삼월 오일', '06.3.5' → 2006.03.05"""
    parts: dict[str, int] = {}
    for num, unit in _DATE_PART_RE.findall(text):
        n = _ko_number(num)
        if n is not None and unit not in parts:
            parts[unit] = n
    if len(parts) < 3:
        nums = _DIGITS_RE.findall(text)
        if len(nums) == 3 and len(nums[0]) == 2:  # 06.3.5
            parts = {"년": int(nums[0]), "월": int(nums[1]), "일": int(nums[2])}
        else:
            return None
    y = parts["년"]
    if y < 100:  # 두 자리 연도: 올해 뒤면 1900년대
        y += 2000 if y <= today.year % 100 else 1900
    try:
        return date(y, parts["월"], parts["일"]).strftime("%Y.%m.%d")
    except (ValueError, KeyError):
        return None


_BLANK_RE = re.compile(r"\[([^\[\]]{1,40})\]")
# 쓸 내용이 없는 말: 모르겠다 · 알아서 해 달라 (서술형에서 AI 가 사실을 지어내지 않게)
_NO_CONTENT = ("모르겠", "몰라", "모름", "글쎄", "생각이안", "생각안", "뭐라고써", "뭘써", "뭐써", "알아서", "아무거나", "대충써")


def _no_content(message: str) -> bool:
    flat = _flat(message)
    return len(flat) <= 16 and any(w in flat for w in _NO_CONTENT)


def _essay_guide(spec: dict, value: str) -> str:
    """서술형 칸을 채울 수 있게 작은 질문 하나로 바꿔 묻는다 (개요 첫 항목 또는 남은 [빈칸])"""
    blanks = _BLANK_RE.findall(value)
    if blanks:
        return f"괜찮아요. 먼저 [{blanks[0]}]부터 같이 채워 봐요. 떠오르는 대로 한두 마디만 말해 주세요."
    outline = spec.get("outline") or []
    if outline:
        return f"괜찮아요. 하나씩 해 봐요. 먼저 '{outline[0]}'부터 떠오르는 대로 한두 마디만 말해 주세요. 제가 문장으로 정리할게요."
    return "괜찮아요. 떠오르는 사실 한두 가지만 말해 주세요. (예: 언제, 무엇을, 왜) 제가 문장으로 정리할게요."


def _partial_date(text: str, base: str | None, today: date) -> str | None:
    """연·월·일 중 일부만 말한 날짜를 지금 칸 값으로 채운다.
    "2008.01.01 맞아요?" → "2월 1일임" → 2008.02.01 / "2009년이요" → 2009.01.01 / "15일" → 2008.01.15.
    칸이 비어 있으면 올해를 쓴다 (신청일 등)"""
    parts: dict[str, int] = {}
    for num, unit in _DATE_PART_RE.findall(text):
        n = _ko_number(num)
        if n is not None and unit not in parts:
            parts[unit] = n
    if not parts:
        nums = _DIGITS_RE.findall(text)
        if len(nums) == 2 and all(len(x) <= 2 for x in nums):  # "2.1", "2/1"
            parts = {"월": int(nums[0]), "일": int(nums[1])}
        else:
            return None
    m = re.match(r"^(\d{4})\.(\d{2})\.(\d{2})$", (base or "").strip())
    known = {"년": int(m.group(1)), "월": int(m.group(2)), "일": int(m.group(3))} if m else {"년": today.year}
    if "년" in parts and parts["년"] < 100:
        parts["년"] += 2000 if parts["년"] <= today.year % 100 else 1900
    merged = {**known, **parts}
    if "월" not in merged or "일" not in merged:
        return None
    try:
        return date(merged["년"], merged["월"], merged["일"]).strftime("%Y.%m.%d")
    except ValueError:
        return None


def _normalize(spec: dict, answer: str, today: date, current: str | None = None) -> tuple[str | None, str | None]:
    """(칸에 넣을 값, 다시 물을 때 안내). 값이 None 이면 다시 묻는다. current 는 칸에 지금 적힌 값 (날짜 일부만 고칠 때)"""
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
        spoken = _spoken_date(answer, today)
        if spoken:
            return spoken, None
        nums = _DIGITS_RE.findall(answer)
        if len(nums) == 1 and len(nums[0]) == 8:
            nums = [nums[0][:4], nums[0][4:6], nums[0][6:]]
        if len(nums) >= 3 and len(nums[0]) == 4:
            y, m, d = int(nums[0]), int(nums[1]), int(nums[2])
            try:
                return date(y, m, d).strftime("%Y.%m.%d"), None
            except ValueError:
                pass
        partial = _partial_date(answer, current, today)
        if partial:
            return partial, None
        return None, "날짜를 다시 알려 주세요. (예: 2026.10.27)"
    if kind == "account":
        digits = "".join(_DIGITS_RE.findall(answer))
        if 10 <= len(digits) <= 16:
            kept = re.sub(r"[^\d-]", "", answer).strip("-")
            return (kept if "".join(_DIGITS_RE.findall(kept)) == digits else digits), None
        return None, "계좌번호를 다시 알려 주세요. 숫자 10~16자리예요. (예: 123-456-789012)"
    if kind == "phone":
        digits = "".join(_DIGITS_RE.findall(answer)) or _spoken_digits(answer)
        if 9 <= len(digits) <= 11 and digits.startswith("0"):
            from app.doc_spec import format_phone

            return format_phone(digits) if len(digits) >= 10 else f"{digits[:2]}-{digits[2:5]}-{digits[5:]}", None
        return None, "전화번호를 다시 알려 주세요. (예: 010-1234-5678)"
    # 이름·주소·이메일·등록번호는 형식을 보고 답만 뽑는다. 못 찾으면 다시 묻는다 (아무 말이나 적지 않음)
    value, kind = answer_extract.extract(spec, answer)
    if value is None:
        return None, answer_extract.RETRY.get(kind, "칸에 쓸 내용만 알려 주세요.")
    if answer_extract.looks_mash(value):  # "asdf", "가가가가" 같은 글자는 적지 않는다
        return None, "칸에 맞는 내용을 알려 주세요."
    return value, None


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


def _check_extract(v: object) -> bool:
    return isinstance(v, dict) and "answer" in v and (v["answer"] is None or isinstance(v["answer"], str))


def _talk_like(text: str) -> bool:
    """짧은 칸 답인데 말하는 문장처럼 보이는지 ("나 배고파", "이모가 내 줄 거예요"). 이름·주소·기관명은 보통 아니다"""
    return answer_extract.looks_sentence(text)


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
) -> str | None:
    """서술형 칸: 사용자가 말한 내용만으로 AI 가 문장을 쓴다 (revise 면 지금 초안을 그 말대로 고친다).
    쓸 내용이 아니면 초안 없이 안내 문구를 돌려준다"""
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
    if out["action"] == "tip":  # 쓸 내용이 아닌 말이라 안내만 온 경우 (잡담 등). 초안은 그대로
        return text
    row.previous_segments = row.segments
    row.segments = [{"text": text, "source": "ai"}]
    row.reused_from = None
    row.updated_at = func.now()
    return None


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
        if _proxy_gate(state, current, specs[current]):
            question = Question(
                field_id=current, label="대리 신청", text=PROXY_QUESTION, input_type="text", options=["아니요", "예"],
                current_value=None, awaiting_confirm=False, can_skip=False,
            )
        else:
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
    res = _interview(db, user, doc, body, today, doc_llm, rag)
    # 답 속 프로필 변경 후보 ("부산으로 이사했어요" 등). 확인 카드는 앱이, 반영은 사용자가 승인할 때만.
    # 승인하면 PATCH /users 가 내 정보와 그 정보를 쓰는 모든 서류 칸을 함께 바꾼다 (writer.sync_profile)
    if body.message and res.route != "crisis":
        changes = profile_signal.detect(body.message, user, today)
        answered = _answered_profile_change(db, user, doc, res)
        if answered and all(c.field != answered.field for c in changes):
            changes.append(answered)
        res.profile_changes = changes
    return res


def _answered_profile_change(db: Session, user: User, doc: Document, res: InterviewResponse):
    """방금 채운 칸이 내 이름 · 전화 칸인데 내 정보와 다르게 적었으면 내 정보도 바꿀지 묻는다
    (대리인 등 다른 사람 칸은 profile_key 가 없어 묻지 않는다)"""
    from app.schemas.chat import ProfileChange

    if res.field is None:
        return None
    spec = w._specs(doc).get(res.field.field_id) or {}
    key = spec.get("profile_key")
    if key is None and answer_extract.kind_of(spec) == "address" and not w.doc_spec.is_other_person(spec):
        key = "region"  # 내 주소 칸 → 사는 지역
    if key not in ("name", "phone", "birth_date", "region"):
        return None
    row = next((r for r in w._rows(db, doc) if r.field_id == res.field.field_id), None)
    value = w._value(row.segments).strip() if row is not None else ""
    if not value or any(seg.get("source") == "profile" for seg in row.segments):
        return None
    if key == "region":
        code = profile_signal.region_in_address(value)
        if not code or code == user.region:
            return None
        return ProfileChange(field="region", label="사는 지역", current=user.region, proposed=code, proposed_label=code)
    if key == "birth_date":
        m = re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})", value)
        if not m:
            return None
        try:
            born = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        except ValueError:
            return None
        if born == user.birth_date:
            return None
        return ProfileChange(field="birth_date", label="생년월일", current=user.birth_date.strftime("%Y.%m.%d"),
                             proposed=born.isoformat(), proposed_label=value)
    if key == "name":
        name = "".join(value.split())
        if not re.fullmatch(r"[가-힣]{2,5}", name) or name == user.name:
            return None
        return ProfileChange(field="name", label="이름", current=user.name, proposed=name, proposed_label=name)
    digits = re.sub(r"\D", "", value)
    if not re.fullmatch(r"01\d{8,9}", digits) or digits == user.phone:
        return None
    label = lambda p: f"{p[:3]}-{p[3:-4]}-{p[-4:]}" if p else None  # noqa: E731
    return ProfileChange(field="phone", label="휴대폰 번호", current=label(user.phone), proposed=digits,
                         proposed_label=label(digits))


def _interview(
    db: Session, user: User, doc: Document, body: InterviewRequest, today: date, doc_llm: DocLlm, rag: SearchPolicies,
) -> InterviewResponse:
    state = _state(doc)
    fill_today(db, w._rows(db, doc), w._specs(doc), today)
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

    if _proxy_gate(state, current, spec):
        answer = None if _is_question(msg) else _proxy_answer(msg)
        if answer is None:
            _save(db, doc, state)
            return _response(db, user, doc, state, message=PROXY_TIP if _is_question(msg) else PROXY_RETRY)
        state["proxy"] = answer
        if answer:
            _save(db, doc, state)
            return _response(db, user, doc, state)
        for r in rows:  # 직접 신청: 대리인 칸은 모두 비워 둔다
            if not r.is_completed and _is_proxy(r.field_id, specs[r.field_id]):
                _complete(r, [])
                if r.field_id in state["skipped"]:
                    state["skipped"].remove(r.field_id)
        state["current"], state["pending"] = None, None
        _save(db, doc, state)
        return _response(db, user, doc, state, message="대리인 칸은 비워 둘게요.")

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

    # 확인하려는데 [빈칸]이 남은 초안: 완료하지 않고 빈칸 내용을 묻는다 (빈칸째 제출되지 않게)
    blanks = _BLANK_RE.findall(value)
    if value and flat in CONFIRM_WORDS and blanks:
        state["pending"] = current
        _save(db, doc, state)
        return _response(db, user, doc, state, message=f"아직 [{blanks[0]}] 부분이 비어 있어요. 이 부분에 들어갈 내용을 말해 주세요.")

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

    # 답이 아닌 말 ("아니", "음", "ㅋㅋ", "막 아니"): 칸에 적지 않고 다시 묻는다
    # (선택지 칸은 선택지를 다시 보여 주는 안내가 따로 있다)
    if (not spec.get("options") and flat not in NOT_APPLICABLE_WORDS
            and not any(flat.startswith(x) for x in LATER_WORDS) and answer_extract.is_noise(msg)):
        name = spec.get("easy_label") or spec["label"]
        if answer_extract.is_no(msg) and pending:
            text = "어떻게 고칠지 말해 주세요. (예: 더 짧게, 등록금 이야기 넣어 줘)"
        elif answer_extract.is_no(msg) and value:
            text = f"그럼 '{name}'에 쓸 내용을 바르게 알려 주세요."
        else:
            text = f"'{name}'에 쓸 내용을 알려 주세요. 넘어가려면 '나중에'라고 해 주세요."
        _save(db, doc, state)
        return _response(db, user, doc, state, message=text)

    # 칸과 상관없는 잡담 ("나 배고파", "졸려"): 적지 않고, LLM 도 부르지 않고 같은 칸을 다시 묻는다
    if not spec.get("options") and answer_extract.is_small_talk(msg):
        name = spec.get("easy_label") or spec["label"]
        _save(db, doc, state)
        return _response(db, user, doc, state,
                         message=f"쓰던 내용은 저장돼 있어요. 이어서 '{name}'에 쓸 내용을 알려 주세요.")

    # 해당 없음 (필수가 아닌 칸) / 나중에 (필수 칸)
    if flat in NOT_APPLICABLE_WORDS or any(flat.startswith(x) for x in LATER_WORDS):
        if spec.get("required") is not True and flat in NOT_APPLICABLE_WORDS:
            base = _skip_repeats(rows, specs, current, state)
            if base:
                state["current"], state["pending"] = None, None
                _save(db, doc, state)
                return _response(db, user, doc, state, message=f"{base} 칸은 더 비워 둘게요.")
            _complete(row, [])
            state["pending"] = None
            _save(db, doc, state)
            return _response(db, user, doc, state, message="비워 둘게요.", field_row=row)
        if current not in state["skipped"]:
            state["skipped"].append(current)
        state["current"], state["pending"] = None, None
        _save(db, doc, state)
        return _response(db, user, doc, state, message="나중에 다시 물어볼게요.")

    # 서술형인데 쓸 내용 없이 "모르겠어요" · "알아서 써 줘": 지어내지 않게 초안 대신 첫 질문으로 이끈다
    if spec.get("essay") and _no_content(msg):
        _save(db, doc, state)
        return _response(db, user, doc, state, message=_essay_guide(spec, value))

    # 서술형: AI 가 문장으로 쓰고 확인을 기다린다
    if spec.get("essay"):
        tip = _essay_draft(db, user, doc, row, spec, msg, pending, today, doc_llm)
        if tip:
            _save(db, doc, state)
            return _response(db, user, doc, state, message=tip)
        state["pending"] = current
        _save(db, doc, state)
        return _response(db, user, doc, state, field_row=row)

    # 짧은 칸: 규칙으로 정리해 바로 완료
    normalized, retry = _normalize(spec, msg, today, value)
    if normalized is None:
        _save(db, doc, state)
        return _response(db, user, doc, state, message=retry)
    kind = answer_extract.kind_of(spec)
    free_text = not spec.get("options") and spec.get("input_type") not in ("date", "phone", "account")
    if free_text and kind == "name" and profile_signal.name_in(msg):
        # 이름 칸에 "개명했는데 정서현으로 해 줘" → 새 이름만 적는다 (프로필 변경 카드는 따로 뜬다)
        if current in state["skipped"]:
            state["skipped"].remove(current)
        _complete(row, [{"text": normalized, "source": "user"}])
        state["pending"] = None
        _save(db, doc, state)
        return _response(db, user, doc, state, message=f"'{normalized}'(으)로 적었어요.", field_row=row)
    if free_text and kind == "text" and _talk_like(normalized):  # 말끝·부탁("~로 해 줘")을 떼고도 문장이면
        # 일반 칸(기관명·관계 등)에 대화 문장: AI 가 답만 뽑고 "이렇게 적을까요?"로 확인. 못 뽑으면 다시 묻는다
        field = {"label": spec["label"], "guide": spec.get("guide") or ""}
        out = call_llm("extract_answer", doc_llm.extract_answer, _check_extract, msg, field)
        if not out["answer"]:
            name = spec.get("easy_label") or spec["label"]
            _save(db, doc, state)
            return _response(db, user, doc, state, message=f"'{name}'에 쓸 내용만 짧게 알려 주세요.")
        state["confirm_text"] = {"field": current, "text": answer_extract.strip_tail(out["answer"])}
        _save(db, doc, state)
        return _response(db, user, doc, state)
    if current in state["skipped"]:
        state["skipped"].remove(current)
    _complete(row, [{"text": normalized, "source": "user"}])
    state["pending"] = None
    _save(db, doc, state)
    return _response(db, user, doc, state, message="적었어요.", field_row=row)
