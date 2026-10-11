"""시뮬레이션 기준표 (docs/api.md 5장).

- docs/simulation-criteria.json(데이터 담당)이 있으면 그 파일을 읽는다.
- 파일이 없거나 비어 있으면 백엔드 임시 기준표(app/simulation_criteria_temp.json, 버전 TEMP-...)를 쓴다.
- 형식이 잘못되면 CriteriaError. 서버 시작 시(lifespan) 한 번 읽어서 바로 실패하게 한다.
"""

import json
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, ValidationError, model_validator

from app.codes import ALLOCATION_ITEMS, ALLOCATION_LABELS, POLICY_CATEGORIES
from app.config import get_settings

TEMP_CRITERIA_PATH = Path(__file__).resolve().parent / "simulation_criteria_temp.json"


class CriteriaError(Exception):
    pass


class CriteriaItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    label: StrictStr
    minimum: Annotated[StrictInt, Field(ge=0)]
    policy_categories: list[Literal[POLICY_CATEGORIES]]


class Criteria(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: Annotated[StrictStr, Field(min_length=1, max_length=20)]
    source_note: StrictStr | None = None
    items: dict[str, CriteriaItem]

    @model_validator(mode="after")
    def _check_items(self) -> "Criteria":
        missing = [k for k in ALLOCATION_ITEMS if k not in self.items]
        unknown = sorted(set(self.items) - set(ALLOCATION_ITEMS))
        if missing or unknown:
            raise ValueError(f"items 는 {list(ALLOCATION_ITEMS)} 5개여야 합니다 (누락 {missing}, 알 수 없음 {unknown})")
        wrong_labels = {k: v.label for k, v in self.items.items() if v.label != ALLOCATION_LABELS[k]}
        if wrong_labels:
            raise ValueError(f"label 이 api.md 1.2 항목 이름과 다릅니다: {wrong_labels}")
        # 항목 순서는 파일의 키 순서와 관계없이 api.md 1.2 순서로 고정
        self.items = {k: self.items[k] for k in ALLOCATION_ITEMS}
        return self


def load_criteria(path: Path) -> tuple[Criteria, Path]:
    """기준표를 읽는다. path 가 없거나 비어 있으면 임시 기준표. (기준표, 실제로 읽은 경로)"""
    source = path
    if not path.exists() or not path.read_text(encoding="utf-8-sig").strip():
        source = TEMP_CRITERIA_PATH
    try:
        data = json.loads(source.read_text(encoding="utf-8-sig"))
        return Criteria.model_validate(data), source
    except (json.JSONDecodeError, ValidationError) as e:
        raise CriteriaError(f"시뮬레이션 기준표 형식 오류 ({source}):\n{e}") from e


@lru_cache
def get_criteria() -> Criteria:
    """FastAPI 의존성. 테스트에서는 dependency_overrides 로 다른 기준표를 넣을 수 있다."""
    return load_criteria(get_settings().simulation_criteria_path)[0]
