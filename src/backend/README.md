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
│   └── api/health.py      GET /api/health
├── db/
│   ├── models.py          SQLAlchemy 모델
│   ├── session.py         DB 엔진·세션
│   ├── seed.py            정책 시드 (policy_key 기준 upsert)
│   └── dummy_policies.json  더미 정책 D001~D004 (실제 데이터가 없을 때 사용)
├── alembic/               마이그레이션 (0001 = db-schema.md 부록 DDL)
├── tests/                 pytest
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
