"""쉬운 말 변환 (L1~L7). OpenAI 대신 가짜 LLM 을 쓴다.

실행 (src 폴더에서): backend\\.venv\\Scripts\\python -m pytest easytext/tests
"""

import json

import pytest

from easytext.checks import missing_facts, normalize, style_violations
from easytext.convert import convert_level, find_policy, render, save, source_text
from easytext.glossary import count_hard_terms, tier_violations
from easytext.levels import LEVELS, get_level, recommend_level
from easytext.prompts import CONVERT_SYSTEM, level_instructions

POLICY = {
    "policy_key": "T001",
    "name": "테스트 자립수당",
    "agency": "보건복지부",
    "contact": "129",
    "target_description": "아동복지시설 보호종료 후 5년 이내인 사람. 보호를 2년 이상 받은 사람. 원가정에 복귀한 경우 지원 불가.",
    "support_content": "매월 현금 지급",
    "support_amount": "월 50만 원",
    "support_period": "보호종료 후 5년",
    "apply_method": "주소지 행정복지센터 방문 또는 복지로 온라인 신청",
    "required_docs": [{"name": "신청서 1부", "condition": None}, {"name": "신분증", "condition": None}],
}

def S(text, *src):
    return {"text": text, "src": list(src)}


# POLICY 원문 단위: S1~S3 대상, S4 지원 내용, S5 금액, S6 기간, S7 신청 방법, S8 신청서, S9 신분증, S10 문의처
GOOD = {
    "core": [
        {"item": "받는 사람", "sentences": [
            S("아동복지시설에서 보호가 끝난 뒤 5년 이내인 사람이라면 해당될 수 있습니다.", "S1"),
            S("보호를 2년 이상 받았어야 합니다.", "S2"),
        ]},
        {"item": "받는 것", "sentences": [S("매월 50만 원을 현금으로 지급합니다.", "S4", "S5")]},
        {"item": "받을 수 없는 경우", "sentences": [S("원래 가정으로 돌아간 경우에는 받을 수 없습니다.", "S3")]},
        {"item": "신청 방법", "sentences": [S("주소지 행정복지센터에 방문하거나 복지로에서 온라인으로 신청합니다.", "S7")]},
        {"item": "서류", "sentences": [S("신청서와 신분증이 필요합니다.", "S8", "S9")]},
    ],
    "more": [
        {"item": "기간", "sentences": [S("보호가 끝난 뒤 5년 동안 받습니다.", "S6")]},
        {"item": "문의", "sentences": [S("129로 문의할 수 있습니다.", "S10")]},
    ],
}

BAD = {  # 금액 빠짐, 해요체, 원문에 없는 숫자
    "core": [{"item": "받는 것", "sentences": ["매달 70만 원을 받아요."]}],
    "more": [],
}


class FakeLLM:
    def __init__(self, *outputs):
        self.outputs = list(outputs)
        self.calls: list[list[dict]] = []

    def __call__(self, messages):
        self.calls.append(list(messages))
        return self.outputs[min(len(self.calls), len(self.outputs)) - 1]


# --- 단계 ---


@pytest.mark.parametrize("age,code", [(5, "L1"), (7, "L1"), (10, "L2"), (12, "L3"), (14, "L4"),
                                      (15, "L5"), (17, "L5"), (18, "L6"), (19, "L6"), (24, "L7"), (30, "L7")])
def test_recommend_level(age, code):
    assert recommend_level(age) == code


def test_seven_levels_and_bad_code():
    assert list(LEVELS) == [f"L{i}" for i in range(1, 8)]
    assert get_level("l6").code == "L6"
    with pytest.raises(ValueError):
        get_level("L9")


def test_prompt_has_level_rules():
    block = level_instructions(get_level("L2"), ["- '보호종료': '보호가 끝남'(으)로 바꿔 쓴다"])
    assert "L2" in block and "평균 9어절" in block and "한 달에 한 번" in block and "보호종료" in block
    assert "합니다체" in CONVERT_SYSTEM


# --- 용어 등급 ---


def test_tier_rules():
    l2, l4, l6 = get_level("L2"), get_level("L4"), get_level("L6")
    assert not tier_violations("보호종료 뒤에 받습니다.", l2.plain_tier, l2.gloss_tier)  # T3 는 그대로 + 사전 풀이
    assert tier_violations("압류방지통장 사본이 필요합니다.", l2.plain_tier, l2.gloss_tier)  # T4 는 풀어 씀
    assert not tier_violations("빚 때문에 돈이 묶이지 않는 통장(압류방지통장)이 필요합니다.", l2.plain_tier, l2.gloss_tier)
    assert not tier_violations("보호종료 뒤에 받습니다.", l4.plain_tier, l4.gloss_tier)  # L3~L5: T3 그대로
    assert tier_violations("압류방지통장 사본이 필요합니다.", l4.plain_tier, l4.gloss_tier)  # L3~L5: T4 는 풀어 씀
    assert not tier_violations("압류방지통장 사본이 필요합니다.", l6.plain_tier, l6.gloss_tier)  # L6~L7: 그대로

