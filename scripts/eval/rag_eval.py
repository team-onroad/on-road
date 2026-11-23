"""RAG 정량 지표(환각률 · 정확도) 측정 — 본체는 src/rag/evaluate.py.

실행 (프로젝트 루트에서, 백엔드 가상환경):
    src\\backend\\.venv\\Scripts\\python scripts\\eval\\rag_eval.py          # 실제 OpenAI
    src\\backend\\.venv\\Scripts\\python scripts\\eval\\rag_eval.py --mock   # API 없이 흐름 확인
결과: data/eval_results/rag_eval_{날짜}_{real|mock}.json · .md
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src"))

from rag.evaluate import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
