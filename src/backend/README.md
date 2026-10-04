# On-Road 백엔드

FastAPI + PostgreSQL 16 (Docker Compose) + SQLAlchemy 2.0 / psycopg 3 + Alembic

- 기준 문서: [`docs/db-schema.md`](../../docs/db-schema.md), [`docs/api.md`](../../docs/api.md)
- 1차 데모 범위: 핵심 기능 1~4번

<br/>

## 폴더 구조

```text
src/backend/
├── app/
│   ├── main.py            FastAPI 앱 (모든 API는 /api 아래)
│   ├── config.py          환경변수 설정 (프로젝트 루트 .env 를 읽음)
│   ├── codes.py           코드값 (db-schema.md 4장)
│   ├── errors.py          공통 에러 형식 (api.md 1.1)
│   ├── clock.py           오늘 날짜(Asia/Seoul). 테스트에서 고정 가능
│   ├── rules.py           만 나이·성장 단계·D-day·조건 판정(5.3)·오래된 정보(is_outdated) 계산
│   ├── rag_client.py      RAG 함수 연결 지점 (AI 담당 함수로 바꿀 때 여기만 수정)
│   ├── rag_mock.py        RAG 목 함수 (api.md 4장 형식, 더미 정책 키워드 검색)
│   ├── schemas/           요청·응답 스키마 (users, policies)
│   ├── services/
│   │   ├── users.py       사용자 생성·조회·수정 규칙
│   │   └── policies.py    정책 상세, 검색(후보 필터 → RAG 호출 → 정책별로 묶기)
│   └── api/
│       ├── health.py      GET /api/health
│       ├── users.py       POST·GET·PATCH /api/users
│       └── policies.py    GET /api/policies/{policy_id}, POST /api/search
├── db/
│   ├── models.py          SQLAlchemy 모델
│   ├── session.py         DB 엔진·세션
│   ├── seed.py            정책 시드 (policy_key 기준 upsert)
│   └── dummy_policies.json  더미 정책 D001~D004 (실제 데이터가 없을 때 사용)
├── alembic/               마이그레이션 (0001 = db-schema.md 부록 DDL)
├── tests/
│   ├── conftest.py        테스트 DB·고정 날짜(clock)·TestClient·실제 uvicorn 서버 fixture
│   ├── factories.py       테스트용 정책·사용자를 DB 에 직접 넣는 도우미
│   └── test_*.py          API·규칙·스키마·시드·RAG 목 함수 테스트
├── alembic.ini
├── pytest.ini
└── requirements.txt
```

<br/>

## 실행 방법

아래 명령은 Windows PowerShell 기준입니다. macOS/Linux는 괄호 안의 명령을 사용합니다.

### 1. 환경변수 파일 만들기 (프로젝트 루트)

```powershell
cd on-road
copy .env.example .env      # (cp .env.example .env)
```

`.env`의 `POSTGRES_PASSWORD`를 바꾸고, `DATABASE_URL`, `TEST_DATABASE_URL`의 비밀번호도 같은 값으로 맞춥니다.
`.env`는 커밋하지 않습니다. 실제 값은 팀 채널로 공유합니다.

### 2. PostgreSQL 실행 (프로젝트 루트)

```powershell
docker compose up -d --wait   # healthy 상태가 될 때까지 기다림
docker compose ps
```

- 중지: `docker compose stop` / 데이터까지 삭제: `docker compose down -v`
- 5432 포트를 이미 쓰고 있으면 `.env`의 `POSTGRES_PORT`와 두 URL의 포트를 함께 바꿉니다.

### 3. 파이썬 가상환경과 패키지 설치 (`src/backend`)

```powershell
cd src\backend
py -3.12 -m venv .venv                  # (python3.12 -m venv .venv)
.\.venv\Scripts\Activate.ps1            # (source .venv/bin/activate)
pip install -r requirements.txt
```

