# On-Road 백엔드

FastAPI + PostgreSQL 16 (Docker) 기반 백엔드 서버

- API 명세: `docs/api.md`
- DB 스키마: `docs/db-schema.md`


<br/><br/>
## 폴더 구조

```text
src/backend/
├── app/
│   ├── main.py              ← 서버 시작점 (모든 API는 /api 아래)
│   ├── config.py            ← 환경변수 설정 (프로젝트 루트 .env 사용)
│   ├── codes.py             ← 코드값 (db-schema.md 4장)
│   ├── errors.py            ← 공통 에러 형식 (api.md 1.1)
│   ├── clock.py             ← 오늘 날짜 (한국 시간 기준)
│   ├── rules.py             ← 만 나이, 성장 단계, D-day, 조건 판정, 오래된 정보 계산
│   ├── criteria.py          ← 시뮬레이션 기준표 읽기·검사
│   ├── simulation_criteria_temp.json  ← 임시 기준표 (docs/simulation-criteria.json 받기 전까지 사용)
│   ├── ids.py               ← 경로 ID 확인
│   ├── rag_client.py        ← RAG 함수 연결 지점 (AI 담당 함수로 바꿀 때 여기만 수정)
│   ├── rag_mock.py          ← RAG 임시(목) 함수
│   ├── schemas/             ← 요청·응답 형식
│   ├── services/            ← 기능별 처리 로직 (사용자, 정책·검색, 시뮬레이션, 체크리스트, 홈·성장 기록)
│   └── api/                 ← API 경로 (health, users, policies, simulations, checklists)
├── db/
│   ├── models.py            ← 테이블 모델
│   ├── session.py           ← DB 연결
│   ├── seed.py              ← 정책 시드 스크립트
│   └── dummy_policies.json  ← 더미 정책 D001~D004 (실제 데이터 받기 전까지 사용)
├── alembic/                 ← 테이블 생성 (마이그레이션)
├── tests/                   ← 테스트
├── requirements.txt
├── alembic.ini
└── pytest.ini
```


<br/><br/>
## 실행 방법

Windows PowerShell 기준

### 1. 환경변수 파일 만들기 (프로젝트 루트)

```powershell
copy .env.example .env
```

- `.env`의 비밀번호를 바꾸고, `DATABASE_URL`, `TEST_DATABASE_URL`에도 같은 비밀번호 입력
- `.env`는 커밋하지 않음. 실제 값은 팀 채널로 공유

### 2. DB 실행 (프로젝트 루트)

```powershell
docker compose up -d
```

- 중지: `docker compose stop`
- 데이터까지 삭제: `docker compose down -v`

### 3. 가상환경 만들고 패키지 설치 (src/backend)

