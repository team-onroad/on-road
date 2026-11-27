"""정책 신청 시기 (collector.schedule). 규칙만 본다 (API · AI 없음)."""

import json
from datetime import date

from collector.schedule import build, deadline_text, parse_rounds, stage_of, timing, update, when_text

TODAY = date(2026, 11, 25)


def make_policy(key="G900", name="자립준비청년 자립정착금 지원", deadline="상시신청", target="○ 보호종료 5년 이내 자립준비청년",
                **kw) -> dict:
    p = {"policy_key": key, "name": name, "target_description": target, "support_content": "1,000만 원",
         "apply_method": "방문 신청", "original_text": f"[출처 A: 정부24]\n[신청기한]\n{deadline}\n[문의처]\n120"}
    p.update(kw)
    return p


def test_stage_follows_name_then_app_rules():
    assert stage_of(make_policy()) == "after_care"
    assert stage_of(make_policy(name="가정위탁아동 생계비 지원", target="○ 가정위탁아동")) == "in_care"
    # 대상 문장에 '대학'이 있어도 이름이 보호 중 아동이면 보호 중 (앱 규칙은 대상 문장까지 봐서 놓친다)
    assert stage_of(make_policy(name="가정위탁아동 학습활동 지원", target="○ 가정위탁아동 중 고교생 · 대학생")) == "in_care"
    assert stage_of(make_policy(name="가정위탁 종결아동 자립정착금 지원", target="○ 가정위탁 종결 아동")) == "after_care"
    assert stage_of(make_policy(name="가정위탁아동 대학진학자금 지원")) == "college"
    assert stage_of(make_policy(name="경기도 부동산 중개보수 지원")) == "housing"
    assert stage_of(make_policy(name="청년도전지원사업", target="○ 구직단념청년, 자립준비청년")) == "job"
    assert stage_of(make_policy(key="S001", name="희망 장학금")) == "college"
    assert stage_of(make_policy(name="청년 마음건강 지원", target="○ 19~34세 청년")) == "any"


def test_parse_rounds():
    assert parse_rounds("2026.04.20.~2026.05.08.") == [("2026-04-20", "2026-05-08")]
    assert parse_rounds("2026. 1. 29.(목). ~ 9. 30.(수) 프로그램별 상이") == [("2026-01-29", "2026-09-30")]
    assert parse_rounds("상시신청") == []
    assert deadline_text(make_policy(deadline="보호종료가 이루어진 당해연도")) == "보호종료가 이루어진 당해연도"
    assert deadline_text({"apply_method": "방문\n신청기한: 상하반기 공고시"}) == "상하반기 공고시"


def test_timing_kinds():
    h: dict = {}
    assert timing(make_policy(), TODAY, h)["status"] == "anytime"
    assert timing(make_policy(deadline="보호종료가 이루어진 당해연도"), TODAY, h)["status"] == "event"
    assert timing(make_policy(deadline="상하반기 공고시"), TODAY, h)["status"] == "notice"
    assert timing(make_policy(deadline="신청불요/양육보조금 지급시기와 동일"), TODAY, h)["status"] == "no_apply"
    assert timing(make_policy(deadline="접수기관 별 상이"), TODAY, h)["status"] == "unknown"
    t = timing(make_policy(deadline="2026.11.19~2026.11.29"), TODAY, h)
    assert t["status"] == "open" and when_text(t) == "지금 모집 중 (11월 29일까지)"
    t = timing(make_policy(key="G901", deadline="2026.04.20.~2026.05.08."), TODAY, h)
    assert t["status"] == "expected" and t["next_start"] == "2027-04-20" and when_text(t) == "2027년 4월쯤 모집 예상"
    # 몇 해 전 기록 하나뿐이면 날짜를 지어내지 않는다
    t = timing(make_policy(key="G902", deadline="2024.01.01~2024.02.29"), TODAY, h)
    assert t["status"] == "notice" and t["basis"] == "2024년 모집 기록만 있음"
    assert h["G900"] == [["2026-11-19", "2026-11-29"]]  # 본 회차는 쌓인다


def test_history_builds_yearly_pattern():
    h = {"G900": [["2025-03-03", "2025-03-21"]]}
    t = timing(make_policy(deadline="2026.03.02~2026.03.20"), TODAY, h)
    assert t["status"] == "expected" and "2025, 2026년 3월" in t["basis"] and len(h["G900"]) == 2


def test_build_keeps_scholarship_rounds_and_adds_stage():
    prev = {"S001": {"name": "옛 이름", "source": "한국장학재단 학자금지원정보", "status": "expected",
                     "next_start": "2027-03-02", "next_end": "2027-03-20", "basis": "2025, 2026년 3월 모집"}}
    out = build([make_policy(key="S001", name="희망 장학금"), make_policy()], TODAY, prev, {})
    assert out["S001"]["stage_label"] == "대학 입학 · 등록 때" and out["S001"]["when"] == "2027년 3월쯤 모집 예상"
    assert out["S001"]["name"] == "희망 장학금" and out["S001"]["next_start"] == "2027-03-02"
    assert out["G900"] == {"name": "자립준비청년 자립정착금 지원", "stage": "after_care", "stage_label": "보호종료 직후",
                           "deadline": "상시신청", "status": "anytime", "when": "언제든 신청"}


def test_update_writes_files(tmp_path):
    policies = tmp_path / "policies.json"
    policies.write_text(json.dumps([make_policy(deadline="2026.11.19~2026.11.29")], ensure_ascii=False), encoding="utf-8")
    scholarships = tmp_path / "scholarships.json"
    scholarships.write_text(json.dumps([make_policy(key="S001", name="희망 장학금")], ensure_ascii=False), encoding="utf-8")
    kw = dict(policies_path=policies, scholarships_path=scholarships, schedule_path=tmp_path / "s.json",
              history_path=tmp_path / "h.json")
    update(TODAY, dry_run=True, **kw)
    assert not (tmp_path / "s.json").exists()
    update(TODAY, **kw)
    saved = json.loads((tmp_path / "s.json").read_text(encoding="utf-8"))
    assert saved["G900"]["status"] == "open" and saved["S001"]["stage"] == "college"  # 장학금 파일도 함께
    assert json.loads((tmp_path / "h.json").read_text(encoding="utf-8")) == {"G900": [["2026-11-19", "2026-11-29"]]}
