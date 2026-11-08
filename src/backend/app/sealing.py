"""서류 내용 암호화 저장 (계좌번호 · 전화 · 주소 · 자기소개 문장 등).

- 서류 칸 내용(document_fields.segments · previous_segments)과 상담에서 넘어온 요약(documents.context)을
  DB 에 넣을 때 암호화하고, 꺼낼 때 푼다. 코드에서는 지금처럼 평문으로 다룬다 (db/models.py 의 Sealed* 형식).
- 키는 .env 의 DOC_SECRET_KEY (Fernet 키). 쉼표로 여러 개를 주면 첫 키로 암호화하고 나머지로도 풀 수 있다 (키 교체).
  키 만들기: python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
- 키가 없으면 예전처럼 평문으로 저장한다 (개발 · 테스트). 예전 평문 값은 키를 넣은 뒤에도 그대로 읽힌다.
- 키를 잃으면 암호화된 값은 되살릴 수 없다. 키는 .env 에만 두고 커밋하지 않는다.
"""

import json
import logging
from functools import lru_cache

from cryptography.fernet import Fernet, InvalidToken, MultiFernet

from app.config import get_settings

logger = logging.getLogger(__name__)

TEXT_PREFIX = "enc:v1:"
JSON_KEY = "sealed"


@lru_cache
def _fernet() -> MultiFernet | None:
    raw = get_settings().doc_secret_key
    if not raw or not raw.strip():
        return None
    keys = [k.strip() for k in raw.split(",") if k.strip()]
    return MultiFernet([Fernet(k.encode()) for k in keys])


def reset() -> None:
    """설정을 바꾼 뒤 키를 다시 읽는다 (테스트용)"""
    _fernet.cache_clear()


def enabled() -> bool:
    return _fernet() is not None


def _open(token: str) -> str | None:
    f = _fernet()
    if f is None:
        logger.error("암호화된 서류 값이 있는데 DOC_SECRET_KEY 가 없어요")
        return None
    try:
        return f.decrypt(token.encode()).decode()
    except InvalidToken:
        logger.error("서류 값을 풀지 못했어요 (DOC_SECRET_KEY 가 바뀌었나요?)")
        return None


def seal_text(value: str | None) -> str | None:
    f = _fernet()
    if value is None or f is None or value.startswith(TEXT_PREFIX):
        return value
    return TEXT_PREFIX + f.encrypt(value.encode()).decode()


def open_text(value: str | None) -> str | None:
    if value is None or not value.startswith(TEXT_PREFIX):
        return value  # 평문 (키를 넣기 전 값)
    return _open(value[len(TEXT_PREFIX):])


def seal_json(value):
    """JSON 값 전체를 {"sealed": "..."} 하나로 감싼다. 키가 없으면 그대로"""
    f = _fernet()
    if value is None or f is None:
        return value
    return {JSON_KEY: f.encrypt(json.dumps(value, ensure_ascii=False).encode()).decode()}


def open_json(value, empty=None):
    if isinstance(value, dict) and set(value) == {JSON_KEY}:
        text = _open(value[JSON_KEY])
        return empty if text is None else json.loads(text)
    return value
