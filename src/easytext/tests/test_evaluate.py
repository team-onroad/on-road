"""쉬운 말 정량 평가 도구 (easytext.evaluate). API 없음."""

import json
import shutil
from datetime import date

from easytext import evaluate


def test_run_on_saved_p001(tmp_path):
    shutil.copy(evaluate.EASY_DIR / "P001.json", tmp_path / "P001.json")
    summary, rows, base = evaluate.run(easy_dir=tmp_path, out_dir=tmp_path / "out", today=date(2026, 11, 22))
    assert summary["대상"] == {"정책 수": 1, "단계 수": 7}
    assert {r["level"] for r in rows} == {f"L{i}" for i in range(1, 8)}
    assert all(r["facts_total"] > 0 for r in rows)
    assert base.with_suffix(".md").read_text(encoding="utf-8").startswith("# 쉬운 말 변환 평가 (2026-11-22)")


def test_missing_fact_and_number_are_detected(tmp_path):
    data = json.loads((evaluate.EASY_DIR / "P001.json").read_text(encoding="utf-8"))
    lv = data["levels"]["L6"]
    bad = lv["text"].replace("50만 원", "").replace("20일", "25일")  # 금액을 빼고, 원문에 없는 날짜를 넣음
    data["levels"] = {"L6": {**lv, "text": bad, "core_text": bad}}
    (tmp_path / "P001.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    summary, rows, _ = evaluate.run(easy_dir=tmp_path, out_dir=tmp_path / "out")
    assert any("50만" in m for m in rows[0]["facts_missing"])
    assert "25" in rows[0]["unsupported_numbers"]
    assert summary["정확성"]["원문에 없는 숫자가 있는 단계 수"] == 1 and rows[0]["passed"] is False


# ---------------- 정부24 원문 표기 (수집 정책 변환에서 찾은 검사기 오류) ----------------


def test_short_year_and_amount_are_not_mangled():
    from easytext.checks import expand_dates

    # "'20년 5백만원" 을 "2020년 5월백만원" 으로 망가뜨려 AI 가 "5월백만 원" 이라고 쓰던 것 (G012)
    assert expand_dates("'20년 5백만원, '21년 10백만원") == "2020년 500만원, 2021년 1000만원"  # 달로 읽지 않고, 백만은 만 단위로
    assert expand_dates("'23년부터 시행") == "2023년부터 시행"  # 연도만 (G018 · G038)
    assert expand_dates("'18.8. 이후 보호종료") == "2018년 8월 이후 보호종료"  # 원래 되던 것


def test_sentences_keep_decimals_and_urls():
    from easytext.checks import sentences

    # 소수점 · 주소에서 잘라 '합니다체 아님' · '원문에 없는 숫자 4, 5' 로 걸리던 것 (G017 · G019)
    assert sentences("평점 4.5 만점 기준 1.5 이상입니다. 주소는 https://youth-up.go.kr 입니다.") == [
        "평점 4.5 만점 기준 1.5 이상입니다.", "주소는 https://youth-up.go.kr 입니다."]


def test_duplicate_source_sentence_counts_once():
    from easytext.checks import missing_facts

    # 지원금액 칸 = 지원내용 칸이라 "30%" 를 두 번 쓰라고 하던 것 (W002)
    p = {"support_content": "임대료 : 시중 시세의 30%, 임대보증금 100만원", "support_amount": "임대료 : 시중 시세의 30%, 임대보증금 100만원",
         "apply_method": "", "target_description": ""}
    assert missing_facts(p, "임대료는 시중 시세의 30%입니다. 임대보증금은 100만 원입니다.") == []


def test_hundred_man_unit_matches_plain_unit():
    from easytext.checks import expand_dates
    from rag.guard import unsupported_numbers

    # AI 가 "10백만원" 을 "1,000만 원" 으로 바르게 바꾼 것을 원문에 없는 숫자로 보지 않는다 (G012)
    # 약식 연도 뒤 목록의 따옴표 없는 연도("22년")도 연도로 푼다
    assert expand_dates("'21년 10백만원, 22년 이후 15백만원") == "2021년 1000만원, 2022년 이후 1500만원"
    assert not unsupported_numbers(expand_dates("1,000만 원과 1,500만 원"), expand_dates("10백만원, 15백만원"))


# --- 정부24 표기 오탐 (11/23, 쉬운 말이 안 나오던 수집 정책 25개 중 검사기 탓이던 것) ---


def test_gov24_number_formats_match():
    from easytext.checks import expand_dates, normalize
    from rag.guard import unsupported_numbers

    def ok(converted: str, source: str) -> bool:
        return not unsupported_numbers(expand_dates(converted), expand_dates(source))

    assert ok("2024년 1월 1일부터 2024년 2월 29일까지", "신청기한: 2024.01.01~2024.02.29")  # G020
    assert ok("2026년 4월 20일부터 2026년 5월 8일까지", "신청기한: 2026.04.20.~2026.05.08.")  # G095
    assert ok("2024년 2월 9일 이후", "시행(`24.2.9.)이후")  # 역따옴표 (G027)
    assert ok("하루에 2,400원", "1일/2.4천원")  # G073
    assert ok("1인당 200만 원", "1인당 2,000천원")  # G095
    assert ok("전화번호는 031-839-2936입니다", "사회복지과/0318392936")  # G066
    assert not ok("2024년 3월 1일", "2024.01.01")  # 다른 날짜는 그대로 잡는다
    assert expand_dates("'23년부터, 10년 거주") == "2023년부터, 10년 거주"  # 기간은 연도로 안 바꿈
    assert expand_dates("4.5%") == "4.5%" and normalize("1,000만원") == "1000만원"
    assert normalize("3, 6, 9, 12월") == "3·6·9·12월"  # "36912월" 로 붙지 않게


def test_gov24_fact_false_alarms():
    from easytext.checks import core_facts, missing_facts

    base = {"policy_key": "T", "name": "t", "agency": "기관", "apply_method": "", "required_docs": []}
    # 같은 줄이 대상 칸 · 지원내용 칸에 되풀이되고 지원금액 칸이 그 일부 → 한 번만 쓰면 된다 (G007)
    block = "○ 보호종료 5년 이내 아동(보호 종료 전 30일 이내 신청)\n- 지급내용: 월 50만원/인(매월 20일 지급)"
    p = {**base, "target_description": block, "support_content": block, "support_amount": "월 50만원"}
    assert missing_facts(p, "보호종료 5년 이내, 종료 전 30일 이내 신청. 매월 50만 원을 매월 20일에 받습니다.") == []
    # 거의 같은 문장 (G023)
    p = {**base, "target_description": "- 6개월 이상 취업 및 직업훈련 참여 이력이 없는 청년",
         "support_content": "- 6개월 이상 취업 및 직업훈련 이력이 없는 청년"}
    assert missing_facts(p, "6개월 이상 일하거나 직업훈련을 받은 적이 없는 청년입니다.") == []
    # 쉬운 말 수량 (G027 · G073), "1세대" 는 나이가 아님 (G059)
    p = {**base, "support_content": "자립정착금 1회 지급. 교통비 1일 2.4천원. 1세대에 2명 이상이면 문의"}
    assert missing_facts(p, "자립정착금을 한 번 받습니다. 교통비는 하루에 2,400원입니다. 한 집에 2명 이상이면 문의합니다.") == []
    assert "1세" not in [f.label for f in core_facts(p)]
    assert "1회" in missing_facts(p, "자립정착금을 받습니다. 교통비는 하루에 2,400원입니다. 2명 이상이면 문의합니다.")
    # 사업 예산은 받는 사람의 조건이 아님 (G026)
    p = {**base, "support_content": "○ 월 50만원 지급\n○ 사업예산 :\n- 2026년 총 : 659,900천원(국 579,900 / 시 80,000)"}
    assert [f.label for f in core_facts(p)] == ["50만원"]


# --- 용어 지표 (11/23): 단계 묶음별 · 합계 기준 · 밀도 ---


def test_term_summary_splits_levels_and_uses_totals():
    from easytext.evaluate import _term_summary

    rows = [  # L6 은 원래 용어 유지 단계, L4 는 바꾸는 단계
        {"keeps_terms": True, "hard_terms_source": 10, "hard_terms_converted": 5, "term_reduction": 0.5,
         "term_density_source": 20.0, "term_density_converted": 18.0},
        {"keeps_terms": True, "hard_terms_source": 1, "hard_terms_converted": 1, "term_reduction": 0.0,
         "term_density_source": 5.0, "term_density_converted": 6.0},
        {"keeps_terms": False, "hard_terms_source": 10, "hard_terms_converted": 1, "term_reduction": 0.9,
         "term_density_source": 20.0, "term_density_converted": 2.0},
    ]
    s = _term_summary(rows)
    assert s["어려운 용어(T3 이상) 감소율 · 원래 용어 유지 단계 L6~L7 (합계 기준, 요약으로 줄어든 몫)"] == round(1 - 6 / 11, 3)
    assert s["어려운 용어(T3 이상) 감소율 · L1~L5 (합계 기준)"] == 0.9
    assert s["어려운 용어 감소율 (정책별 평균, 원문 용어 3개 이상)"] == 0.7  # 용어 1개짜리는 평균에서 빠짐
    assert s["어려운 용어 밀도 · L1~L5 변환문 (1,000자당, 평균)"] == 2.0


def test_density_ignores_length():
    from easytext.checks import metrics

    src = "가정위탁 보호종료 아동은 신청합니다. " * 4
    m = metrics(src, "가정위탁 보호종료 아동은 신청합니다.")  # 요약해서 짧아짐 · 용어는 그대로
    assert m["term_reduction"] == 0.75 and m["term_density_source"] == m["term_density_converted"]


def test_term_candidates(tmp_path):
    from easytext import term_candidates as tc

    easy = tmp_path / "easy"
    easy.mkdir()
    for k in ("G1", "G2", "G3"):
        text = "양육보조금을 받습니다. 가정위탁 아동은 주민센터에서 신청합니다. 북구청에 문의할 수 있습니다."
        (easy / f"{k}.json").write_text(json.dumps({"levels": {"L6": {"text": text}}}, ensure_ascii=False), encoding="utf-8")
    rows = tc.candidates({}, ["G1", "G2", "G3"], easy_dir=easy, min_docs=3)
    words = [r["word"] for r in rows]
    assert "양육보조금" in words and "주민센터" in words
    assert "가정위탁" not in words  # 이미 사전에 있음
    assert not {"받습니다", "신청합니다", "북구청", "아동"} & set(words)  # 동사 꼴 · 관청 이름 · 쉬운 낱말
