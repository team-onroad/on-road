"""코드값 정의 (docs/db-schema.md 4장)."""

USER_REGIONS = (
    "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
    "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
)
NATIONWIDE = "전국"
POLICY_REGIONS = (NATIONWIDE, *USER_REGIONS)

USER_STATUSES = ("in_care", "leaving_soon", "left_care")
USER_STAGES = ("child", "teen", "youth")

POLICY_CATEGORIES = (
    "independence", "housing", "education", "employment",
    "living_cost", "medical", "finance",
)
SOURCE_TYPES = ("api", "crawler", "manual")

ELIGIBILITY_VALUES = ("match", "needs_check", "excluded")  # db-schema.md 5.3

# 시뮬레이션 배분 항목과 이름 (api.md 1.2). 순서 = 기준표·슬라이더 순서
ALLOCATION_LABELS = {
    "housing": "주거비",
    "food": "식비",
    "transport": "교통비",
    "telecom": "통신비",
    "other": "기타",
}
ALLOCATION_ITEMS = tuple(ALLOCATION_LABELS)

ITEM_TYPES = ("step", "document")
STEP_KEYS = ("target_check", "condition_check", "doc_prepare", "apply")