def test_hard_terms_ignore_gloss():
    assert count_hard_terms("보호종료 후 가정위탁") == 2
    assert count_hard_terms("보호가 끝남(보호종료)") == 0


# --- 핵심 조건·말투 ---


def test_normalize():
    assert normalize("'18.8. 이후") == "2018년8월이후"
    assert normalize("만 18살 이후") == "만18세이후"
    assert normalize("50만 원") == "50만원"


def test_boundary_words_are_kept():
    assert "2년 이상" in missing_facts(POLICY, "보호를 2년 넘게 받았어야 합니다.")
    assert "2년 이상" not in missing_facts(POLICY, "보호를 2년 이상 받았어야 합니다.")
    assert "5년 이내" not in missing_facts(POLICY, "보호가 끝나고 5년 안에 신청합니다.")


def test_missing_facts_all_found_in_good_draft():
    _, full = render(GOOD)
    assert missing_facts(POLICY, full) == []


def test_p001_core_facts():
    from easytext.checks import core_facts

    labels = {f.label for f in core_facts(find_policy("P001"))}
    assert {"2년 이상", "18세 이후", "50만원", "주기 '매월'", "신청 방법 '복지로'", "받을 수 없는 경우", "서류 '신분증'"} <= labels


def test_style():
    assert style_violations("[받는 것]\n매달 받아요.") == ["매달 받아요."]
    assert style_violations("[받는 것]\n매달 받습니다. 1. 신청서입니다.\n받는 것: 50만 원입니다.") == []


# --- 변환 ---


def test_render_paragraph_per_item_in_order():
    core, full = render(GOOD)
    paras = core.split("\n\n")
    assert [p.split("\n")[0] for p in paras] == ["[받는 것]", "[받는 사람]", "[받을 수 없는 경우]", "[신청 방법]", "[서류]"]
    assert "\n\n[더 알아보기]\n\n[기간]" in full
    numbered, _ = render({"core": [{"item": "받는 사람", "sentences": ["모두 맞아야 합니다.", "1. 가", "2. 나"]}]})
    assert numbered == "[받는 사람]\n모두 맞아야 합니다.\n1. 가\n2. 나"


def test_convert_passes_first_time():
    llm = FakeLLM(GOOD)
    r = convert_level(POLICY, get_level("L6"), llm)
    assert r["passed"] and r["attempts"] == 1 and r["verified"] is False
    assert r["metrics"]["avg_eojeol_converted"] > 0
    user = llm.calls[0][1]["content"]
    assert "[단계] L6" in user and "S5 [지원 금액] 월 50만 원" in user  # 정형 필드가 원문으로 들어감


def test_convert_retries_with_feedback():
    llm = FakeLLM(BAD, {"core": []}, {"more": []}, GOOD)  # BAD → core 채우기(빈 결과) → 빠진 원문 채우기(빈 결과) → 지적 후 GOOD
    r = convert_level(POLICY, get_level("L6"), llm)
    assert r["passed"] and r["attempts"] == 2
    assert "50만원" in r["first_attempt_missing"]
    feedback = llm.calls[3][-1]["content"]
    assert "50만원" in feedback and "합니다체" in feedback and "70" in feedback


def test_convert_gives_up_after_three_retries():
    llm = FakeLLM(BAD)
    r = convert_level(POLICY, get_level("L6"), llm)
    assert not r["passed"] and r["attempts"] == 4
    assert r["checks"]["unsupported_numbers"] == ["70"]


def test_level_limits_trigger_retry():
    long = {"core": [{"item": "받는 것", "sentences": [
        "매월 " + " ".join(["아주"] * 20) + " 50만 원을 현금으로 지급합니다."]}] + GOOD["core"][1:], "more": GOOD["more"]}
    r = convert_level(POLICY, get_level("L2"), FakeLLM(long))
    assert not r["passed"]
    assert any("긴 문장" in p for p in r["checks"]["level_problems"])


ISSUE = {"issues": [{"type": "changed", "original": "시설종사자가 신청", "converted": "본인이 신청", "reason": "신청 주체가 바뀜"}]}


def test_judge_blocks_pass_and_feeds_back():
    judge = FakeLLM(ISSUE, {"issues": []})
    llm = FakeLLM(GOOD)
    r = convert_level(POLICY, get_level("L6"), llm, judge)
    assert r["passed"] and r["attempts"] == 2 and r["judge_issues"] == []
    assert "신청 주체가 바뀜" in llm.calls[1][-1]["content"]  # 지적이 재변환에 들어감
    assert "[변환문]" in judge.calls[0][1]["content"]


def test_judge_not_called_until_auto_checks_pass():
    judge = FakeLLM({"issues": []})
    r = convert_level(POLICY, get_level("L6"), FakeLLM(BAD, GOOD), judge)
    assert r["passed"] and len(judge.calls) == 1


