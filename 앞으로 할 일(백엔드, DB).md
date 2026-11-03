## DB

정책 데이터와 상담 AI쪽은 김수정 담당, 필요시 연락 드릴 예정

### `users`에 칼럼 1개 추가

상담 대화에서 "요즘 알바 시작했어요" 같은 말이 나오면 앱이 지금 하는 일을 바꿀지 묻습니다.

현재는 값을 휴대폰에 저장함 (서버에 반영 필요)

| 칼럼 | 타입 | 들어갈 내용 |
| --- | --- | --- |
| work | VARCHAR(20), 빈칸 허용 | 지금 하는 일: `student` 학생 · `working` 일함 · `job_seeking` 구직 중 · `resting` 쉬는 중 |

기존 사용자는 빈칸으로 둡니다

### 새 테이블 `saved_policies`: 사용자가 저장한 정책

| 칼럼 | 타입 | 들어갈 내용 |
| --- | --- | --- |
| id | BIGSERIAL PK |  |
| user\_id | UUID → users, 사용자 삭제 시 같이 삭제 | 저장한 사람 |
| policy\_id | BIGINT → policies | 저장한 정책 |
| created\_at | TIMESTAMPTZ | 저장한 시각 |

(user\_id, policy\_id)는 중복 금지(같은 정책 중복 저장 X)

### 새 테이블 `notifications`: 사용자에게 보낸 알림

| 칼럼 | 타입 | 들어갈 내용 |
| --- | --- | --- |
| id | BIGSERIAL PK |  |
| user\_id | UUID → users, 사용자 삭제 시 같이 삭제 | 받는 사람 |
| policy\_id | BIGINT → policies, 빈칸 허용 | 관련 정책 |
| kind | VARCHAR(20) | 알림 종류: `deadline_soon` 신청 마감 임박 `support_end_soon` 지원 종료 임박 · `policy_changed` 정책 내용 변경 `new_policy` 받을 수 있는 새 정책 |
| title | VARCHAR(100) | 예: 청년 월세 지원 마감 7일 전 |
| body | TEXT | 예: 11월 10일까지 신청해야 해요 |
| due\_date | DATE, 빈칸 허용 | 마감일 또는 지원 종료일 |
| read\_at | TIMESTAMPTZ, 빈칸 허용 | 읽은 시각. 빈칸이면 안 읽음 |
| created\_at | TIMESTAMPTZ | 만든 시각 |

푸시 알림시  휴대폰 토큰(FCM)을 담는 테이블이 하나 더 필요합니다

### `checklists`에 칼럼 1개 추가

사용자가 "이 날까지 신청할게요"라고 정하는 날짜입니다. 정책 날짜 데이터 없이도 알림을 보낼 수 있습니다.

| 칼럼 | 타입 | 들어갈 내용 |
| --- | --- | --- |
| due\_date | DATE, 빈칸 허용 | 사용자가 정한 신청 예정일 |

### 새 테이블 `readiness_snapshots`: 자립 준비도 기록

| 칼럼 | 타입 | 들어갈 내용 |
| --- | --- | --- |
| id | BIGSERIAL PK |  |
| user\_id | UUID → users, 사용자 삭제 시 같이 삭제 | 누구 점수인지 |
| score | SMALLINT | 전체 점수 (0~100) |
| areas | JSONB | 영역별 점수. 예: {"housing": 50, "money": 75, ...} |
| snapshot\_date | DATE | 며칠 점수인지 |

(user\_id, snapshot\_date)는 중복 금지

### (선택) 보호자(담당자) 페이지용 새 테이블 2개

시설 담당자나 자립지원전담요원이 웹 페이지에서 맡은 청년의 준비 상황을 보는 기능입니다.

청년이 앱에서 허락해야만 연결되고, 언제든 끊을 수 있습니다. 상담 대화, 서류 내용, 돈 정보는 보여 주지 않습니다.

**`guardians`: 담당자 계정**