```powershell
cd src\backend
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

- 스크립트 실행 오류가 나면 먼저 `Set-ExecutionPolicy -Scope Process Bypass` 실행

### 4. 테이블 생성

```powershell
alembic upgrade head
```

### 5. 정책 데이터 넣기

```powershell
python -m db.seed
```

- `data/policies/policies.json`을 DB에 넣음. 여러 번 실행해도 중복되지 않음
- `policies.json`이 비어 있으면 더미 정책 4개(D001~D004)를 넣음
- 실제 정책으로 시드하면 더미 정책은 자동으로 비활성화 (`--dummy-action delete`로 삭제 가능)

### 6. 서버 실행

```powershell
uvicorn app.main:app --reload
```

- 상태 확인: http://localhost:8000/api/health
- API 문서 (직접 호출 가능): http://localhost:8000/docs

### 7. 테스트

```powershell
pytest
```

- DB가 꺼져 있으면 DB 테스트는 skip. `docker compose up -d` 후 실행


<br/><br/>
## 구현된 API

| 메서드 | 경로 | 명세 |
|---|---|---|
| GET | `/api/health` | 3.1 |
| POST | `/api/users` | 3.2 |
| GET | `/api/users/{user_id}` | 3.3 |
| GET | `/api/users/{user_id}/dashboard` | 3.4 |
| GET | `/api/users/{user_id}/growth` | 3.13 |
| PATCH | `/api/users/{user_id}` | 3.12 |
| POST | `/api/search` | 3.5 (RAG는 목 함수) |
| GET | `/api/policies/{policy_id}` | 3.6 |
| GET | `/api/simulations/criteria` | 3.7 |
| POST | `/api/simulations` | 3.8 |
| GET | `/api/simulations/{simulation_id}` | 3.14 |
| POST | `/api/checklists` | 3.9 |
| GET | `/api/checklists/{checklist_id}` | 3.10 |
| PATCH | `/api/checklists/{checklist_id}/items/{item_id}` | 3.11 |

명세에 없어서 따로 정한 부분
- `user_id`가 잘못된 형식이거나 없는 사용자면 항상 404 `USER_NOT_FOUND` (앱은 온보딩으로 이동)
- 사용자 확인(404)을 입력값 검증(422)보다 먼저 함
- 이름, 질문은 앞뒤 공백을 지우고 저장·검색
- 질문 내용은 DB와 로그에 남기지 않음
- 시뮬레이션 금액은 정수만 받음. 배분 합계가 총소득보다 크면 422 `SUM_EXCEEDS_INCOME`
- 시뮬레이션 결과 조회는 본인 기록만 가능. 다른 사용자 기록이면 404 `SIMULATION_NOT_FOUND`
- 체크리스트 생성 시 `policy_id` 형식 오류는 422, 없는 정책은 404 `POLICY_NOT_FOUND`
- 이미 만든 체크리스트는 정책이 비활성화돼도 조회·체크 가능
- 체크 값(`is_checked`)은 `true`/`false`만 받음
- 홈·성장 기록 체크리스트 정렬: 진행 중 → 완료, 각각 최근 수정순 (같으면 나중에 만든 것 먼저)
- 성장 기록 시뮬레이션은 최신순


<br/><br/>
## 시뮬레이션 기준표

- `docs/simulation-criteria.json`(데이터 담당)이 있으면 그 파일을 사용
- 파일이 비어 있으면 `app/simulation_criteria_temp.json`(임시 기준표, 버전 `TEMP-...`)을 사용
- 기준표 형식이 틀리면 서버가 시작되지 않음
- 다른 경로의 기준표를 쓰려면 `.env`에 `SIMULATION_CRITERIA_PATH=경로` 추가


<br/><br/>
## 시뮬레이션 대안 규칙

1. 남은 금액을 부족 항목에 먼저 채움 (주거비 → 식비 → 교통비 → 통신비 → 기타 순서)
2. 그래도 부족하면 기준보다 많이 넣은 항목에서 옮김. 여유가 큰 항목부터 쓰고, 기준값 아래로는 줄이지 않음
3. 첫 번째 대안은 다 못 채워도 보여주고, 남은 부족분은 `remaining_shortages`에 담음
4. 두 번째, 세 번째 대안은 앞에서 쓴 항목을 빼고 만들고, 부족분을 다 채울 때만 보여줌
5. 대안은 최대 3개. 상황에 따라 0개나 1개일 수 있음

| 경우 | 배분 (주거·식·교통·통신·기타) / 총소득 | 대안 |
|---|---|---|
| 명세 예시 | 30만·30만·10만·5만·10만 / 90만 | ① 남은 금액과 식비로 주거비 보완 ② 남은 금액과 교통비·통신비로 주거비 보완 |
| 남은 금액만으로 충분 | 30만·25만·6만·4만·10만 / 100만 | ① 남은 금액으로 주거비 보완 |
| 옮길 돈이 없음 | 30만·25만·6만·4만·10만 / 75만 | 없음 |


<br/><br/>
## RAG 함수 연결 (AI 담당 함수 받으면)

지금은 `app/rag_mock.py`의 임시 함수를 사용. `src/rag/` 함수가 준비되면 `app/rag_client.py`의 import 한 줄만 바꾸면 됨

```python
# app/rag_client.py
import sys
from app.config import PROJECT_ROOT

sys.path.insert(0, str(PROJECT_ROOT / "src"))
from rag.<모듈이름> import search_policies
```

- 함수 형식은 api.md 4장과 같아야 함
- 필요한 패키지(Chroma 등)는 `requirements.txt`에 추가


<br/><br/>
## 참고

- 날짜와 시간은 모두 한국 시간 기준
- `alembic.ini`에는 한글을 쓰지 않음 (Windows 인코딩 문제)