def test_judge_issues_left_means_not_passed():
    r = convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), FakeLLM(ISSUE))
    assert not r["passed"] and r["auto_checks_passed"]
    assert r["judge_issues"][0]["reason"] == "신청 주체가 바뀜"


def test_repeated_number_must_appear_for_each_condition():
    policy = {**POLICY, "target_description": "일반 보호종료자는 보호종료 후 5년 이내. 조기 보호종료자는 18세부터 5년 이내."}
    assert "5년 이내" in missing_facts(policy, "보호가 끝난 뒤 5년 이내입니다.")
    assert "5년 이내" not in missing_facts(policy, "보호가 끝난 뒤 5년 이내입니다. 18세부터 5년 이내입니다.")


def test_missing_details_show_source_sentence():
    from easytext.checks import missing_details

    d = missing_details(POLICY, "매월 50만 원입니다.")
    assert any(x.startswith("2년 이상") and "원문:" in x and "2년 이상" in x.split("원문:")[1] for x in d)


def test_source_text_uses_structured_fields_only():
    src = source_text(find_policy("P001"))
    assert "[지원 대상]" in src and "[필요 서류]" in src
    assert "[출처 A" not in src  # original_text 는 넣지 않음


def test_save_merges_levels(tmp_path):
    r6 = convert_level(POLICY, get_level("L6"), FakeLLM(GOOD))
    r5 = {**r6, "level": "L5"}
    save(POLICY, [r6], "fake", tmp_path)
    path = save(POLICY, [r5], "fake", tmp_path)
    data = json.loads(path.read_text(encoding="utf-8"))
    assert set(data["levels"]) == {"L5", "L6"}
    assert data["levels"]["L6"]["model"] == "fake"


def test_auto_gloss_first_use_outside_parens():
    from easytext.glossary import add_glosses

    out = add_glosses([["통장 사본(압류방지통장을 쓰는 경우)."], ["압류방지통장 확인 실패 시.", "압류방지통장 다시."]], 3, 4)
    assert out[0][0] == "통장 사본(압류방지통장을 쓰는 경우)."
    assert out[1][0].startswith("압류방지통장(빚 때문에")
    assert out[1][1] == "압류방지통장 다시."
    assert not tier_violations(" ".join(x for s in out for x in s), 3, 4)


def test_terms_are_linked_not_glossed_inline():
    draft = {"core": GOOD["core"] + [{"item": "서류", "sentences": [S("압류방지통장 사본이 필요합니다.", "S8")]}],
             "more": GOOD["more"]}
    r = convert_level(POLICY, get_level("L6"), FakeLLM(draft))
    assert "압류방지통장(" not in r["text"]  # 문장 안에 괄호 풀이를 넣지 않음
    terms = {t["term"]: t for t in r["terms"]}
    assert "압류방지통장" in terms and terms["압류방지통장"]["gloss"]
    assert "아동복지시설" in terms  # T3 이상 연결


def test_easy_levels_link_t2_terms_too():
    from easytext.glossary import term_links

    links = {t["term"] for t in term_links("주민센터(행정복지센터)에서 신청합니다.", get_level("L2").link_tier)}
    assert "행정복지센터" in links
    assert "행정복지센터" not in {t["term"] for t in term_links("행정복지센터", get_level("L6").link_tier)}

def test_compound_doc_name_accepts_easy_words():
    policy = {"target_description": "", "apply_method": "", "required_docs": [{"name": "신청인과의 관계 증빙서류"}]}
    assert missing_facts(policy, "관계를 증명하는 서류가 필요합니다.") == []


def test_long_core_moves_low_priority_items_to_more():
    many = {"core": GOOD["core"] + [{"item": "문의", "sentences": ["129로 문의합니다."]}], "more": GOOD["more"]}
    core, full = render(many, get_level("L1"))  # L1 은 core 5문장까지
    assert "[문의]" not in core and "[신청 방법]" in core
    more = full.split("[더 알아보기]")[1]
    # 옮겨진 문장이 more 의 같은 내용 문장("129로 문의할 수 있습니다.")과 합쳐져 한 번만 남는다
    assert "[문의]\n129로" in more and more.count("129로") == 1


def test_judge_missing_with_quoted_sentence_is_ignored():
    judge = FakeLLM({"issues": [{"type": "missing", "original": "대리인 온라인 신청 불가",
                                 "converted": "온라인으로 대신 신청할 수 없습니다.", "reason": "명확하지 않음"}]})
    r = convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), judge)
    assert r["passed"] and r["judge_issues"] == []


