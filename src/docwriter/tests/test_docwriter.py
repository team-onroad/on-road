"""문서 AI 실제 함수 (docwriter.llm). OpenAI 대신 가짜 LLM 을 쓴다.

실행 (src 폴더에서): backend\\.venv\\Scripts\\python -m pytest docwriter/tests
"""

import json

import pytest

from docwriter.llm import DocwriterFormatError, assist_field, extract_answer, read_form, route_message


class FakeLLM:
    def __init__(self, output):
        self.output = output
        self.calls: list[list[dict]] = []

    def __call__(self, messages):
        self.calls.append(list(messages))
        return self.output


FIELD = {"label": "지원 동기", "guide": "지원한 이유를 써요", "outline": ["계기", "준비", "계획"], "essay": True}


def req(**kw):
    base = {"document_title": "입사지원서", "field": FIELD, "current_text": "", "prompt": "예시 써 줘",
            "stage": "youth", "status": "left_care", "context": None, "other_fields": []}
    base.update(kw)
    return base


# --- 라우터 ---


@pytest.mark.parametrize("out,expected", [
    ({"route": "document", "doc_hint": "이력서"}, {"route": "document", "doc_hint": "이력서"}),
    ({"route": "document", "doc_hint": ""}, {"route": "document", "doc_hint": None}),
    ({"route": "other", "doc_hint": "이력서"}, {"route": "other", "doc_hint": None}),
    ({"route": "모름"}, {"route": "other", "doc_hint": None}),
    ([], {"route": "other", "doc_hint": None}),
])
def test_route_chat(out, expected):
    assert route_message("이력서 써 줘", [], {"screen": "chat"}, generate=FakeLLM(out)) == expected


@pytest.mark.parametrize("out,expected", [
    ({"route": "policy"}, "policy"), ({"route": "counsel"}, "counsel"), ({"route": "field"}, "field"),
    ({"route": "document"}, "field"), ({}, "field"),
])
def test_route_document(out, expected):
    fake = FakeLLM(out)
    ctx = {"screen": "document", "document_title": "장학금 신청서", "field_label": "신청 사유"}
    assert route_message("소득 기준이 뭐야?", [], ctx, generate=fake)["route"] == expected
    assert "장학금 신청서" in fake.calls[0][0]["content"] and "신청 사유" in fake.calls[0][0]["content"]


# --- 칸 작성 도우미 ---


def test_assist_prompt_has_field_and_rules():
    fake = FakeLLM({"action": "draft", "text": "저는 [회사 이름]에 지원합니다."})
    out = assist_field(req(other_fields=[{"label": "자기소개", "text": "성실합니다."}]), generate=fake)
    system, user = fake.calls[0][0]["content"], json.loads(fake.calls[0][1]["content"])

    assert out == {"action": "draft", "text": "저는 [회사 이름]에 지원합니다."}
    assert "지원 동기" in system and "계기 → 준비 → 계획" in system and "지어내지 않는다" in system
    assert "「자기소개」: 성실합니다." in system
    assert user == {"지금_쓴_내용": "(비어 있음)", "요청": "예시 써 줘"}


@pytest.mark.parametrize("out", [{"action": "x", "text": "a"}, {"action": "draft", "text": "  "}, [], {"action": "tip"}])
def test_assist_bad_output_raises(out):
    with pytest.raises(DocwriterFormatError):
        assist_field(req(), generate=FakeLLM(out))


def test_revise_on_empty_is_draft():
    out = assist_field(req(), generate=FakeLLM({"action": "revise", "text": "문장"}))
    assert out["action"] == "draft"


def test_revise_keeps_user_placeholders():
    fake = FakeLLM({"action": "revise", "text": "OO고등학교에서 공부했습니다."})
    out = assist_field(req(current_text="[학교 이름]에서 공부했습니다.", prompt="다듬어 줘"), generate=fake)
    assert out["action"] == "tip"
    assert "[학교 이름]" in out["text"]


def test_text_is_capped():
    out = assist_field(req(max_chars=10), generate=FakeLLM({"action": "draft", "text": "가" * 50}))
    assert out["text"] == "가" * 10


def test_force_action_for_interview():
    fake = FakeLLM({"action": "outline", "text": "혼자 살며 등록금이 부담되어 신청합니다."})
    out = assist_field(req(prompt="이 칸에 들어갈 문장을 써 줘. 내가 넣고 싶은 내용: 등록금 부담", force_action="draft"),
                       generate=fake)
    assert out["action"] == "draft"  # 대화형 작성은 바로 칸에 들어갈 글
    assert '"draft"로 정해져 있다' in fake.calls[0][0]["content"]
    tip = assist_field(req(force_action="draft"), generate=FakeLLM({"action": "tip", "text": "안내"}))
    assert tip["action"] == "tip"  # 안내만 온 경우는 그대로 (백엔드가 칸을 바꾸지 않음)


def test_extract_answer():
    fake = FakeLLM({"answer": "이모"})
    out = extract_answer("우리 이모가 대신 내 줄 거예요", {"label": "대상아동과의 관계", "guide": "관계"}, generate=fake)
    assert out == {"answer": "이모"}
    assert "대상아동과의 관계" in fake.calls[0][0]["content"]
    assert extract_answer("날씨 좋다", {"label": "관계"}, generate=FakeLLM({"answer": None})) == {"answer": None}
    assert extract_answer("x", {"label": "관계"}, generate=FakeLLM([])) == {"answer": None}


def test_read_form_photo_and_text():
    fake = FakeLLM({"title": "신청서", "form_text": "성명 연락처", "fields": [{"label": "성명"}, {"label": ""}, "x"]})
    out = read_form(None, ["data:image/png;base64,AAA"], generate=fake)
    assert out == {"title": "신청서", "form_text": "성명 연락처", "fields": [{"label": "성명"}]}
    parts = fake.calls[0][1]["content"]
    assert parts[1]["image_url"]["url"] == "data:image/png;base64,AAA"

    fake = FakeLLM({"title": "", "fields": [{"label": "주소"}]})
    out = read_form("주소 칸이 있는 양식", [], generate=fake)
    assert out["form_text"] == "주소 칸이 있는 양식" and "주소 칸이 있는 양식" in fake.calls[0][1]["content"]
    with pytest.raises(DocwriterFormatError):
        read_form("x", [], generate=FakeLLM([]))
