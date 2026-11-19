"""서류 개인정보 관리 명령.

    python -m db.document_privacy clear   # 오래된 서류의 개인정보 칸 비우기 (모든 사용자)
    python -m db.document_privacy seal    # 이미 저장된 서류 내용을 DOC_SECRET_KEY 로 한 번에 암호화

clear 는 사용자가 서류 목록 · 서류를 열 때도 자동으로 돈다. 서버를 오래 켜 둘 때 하루 한 번 돌려도 된다.
seal 은 DOC_SECRET_KEY 를 처음 넣은 뒤 한 번만 돌리면 된다 (안 돌려도 다음에 고칠 때부터 암호화된다).
"""

import sys

from sqlalchemy import select
from sqlalchemy.orm import Session
from sqlalchemy.orm.attributes import flag_modified

from app import sealing
from app.services.writer import clear_stale
from db.models import Document, DocumentField
from db.session import get_engine


def seal(db: Session) -> int:
    if not sealing.enabled():
        raise SystemExit("DOC_SECRET_KEY 가 없어요. .env 에 키를 넣고 다시 실행해 주세요.")
    count = 0
    for row in db.scalars(select(DocumentField)):
        flag_modified(row, "segments")  # 다시 저장하면서 암호화된다
        if row.previous_segments is not None:
            flag_modified(row, "previous_segments")
        count += 1
    for doc in db.scalars(select(Document).where(Document.context.is_not(None))):
        flag_modified(doc, "context")
    db.commit()
    return count


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else ""
    with Session(get_engine(), expire_on_commit=False) as db:
        if cmd == "clear":
            print(f"개인정보 칸을 비운 서류: {clear_stale(db)}개")
        elif cmd == "seal":
            print(f"암호화한 서류 칸: {seal(db)}개")
        else:
            print(__doc__)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
