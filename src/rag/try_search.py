"""시연 전 점검용: 서버·DB 없이 실제 RAG 결과를 바로 본다 (OPENAI_API_KEY, 인덱스 필요).

실행 (src 폴더에서):
    backend\\.venv\\Scripts\\python -m rag.try_search "퇴소하고 매달 받을 수 있는 돈이 있나요?"
    backend\\.venv\\Scripts\\python -m rag.try_search "자립수당 신청하려면 뭐가 필요해?" --keys P001
"""

import argparse
import json

from rag.policy_search import search_policies


def main() -> None:
    parser = argparse.ArgumentParser(description="RAG 검색 결과 확인")
    parser.add_argument("question")
    parser.add_argument("--keys", nargs="+", default=["P001", "D001", "D002", "D003", "D004"],
                        help="후보 정책 키 (백엔드가 연령·지역 필터로 넘기는 목록 대신)")
    parser.add_argument("--top-k", type=int, default=5)
    args = parser.parse_args()
    out = search_policies(args.question, args.keys, args.top_k)
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
