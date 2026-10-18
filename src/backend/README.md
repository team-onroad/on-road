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
│   ├── llm_client.py        ← LLM 함수 연결 지점 (실제 함수로 바꿀 때 여기만 수정)
│   ├── llm_mock.py          ← LLM 임시(목) 함수
│   ├── crisis.py            ← 위기 신호 키워드, 안내 문구, 연락처
│   ├── roleplay.py          ← 롤플레잉 상황 읽기, 난이도·피드백 규칙
│   ├── roleplay_scenarios.json  ← 롤플레잉 상황 7개 (앱 더미에서 옮김)
│   ├── forms.py             ← 서류 양식 읽기·검사
│   ├── forms.json           ← 서류 양식 5개 (앱 더미에서 옮김)
│   ├── schemas/             ← 요청·응답 형식
│   ├── services/            ← 기능별 처리 로직 (사용자, 정책·검색, 시뮬레이션, 체크리스트, 홈·성장 기록, 상담, 롤플레잉, 서류·초안)
│   └── api/                 ← API 경로 (health, users, policies, simulations, checklists, chat, roleplay, forms)
├── db/
│   ├── models.py            ← 테이블 모델
│   ├── session.py           ← DB 연결
│   ├── seed.py              ← 정책 시드 스크립트
│   ├── demo_seed.py         ← 시연 데이터 스크립트
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

- 이미 만든 DB도 같은 명령으로 최신 구조로 바꿈

### 5. 정책 데이터 넣기

```powershell
python -m db.seed
```

- `data/policies/policies.json`을 DB에 넣음. 여러 번 실행해도 중복되지 않음
- `policies.json`이 비어 있으면 더미 정책 4개(D001~D004)를 넣음
- 실제 정책으로 시드하면 더미 정책은 자동으로 비활성화 (`--dummy-action delete`로 삭제 가능)
- `required_docs` 요소는 `{"name", "condition"}` 객체 또는 문자열. 문자열은 조건 없는 서류

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

## 시연 데이터 넣기

```powershell
docker compose up -d          # 프로젝트 루트
cd src\backend
alembic upgrade head
python -m db.demo_seed
```

- 시연 사용자 user_id: `00000000-0000-4000-8000-000000000001` (김민지, 서울, 퇴소 예정, 목표일 2026-12-31)
- 넣는 데이터
  - 정책: P001 자립수당은 DB에서만 노출 상태와 임시 쉬운 말로 넣음 (`policies.json`은 그대로). 더미 D001~D004는 활성 상태
  - 체크리스트 P001: 대상 확인·조건 확인 체크, 신청서·신분증 체크, 대리 신청 서류 3개 해당 없음
  - 체크리스트 D001: 4단계와 서류 모두 체크 (완료)
  - 체크리스트 D002: 생성만 (진행률 0%)
  - 시뮬레이션 2회 (총소득 100만 원): 부족 없음 1회, 주거비만 부족 1회 (홈 최근 시뮬레이션)
  - 롤플레잉 피드백 2회: 주민센터 전입신고 (확정일자 missed), 은행 계좌 개설 (모두 good)
- 여러 번 실행해도 됨. 시연 사용자가 이미 있으면 기록까지 지우고 다시 만듦
- 시연 뒤 `python -m db.seed`를 실행하면 P001은 다시 비노출, 더미는 비활성화됨


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
| POST | `/api/chat` | 3.15 (LLM은 목 함수) |
| GET | `/api/coach/roleplay/scenarios` | 3.16 |
| GET | `/api/coach/roleplay/{scenario_id}/opening` | 3.17 |
| POST | `/api/coach/roleplay/{scenario_id}/reply` | 3.18 (LLM은 목 함수) |
| POST | `/api/coach/roleplay/{scenario_id}/feedback` | 3.19 |
| GET | `/api/forms/templates` | 3.20 |
| GET | `/api/forms/templates/{template_id}` | 3.21 |
| POST | `/api/users/{user_id}/documents` | 3.22 |

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

조건부 서류 해당 없음 (3.6, 3.10, 3.11)
- 해당 없음인 서류에 `is_checked: false`를 보내면 변화 없음 (해당 없음 유지)
- 체크된 서류에 `is_not_applicable: false`를 보내면 변화 없음 (체크 유지)
- 값이 그대로면 체크리스트 `updated_at`도 그대로
- 시드: `name`이 빈 값이거나 공백만 있으면 오류, `condition` 빈 문자열은 null, 앞뒤 공백은 그대로 저장, 다른 키가 있으면 오류
- 마이그레이션을 0002로 되돌리면 `required_docs`는 서류명만 남음

