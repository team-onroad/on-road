"""정책 원문을 청크로 나눠 Chroma 에 넣는다. 같은 policy_key 는 지우고 다시 넣으므로 여러 번 실행해도 된다.

실행 (src 폴더에서, 백엔드 가상환경 사용):
    python -m rag.index                     # data/policies/policies.json + 더미 정책
    python -m rag.index --no-dummy
    python -m rag.index --file path/to/policies.json
"""

import argparse
import json
from collections.abc import Callable
from pathlib import Path

from chromadb.api.models.Collection import Collection

from rag.chunking import Chunk, chunk_policy
from rag.settings import PROJECT_ROOT

POLICIES_PATH = PROJECT_ROOT / "data" / "policies" / "policies.json"
DUMMY_POLICIES_PATH = PROJECT_ROOT / "src" / "backend" / "db" / "dummy_policies.json"
_BATCH = 64

Embed = Callable[[list[str]], list[list[float]]]


def load_policies(path: Path) -> list[dict]:
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8").strip()
    return json.loads(text) if text else []


def index_policies(collection: Collection, policies: list[dict], embed: Embed) -> list[Chunk]:
    keys = [p["policy_key"] for p in policies]
    if keys:
        collection.delete(where={"policy_key": {"$in": keys}})
    chunks = [c for p in policies for c in chunk_policy(p)]
    for i in range(0, len(chunks), _BATCH):
        batch = chunks[i : i + _BATCH]
        collection.add(
            ids=[c.id for c in batch],
            embeddings=embed([c.embed_text for c in batch]),
            documents=[c.content for c in batch],
            metadatas=[c.metadata for c in batch],
        )
    return chunks


def main() -> None:
    parser = argparse.ArgumentParser(description="정책 원문 Chroma 인덱싱")
    parser.add_argument("--file", type=Path, default=POLICIES_PATH)
    parser.add_argument("--no-dummy", action="store_true", help="더미 정책(D001~)을 넣지 않는다")
    args = parser.parse_args()

    from rag.openai_client import embed
    from rag.store import open_collection

    policies = load_policies(args.file)
    if not args.no_dummy:
        policies += load_policies(DUMMY_POLICIES_PATH)
    chunks = index_policies(open_collection(), policies, embed)
    per_policy: dict[str, int] = {}
    for c in chunks:
        per_policy[c.policy_key] = per_policy.get(c.policy_key, 0) + 1
    print(f"정책 {len(policies)}개, 청크 {len(chunks)}개 인덱싱 완료")
    for key, n in per_policy.items():
        print(f"  {key}: {n}")


if __name__ == "__main__":
    main()
