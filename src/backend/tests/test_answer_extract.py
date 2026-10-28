"""대화로 채우기: 칸 종류별 답 뽑기 (app/answer_extract.py). DB 없이 규칙만 본다."""

import pytest

from app.answer_extract import extract

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
])
def test_extract(spec, message, expected):
    assert extract(spec, message)[0] == expected
