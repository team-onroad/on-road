"""신청서 파일 → 작성 항목 JSON (데이터 준비용, 양식마다 한 번).

실행 (src 폴더에서, 백엔드 가상환경 사용):
    backend\\.venv\\Scripts\\python -m formguide.build "신청서.hwp" --policy-key P001 --form-id P001-apply
    backend\\.venv\\Scripts\\python -m formguide.build "https://.../신청서.pdf" --policy-key P001 --form-id P001-apply
    backend\\.venv\\Scripts\\python -m formguide.build --notice "https://공고 페이지 주소" --policy-key P001 --form-id P001-apply
        (공고 첨부파일 중 신청서로 보이는 파일을 골라 내려받는다. 다른 파일은 --pick N)

결과:
    data/forms/P001-apply.json         작성 항목 (checked: false 로 저장 → 원본과 대조 후 true 로 바꾼다)
    data/forms/P001-apply.source.txt   PDF 에서 뽑은 원문 (대조용)
    data/forms/P001-apply.pdf          원본 양식 PDF (문서 AI 가 이 위에 작성 내용을 채워 내보낸다.
                                       글꼴이 깨진 PDF 는 글자를 다시 그려 저장, --redraw-text / --no-redraw-text)
    data/forms/_work/                  내려받은 파일·변환한 PDF (커밋하지 않음)
"""

import argparse
from collections.abc import Callable
from pathlib import Path

from formguide.convert import to_pdf
from formguide.extract import FormText, extract
from formguide.fetch import fetch
from formguide.layout import attach_boxes, save_layout_pdf
from formguide.schema import FormSpec
from formguide.validate import check_fields, check_notes
from rag.settings import PROJECT_ROOT

FORMS_DIR = PROJECT_ROOT / "data" / "forms"

Draft = Callable[[FormText], dict]


def build_spec(form: FormText, form_id: str, policy_key: str, source: str, draft: Draft) -> FormSpec:
    raw = draft(form)
    fields, warnings = check_fields(raw.get("fields"), form)
    notes, note_warnings = check_notes(raw.get("notes"), form)
    title = raw.get("title") if isinstance(raw.get("title"), str) and raw["title"].strip() else form_id
    return FormSpec(
        form_id=form_id, policy_key=policy_key, title=title.strip(), source=source,
        fields=fields, notes=notes, warnings=warnings + note_warnings, checked=False,
    )


def build(
    source: str, form_id: str, policy_key: str, out_dir: Path = FORMS_DIR,
    draft: Draft | None = None, suggested_name: str | None = None, redraw: bool | None = None,
) -> Path:
    if draft is None:
        from formguide.llm import draft_fields as draft

    work = out_dir / "_work"
    src = fetch(source, work, suggested_name=suggested_name)
    pdf = to_pdf(src, work)
    form = extract(pdf)

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{form_id}.source.txt").write_text(form.as_prompt_text(), encoding="utf-8")
    spec = build_spec(form, form_id, policy_key, source, draft)
    spec = attach_boxes(spec, save_layout_pdf(pdf, out_dir, form_id, redraw))
    out = out_dir / f"{form_id}.json"
    out.write_text(spec.model_dump_json(indent=2), encoding="utf-8")
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="신청서 → 작성 항목 JSON")
    parser.add_argument("source", nargs="?", help="신청서 파일 경로 또는 URL (.pdf/.hwp/.hwpx)")
    parser.add_argument("--notice", metavar="URL", help="정책·지원 공고 페이지 주소 (첨부파일에서 신청서를 찾음)")
    parser.add_argument("--search", metavar="검색어", help="공고(공공서비스)를 검색해 그 상세 페이지를 --notice 로 쓴다")
    parser.add_argument("--result", type=int, default=1, metavar="N", help="--search 결과의 N번 공고 (기본 1)")
    parser.add_argument("--pick", type=int, metavar="N", help="--notice 첨부 목록의 N번 파일을 쓴다")
    parser.add_argument("--policy-key", required=True, help="연결할 정책 키 (예: P001)")
    parser.add_argument("--form-id", required=True, help="저장 이름 (예: P001-apply)")
    parser.add_argument("--out", type=Path, default=FORMS_DIR)
    parser.add_argument("--redraw-text", action=argparse.BooleanOptionalAction, default=None,
                        help="양식 글자를 깨끗한 글꼴로 다시 그린다 (기본: 글꼴이 깨진 PDF 만)")
    args = parser.parse_args()
    if sum(map(bool, (args.source, args.notice, args.search))) != 1:
        parser.error("파일 경로·URL, --notice, --search 중 하나만 주세요.")

    if args.search:
        from formguide.search import search_notices

        notices = search_notices(args.search)
        for i, n in enumerate(notices, 1):
            print(f"  {i}. {n.title} — {n.agency or '기관 정보 없음'}")
        if not notices:
            parser.exit(1, "검색 결과가 없습니다. 더 짧은 검색어로 다시 찾아 보세요.\n")
        if not 1 <= args.result <= len(notices):
            parser.exit(1, f"--result 는 1~{len(notices)} 사이여야 합니다.\n")
        chosen_notice = notices[args.result - 1]
        if not chosen_notice.url:
            parser.exit(1, "선택한 공고에 상세 페이지 주소가 없습니다.\n")
        print(f"공고: {chosen_notice.title} ({chosen_notice.url})")
        args.notice = chosen_notice.url

    source, name = args.source, None
    if args.notice:
        from formguide.notice import find_attachments, load_page, pick_form

        attachments = find_attachments(load_page(args.notice), args.notice)
        for i, a in enumerate(attachments, 1):
            mark = "" if a.downloadable else "  (자동 다운로드 불가)"
            print(f"  {i}. [신청서 점수 {a.score:+d}] {a.name}{mark}")
        chosen = attachments[args.pick - 1] if args.pick else pick_form(attachments)
        if chosen is None or not chosen.downloadable:
            parser.exit(1, "내려받을 수 있는 첨부파일이 없습니다. 브라우저에서 받아 파일 경로로 넣어 주세요.\n")
        if not args.pick and chosen.score <= 0:
            parser.exit(1, "신청서로 보이는 첨부파일이 없습니다. 맞는 파일이면 --pick N 으로 직접 고르세요.\n")
        print(f"선택: {chosen.name}")
        source, name = chosen.url, chosen.name

    out = build(source, args.form_id, args.policy_key, args.out, suggested_name=name, redraw=args.redraw_text)
    spec = FormSpec.model_validate_json(out.read_text(encoding="utf-8"))
    print(f"저장: {out}")
    print(f"항목 {len(spec.fields)}개, 유의사항 {len(spec.notes)}개, 경고 {len(spec.warnings)}개")
    for w in spec.warnings:
        print(f"  [확인 필요] {w}")
    print("원본과 대조한 뒤 JSON 의 \"checked\" 를 true 로 바꿔 주세요.")


if __name__ == "__main__":
    main()