def test_eligibility_notice_always_added_once():
    from easytext.prompts import ELIGIBILITY_NOTICE

    _, full = render(GOOD, get_level("L6"))
    assert full.count(ELIGIBILITY_NOTICE) == 1 and full.rstrip().endswith(ELIGIBILITY_NOTICE)
    again = {"core": GOOD["core"], "more": GOOD["more"] + [{"item": "확인 필요", "sentences": [ELIGIBILITY_NOTICE]}]}
    assert render(again, get_level("L6"))[1].count(ELIGIBILITY_NOTICE) == 1


def test_judge_meta_note_is_ignored():
    judge = FakeLLM({"issues": [{"type": "missing", "original": "복지로와 2026년 업무매뉴얼 표기가 달라 확인 필요",
                                 "converted": "", "reason": "빠짐"}]})
    assert convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), judge)["passed"]


def test_uncited_required_unit_fails_and_is_reported():
    no_contact = {"core": GOOD["core"], "more": GOOD["more"][:1]}
    llm = FakeLLM(no_contact, {"more": []}, GOOD)  # 빠진 원문 채우기가 실패하면 지적 후 재변환
    r = convert_level(POLICY, get_level("L6"), llm)
    assert r["passed"] and r["attempts"] == 2
    assert "S10 [문의처]" in llm.calls[2][-1]["content"]


def test_missing_units_filled_without_full_retry():
    no_contact = {"core": GOOD["core"], "more": GOOD["more"][:1]}
    fill = {"more": [{"item": "문의처", "sentences": [S("129로 문의할 수 있습니다.", "S10"),
                                                     S("지원금은 100만 원입니다.", "S3")]}]}
    llm = FakeLLM(no_contact, fill)
    r = convert_level(POLICY, get_level("L6"), llm)
    assert r["passed"] and r["attempts"] == 1
    assert "[S10] (문의처) 129" in llm.calls[1][-1]["content"]
    assert "129로 문의할 수 있습니다." in r["text"]
    assert "100만" not in r["text"]  # 요청하지 않은 원문 번호를 단 문장은 받지 않는다


def test_source_units_mark_meta_optional_but_keep_exclusions():
    from easytext.convert import source_units

    units = {u["text"]: u["required"] for u in source_units(find_policy("P001"))}
    assert units["업무매뉴얼에는 재보호종료자도 명시되어 있다."] is False
    assert any(t.startswith("타 법령") and req for t, req in units.items())  # 제외 조건은 필수


def test_prompt_lists_numbered_units():
    llm = FakeLLM(GOOD)
    convert_level(POLICY, get_level("L6"), llm)
    user = llm.calls[0][1]["content"]
    assert "S1 [지원 대상]" in user and "S10 [문의처] 129" in user


def test_too_short_core_is_flagged():
    by = {b["item"]: b for b in GOOD["core"]}
    thin = {"core": [by["받는 것"], {"item": "받는 사람", "sentences": by["받는 사람"]["sentences"][:1]},
                     by["신청 방법"], by["서류"]],  # 4문장 < L6 하한 5
            "more": GOOD["more"] + [by["받을 수 없는 경우"], {"item": "받는 사람", "sentences": by["받는 사람"]["sentences"][1:]}]}
    r = convert_level(POLICY, get_level("L6"), FakeLLM(thin))
    assert any("너무 짧다" in p for p in r["checks"]["level_problems"])


def test_core_must_have_required_items():
    no_docs_in_core = {"core": [b for b in GOOD["core"] if b["item"] != "신청 방법"], "more": GOOD["more"]}  # 어디에도 없음
    r = convert_level(POLICY, get_level("L6"), FakeLLM(no_docs_in_core))
    assert any("'신청 방법'" in p for p in r["checks"]["level_problems"])


def test_missing_core_items_are_filled_by_focused_call():
    only_money = {"core": GOOD["core"][1:2], "more": GOOD["more"] + [b for b in GOOD["core"] if b["item"] != "받는 것"]}
    fill = {"core": [b for b in GOOD["core"] if b["item"] != "받는 것"]}
    llm = FakeLLM(only_money, fill)
    r = convert_level(POLICY, get_level("L6"), llm)
    assert r["passed"] and r["attempts"] == 1
    assert "만들 항목: 받는 사람, 신청 방법, 서류" in llm.calls[1][1]["content"]


def test_doc_condition_must_stay_with_doc():
    from easytext.checks import doc_condition_gaps

    policy = {"required_docs": [{"name": "위임장", "condition": "대리 신청 시"}, {"name": "신분증", "condition": None}]}
    assert doc_condition_gaps(policy, "위임장이 필요합니다.")
    assert not doc_condition_gaps(policy, "대신 신청할 때는 위임장이 필요합니다.")
    assert not doc_condition_gaps(policy, "대신 신청할 때는 대신 신청을 맡긴다는 서류가 필요합니다.")


def test_rewrites_at_easy_levels_only():
    from easytext.glossary import apply_rewrites

    assert apply_rewrites("매월 20일에 지급합니다. 지급일이 주말이면", 1) == "매월 20일에 줍니다. 돈을 받는 날이 주말이면"
    assert apply_rewrites("매월 20일에 지급합니다.", 4) == "매월 20일에 지급합니다."  # L6 등은 그대로


