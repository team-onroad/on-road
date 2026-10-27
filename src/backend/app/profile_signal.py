"""대화 속 프로필 변경 감지 (상담 AI·문서 AI 공통).

"부산으로 이사했어요", "번호 010-9876-5432로 바꿨어요", "저 이번에 퇴소했어요" 처럼 프로필과 다른 값이 나오면
바꿀 후보를 돌려준다. 앱은 확인 카드를 띄우고, 사용자가 승인해야 PATCH /users/{id} 로 반영한다 (자동 반영 없음).

- 규칙으로만 찾는다. 메시지·프로필 값을 LLM 에 보내지 않는다 (api.md 1.6)
- 바뀌었다는 말(이사·바꿨·퇴소했 등)이 있을 때만 후보로 낸다. "부산 정책 알려줘" 같은 말은 후보가 아니다
"""

import re
from datetime import date

from app.codes import USER_REGIONS
from app.schemas.chat import ProfileChange
from db.models import User

STATUS_LABELS = {"in_care": "보호 중", "leaving_soon": "퇴소 예정", "left_care": "퇴소함"}

# 지역 이름(긴 것부터) → 코드
_REGION_NAMES: list[tuple[str, str]] = sorted(
    [
        *((r, r) for r in USER_REGIONS),
        ("서울특별시", "서울"), ("서울시", "서울"), ("부산광역시", "부산"), ("부산시", "부산"),
        ("대구광역시", "대구"), ("인천광역시", "인천"), ("광주광역시", "광주"), ("대전광역시", "대전"),
        ("울산광역시", "울산"), ("세종특별자치시", "세종"), ("세종시", "세종"), ("경기도", "경기"),
        ("강원특별자치도", "강원"), ("강원도", "강원"), ("충청북도", "충북"), ("충청남도", "충남"),
        ("전북특별자치도", "전북"), ("전라북도", "전북"), ("전라남도", "전남"), ("경상북도", "경북"),
        ("경상남도", "경남"), ("제주특별자치도", "제주"), ("제주도", "제주"),
    ],
    key=lambda x: -len(x[0]),
)
_MOVE_WORDS = ("이사", "옮겼", "옮기게", "이전했", "살게됐", "살게되", "로왔", "으로왔", "에살아", "에살고", "에서살아", "에서살고")
_PHONE_RE = re.compile(r"(01[016789])[-\s.]?(\d{3,4})[-\s.]?(\d{4})")
_PHONE_WORDS = ("번호", "바꿨", "바뀌", "바꿔", "새폰", "새로")
_LEFT_WORDS = ("퇴소했", "퇴소를했", "보호종료됐", "보호종료되었", "보호가끝났", "시설에서나왔", "시설나왔")
_LEAVING_WORDS = ("곧퇴소", "퇴소예정", "퇴소할예정", "퇴소를앞두", "퇴소앞두", "퇴소하게됐", "퇴소하게되")
_DATE_RE = re.compile(r"(\d{4})\s*(?:년|[.\-/])\s*(\d{1,2})\s*(?:월|[.\-/])\s*(\d{1,2})")


def _flat(text: str) -> str:
    return re.sub(r"\s+", "", text)


def _region(message: str) -> str | None:
    """이사 간 곳. "서울에서 부산으로" 처럼 둘이면 '(으)로' 앞의 곳, 아니면 마지막에 나온 곳"""
    flat = _flat(message)
    if not any(w in flat for w in _MOVE_WORDS):
        return None
    found: list[tuple[int, str, bool]] = []  # (위치, 코드, 뒤에 '(으)로')
    taken: list[tuple[int, int]] = []
    for name, code in _REGION_NAMES:
        for m in re.finditer(re.escape(name), flat):
            s, e = m.start(), m.end()
            if any(s < te and e > ts for ts, te in taken):
                continue  # 더 긴 이름(서울특별시)에 이미 포함
            taken.append((s, e))
            found.append((s, code, flat[e:e + 2].startswith(("으로", "로"))))
    if not found:
        return None
    toward = [f for f in found if f[2]]
    return max(toward or found)[1]


def _phone(message: str) -> str | None:
    flat = _flat(message)
    m = _PHONE_RE.search(message)
    if m is None or not any(w in flat for w in _PHONE_WORDS):
        return None
    return "".join(m.groups())


def _status(message: str, current: str) -> str | None:
    flat = _flat(message)
    if current != "left_care" and any(w in flat for w in _LEFT_WORDS):
        return "left_care"
    if current == "in_care" and any(w in flat for w in _LEAVING_WORDS):
        return "leaving_soon"
    return None


def _d_date(message: str) -> date | None:
    if "퇴소" not in message:
        return None
    m = _DATE_RE.search(message)
    if m is None:
        return None
    try:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def _phone_label(p: str | None) -> str | None:
    if not p:
        return None
    return f"{p[:3]}-{p[3:-4]}-{p[-4:]}"


def detect(message: str, user: User, today: date) -> list[ProfileChange]:
    out: list[ProfileChange] = []
    region = _region(message)
    if region and region != user.region:
        out.append(ProfileChange(field="region", label="사는 지역", current=user.region, proposed=region,
                                 proposed_label=region))
    phone = _phone(message)
    if phone and phone != user.phone:
        out.append(ProfileChange(field="phone", label="휴대폰 번호", current=_phone_label(user.phone),
                                 proposed=phone, proposed_label=_phone_label(phone)))
    status = _status(message, user.status)
    if status:
        out.append(ProfileChange(field="status", label="보호 상태", current=STATUS_LABELS.get(user.status),
                                 proposed=status, proposed_label=STATUS_LABELS[status]))
    d = _d_date(message)
    new_status = status or user.status
    if d and new_status == "leaving_soon" and d >= today and d != user.d_date:
        out.append(ProfileChange(field="d_date", label="퇴소 예정일",
                                 current=user.d_date.strftime("%Y.%m.%d") if user.d_date else None,
                                 proposed=d.isoformat(), proposed_label=d.strftime("%Y.%m.%d")))
    return out
