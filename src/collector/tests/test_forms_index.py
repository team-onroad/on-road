"""서버 양식 ↔ 정책 · 필요 서류 연결표 (collector.forms_index)."""

import json

from collector.forms_index import build, fix_specs, unlinked_key

POLICIES = [
    {"policy_key": "G012", "name": "경기 자립정착금", "required_docs": [{"name": "자립정착금 사용계획서(1차, 2차)", "condition": None},
                                                                   {"name": "통장 사본", "condition": None}]},
    {"policy_key": "G024", "name": "광주시 자립정착금", "required_docs": [{"name": "1차 자립정착금 사용계획서", "condition": None},
                                                                    {"name": "1차 자립정착금 신청서", "condition": None}]},
    {"policy_key": "P001", "name": "자립준비청년 자립수당 지급", "required_docs": [{"name": "위임장", "condition": "대리 신청 시"}]},
    {"policy_key": "G032", "name": "진도 만원주택", "required_docs": [{"name": "위임장", "condition": None}]},
    {"policy_key": "G084", "name": "디딤씨앗통장", "required_docs": []},
]
SRC = {"source": {"kind": "guideline", "name": "사업안내", "url": "https://example.go.kr/a"}}


def _spec(tmp_path, fid, **kw):
    (tmp_path / f"{fid}.json").write_text(json.dumps({"form_id": fid, "policy_key": "G012", "title": fid, "source": "C:/내 파일.pdf",
                                                       "fields": [{"id": "a"}, {"id": "b"}], "checked": False, **kw},
                                                      ensure_ascii=False), encoding="utf-8")


def test_match_by_doc_name_and_policy_name(tmp_path):
    for fid in ("plan", "proxy", "didim"):
        _spec(tmp_path, fid)
    sources = {"forms": [
        {"form_id": "plan", "title": "자립정착금 사용계획서", "match": "정착금\\s*사용\\s*계획서|^자립정착금\\s*사용계획서", **SRC},
        {"form_id": "proxy", "title": "자립수당 위임장", "match": "위임장", "policy_name": "자립수당", **SRC},
        {"form_id": "didim", "title": "디딤씨앗통장 신청서", "policies": ["G084", "X999"], "scope": "common", **SRC},
        {"form_id": "not-built", "title": "아직 안 만든 양식", "policies": ["G012"], **SRC},
    ]}
    index = build(POLICIES, sources, tmp_path)
    assert index["forms"]["plan"]["policy_keys"] == ["G012", "G024"] and index["forms"]["plan"]["fields"] == 2
    assert index["forms"]["proxy"]["policy_keys"] == ["P001"]  # 만원주택의 위임장은 자립수당 위임장이 아니다
    assert index["forms"]["didim"]["docs"] == [{"policy_key": "G084", "doc_name": None}]  # 없는 정책 키는 버림
    assert "not-built" not in index["forms"]
    assert index["by_policy"]["G024"] == [{"form_id": "plan", "doc_name": "1차 자립정착금 사용계획서"}]
    # 작성 양식처럼 보이는데 서버 양식이 없는 것 (통장 사본은 작성 양식이 아님)
    assert index["unmatched_docs"] == ["1차 자립정착금 신청서", "위임장"]


def test_substitute_form_carries_notice(tmp_path):
    _spec(tmp_path, "welfare")
    sources = {"forms": [{"form_id": "welfare", "title": "사회보장급여 신청서", "match": "정착금\\s*(지원\\s*)?신청서",
                          "policy_name": "정착금", "substitute": True, "notice": "지자체마다 다를 수 있어요", **SRC}]}
    f = build(POLICIES, sources, tmp_path)["forms"]["welfare"]
    assert f["docs"] == [{"policy_key": "G024", "doc_name": "1차 자립정착금 신청서"}]
    assert f["substitute"] is True and f["notice"] == "지자체마다 다를 수 있어요"


def test_fix_specs_sets_official_source_and_unlinked_key(tmp_path):
    _spec(tmp_path, "plan")
    _spec(tmp_path, "apply")
    sources = {"forms": [{"form_id": "plan", "title": "t", **SRC}, {"form_id": "apply", "title": "t", "app_link": "G084", **SRC}]}
    assert sorted(fix_specs(sources, tmp_path)) == ["apply", "plan"]
    plan = json.loads((tmp_path / "plan.json").read_text(encoding="utf-8"))
    apply = json.loads((tmp_path / "apply.json").read_text(encoding="utf-8"))
    # 지금 백엔드가 정책 신청서로 바로 열지 않게 (index.json 으로만 연결), app_link 를 적은 것만 실제 정책 키
    assert plan["policy_key"] == unlinked_key("plan") and plan["source"] == "https://example.go.kr/a"
    assert apply["policy_key"] == "G084" and apply["fields"] == [{"id": "a"}, {"id": "b"}]
    assert fix_specs(sources, tmp_path) == []  # 다시 돌려도 그대로


def test_real_forms_are_valid_and_not_opened_as_policy_forms():
    """실제 data/forms: 연결표의 양식은 모두 formguide 형식이고, app_link 가 없는 양식은 실제 정책 키를 쓰지 않는다
    (지금 백엔드가 그 정책의 '신청서 쓰기'로 바로 열지 않게. 연결은 index.json)"""
    from collector.forms_index import FORMS_DIR, INDEX_PATH, SOURCES_PATH
    from formguide.schema import FormSpec

    sources = {s["form_id"]: s for s in json.loads(SOURCES_PATH.read_text(encoding="utf-8"))["forms"]}
    index = json.loads(INDEX_PATH.read_text(encoding="utf-8"))
    assert set(index["forms"]) <= set(sources)
    for fid in index["forms"]:
        spec = FormSpec.model_validate_json((FORMS_DIR / f"{fid}.json").read_text(encoding="utf-8"))
        assert spec.policy_key == (sources[fid].get("app_link") or unlinked_key(fid))
        assert (FORMS_DIR / f"{fid}.pdf").exists()


def test_existing_form_kept_as_is_with_keep_source(tmp_path):
    """다른 사람이 등록한 양식(P001-apply)은 연결표에만 넣고 파일은 그대로 (policy_key · source 모두)"""
    _spec(tmp_path, "P001-apply", policy_key="P001")
    before = (tmp_path / "P001-apply.json").read_bytes()
    sources = {"forms": [{"form_id": "P001-apply", "title": "자립수당 신청서", "app_link": "P001", "keep_source": True,
                          "policies": ["P001"], **SRC}]}
    assert fix_specs(sources, tmp_path) == [] and (tmp_path / "P001-apply.json").read_bytes() == before
    assert build(POLICIES, sources, tmp_path)["by_policy"]["P001"] == [{"form_id": "P001-apply", "doc_name": None}]