| 칼럼 | 타입 | 들어갈 내용 |
| --- | --- | --- |
| id | BIGSERIAL PK |  |
| name | VARCHAR(50) | 담당자 이름 |
| org | VARCHAR(100) | 소속 기관 (예: OO보육원, OO자립지원전담기관) |
| role | VARCHAR(20) | `facility` 시설 담당자 · `support_worker` 자립지원전담요원 · `other` |
| email | VARCHAR(200), 중복 금지 | 로그인 아이디 |
| password\_hash | VARCHAR(200) | 비밀번호 해시 (원문 저장 안 함) |
| created\_at | TIMESTAMPTZ | 가입한 시각 |

**`guardian_links`: 누가 누구를 볼 수 있는지**

한 줄이 "이 청년이 이 담당자에게 보여 주기를 허락했다"입니다.

| 칼럼 | 타입 | 들어갈 내용 |
| --- | --- | --- |
| id | BIGSERIAL PK |  |
| user\_id | UUID → users, 사용자 삭제 시 같이 삭제 | 청년 |
| guardian\_id | BIGINT → guardians, 삭제 시 같이 삭제 | 담당자. 코드를 입력하기 전에는 빈칸 |
| invite\_code | VARCHAR(8), 빈칸 허용 | 청년이 앱에서 만든 연결 코드. 담당자가 페이지에 입력 |
| invite\_expires\_at | TIMESTAMPTZ, 빈칸 허용 | 코드 만료 시각 (예: 10분) |
| status | VARCHAR(10) | `pending` 코드만 만듦 · `active` 연결됨 · `revoked` 청년이 끊음 |
| scopes | JSONB | 보여 줄 항목. 예: \["readiness", "checklists", "deadlines", "practice"\] |
| created\_at | TIMESTAMPTZ |  |
| revoked\_at | TIMESTAMPTZ, 빈칸 허용 | 끊은 시각 |

## 백엔드

| 만들 API (예시 경로) | 하는 일 | 쓰는 DB |
| --- | --- | --- |
| `PATCH /api/users/{id}` 수정 | `work` 값을 받기. 채팅 `profile_change`에서 `work`도 다른 항목처럼 PATCH로 반영되게 | users |
| `POST` · `DELETE` · `GET /api/users/{id}/saved-policies` | 정책 저장 · 저장 취소 · 저장 목록. 없는 정책이면 404, 이미 저장했으면 그대로 성공 | saved\_policies |
| `GET /api/users/{id}/notifications` | 알림 목록 (최신순), 안 읽은 개수 | notifications |
| `PATCH /api/users/{id}/notifications/{nid}/read` | 알림 읽음 처리 (`read_at` 채우기) | notifications |
| 알림 만들기 (하루 한 번 실행) | 저장한 정책을 보고 알림을 한 줄씩 추가. 같은 알림은 두 번 만들지 않음 | saved\_policies, policies, notifications |
| 문서 작성 `app_records` | 앱이 보내던 "저장한 정책 개수"를 `saved_policies`에서 직접 세도록 (앱이 안 보내도 되게) | saved\_policies |
| 한 달 살아보기 | 앱에서 결과 항목이 바뀌면 `MonthLifeData` 스키마와 자립 준비도 항목 맞추기. 실제 수입·지출은 저장하지 않음 | activity\_logs |

### 알림을 만드는 기준

| kind | 언제 만드나 | 필요한 것 |
| --- | --- | --- |
| deadline\_soon | 저장한 정책의 신청 마감 7일 전, 하루 전 | 정책 마감일 (정책 담당에게 받음) |
| support\_end\_soon | 저장한 정책의 지원 종료 90일 전 | 지원 기간 + 사용자 퇴소일(`users.d_date`) |
| policy\_changed | 저장한 정책 내용이 바뀌었을 때 | 정책 `updated_at`이 마지막 알림보다 늦으면 |
| new\_policy | 사용자가 받을 수 있는 정책이 새로 들어왔을 때 | 정책 `created_at` + 기존 추천 조건(나이·지역) |

`policy_changed`와 `new_policy`를 먼저. 날짜 알림 2가지는 날짜 데이터를 주면 연결하기

### 추가 기능 API