### 4. 마이그레이션 (테이블 5개, 제약 조건, 인덱스 생성)

```powershell
alembic upgrade head
alembic current          # 0001 (head) 이면 정상
```

되돌리기: `alembic downgrade base` (모든 테이블 삭제)

### 5. 정책 시드

```powershell
python -m db.seed
```

- `data/policies/policies.json`에 있는 정책을 `policy_key` 기준으로 upsert합니다. 여러 번 실행해도 중복되지 않습니다.
- **`policies.json`이 없거나 비어 있으면** `db/dummy_policies.json`의 더미 정책 4개(`D001`~`D004`, `easy_text_verified = true`)를 넣습니다.
- **`policies.json`에 실제 정책이 있으면** 그 파일로 시드하고, DB에 남아 있는 더미 정책(`D`로 시작)은 `--dummy-action` 옵션대로 처리합니다. 더미가 실제 정책과 섞여 노출되지 않게 하기 위함입니다.

| 옵션 | 동작 |
|---|---|
| `--dummy-action deactivate` (기본) | 더미 정책을 `is_active = false`로 바꿔서 노출하지 않음 |
| `--dummy-action delete` | 더미 정책 삭제. 체크리스트가 연결된 더미는 FK(`ON DELETE RESTRICT`) 때문에 지우지 않고 비활성화 |
| `--dummy-action keep` | 더미 정책을 그대로 둠 (실제 정책과 함께 노출되니 주의) |
| `--file 경로` | 다른 json 파일로 시드 |

- 실제 데이터의 `policy_key`는 `D`로 시작할 수 없습니다 (더미 전용). 시드가 거부합니다.
- json에 없는 선택 키(`contact`, `easy_text` 등)는 `null`로 저장합니다. `is_active`는 json에 있을 때만 반영하고, 없으면 DB 값을 그대로 둡니다.
- 필수 키 누락, 코드값 오류, 날짜 형식 오류, 알 수 없는 키가 있으면 아무것도 저장하지 않고 실패합니다.

### 6. 서버 실행

```powershell
uvicorn app.main:app --reload --port 8000
```

- 상태 확인: http://localhost:8000/api/health → `{"status": "ok"}`
- API 문서: http://localhost:8000/docs

**구현된 API**

| 메서드 | 경로 | 명세 |
|---|---|---|
| GET | `/api/health` | api.md 3.1 |
| POST | `/api/users` | api.md 3.2 |
| GET | `/api/users/{user_id}` | api.md 3.3 |
| PATCH | `/api/users/{user_id}` | api.md 3.12 |
| GET | `/api/policies/{policy_id}` | api.md 3.6 |
| POST | `/api/search` | api.md 3.5 (RAG 는 목 함수) |

