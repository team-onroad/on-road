"""경로의 BIGSERIAL ID 파싱. 형식이 맞지 않으면 None (호출하는 쪽에서 404 로 처리)."""

BIGINT_MAX = 2**63 - 1


def parse_id(value: str) -> int | None:
    if not value.isascii() or not value.isdigit():
        return None
    n = int(value)
    return n if 1 <= n <= BIGINT_MAX else None