| API (예시 경로) | 하는 일 | 쓰는 DB |
| --- | --- | --- |
| `GET /api/users/{id}/growth` 수정 | 연습 횟수를 `roleplay_results`만 세고 있어서, "기록에 남기기"를 안 누른 연습과 한 달 살아보기가 빠짐. `activity_logs`도 같이 세기 | activity\_logs |
| `GET /api/users/{id}/readiness` 수정 | 계산할 때 오늘 점수를 저장하고, 응답에 "지난주 점수", 최근 점수 목록 추가 | readiness\_snapshots |
| `PATCH /api/checklists/{id}` 새로 (지금은 항목 체크용 PATCH만 있음) | 신청 예정일(`due_date`) 받기. 알림 종류에 `checklist_due`(예정일 3일 전) 추가 | checklists, notifications |
| POST /api/coach/roleplay/{id}/feedback 수정 | 연습이 끝나면 서버가 activity\_logs에 바로 남기기. 지금은 앱이 activities API를 한 번 더 불러야 해서, 그 호출이 실패하면 자립 준비도에서 빠짐 | activity\_logs |
| GET /api/users/{id}/activities 새로 | 지난 한 달 살아보기 결과·연습 기록 목록. 지금은 남기기만 하고 다시 볼 방법이 없음 | activity\_logs |
| GET /api/users/{id}/roleplays/{rid} 새로 | 기록에 남긴 연습 결과 다시 보기 (챙긴 것·놓친 것). 지금은 성장 기록에 요약만 있음 | roleplay\_results |

### 개선할 점 - develop 브랜치(`7b8474d`) 기준

| 중요도 | 무엇 | 지금 | 고칠 방법 |
| --- | --- | --- | --- |
| 높음 | 사용자 확인 | 로그인·토큰이 없어 user\_id만 알면 누구나 그 사람 서류·상담 정보를 보고 고칠 수 있음 | 가입 응답으로 기기 토큰 발급, 모든 사용자 API가 `Authorization` 헤더로 확인 (`api/deps.py`에 공통 의존성 하나). DB는 users에 토큰 해시 칼럼 `token_hash` |
| 높음 | 삭제 API | `DELETE` API가 하나도 없음. 탈퇴해도 개인정보가 남고, 쓰던 서류·체크리스트도 지울 수 없음 | `DELETE /users/{id}`(탈퇴, 테이블이 이미 CASCADE라 한 줄로 전부 삭제), 서류·체크리스트 삭제 |
| 중간 | AI 요청이 서버를 막음 | API가 동기 함수라 서버 스레드 40개를 나눠 씀. 서류 읽기(최대 60~120초) 같은 긴 AI 요청이 겹치면 홈·체크리스트 같은 빠른 요청도 기다림 | AI를 부르는 API만 동시에 N개까지 돌게 제한하거나, 비동기 OpenAI 클라이언트로 바꾸기 |
| 중간 | AI를 기다리는 동안 DB 연결이 묶임 | 요청마다 사용자를 조회한 뒤, AI 답을 기다리는 동안에도 DB 연결을 계속 잡고 있음. DB 연결은 기본값인 최대 15개라서, 긴 AI 요청이 15개 겹치면 나머지 요청은 DB를 못 쓰고 30초 뒤 오류가 남. | AI 호출 전에 연결을 돌려주거나, DB 연결 개수를 늘리기 (db/session.py의 pool\_size·max\_overflow) |
| 중간 | AI 호출 횟수 제한 | 상담·롤플레잉·서류 읽기는 부를 때마다 OpenAI 비용이 나는데 제한이 없음 | 사용자별 1분에 N번 제한, 넘으면 429 (`cache.py`처럼 서버 메모리로 충분) |
| 중간 | 예상 못 한 오류 | 오류 처리기가 정해 둔 오류 3종류만 있어, 그 밖의 500 오류는 다른 형식으로 나가고 내용도 남지 않음 | `errors.py`에 나머지 오류 처리기 추가: 로그를 남기고 같은 오류 형식(`INTERNAL_ERROR`)으로 응답 |
| 중간 | AI가 안 될 때 대신할 것 | 정책 검색은 AI 검색(RAG)이 실패하면 503만 돌려줌. OpenAI가 느리거나 키가 만료되면 상담 화면에서 정책을 하나도 못 찾음 | 서버에 이미 있는 키워드 검색(rag\_mock.py)으로 대신 결과를 돌려주고 "간단 검색 결과"라고 표시. 상담 AI 담당과 협의 |
| 낮음 | 응답 시간 기록 | 어떤 API가 얼마나 느린지 남지 않음 | main.py에 요청별 처리 시간을 로그로 남기는 미들웨어. 느린 API를 찾고, 발표 때 "평균 응답 n초" 근거로 씀 |
| 낮음 | 가입 중복 | POST /users를 부를 때마다 새 사용자가 생김. 앱을 다시 깔면 기록이 남은 이전 계정을 찾을 방법이 없음 | 사용자 확인(토큰)을 넣을 때 같이: 휴대폰 번호 등으로 이전 계정 되찾기 |
| 낮음 | 목록 길이 | 성장 기록이 시뮬레이션·연습을 전부 돌려줌 | 최근 N개만 돌려주거나 페이지 나누기 |
| 낮음 | 인덱스 | `documents.policy_id`, `documents.checklist_item_id`에 인덱스가 없어 정책·체크리스트 지울 때 서류를 전부 훑음 | 두 칼럼에 인덱스 추가 |

