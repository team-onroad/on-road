"""Chroma 컬렉션 열기. 정책 연결 키는 policy_key 메타데이터 (db-schema.md: DB id 는 쓰지 않음)."""

import chromadb
from chromadb.api.models.Collection import Collection

from rag.settings import get_rag_settings


def open_collection(client: chromadb.ClientAPI | None = None, name: str | None = None) -> Collection:
    s = get_rag_settings()
    if client is None:
        s.rag_chroma_dir.mkdir(parents=True, exist_ok=True)
        client = chromadb.PersistentClient(
            path=str(s.rag_chroma_dir), settings=chromadb.Settings(anonymized_telemetry=False)
        )
    return client.get_or_create_collection(
        name or s.rag_collection,
        configuration={"hnsw": {"space": "cosine"}},
    )