명세에 없어서 팀에서 정한 동작:
- `user_id`가 UUID 형식이 아니어도 404 `USER_NOT_FOUND`로 응답합니다 (앱은 이때 온보딩으로 이동).
- PATCH에서 사용자가 없으면 본문이 잘못됐어도(깨진 JSON 포함) 404를 먼저 응답합니다.
- 조회할 때 `stage`가 바뀌어 갱신하면 `updated_at`도 갱신합니다.
- 이미 `left_care`인 사용자에게 `status: left_care`를 다시 보내면 `d_date`를 유지합니다. 다른 상태에서 `left_care`로 실제로 바뀔 때만 비웁니다.
- 이름은 앞뒤 공백을 제거한 뒤 1~50자인지 검사하고 저장합니다.
- PATCH는 요청한 필드와 관계없이 `stage`를 오늘 기준으로 다시 계산합니다. 값이 실제로 바뀐 경우에만 `updated_at`이 갱신됩니다.
- 422 메시지에는 문제 필드 이름만 담고, 입력값(개인정보)은 담지 않습니다.
- 정책 상세: `policy_id`가 양의 정수가 아니거나 범위를 벗어나면 404 `POLICY_NOT_FOUND`로 응답합니다. `user_id` 쿼리가 UUID 형식이 아니거나 없는 사용자이면 404 `USER_NOT_FOUND`이고, 정책 404를 먼저 확인합니다. 판정이 `excluded`여도 200으로 판정값을 그대로 보여줍니다.
- 검색: `user_id`가 UUID 형식이 아니거나 없는 사용자이면 404이고, 질문 검증(422)보다 먼저 확인합니다. 질문은 앞뒤 공백을 제거한 뒤 1~500자인지 검사하고, 제거한 문장을 RAG 함수에 전달합니다.
- 검색: `results`는 RAG `evidences`에 처음 나온 순서를 따릅니다. 후보 밖 `policy_key`를 빼고 나서 근거가 하나도 남지 않으면 `no_evidence`로 응답합니다.
- 검색: RAG 함수가 예외를 내거나 4장 형식과 다른 값을 돌려주면(`answered`인데 `answer`가 없는 경우 포함) 503 `RAG_UNAVAILABLE`로 응답합니다. `score`는 없어도 됩니다.
- 검색: 질문은 DB에 저장하지 않고 로그에도 남기지 않습니다. RAG 예외는 예외 종류 이름만 로그에 남깁니다.

### RAG 함수를 AI 담당 함수로 바꾸기

백엔드는 `app/rag_client.py` 한 곳에서만 RAG 함수를 가져옵니다. 지금은 목 함수(`app/rag_mock.py`)를 씁니다.
AI 담당 함수(`src/rag/` 안, 모듈 이름은 api.md 8장에서 확정 예정)가 준비되면 `app/rag_client.py`의 import 줄만 아래처럼 바꿉니다.

```python
# app/rag_client.py
import sys

from app.config import PROJECT_ROOT

sys.path.insert(0, str(PROJECT_ROOT / "src"))  # src/rag 를 import 할 수 있게 함
from rag.<모듈이름> import search_policies  # noqa: E402  ← 목 함수 import 를 이 줄로 교체
```

- 함수 형식은 api.md 4장과 같아야 합니다: `search_policies(question, candidate_policy_keys, top_k=5) -> dict`
- 바꾼 뒤 `pytest`를 실행합니다. 검색 API 테스트는 가짜 RAG를 넣어서 돌기 때문에 영향을 받지 않습니다. 목 함수 전용 테스트(`tests/test_rag_mock.py`의 `test_rag_client_uses_mock_by_default`, `test_search_with_mock_and_dummy_seed`)는 실제 함수에 맞게 고치거나 지웁니다.
- AI 담당 함수에 필요한 패키지(Chroma 등)는 `requirements.txt`에 추가합니다.

### 7. 테스트

```powershell
pytest
```

- DB 테스트는 `.env`의 `TEST_DATABASE_URL` DB(`onroad_test`)를 사용합니다. DB가 없으면 자동으로 만들고, 실행할 때마다 마이그레이션을 처음부터 다시 적용한 뒤 테스트마다 테이블을 비웁니다. 개발 DB는 건드리지 않습니다.
- `TEST_DATABASE_URL`이 없거나 DB 컨테이너가 꺼져 있으면 DB 테스트는 **skip**됩니다. 결과 요약(`SKIPPED ...`)에 이유가 표시됩니다. 전체 테스트를 실행하려면 먼저 `docker compose up -d`를 실행합니다.

<br/>

## 참고

- 한국 시간(Asia/Seoul) 기준: DB 컨테이너와 DB 세션 시간대를 `Asia/Seoul`로 설정했습니다. `TIMESTAMPTZ`는 `+09:00`으로 반환됩니다.
- `alembic.ini`에는 한글을 쓰지 않습니다. Windows가 이 파일을 시스템 인코딩(cp949)으로 읽기 때문입니다.
- Git Bash에서 한글 출력이 깨지면 `PYTHONUTF8=1`을 붙여서 실행합니다.