def test_core_item_copied_from_more_when_missing():
    no_docs_core = {"core": [b for b in GOOD["core"] if b["item"] != "서류"],
                    "more": GOOD["more"] + [b for b in GOOD["core"] if b["item"] == "서류"]}
    core, _ = render(no_docs_core, get_level("L6"))
    assert "[서류]\n신청서와 신분증이 필요합니다." in core


def test_long_core_moves_extra_sentences_to_more():
    many = {"core": [{"item": "서류", "sentences": [S(f"서류 {i}번이 필요합니다.", "S8") for i in range(1, 7)]}],
            "more": [{"item": "서류", "sentences": [S("자세한 서류입니다.", "S9")]}]}
    core, full = render(many, get_level("L1"))  # L1 core 상한 5
    assert core.count("필요합니다") == 5
    more = full.split("[더 알아보기]")[1]
    assert "서류 6번이 필요합니다." in more and "서류 3번" not in more
    assert more.index("서류 6번") < more.index("자세한 서류")  # 옮긴 문장은 more 의 같은 항목 앞쪽


def test_low_severity_judge_issue_does_not_block():
    judge = FakeLLM({"issues": [{"type": "changed", "severity": "low", "original": "메뉴 경로", "converted": "", "reason": "경로 생략"}]})
    r = convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), judge)
    assert r["passed"] and r["judge_issues"][0]["severity"] == "low"


def test_dedupe_removes_repeats_within_item_and_core_copies():
    draft = {"core": GOOD["core"],
             "more": [{"item": "받는 사람", "sentences": [S("보호를 2년 이상 받았어야 합니다.", "S2"),
                                                       S("자세한 조건입니다. 자세한 조건입니다.", "S1")]}] + GOOD["more"]}
    _, full = render(draft, get_level("L6"))
    more = full.split("[더 알아보기]")[1]
    assert "보호를 2년 이상" not in more  # core 와 같은 문장은 more 에서 뺌
    assert more.count("자세한 조건입니다.") == 1


def test_postal_needs_limit_condition():
    from easytext.checks import channel_condition_gaps

    policy = {"apply_method": "우편·팩스: 직접 방문이 제한될 때 사유 증빙을 첨부한다."}
    assert channel_condition_gaps(policy, "우편이나 팩스로도 신청할 수 있습니다.")
    assert not channel_condition_gaps(policy, "직접 방문이 어려울 때는 우편이나 팩스로 신청할 수 있습니다.")
    assert not channel_condition_gaps({"apply_method": "방문 또는 우편 신청"}, "우편으로 신청합니다.")



def test_parenthetical_appendix_is_not_a_sentence():
    assert style_violations("[신청 방법]\n복지로에서 신청합니다. (로그인 > 서비스 신청 > 복지서비스 신청).") == []


def test_copy_doc_condition_checked_in_every_mention():
    from easytext.checks import doc_condition_gaps

    policy = {"required_docs": [{"name": "통장 사본 1부", "condition": "압류방지통장 사용 시"}]}
    text = "통장 사본 1부가 필요합니다. 압류방지통장을 쓰면 통장 사본 1부가 필요합니다. 돈은 본인 통장으로 받습니다."
    assert doc_condition_gaps(policy, text)  # 첫 문장에 조건 없음
    assert not doc_condition_gaps(policy, "압류방지통장을 쓰면 통장 사본 1부가 필요합니다. 돈은 본인 통장으로 받습니다.")


def test_protection_end_rewrites_keep_particles():
    from easytext.glossary import apply_rewrites

    out = apply_rewrites("보호종료일 기준입니다. 보호종료 예정자는 미리 신청합니다. 자립수당 지급 신청서가 필요합니다.", 1)
    assert out == "보호가 끝난 날 기준입니다. 보호가 끝날 예정인 사람은 미리 신청합니다. 자립수당 신청서가 필요합니다."


def test_easy_word_mention_needs_distinct_word():
    from easytext.checks import doc_condition_gaps

    policy = {"required_docs": [{"name": "신청인과의 관계 증빙서류", "condition": "대리 신청 시"}]}
    # "증명하는 서류" 만 있고 관계·신청인이 없으면 다른 서류 이야기로 본다
    assert not doc_condition_gaps(policy, "우편으로 신청할 때는 방문이 어려운 사유를 증명하는 서류가 필요합니다.")
    assert doc_condition_gaps(policy, "신청인과의 관계를 증명하는 서류가 필요합니다.")