AI 상담 (3.15)
- 위기 신호는 이번 `message`만 확인 (`history`는 안 봄). `app/crisis.py` 키워드 목록 기준, 띄어쓰기 무시
- `message`, `history[].content`는 앞뒤 공백을 지우고 길이 검사
- `history`가 `null`이면 `[]`로 처리
- 정책 질문은 이번 `message`만 검색에 사용. RAG 오류는 503 `RAG_UNAVAILABLE`
- LLM에 보내는 `stage`는 오늘 기준으로 다시 계산 (DB 값은 안 바꿈)

AI 롤플레잉 (3.16~3.19, 3.13)
- 상황 데이터는 앱 더미 그대로 (명세 예시와 다른 부분 있음, 예: community-center `category` "생활")
- 난이도: 아동은 모든 상황 쉬움, 아르바이트 면접은 청년이면 실전. 목록에도 적용
- `stage`가 없거나 `null`이면 기본 난이도. 대상 단계가 아닌 `stage`로 첫 대사를 요청해도 200, 난이도는 3.16 규칙 (child면 EASY), 그 외 기본 난이도
- 상황 목록은 앱 더미 순서
- 실전은 압박 질문을 대사 사이사이에 섞음
- LLM에는 상황 id, 제목, 상대역, 상황, 목표, 난이도, 대사만 보냄 (체크 항목, 준비 서류, 고친 말 예시는 안 보냄)
- 대사가 끝나면 "네, 오늘은 여기까지 할게요. 수고했어요."
- 위기 신호는 AI 상담과 같은 규칙
- `history`는 빈 배열, `null` 모두 422. 첫 항목이 첫 대사인지는 검사 안 함
- 없는 상황 404 → 없는 사용자 404 → 입력값 422 순서
- `save`는 `true`/`false`만 받음
- 체크 항목: 사용자 발화를 공백 하나로 이어 붙여 키워드 포함 여부 확인 (띄어쓰기 그대로)
- 성장 기록에서 상황 파일에 없는 기록은 `title`에 `scenario_id`를 그대로 넣음
- 성장 기록 `roleplays`는 같은 시각이면 ID 큰 것 먼저

서류 양식, 초안 (3.20~3.22)
- 양식 항목과 초안 안내 문구(`notice`)는 앱 더미 그대로 (명세 예시와 다른 부분 있음)
- 공통 항목은 양식마다 앱 더미와 같음 (프로그램 참가 신청서는 3개, 전입신고서는 성명만)
- 양식 목록은 앱 더미 순서
- 완료한 체크리스트 문장은 성장 기록과 같은 순서. 정책이 비활성이어도 포함
- 흥미 탐색 결과는 받은 문장 그대로, 출처 "흥미 탐색 결과". 빈 문자열은 없는 것으로 봄
- 흥미 탐색 결과는 기록 수에 안 셈. 흥미 결과만 있으면 흥미 문장은 넣고 `notice`는 기록 없음 문구
- 사용자 단계와 상관없이 초안 3종 모두 만듦
- `app_records`가 `null`이면 없는 것과 같음. 빠진 필드는 `saved_policy_count` 0, `interest_summary` null
- `saved_policy_count`는 정수만 (INT 범위). `interest_summary`는 앞뒤 공백 제거 후 500자 이하. 모르는 필드는 422


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
## RAG 함수 연결

`.env`의 `RAG_MODE`로 고른다 (`app/rag_client.py`).

| RAG_MODE | 사용하는 함수 | 필요한 것 |
|---|---|---|
| `mock` (기본) | `app/rag_mock.py` | 없음 |
| `real` | `src/rag/policy_search.py`의 `search_policies` | `.env`의 `OPENAI_API_KEY`, Chroma 인덱스 |

실제 RAG 사용 순서 (PowerShell):

```powershell
# 1) .env 에 RAG_MODE=real, OPENAI_API_KEY=... 입력
# 2) 인덱스 만들기 (정책 원문이 바뀔 때마다 다시 실행). 결과는 data/chroma/ (커밋하지 않음)
cd src                                   # 프로젝트 루트에서
backend\.venv\Scripts\python -m rag.index
cd backend
# 3) 서버 실행
uvicorn app.main:app --reload
```

- 함수 형식은 api.md 4장과 같음. 백엔드 테스트는 `dependency_overrides`로 가짜 함수를 쓰므로 키 없이 돌아감
- RAG 단위 테스트(`tests/test_rag_search.py`)는 가짜 임베딩·가짜 LLM으로 돌아가며 OpenAI를 부르지 않음


<br/><br/>
## 참고

- 날짜와 시간은 모두 한국 시간 기준
- `alembic.ini`에는 한글을 쓰지 않음 (Windows 인코딩 문제)
