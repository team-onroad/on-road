"""대화로 채우기: 칸 종류별 답 뽑기 (app/answer_extract.py). DB 없이 규칙만 본다."""

import pytest

from app.answer_extract import extract, is_small_talk, looks_sentence

NAME = {"label": "성 명"}
ADDRESS = {"label": "주 소", "input_type": "address"}
EMAIL = {"label": "E-mail"}
BIZNO = {"label": "보호기관 사업자등록번호* (고유번호)"}
TEXT = {"label": "보호기관명*"}


@pytest.mark.parametrize("spec,message,expected", [
    (NAME, "정서현", "정서현"),
    (NAME, "정서현이에요", "정서현"),
    (NAME, "제 이름은 정서현입니다", "정서현"),
    (NAME, "저는 정서현이에요", "정서현"),
    (NAME, "나 개명했는데 정서현으로 해줘", "정서현"),
    (NAME, "Kim Minji", "Kim Minji"),
    (NAME, "몰라", None),
    (NAME, "오늘 날씨 좋다", None),
    (ADDRESS, "서울특별시 OO구 OO로 12, 101동 202호", "서울특별시 OO구 OO로 12, 101동 202호"),
    (ADDRESS, "부산 해운대구 우동 123에 살아요", "부산 해운대구 우동 123"),
    (ADDRESS, "경기도 수원시 팔달구 효원로 1번길 3이요", "경기도 수원시 팔달구 효원로 1번길 3"),
    (ADDRESS, "서울", None),
    (ADDRESS, "배고파", None),
    (EMAIL, "제 메일은 a.b@gmail.com 이에요", "a.b@gmail.com"),
    (EMAIL, "없어", None),
    (BIZNO, "1238212345", "123-82-12345"),
    (BIZNO, "몰라요", None),
    (TEXT, "OO아동복지원이에요", "OO아동복지원"),
    # 조사·부탁 말끝 떼기
    (NAME, "정서현으로", "정서현"),
    (NAME, "정서현으로 해줘", "정서현"),
    (NAME, "정서현이라고 써 주세요", "정서현"),
    (NAME, "이름 정서현으로 바꿔줘", "정서현"),
    (NAME, "김민지로", "김민지"),
    (NAME, "김민지라고 해요", "김민지"),
    (NAME, "정 서현", "정서현"),
    (NAME, "남궁민수로 해줘", "남궁민수"),
    (NAME, "알아서 해줘", None),
    (TEXT, "OO복지원으로 해줘", "OO복지원"),
    (TEXT, "카카오뱅크로", "카카오뱅크"),
    (TEXT, "이모라고 써줘", "이모"),
    (TEXT, "OO대학교로 할게요", "OO대학교"),
    (TEXT, "알아서 해줘", None),
])
def test_extract(spec, message, expected):
    assert extract(spec, message)[0] == expected


@pytest.mark.parametrize("message, expected", [
    ("나 배고파", True), ("배고파", True), ("졸려요", True), ("OO아동복지원", False), ("이모", False),
])
def test_small_talk(message, expected):
    assert is_small_talk(message) is expected


@pytest.mark.parametrize("message, expected", [
    ("나 배고파", True), ("배고파", True), ("이모가 내 줄 거예요", True), ("다녔었어요", True),
    ("OO아동복지원", False), ("이모", False), ("어머니", False), ("새마을금고", False), ("OO대학교", False),
])
def test_looks_sentence(message, expected):
    assert looks_sentence(message) is expected


# ---- 음성으로 말한 답 (이어 말하기 · 한글 숫자 · 존댓말 문장) ----
from datetime import date as _date  # noqa: E402

from app.services.interview import _no_content, _normalize  # noqa: E402

_T = _date(2026, 11, 17)


@pytest.mark.parametrize("message,expected", [
    ("제 이름은 김민지고 2006년생이에요", "김민지"),
    ("저 김민지라고 해요", "김민지"),
    ("김 민 지", "김민지"),
])
def test_spoken_name(message, expected):
    assert _normalize({"field_id": "name", "label": "성명", "input_type": "text"}, message, _T)[0] == expected


@pytest.mark.parametrize("message,expected", [
    ("2006년 3월 5일이요", "2006.03.05"),
    ("06년 3월 5일", "2006.03.05"),
    ("98년 12월 1일", "1998.12.01"),
    ("이천육년 삼월 오일", "2006.03.05"),
    ("3월 5일 2006년", "2006.03.05"),
])
def test_spoken_date(message, expected):
    assert _normalize({"field_id": "birth", "label": "생년월일", "input_type": "date"}, message, _T)[0] == expected


def test_spoken_phone_digits():
    assert _normalize({"field_id": "p", "label": "연락처", "input_type": "phone"}, "공일공 일이삼사 오육칠팔", _T)[0] == "010-1234-5678"


def test_text_keeps_polite_sentence():
    assert _normalize({"field_id": "c", "label": "경력사항", "input_type": "text"}, "카페에서 6개월 일했어요", _T)[0] == "카페에서 6개월 일했어요"


@pytest.mark.parametrize("message", ["잘 모르겠어요", "알아서 써줘", "글쎄요"])
def test_no_content(message):
    assert _no_content(message)