def test_unconditional_duplicate_doc_sentence_is_dropped():
    policy = {**POLICY, "required_docs": POLICY["required_docs"] + [{"name": "통장 사본 1부", "condition": "압류방지통장 사용 시"}]}
    draft = {"core": GOOD["core"] + [{"item": "서류", "sentences": [S("통장 사본 1부가 필요합니다.", "S11")]}],
             "more": GOOD["more"] + [{"item": "서류", "sentences": [S("압류방지통장을 쓰면 통장 사본 1부가 필요합니다.", "S11")]}]}
    _, full = render(draft, get_level("L6"), policy)
    assert full.count("통장 사본 1부가 필요합니다.") == 1  # 조건 있는 문장만 남음
    assert "압류방지통장" in full


def test_nested_parenthetical_style_ok():
    assert style_violations("통장 사본 1부가 필요합니다(압류 통장(압류방지통장)을 쓰는 경우입니다).") == []


def test_applicant_actor_must_appear():
    policy = {**POLICY, "apply_method": POLICY["apply_method"] + ". 시설 보호종료 예정자는 시설종사자가 사전신청한다."}
    assert "신청 주체 '시설종사자'" in missing_facts(policy, "보호가 끝날 예정인 사람은 미리 신청합니다.")
    assert "신청 주체 '시설종사자'" not in missing_facts(policy, "시설 선생님이 미리 신청합니다.")


def test_nested_paren_gloss_counts_as_inside():
    assert not tier_violations("통장 사본(빚 통장(압류방지통장) 또는 온라인 수급(지원을 받는 것) 실패 시).", 2, 3)



def test_source_units_split_conditions_into_clauses():
    from easytext.convert import source_units

    policy = {**POLICY, "apply_method": "시설 예정자는 시설종사자가 사전신청하며, 가정위탁 예정자는 본인이 사전신청한다."}
    texts = [u["text"] for u in source_units(policy) if u["field"] == "신청 방법"]
    assert texts == ["시설 예정자는 시설종사자가 사전신청하며", "가정위탁 예정자는 본인이 사전신청한다."]
    target = {**POLICY, "target_description": "① 18세 이후 보호종료자, 또는 ② 15세 이후 조기 보호종료자."}
    assert [u["text"] for u in source_units(target) if u["field"] == "지원 대상"] == ["① 18세 이후 보호종료자", "② 15세 이후 조기 보호종료자."]


def test_menu_path_issue_is_minor():
    judge = FakeLLM({"issues": [{"type": "changed", "severity": "high", "original": "로그인 > 서비스 신청", "reason": "경로 생략"}]})
    r = convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), judge)
    assert r["passed"]


def test_missing_doc_condition_is_attached_and_moved_to_more():
    policy = {**POLICY, "required_docs": POLICY["required_docs"] + [{"name": "위임장", "condition": "대리 신청 시"}]}
    draft = {"core": GOOD["core"] + [{"item": "서류", "sentences": [S("위임장이 필요합니다.", "S11")]}], "more": GOOD["more"]}
    r = convert_level(policy, get_level("L6"), FakeLLM(draft))
    assert "위임장" not in r["core_text"]
    assert "위임장이 필요합니다(조건: 대리 신청 시)." in r["text"]
    assert r["checks"]["condition_gaps"] == []


def test_foster_care_rewrite():
    from easytext.glossary import apply_rewrites

    assert apply_rewrites("가정위탁 예정자는 미리 신청합니다. 가정위탁에서 보호", 2) == \
        "위탁가정에서 보호가 끝날 예정인 사람은 미리 신청합니다. 위탁가정에서 보호"


def test_cited_but_dropped_content_is_caught():
    from easytext.convert import source_units, unit_content_gaps

    policy = {**POLICY, "apply_method": "시설 예정자는 시설종사자가 사전신청하며, 가정위탁 예정자는 본인 또는 대리인이 사전신청한다."}
    units = source_units(policy)
    sid = next(u["id"] for u in units if u["text"].startswith("가정위탁"))
    dropped = {"core": GOOD["core"], "more": GOOD["more"] + [
        {"item": "신청 방법", "sentences": [S("미리 신청할 수 있습니다.", sid)]}]}
    gaps = unit_content_gaps(units, dropped, get_level("L6"))
    assert any(g.startswith(sid) and "가정위탁" in g and "대리인" in g for g in gaps)
    kept = {"core": GOOD["core"], "more": GOOD["more"] + [
        {"item": "신청 방법", "sentences": [S("위탁가정 예정자는 본인이나 대신 신청하는 사람이 미리 신청합니다.", sid)]}]}
    assert not any(g.startswith(sid) for g in unit_content_gaps(units, kept, get_level("L2")))


def test_negation_must_stay_in_citing_sentence():
    from easytext.convert import source_units, unit_content_gaps

    units = source_units(POLICY)  # S3: 원가정에 복귀한 경우 지원 불가.
    flipped = {"core": [{"item": "받을 수 없는 경우", "sentences": [S("원래 가정으로 돌아간 경우입니다.", "S3")]}]}
    assert any(g.startswith("S3") and "부정 표현" in g for g in unit_content_gaps(units, flipped, get_level("L6")))


