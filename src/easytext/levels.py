"""난이도 7단계 (L1~L7) 기준. 수치는 설계 문서의 초안이며 P001 결과로 조정한다.

2026-10-19: P001 실행 결과로 문장 길이 기준을 단계마다 평균 +1어절, 최대 +2어절 늘림.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Level:
    code: str
    name: str
    min_age: int  # 추천 나이 하한 (만)
    avg_eojeol: int  # 핵심 블록 문장당 평균 어절 상한
    max_eojeol: int  # 핵심 블록 한 문장 최대 어절
    max_conditions: int  # 한 문장에 담는 조건 수 (프롬프트 지시용)
    plain_tier: int  # 문장 안에 그대로 쓸 수 있는 용어 등급 상한 (풀이는 앱이 용어 사전으로 보여줌)
    gloss_tier: int  # 괄호 풀이를 붙이면 쓸 수 있는 등급 상한. 사전 풀이 방식이라 plain_tier 와 같게 둔다
    core_sentences: tuple[int, int]  # 핵심 블록 문장 수 범위
    structure: str
    focus: str
    link_tier: int = 3  # 이 등급 이상 용어에 사전 풀이를 연결한다 (괄호 안 원래 이름 포함)


LEVELS: dict[str, Level] = {
    lv.code: lv
    for lv in (
        # 용어: L1~L5 는 T3 까지 그대로 쓰고 사전 풀이 연결, T4 는 풀어 씀 (L1~L2 는 T2 부터 연결)
        #       L6~L7 은 원래 용어 그대로 (T3 이상 사전 연결)
        Level("L1", "아주 쉽게", 7, 7, 11, 1, 3, 3, (3, 5),
              "항목마다 짧은 문단, 한 문단에 한두 문장", "어른과 함께 확인하도록 안내", link_tier=2),
        Level("L2", "쉽게", 9, 9, 14, 1, 3, 3, (5, 8),
              "항목마다 문단", "무엇을 받는지", link_tier=2),
        Level("L3", "조금 쉽게", 11, 11, 16, 1, 3, 3, (6, 9),
              "항목마다 문단, 조건은 번호 목록", "누가, 무엇을"),
        Level("L4", "보통", 13, 13, 20, 1, 3, 3, (8, 12),
              "항목마다 문단, 조건은 번호 목록", "조건의 '모두/하나' 관계"),
        Level("L5", "조금 자세히", 15, 15, 24, 2, 3, 3, (8, 14),
              "항목마다 짧은 문단, 필요하면 목록", "조기 보호종료·퇴소 준비·사전신청"),
        Level("L6", "자세히", 18, 16, 26, 2, 4, 4, (10, 15),
              "항목마다 문단, 서류·절차는 목록", "신청 방법·서류"),
        Level("L7", "꼼꼼히", 20, 17, 26, 2, 4, 4, (10, 15),
              "항목마다 문단, 서류·절차는 목록", "주거 계약·금융 용어 해설"),
    )
}

DEFAULT_LEVEL = "L6"  # 자립준비청년 데모 기본값
TOLERANCE = 1.2  # 어절·문장 수 기준 120% 이내면 통과 (P001 실행 결과로 110% → 120% 완화)


def get_level(code: str) -> Level:
    try:
        return LEVELS[code.upper()]
    except KeyError:
        raise ValueError(f"없는 단계입니다: {code} (가능: {', '.join(LEVELS)})") from None


def recommend_level(age: int) -> str:
    """나이로 추천 단계. 사용자가 직접 고른 단계가 항상 우선이다."""
    code = "L1"
    for lv in LEVELS.values():
        if age >= lv.min_age:
            code = lv.code
    return code
