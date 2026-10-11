"""정책 시드 스크립트 (docs/db-schema.md 6장).

data/policies/policies.json 을 policy_key 기준으로 upsert 한다. 여러 번 실행해도 중복되지 않는다.

- policies.json 이 없거나 비어 있으면 db/dummy_policies.json(D001~D004)으로 시드한다.
- policies.json 에 실제 정책이 있으면 그 파일로 시드하고, 이미 들어가 있는 더미 정책(D로 시작)은
  --dummy-action 에 따라 비활성화(기본) / 삭제 / 유지한다. 더미가 실제 정책과 섞여 노출되지 않게 하기 위함.

실행 (src/backend 에서):
    python -m db.seed
    python -m db.seed --dummy-action delete
    python -m db.seed --file path/to/policies.json
"""

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Literal

from sqlalchemy import delete, func, literal_column, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.codes import POLICY_CATEGORIES, POLICY_REGIONS, SOURCE_TYPES
from app.config import get_settings
from db.models import Checklist, Policy

DUMMY_PREFIX = "D"
DUMMY_POLICIES_PATH = Path(__file__).resolve().parent / "dummy_policies.json"

DummyAction = Literal["deactivate", "delete", "keep"]
DUMMY_ACTIONS: tuple[DummyAction, ...] = ("deactivate", "delete", "keep")

REQUIRED_KEYS = (
    "policy_key", "name", "agency", "category", "region", "target_description",
    "support_content", "apply_method", "required_docs", "source_url", "original_text",
    "source_type", "checked_at",
)
# 키가 없으면 이 값으로 채운다. json 이 정책 내용의 기준이므로 빠진 값은 기존 DB 값을 남기지 않고 비운다.
OPTIONAL_DEFAULTS: dict[str, Any] = {
    "contact": None,
    "age_min": None,
    "age_max": None,
    "support_amount": None,
    "support_period": None,
    "apply_url": None,
    "easy_text": None,
    "easy_text_verified": False,
}
# is_active 는 json 에 있을 때만 반영한다 (없으면 DB에서 비활성화한 상태를 유지).
ALLOWED_KEYS = set(REQUIRED_KEYS) | set(OPTIONAL_DEFAULTS) | {"is_active"}

_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class SeedError(Exception):
    pass


@dataclass
class SeedResult:
    source: Literal["policies", "dummy"]
    path: Path
    inserted: list[str] = field(default_factory=list)
    updated: list[str] = field(default_factory=list)
    dummy_deactivated: list[str] = field(default_factory=list)
    dummy_deleted: list[str] = field(default_factory=list)


def load_policy_file(path: Path) -> list[dict] | None:
    """정책 json 을 읽는다. 파일이 없거나 비어 있거나 빈 배열이면 None."""
    if not path.exists():
        return None
    raw = path.read_text(encoding="utf-8-sig").strip()
    if not raw:
        return None
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        raise SeedError(f"{path}: JSON 형식 오류 ({e})") from e
    if not isinstance(data, list):
        raise SeedError(f"{path}: 최상위는 정책 객체의 배열이어야 합니다.")
    return data or None


def _normalize(item: Any, index: int, *, dummy: bool) -> dict[str, Any]:
    where = f"{index + 1}번째 정책"
    if not isinstance(item, dict):
        raise SeedError(f"{where}: 객체여야 합니다.")
    key = item.get("policy_key")
    if isinstance(key, str):
        where = f"{where}({key})"

    missing = [k for k in REQUIRED_KEYS if item.get(k) in (None, "")]
    if missing:
        raise SeedError(f"{where}: 필수 키 누락 {missing}")
    unknown = sorted(set(item) - ALLOWED_KEYS)
    if unknown:
        raise SeedError(f"{where}: 알 수 없는 키 {unknown} (db-schema.md 6장 키 이름 확인)")
    if not isinstance(key, str) or len(key) > 20:
        raise SeedError(f"{where}: policy_key 는 20자 이내 문자열이어야 합니다.")

    if dummy != key.startswith(DUMMY_PREFIX):
        if dummy:
            raise SeedError(f"{where}: 더미 정책의 policy_key 는 '{DUMMY_PREFIX}'로 시작해야 합니다.")
        raise SeedError(
            f"{where}: '{DUMMY_PREFIX}'로 시작하는 policy_key 는 더미 정책 전용입니다. 다른 키를 사용하세요."
        )
    if item["category"] not in POLICY_CATEGORIES:
        raise SeedError(f"{where}: category 코드값 오류 '{item['category']}'")
    if item["region"] not in POLICY_REGIONS:
        raise SeedError(f"{where}: region 코드값 오류 '{item['region']}'")
    if item["source_type"] not in SOURCE_TYPES:
        raise SeedError(f"{where}: source_type 코드값 오류 '{item['source_type']}'")

    checked_at = item["checked_at"]
    if not isinstance(checked_at, str) or not _DATE_RE.match(checked_at):
        raise SeedError(f"{where}: checked_at 은 YYYY-MM-DD 형식이어야 합니다.")
    try:
        checked_date = date.fromisoformat(checked_at)
    except ValueError as e:
        raise SeedError(f"{where}: checked_at 날짜 오류 '{checked_at}'") from e

    docs = item["required_docs"]
    if not isinstance(docs, list) or not all(isinstance(d, str) and d for d in docs):
        raise SeedError(f"{where}: required_docs 는 문자열 배열이어야 합니다.")

    row = {**OPTIONAL_DEFAULTS, **item, "checked_at": checked_date}
    for k in ("age_min", "age_max"):
        if row[k] is not None and (isinstance(row[k], bool) or not isinstance(row[k], int)):
            raise SeedError(f"{where}: {k} 는 정수 또는 null 이어야 합니다.")
    if row["age_min"] is not None and row["age_max"] is not None and row["age_min"] > row["age_max"]:
        raise SeedError(f"{where}: age_min 이 age_max 보다 큽니다.")
    if row["easy_text_verified"] and not row["easy_text"]:
        raise SeedError(f"{where}: easy_text_verified 가 true 이면 easy_text 가 있어야 합니다.")
    if dummy:
        row["is_active"] = True
    return row