def test_unit_check_ignores_verbal_terms_and_parenthetical_parts():
    from easytext.convert import unit_content_gaps

    units = [{"id": "S1", "field": "필요 서류", "required": True, "text": "위임장 (조건: 대리 신청 시 (온라인 대리 신청 불가))"},
             {"id": "S2", "field": "지원 대상", "required": True, "text": "18세 도달자부터 적용."}]
    draft = {"more": [{"item": "서류", "sentences": [S("대신 신청할 때는 위임장이 필요합니다.", "S1")]},
                      {"item": "받는 사람", "sentences": [S("18세가 된 사람부터 적용됩니다.", "S2")]}]}
    assert unit_content_gaps(units, draft, get_level("L2")) == []


def test_judge_false_missing_claim_is_dropped():
    claim = {"issues": [{"type": "changed", "severity": "high", "original": "보호를 2년 이상 받은 사람.",
                         "converted": "", "reason": "2년 이상 조건이 빠져 있음"}]}
    r = convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), FakeLLM(claim))
    assert r["passed"]  # GOOD 에 "보호를 2년 이상 받았어야" 가 있으므로 오탐
    real = {"issues": [{"type": "changed", "severity": "high", "original": "보호를 3년 이상 받은 사람.",
                        "converted": "", "reason": "3년 이상 조건이 빠져 있음"}]}
    assert not convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), FakeLLM(real))["passed"]


def test_no_double_rewrite_inside_parentheses():
    from easytext.glossary import apply_rewrites

    once = "빚 때문에 돈이 묶이지 않는 통장(압류방지통장)을 쓰는 경우"
    assert apply_rewrites(once, 3) == once


def test_orphan_condition_sentence_is_dropped_after_attach():
    policy = {**POLICY, "required_docs": POLICY["required_docs"] + [
        {"name": "통장 사본 1부", "condition": "압류방지통장 사용 시 또는 계좌번호 확인 5회 이상 실패 시"}]}
    draft = {"core": GOOD["core"], "more": GOOD["more"] + [{"item": "서류", "sentences": [
        S("압류방지통장을 쓰거나 계좌 확인에 5회 이상 실패할 때 필요합니다.", "S11"),
        S("통장 사본 1부가 필요합니다.", "S11"),
        S("압류방지통장·계좌 확인 실패 등에는 추가 서류가 필요합니다.", "S11")]}]}
    _, full = render(draft, get_level("L6"), policy)
    assert "실패할 때 필요합니다." not in full  # 서류 이름 없는 조건 문장은 빠짐
    assert "통장 사본 1부가 필요합니다(조건:" in full
    assert "추가 서류가 필요합니다." in full  # 서류를 가리키는 말이 있는 문장은 남음


def test_dangling_reference_and_example_flagged():
    from easytext.checks import dangling_sentences

    assert dangling_sentences("[받는 사람]\n이 경우 2018년 8월 이후여야 합니다.")
    assert dangling_sentences("[서류]\n증빙 서류가 필요합니다. 예를 들면, 재학증명서 등이 있습니다.")
    assert not dangling_sentences("[받는 사람]\n18세 이후 끝난 사람입니다. 이 경우 2018년 8월 이후여야 합니다.")


def test_dedupe_keeps_sentence_referred_by_next():
    core = [("받는 사람", ["18세 이후 끝난 사람은 5년 이내입니다."])]
    from easytext.convert import _dedupe

    _, more = _dedupe(core, [("받는 사람", ["18세 이후 끝난 사람은 5년 이내입니다. 이 경우 2018년 8월 이후여야 합니다."])])
    assert more[0][1][0].startswith("18세 이후")


def test_person_subject_must_stay():
    from easytext.convert import unit_content_gaps

    units = [{"id": "S1", "field": "신청 방법", "required": True,
              "text": "시설 보호종료 예정자는 시설종사자가 30일 이내 사전신청하며"}]
    lost = {"more": [{"item": "신청 방법", "sentences": [S("시설종사자는 보호종료 30일 이내에 사전신청할 수 있습니다.", "S1")]}]}
    assert any("주어 '예정자'" in g for g in unit_content_gaps(units, lost, get_level("L3")))
    kept = {"more": [{"item": "신청 방법", "sentences": [S("시설 보호종료 예정자는 시설종사자가 30일 이내에 사전신청합니다.", "S1")]}]}
    assert unit_content_gaps(units, kept, get_level("L3")) == []


def test_easy_negation_and_multiword_term_accepted():
    from easytext.convert import unit_content_gaps

    units = [{"id": "S1", "field": "지원 기간", "required": True, "text": "재보호 중 지급 중지·종료 후 재개."},
             {"id": "S2", "field": "지원 대상", "required": True, "text": "18세 이후 만기·연장 보호종료자."}]
    draft = {"more": [{"item": "기간", "sentences": [S("다시 보호를 받는 동안에는 돈이 멈춥니다.", "S1")]},
                      {"item": "받는 사람", "sentences": [S("18세 이후 보호가 연장되었다가 끝난 사람입니다.", "S2")]}]}
    assert unit_content_gaps(units, draft, get_level("L2")) == []


