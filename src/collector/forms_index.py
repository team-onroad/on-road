"""서버에 둔 공식 양식 ↔ 정책 · 필요 서류 연결표 (data/forms/index.json).

양식 파일(data/forms/{form_id}.json · .pdf)은 formguide.build 가 만든다. 그 양식이 어느 정책의 어느 서류인지는
data/forms/sources.json 에 적은 규칙으로 정한다 (사람이 고르는 출처 · 규칙, 정책이 바뀌어도 다시 돌리면 맞춰짐):
- match: 정책 required_docs 의 서류 이름 정규식 → 맞는 서류가 있는 정책 (policy_name 이 있으면 정책 이름에도 그 말이 있어야)
- policies: 서류 이름과 상관없이 붙이는 정책 (원문에 서류가 따로 안 적힌 정책)
- substitute + notice: 그 서류의 공식 양식이 공개되지 않아 대신 쓰는 공통 양식 (자립정착금 신청서 → 사회보장급여 신청서).
  index 에 substitute · notice 를 넣어 앱이 "지자체마다 다를 수 있어요" 안내를 함께 보여 준다
- scope common: 특정 정책 서류는 아니지만 보호 중 · 퇴소 사용자가 직접 내는 서류 (보호기간 연장 · 퇴소 신청 등)
- 양식 JSON 의 policy_key 는 'form:{form_id}' 로 둔다 (fix_specs: 백엔드가 index.json 을 읽기 전까지 기존 '신청서 쓰기' 화면이
  바뀌지 않게). 바로 정책 신청서로 열고 싶은 양식만 sources.json 에 "app_link": 정책 키

index.json 형식
    {"forms": {form_id: {title, scope, source, checked, fields, policy_keys, docs: [{policy_key, doc_name}]}},
     "by_policy": {policy_key: [{form_id, doc_name}]},
     "unmatched_docs": [서류 이름 …]   # 작성 양식처럼 보이는데 서버 양식이 없는 것 (다음에 모을 것)}

실행 (src 폴더에서): python -m collector.forms_index [--dry-run]
"""

import argparse
import re
import sys
from pathlib import Path

from collector.merge import read_json, write_json
from collector.run import POLICIES_PATH, PROJECT_ROOT

FORMS_DIR = PROJECT_ROOT / "data" / "forms"
SOURCES_PATH = FORMS_DIR / "sources.json"
INDEX_PATH = FORMS_DIR / "index.json"
# 앱에서 쓸 수 있는 '작성 양식'으로 보이는 서류 (증명서 · 통장 사본처럼 떼거나 준비하는 서류는 아님)
FORM_LIKE_RE = re.compile(r"신청서|청구서|계획서|동의서|위임장|문답표|질문지|신청확인서|불가사항 확인서")


def build(policies: list[dict], sources: dict, forms_dir: Path = FORMS_DIR) -> dict:
    forms: dict[str, dict] = {}
    by_policy: dict[str, list[dict]] = {}
    linked_docs: set[tuple[str, str]] = set()
    names = {p["policy_key"]: p.get("name") or "" for p in policies}
    for src in sources.get("forms", []):
        fid = src["form_id"]
        spec = read_json(forms_dir / f"{fid}.json", None)
        if spec is None:  # 출처만 적고 아직 formguide 로 만들지 않은 양식
            continue
        docs: list[dict] = []
        if rx := src.get("match"):
            pattern = re.compile(rx)
            for p in policies:
                if src.get("policy_name") and src["policy_name"] not in names[p["policy_key"]]:
                    continue
                for d in p.get("required_docs") or []:
                    if pattern.search(d["name"]):
                        docs.append({"policy_key": p["policy_key"], "doc_name": d["name"]})
        for key in src.get("policies", []):
            if key in names and not any(d["policy_key"] == key for d in docs):
                docs.append({"policy_key": key, "doc_name": None})
        keys = list(dict.fromkeys(d["policy_key"] for d in docs))
        forms[fid] = {"title": src["title"], "scope": src.get("scope", "policy"), "source": src["source"],
                      "checked": bool(spec.get("checked")), "fields": len(spec.get("fields") or []),
                      "policy_keys": keys, "docs": docs}
        if src.get("substitute"):  # 그 서류의 공식 양식이 아니라 대신 쓰는 공통 양식 (앱이 notice 를 함께 보여 준다)
            forms[fid].update(substitute=True, notice=src.get("notice"))
        for d in docs:
            by_policy.setdefault(d["policy_key"], []).append({"form_id": fid, "doc_name": d["doc_name"]})
            if d["doc_name"]:
                linked_docs.add((d["policy_key"], d["doc_name"]))
    unmatched = sorted({d["name"] for p in policies for d in p.get("required_docs") or []
                        if FORM_LIKE_RE.search(d["name"]) and (p["policy_key"], d["name"]) not in linked_docs})
    return {"forms": forms, "by_policy": dict(sorted(by_policy.items())), "unmatched_docs": unmatched}


def unlinked_key(form_id: str) -> str:
    return f"form:{form_id}"


def fix_specs(sources: dict, forms_dir: Path = FORMS_DIR) -> list[str]:
    """양식 JSON 두 칸을 맞춘다:
    - source: formguide.build 가 내 컴퓨터 파일 경로를 적는다 → 공식 출처 주소
    - policy_key: 지금 백엔드(ai_content.find_form)는 policy_key 가 같은 양식을 그 정책의 '신청서'로 바로 연다.
      정책과의 연결은 index.json 으로 하고(양식 하나 ↔ 정책 여럿, 정책 하나 ↔ 서류 여럿), 백엔드가 index.json 을 읽기 전까지는
      기존 화면이 바뀌지 않게 실제 정책 키가 아닌 'form:{form_id}' 로 둔다. sources.json 에 "app_link": "G012" 처럼 적은 양식만 실제 키로"""
    fixed = []
    for src in sources.get("forms", []):
        path = forms_dir / f"{src['form_id']}.json"
        spec = read_json(path, None)
        if spec is None:
            continue
        want = {"policy_key": src.get("app_link") or unlinked_key(src["form_id"])}
        if not src.get("keep_source"):  # keep_source: 다른 사람이 등록한 양식(P001-apply)은 source 칸을 건드리지 않는다
            want["source"] = src["source"]["url"]
        if any(spec.get(k) != v for k, v in want.items()):
            write_json(path, {**spec, **want})
            fixed.append(src["form_id"])
    return fixed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="서버 양식 ↔ 정책 · 필요 서류 연결표 (data/forms/index.json)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sources = read_json(SOURCES_PATH, {})
    index = build(read_json(POLICIES_PATH, []), sources)
    for fid, f in index["forms"].items():
        print(f"{fid} | {f['title'][:24]} | 칸 {f['fields']} | {'확인함' if f['checked'] else '확인 전'} | 정책 {', '.join(f['policy_keys']) or '-'}")
    print(f"정책 {len(index['by_policy'])}개에 양식 연결 · 아직 양식 없는 작성 서류 {len(index['unmatched_docs'])}종")
    if not args.dry_run:
        fixed = fix_specs(sources)
        write_json(INDEX_PATH, index)
        print(f"저장: {INDEX_PATH}" + (f" (출처 주소 · 정책 키 맞춤: {', '.join(fixed)})" if fixed else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