def validate_policies(items: list[Any], *, dummy: bool) -> list[dict[str, Any]]:
    rows = [_normalize(item, i, dummy=dummy) for i, item in enumerate(items)]
    keys = [r["policy_key"] for r in rows]
    dupes = sorted({k for k in keys if keys.count(k) > 1})
    if dupes:
        raise SeedError(f"policy_key 중복: {dupes}")
    return rows


def _upsert(session: Session, rows: list[dict[str, Any]], result: SeedResult) -> None:
    for row in rows:
        stmt = insert(Policy).values(**row)
        stmt = stmt.on_conflict_do_update(
            index_elements=[Policy.policy_key],
            set_={
                **{k: stmt.excluded[k] for k in row if k != "policy_key"},
                "updated_at": func.now(),
            },
        ).returning(Policy.policy_key, literal_column("(xmax = 0)").label("inserted"))
        key, inserted = session.execute(stmt).one()
        (result.inserted if inserted else result.updated).append(key)


def _handle_dummies(session: Session, action: DummyAction, result: SeedResult) -> None:
    if action == "keep":
        return
    is_dummy = Policy.policy_key.startswith(DUMMY_PREFIX, autoescape=True)
    if action == "delete":
        # 체크리스트가 연결된 더미는 FK(ON DELETE RESTRICT) 때문에 지울 수 없으므로 비활성화만 한다.
        referenced = select(Checklist.policy_id)
        deleted = session.scalars(
            delete(Policy)
            .where(is_dummy, Policy.id.not_in(referenced))
            .returning(Policy.policy_key)
        ).all()
        result.dummy_deleted = sorted(deleted)
    deactivated = session.scalars(
        update(Policy)
        .where(is_dummy, Policy.is_active.is_(True))
        .values(is_active=False, updated_at=func.now())
        .returning(Policy.policy_key)
    ).all()
    result.dummy_deactivated = sorted(deactivated)


def seed_policies(
    session: Session,
    path: Path | None = None,
    dummy_action: DummyAction = "deactivate",
) -> SeedResult:
    """정책을 upsert 한다. 커밋은 호출하는 쪽에서 한다."""
    if dummy_action not in DUMMY_ACTIONS:
        raise SeedError(f"dummy_action 은 {DUMMY_ACTIONS} 중 하나여야 합니다.")
    path = path or get_settings().policies_json_path

    items = load_policy_file(path)
    if items is None:
        result = SeedResult(source="dummy", path=DUMMY_POLICIES_PATH)
        dummy_items = load_policy_file(DUMMY_POLICIES_PATH)
        if dummy_items is None:
            raise SeedError(f"더미 정책 파일이 비어 있습니다: {DUMMY_POLICIES_PATH}")
        _upsert(session, validate_policies(dummy_items, dummy=True), result)
        return result

    result = SeedResult(source="policies", path=path)
    _upsert(session, validate_policies(items, dummy=False), result)
    _handle_dummies(session, dummy_action, result)
    return result


def _print_result(result: SeedResult, dummy_action: DummyAction) -> None:
    if result.source == "dummy":
        print(f"[seed] 실제 정책 데이터가 없어 더미 정책으로 시드합니다: {result.path}")
    else:
        print(f"[seed] 정책 데이터: {result.path}")
    print(f"[seed] 추가 {len(result.inserted)}개 {result.inserted}")
    print(f"[seed] 갱신 {len(result.updated)}개 {result.updated}")
    if result.source == "policies":
        if dummy_action == "keep":
            print("[seed] 더미 정책 유지 (--dummy-action keep) — 실제 정책과 함께 노출될 수 있습니다.")
        if result.dummy_deleted:
            print(f"[seed] 더미 정책 삭제 {result.dummy_deleted}")
        if result.dummy_deactivated:
            print(f"[seed] 더미 정책 비활성화 {result.dummy_deactivated}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="정책 시드 (policy_key 기준 upsert)")
    parser.add_argument("--file", type=Path, help="정책 json 경로 (기본: data/policies/policies.json)")
    parser.add_argument(
        "--dummy-action",
        choices=DUMMY_ACTIONS,
        default="deactivate",
        help="실제 정책으로 시드할 때 기존 더미 정책(D로 시작) 처리: "
        "deactivate=비활성화(기본), delete=삭제(체크리스트가 있으면 비활성화), keep=유지",
    )
    args = parser.parse_args(argv)

    from db.session import get_engine

    try:
        with Session(get_engine()) as session, session.begin():
            result = seed_policies(session, path=args.file, dummy_action=args.dummy_action)
    except SeedError as e:
        print(f"[seed] 실패: {e}", file=sys.stderr)
        return 1
    _print_result(result, args.dummy_action)
    return 0


if __name__ == "__main__":
    sys.exit(main())