def test_postal_condition_attached():
    policy = {**POLICY, "apply_method": POLICY["apply_method"] + ". 우편·팩스: 직접 방문이 제한될 때 사유 증빙을 첨부한다."}
    draft = {"core": GOOD["core"], "more": GOOD["more"] + [{"item": "신청 방법", "sentences": [S("우편이나 팩스로도 신청할 수 있습니다.", "S7")]}]}
    _, full = render(draft, get_level("L6"), policy)
    assert "우편이나 팩스로도 신청할 수 있습니다(직접 방문이 제한될 때)." in full


def test_vague_judge_issue_is_minor():
    judge = FakeLLM({"issues": [{"type": "changed", "severity": "high", "original": "온라인 기본 첨부",
                                 "reason": "'기본 첨부 서류'로 표현되어 의미가 다소 모호함"}]})
    assert convert_level(POLICY, get_level("L6"), FakeLLM(GOOD), judge)["passed"]


def test_core_trim_splits_multi_sentence_strings():
    many = {"core": [{"item": "받는 것", "sentences": [S("가입니다. 나입니다. 다입니다. 라입니다.", "S5")]},
                     {"item": "받는 사람", "sentences": [S("마입니다. 바입니다. 사입니다.", "S1")]}]}
    core, _ = render(many, get_level("L1"))  # L1 상한 5
    from easytext.checks import sentences
    assert len(sentences(core)) <= 5


def test_best_attempt_kept_when_all_fail():
    worse = {"core": GOOD["core"][:1], "more": []}
    calls = []

    def gen(m):
        calls.append(1)
        return BAD if len(calls) == 1 else worse
    result = convert_level(POLICY, get_level("L6"), gen)
    assert not result["passed"]
    assert len(result["checks"]["missing_facts"]) <= len(convert_level(POLICY, get_level("L6"), lambda m: worse)["checks"]["missing_facts"])


def test_tidy_merges_trailing_condition_and_fixes_ending():
    from easytext.convert import _tidy
    assert _tidy("위임장이 필요합니다. (대리 신청 시) 신분증이 필요합니다.") == "위임장이 필요합니다(대리 신청 시). 신분증이 필요합니다."
    assert _tidy("129로 문의하세요.") == "129로 문의하시기 바랍니다."


def test_generic_doc_key_needs_distinct_word():
    from easytext.checks import DocCondition, normalize
    dc = DocCondition("증빙서류", (), ("신청인", "관계"), (("대리",),), "대리 신청 시")
    assert not dc.mentioned(normalize("방문 제한 사유 증빙 서류가 필요합니다."))
    assert dc.mentioned(normalize("신청인과의 관계 증빙서류가 필요합니다."))


def test_drop_covered_keeps_detailed_sentence():
    from easytext.convert import _drop_covered
    blocks = [("서류", ["재무상담 확인서나 자립교육 이수증 중 하나가 필요합니다. "
                        "국민연금공단 희망출발 재무상담 확인서, 국가아동권리보장원 온라인 자립교육 이수증 중 하나가 필요합니다."])]
    assert _drop_covered(blocks) == [("서류", ["국민연금공단 희망출발 재무상담 확인서, 국가아동권리보장원 온라인 자립교육 이수증 중 하나가 필요합니다."])]


def test_drop_covered_keeps_different_details():
    from easytext.convert import _drop_covered
    a = "우편이나 팩스로도 신청할 수 있습니다(직접 방문이 제한될 때)."
    b = "우편이나 팩스로도 신청할 수 있으며, 방문이 어려운 이유를 증명하는 서류가 필요합니다."
    assert _drop_covered([("신청 방법", [a, b])]) == [("신청 방법", [a, b])]
    # 부정 표현이나 숫자가 다르면 같은 내용이 아니다
    c, d = "대리인은 온라인으로 신청할 수 없습니다.", "대리인은 온라인으로 신청할 수 있습니다."
    assert _drop_covered([("신청 방법", [c, d])]) == [("신청 방법", [c, d])]
    e, f = "5년 이내여야 합니다.", "보호가 끝난 후 3년 이내여야 합니다."
    assert _drop_covered([("받는 사람", [e, f])]) == [("받는 사람", [e, f])]


def test_drop_covered_same_content_keeps_first():
    from easytext.convert import _drop_covered
    a = "18세 이후 보호가 끝난 사람은 2018년 8월 이후여야 합니다."
    b = "18세 이후 만기 또는 연장 보호가 끝난 보호종료자는 보호가 끝난 후 5년 이내여야 하며, 2018년 8월 이후여야 합니다."
    assert _drop_covered([("받는 사람", [a, b])]) == [("받는 사람", [b])]