### (선택) 보호자(담당자) 페이지 API

페이지 자체(웹 화면)는 따로 만들어야 합니다. 아래는 그 페이지와 앱이 쓸 API입니다.

| API (예시 경로) | 누가 씀 | 하는 일 |
| --- | --- | --- |
| `POST /api/users/{id}/guardian-invites` | 앱 (청년) | 연결 코드 만들기. 보여 줄 항목(scopes)을 고름 |
| `GET /api/users/{id}/guardians` | 앱 (청년) | 내가 연결한 담당자 목록 |
| `DELETE /api/users/{id}/guardians/{gid}` | 앱 (청년) | 연결 끊기 (status = revoked) |
| `POST /api/guardian/signup`, `/login` | 페이지 (담당자) | 담당자 가입·로그인, 토큰 발급 |
| `POST /api/guardian/links` | 페이지 (담당자) | 연결 코드 입력 → 연결 (만료·잘못된 코드는 거절) |
| `GET /api/guardian/users` | 페이지 (담당자) | 맡은 청년 목록: 이름, 자립 준비도, 가장 약한 영역, 다가오는 마감 수, 마지막 활동일 |
| `GET /api/guardian/users/{id}` | 페이지 (담당자) | 한 명 상세: 영역별 준비도, 체크리스트 진행, 다가오는 마감, 최근 연습. 허락한 항목(scopes)만 |

담당자 화면에서 가장 쓸모 있는 건 "오래 활동이 없는 청년", "마감이 곧인데 준비가 안 된 청년"을 먼저 보여 주는 것입니다. 목록을 이 순서로 정렬합니다.

### API 응답 속도 개선

지금도 gzip 압축, 고정 데이터 캐시, 정책 검색 캐시(`cache.py`)는 들어가 있습니다. 그 외에 해 볼 만한 것입니다.

| 무엇 | 지금 | 고칠 방법 |
| --- | --- | --- |
| 성장 기록 화면 요청 수 | 성장 기록 화면이 성장 기록과 자립 준비도를 따로 불러 서버를 두 번 다녀옴 (타임라인도 준비도를 따로 부름) | 성장 기록 응답에 자립 준비도를 같이 넣기 |
| 자립 준비도 계산 | 볼 때마다 활동 기록·연습 기록·서류·체크리스트를 전부 다시 읽어 계산 | 사용자별로 결과를 잠깐 저장하고, 활동·연습·체크가 생길 때만 다시 계산 (위 `readiness_snapshots`와 같이 하면 됨) |
| 정책 상세 | 정책 상세·쉬운 말·작성 도우미는 잘 안 바뀌는데 매번 새로 받음 (`no-cache`) | 사용자별 자격 표시를 뺀 부분만 서버 캐시에 담기 |
| 느린 API 찾기 | 어느 API가 느린지 모름 | 요청별 처리 시간 로그 (위 개선할 점의 "응답 시간 기록")로 먼저 재고, 제일 느린 것부터 |

## 같이 정해야 할 것

- 알림을 앱 안 목록만 할지, 푸시까지 할지
- "하루 한 번 실행"을 어디서 돌릴지 (서버 안 스케줄러, 또는 서버 밖 cron)
- 정책 담당에게 받을 날짜 데이터의 형식
