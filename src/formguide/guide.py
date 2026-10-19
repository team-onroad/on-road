"""작성 항목 JSON + 사용자 프로필 → 항목별 안내 (쉬운 설명·예시 답변·미리 채운 값).

프로필 값은 코드로만 채우고 LLM 에 보내지 않는다 (api.md 1.6). 민감 항목은 값을 받지도 채우지도 않는다.

실행 (src 폴더에서):
    backend\\.venv\\Scripts\\python -m formguide.guide P001-apply --demo-user
    backend\\.venv\\Scripts\\python -m formguide.guide P001-apply --json
"""

import argparse
import json
from datetime import date
from pathlib import Path

from formguide.schema import FormField, FormSpec
from rag.settings import PROJECT_ROOT

FORMS_DIR = PROJECT_ROOT / "data" / "forms"

HOW = {
    "profile": "앱 정보로 미리 채움 (맞는지 확인)",
    "user": "직접 입력",
    "handwrite": "양식에 직접 손으로 작성 (앱에 입력하지 않아요)",
    "office": "담당 공무원이 작성 (비워 두세요)",
}
NOT_CHECKED_NOTICE = "아직 사람이 원본 양식과 대조하지 않은 안내예요. 공식 양식과 함께 확인해 주세요."
FINAL_NOTICE = "예시 답변은 가상의 예시예요. 실제 작성은 공식 양식이나 온라인 신청 화면에서 본인 정보로 해 주세요."
# 데모 사용자 (db/demo_seed.py 와 같은 값)
DEMO_PROFILE = {"name": "김민지", "birth_date": "2007-03-15", "region": "서울", "phone": None}


def load_spec(form_id: str, forms_dir: Path = FORMS_DIR) -> FormSpec:
    return FormSpec.model_validate_json((forms_dir / f"{form_id}.json").read_text(encoding="utf-8"))


def _prefill(field: FormField, profile: dict | None) -> str | None:
    if field.value_source != "profile" or field.profile_key is None or not profile:
        return None
    value = profile.get(field.profile_key)
    if value is None:
        return None
    if field.profile_key == "birth_date":
        d = value if isinstance(value, date) else date.fromisoformat(str(value))
        return f"{d.year}년 {d.month}월 {d.day}일"
    if field.profile_key == "region":
        return f"{value} (시/도만 있어요. 상세 주소는 직접 적어 주세요)"
    return str(value)


def make_guide(spec: FormSpec, profile: dict | None = None) -> dict:
    items = []
    for f in spec.fields:
        items.append({
            "field_id": f.id,
            "label": f.label,
            "section": f.section,
            "how": HOW[f.value_source],
            "value_source": f.value_source,
            "required": f.required,
            "guide": f.guide,
            "example": None if f.sensitive else f.example,
            "prefill": None if f.sensitive else _prefill(f, profile),
            "evidence": f.evidence,
        })
    notices = [] if spec.checked else [NOT_CHECKED_NOTICE]
    notices.append(FINAL_NOTICE)
    return {
        "form_id": spec.form_id,
        "policy_key": spec.policy_key,
        "title": spec.title,
        "checked": spec.checked,
        "notes": spec.notes,
        "items": items,
        "notices": notices,
    }


def to_text(guide: dict) -> str:
    lines = [f"# {guide['title']} 작성 도우미", ""]
    for n in guide["notices"]:
        lines.append(f"※ {n}")
    if guide["notes"]:
        lines += ["", "[양식 유의사항]"] + [f"- {n}" for n in guide["notes"]]
    section = object()
    for it in guide["items"]:
        if it["section"] != section:
            section = it["section"]
            lines += ["", f"## {section or '작성 항목'}"]
        req = " (필수)" if it["required"] else ""
        lines.append(f"\n▶ {it['label']}{req} — {it['how']}")
        lines.append(f"  설명: {it['guide']}")
        if it["prefill"]:
            lines.append(f"  미리 채운 값: {it['prefill']}")
        if it["example"]:
            lines.append(f"  예시: {it['example']}")
        if it["evidence"]:
            lines.append(f"  양식 원문: \"{it['evidence']}\"")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="신청서 작성 안내 보기")
    parser.add_argument("form_id")
    parser.add_argument("--demo-user", action="store_true", help="데모 사용자(김민지) 프로필로 미리 채우기")
    parser.add_argument("--json", action="store_true", help="앱 응답 형식(JSON)으로 출력")
    args = parser.parse_args()

    guide = make_guide(load_spec(args.form_id), DEMO_PROFILE if args.demo_user else None)
    print(json.dumps(guide, ensure_ascii=False, indent=2) if args.json else to_text(guide))


if __name__ == "__main__":
    main()
