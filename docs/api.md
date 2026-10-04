# API 명세 (On-Road)

> 범위: 1차 데모 핵심 기능 1~4번 (정책 검색, 쉬운 말 변환, 생활비 시뮬레이션, 신청 준비 체크리스트)
> DB 구조는 `docs/db-schema.md`, 화면은 프론트엔드 피그마 시안 기준

<br/>

## 1. 공통 규칙

| 항목 | 내용 |
|---|---|
| Base URL | 로컬 `http://localhost:8000/api` (배포 주소는 배포 후 추가) |
| 형식 | 요청·응답 모두 JSON (UTF-8) |
| 필드 이름 | snake_case. Kotlin에서는 직렬화 어노테이션으로 매핑 |
| 사용자 식별 | 로그인 없음. 온보딩 응답의 `user_id`(UUID)를 앱에 저장하고, 필요한 요청마다 전달 |
| 날짜·시각 | 날짜는 `YYYY-MM-DD`, 시각은 ISO 8601 (예: `2026-10-02T09:30:00+09:00`) |
| 날짜 계산 기준 | 만 나이, `d_day`, `is_outdated` 등 모든 날짜 계산은 **한국 시간(KST, UTC+9)의 오늘 날짜** 기준 |
| null 값 | 응답 필드는 각 API의 **null 가능 필드** 표에 적힌 것만 `null`이 될 수 있다. 표에 없는 필드는 항상 값이 있다. 목록(배열)은 비어 있으면 `null`이 아니라 `[]`. 모든 응답 필드의 타입과 null 여부는 **부록** 표에 따로 정리 |
| 금액 | 원 단위 정수 |
| 자동 문서 | 서버 실행 후 `http://localhost:8000/docs`에서 모든 API를 직접 호출해볼 수 있음 |

### 1.1 에러 형식

모든 에러는 아래 형식으로 응답한다.

```json
{
  "error": {
    "code": "USER_NOT_FOUND",
    "message": "사용자를 찾을 수 없습니다."
  }
}
```

| HTTP | code | 상황 |
|---|---|---|
| 404 | `USER_NOT_FOUND` | `user_id`에 해당하는 사용자가 없음 (앱에 저장된 값이 무효하면 온보딩으로 이동) |
| 404 | `POLICY_NOT_FOUND` | 정책이 없거나 서비스 노출 대상이 아님 (비활성 또는 쉬운 말 미검증) |
| 404 | `CHECKLIST_NOT_FOUND` | 체크리스트가 없거나 요청한 사용자의 것이 아님 |
| 404 | `ITEM_NOT_FOUND` | 체크리스트 항목이 없거나 해당 체크리스트 소속이 아님 |
| 404 | `SIMULATION_NOT_FOUND` | 시뮬레이션 기록이 없거나 요청한 사용자의 것이 아님 |
| 422 | `VALIDATION_ERROR` | 필수값 누락, 코드값 오류, 형식 오류 (예: 미래 생년월일, 휴대폰 번호 형식) |
| 422 | `SUM_EXCEEDS_INCOME` | 시뮬레이션 배분 합계가 총소득보다 큼 |
| 503 | `RAG_UNAVAILABLE` | 정책 검색(RAG) 처리 중 오류 |

### 1.2 공통 코드값

코드값은 `db-schema.md` 4장과 같다.

| 필드 | 값 |
|---|---|
| `region` (사용자) | `서울` `부산` `대구` `인천` `광주` `대전` `울산` `세종` `경기` `강원` `충북` `충남` `전북` `전남` `경북` `경남` `제주` |
| `status` | `in_care`(시설 거주) / `leaving_soon`(퇴소 예정) / `left_care`(퇴소 후) |
| `stage` (성장 단계, 서버 계산) | `child`(아동) / `teen`(청소년) / `youth`(자립준비청년) |
| `category` (정책 분야) | `independence`(자립) / `housing`(주거) / `education`(교육) / `employment`(취업) / `living_cost`(생활비) / `medical`(의료) / `finance`(금융) |
| 시뮬레이션 배분 항목 | `housing`(주거비) / `food`(식비) / `transport`(교통비) / `telecom`(통신비) / `other`(기타) |
| 체크리스트 단계 `step_key` | `target_check`(대상 확인) / `condition_check`(조건 확인) / `doc_prepare`(서류 준비) / `apply`(신청) |
| 체크리스트 항목 `item_type` | `step`(단계) / `document`(서류) |

### 1.3 조건 판정 (`eligibility`)

정책 응답에는 사용자 기준 조건 판정이 붙는다. 판정 규칙은 `db-schema.md` 5.3과 같다. 나이는 생년월일로 오늘 기준 만 나이를 계산해서 비교한다.

```json
"eligibility": {
  "status": "match",
  "age": "match",
  "region": "match"
}
```

| 값 | 화면 표시 |
|---|---|
| `match` | 기본 조건(연령·지역) 해당. "기본 조건 해당 · 세부 조건은 공식 원문에서 확인"으로 안내하고, "신청 가능"으로 표시하지 않음 |
| `needs_check` | **조건 확인 필요**. 현재 연령·지역 필터에서는 나오지 않으며, 이후 다른 조건을 필터에 추가할 때 사용 |
| `excluded` | 기본 조건 미해당 (검색 결과에는 나오지 않음) |

- 자격 여부는 "신청 가능"으로 확정하지 않는다 (계획서 7.3). 보호종료 여부·소득 등은 필터하지 않으므로 공식 원문 확인을 안내한다.

### 1.4 정책 공통 필드

- `checked_at`: 정책 최종 확인일. 정책 카드의 "OOOO.OO.OO 기준"에 표시
- `is_outdated`: 최종 확인일이 오늘 기준 **180일**보다 오래됐으면 `true` → "오래된 정보일 수 있음" 경고 표시
- `source_url`: 공식 원문 링크. 정책 관련 응답에는 정책명·담당기관·확인일·원문 링크를 항상 포함한다 (계획서 7.2.1).

### 1.5 체크리스트의 정책 객체 (`policy`)

체크리스트가 들어가는 응답(3.4 홈, 3.9·3.10 체크리스트, 3.13 성장 기록)은 정책 정보를 아래 `policy` 객체 하나로 묶는다. 앱에서 같은 모델을 재사용한다.

```json
"policy": {
  "policy_id": 3,
  "name": "자립수당",
  "agency": "보건복지부",
  "category": "living_cost",
  "contact": "129",
  "apply_url": "https://...",
  "checked_at": "2026-09-28",
  "is_active": true
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `contact` | 문의처 없음. "문의" 문구 숨김 |
| `apply_url` | 공식 신청 페이지 없음. "공식 신청 바로가기" 버튼 숨김 |

- `is_active`: 정책이 현재 서비스 노출 대상인지 (`is_active = true AND easy_text_verified = true`).
  - 체크리스트를 만든 뒤 정책이 비활성화되어도 체크리스트는 계속 조회·체크할 수 있다.
  - `false`면 앱에서 "더 이상 운영되지 않는 정책"으로 표시하고, 정책 상세(3.6, 404 응답)로 이동하지 않는다.

<br/>

## 2. 엔드포인트 목록

| 기능 | 메서드 | 경로 | 설명 |
|---|---|---|---|
| 공통 | GET | `/health` | 서버 상태 확인 |
| 온보딩 | POST | `/users` | 사용자 생성, `user_id` 발급 |
| 온보딩 | GET | `/users/{user_id}` | 저장된 `user_id`가 유효한지 확인, 프로필 조회 (성장 단계 재계산) |
| 회원정보 | PATCH | `/users/{user_id}` | 회원정보 수정 (퇴소 처리, D-day 목표일 설정 등) |
| 홈 | GET | `/users/{user_id}/dashboard` | 인사말, 진행 중인 체크리스트("지금 할 일"), 최근 시뮬레이션 |
| 성장 기록 | GET | `/users/{user_id}/growth` | 체크리스트·시뮬레이션 기록 모아 보기 (3.13) |
| 정책 검색 | POST | `/search` | 질문 → 조건 필터 → RAG 답변 + 근거 (상담 화면) |
| 정책 상세 | GET | `/policies/{policy_id}` | 정책 카드·상세 정보 |
| 시뮬레이션 | GET | `/simulations/criteria` | 배분 기준표 조회 |
| 시뮬레이션 | POST | `/simulations` | 배분 제출 → 부족 판정, 대안, 관련 정책 |
| 시뮬레이션 | GET | `/simulations/{simulation_id}` | 지난 시뮬레이션 결과 다시 보기 (3.14) |
| 체크리스트 | POST | `/checklists` | 체크리스트 조회 또는 생성 (get-or-create) |
| 체크리스트 | GET | `/checklists/{checklist_id}` | 체크리스트 상세 조회 |
| 체크리스트 | PATCH | `/checklists/{checklist_id}/items/{item_id}` | 단계·서류 항목 체크 토글 |

<br/>

## 3. 엔드포인트 상세

### 3.1 `GET /health` — 서버 상태 확인

**응답 200**
```json
{ "status": "ok" }
```

<br/>

### 3.2 `POST /users` — 온보딩

**요청**
```json
{
  "name": "김지민",
  "birth_date": "2005-03-15",
  "phone": "01098765432",
  "region": "서울",
  "status": "leaving_soon",
  "d_date": "2026-11-12"
}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| name | string | O | 1~50자. 온보딩 화면의 "이름" |
| birth_date | string (date) | O | `YYYY-MM-DD`. 오늘보다 미래면 422 |
| phone | string | X | 하이픈 없이 `01`로 시작하는 숫자 10~11자리. 입력하지 않으면 생략하거나 `null` (빈 문자열 `""`도 `null`로 저장) |
| region | string | O | 1.2 코드값 (`전국` 불가). 화면의 시/도 선택값을 약칭으로 변환해서 전송 (6.1) |
| status | string | O | 1.2 코드값 |
| d_date | string (date) | X | D-day 기준일. 퇴소 전이면 퇴소 예정일, 퇴소 후면 목표일 |

- `stage`는 보내지 않는다. 서버가 `birth_date`와 `status`로 계산해서 저장한다 (`db-schema.md` 5.2).

**응답 201**
```json
{
  "user_id": "3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f",
  "name": "김지민",
  "birth_date": "2005-03-15",
  "age": 21,
  "phone": "01098765432",
  "region": "서울",
  "status": "leaving_soon",
  "stage": "youth",
  "d_date": "2026-11-12",
  "d_day": 39,
  "created_at": "2026-10-04T09:30:00+09:00",
  "updated_at": "2026-10-04T09:30:00+09:00"
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `phone` | 휴대폰 번호를 입력하지 않음 |
| `d_date` | D-day 기준일 없음 (퇴소 후 목표일을 아직 정하지 않은 경우 포함) |
| `d_day` | `d_date`가 `null` |

- `age`: 오늘(한국 시간) 기준 만 나이. 저장하지 않고 응답할 때 계산
- `d_day`: `d_date`까지 남은 일수. 오늘이면 `0`, 지났으면 음수, `d_date`가 없으면 `null`. 화면에는 "D-39"처럼 표시
- 예시는 2026-10-04 기준 계산값이다.
- 프론트엔드는 `user_id`를 앱에 저장하고, 이후 요청에 사용한다.
- 성별, 시/군/구는 서버로 보내지 않는다. 휴대폰 번호는 저장만 하고 인증은 하지 않는다 (7장).

<br/>

### 3.3 `GET /users/{user_id}` — 프로필 조회

앱 실행 시 저장된 `user_id`가 유효한지 확인하는 용도. 404면 온보딩으로 이동한다.

- 호출할 때마다 `stage`를 다시 계산하고, 저장된 값과 다르면 갱신한다 (생일이 지나 단계가 바뀌는 경우).

**응답 200**: 3.2 응답과 같은 형식 (null 가능 필드도 같음)

<br/>

### 3.4 `GET /users/{user_id}/dashboard` — 홈

**응답 200**
```json
{
  "user": {
    "user_id": "3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f",
    "name": "김지민",
    "stage": "youth",
    "status": "leaving_soon",
    "d_date": "2026-11-12",
    "d_day": 39
  },
  "checklists": [
    {
      "checklist_id": 12,
      "policy": {
        "policy_id": 3,
        "name": "자립수당",
        "agency": "보건복지부",
        "category": "living_cost",
        "contact": "129",
        "apply_url": "https://...",
        "checked_at": "2026-09-28",
        "is_active": true
      },
      "progress": { "checked_steps": 2, "total_steps": 4, "percent": 50 },
      "next_step": { "step_key": "doc_prepare", "label": "서류 준비" },
      "updated_at": "2026-10-02T10:15:00+09:00"
    }
  ],
  "latest_simulation": {
    "simulation_id": 5,
    "shortage_count": 1,
    "created_at": "2026-10-01T20:00:00+09:00"
  }
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `user.d_date`, `user.d_day` | 3.2와 같음 |
| `checklists[].policy.contact`, `checklists[].policy.apply_url` | 1.5와 같음 |
| `checklists[].next_step` | 4단계를 모두 체크함 (완료) |
| `latest_simulation` | 시뮬레이션 기록 없음 |

- 이 API도 사용자 정보를 내려주므로 3.3과 같이 `stage`를 다시 계산하고, 저장된 값과 다르면 갱신한다.
- 상단 "자립까지 D-39" 표시는 `user.d_day`를 사용한다. `null`이면 숨긴다. 퇴소 후에는 목표일 기준 D-day다.
- 홈의 "지금 할 일" 카드는 `checklists[0]`을 사용한다.
  - 기관·확인일: `policy.agency`, `policy.checked_at` / 분야 태그: `policy.category` / 진행: `progress`
  - "'자립수당' 서류 준비 이어서 하기"처럼 `policy.name`과 `next_step.label`을 사용
  - `checklists`가 비어 있거나 `checklists[0].next_step`이 `null`이면 진행 중인 체크리스트가 없는 것
- `checklists`: 사용자의 체크리스트 **전체** (개수 제한 없음). 없으면 빈 배열. 체크리스트 목록 화면도 이 값을 사용한다.
  - 정렬: 진행 중(`next_step`이 있음)을 먼저, 완료(`next_step == null`)를 뒤에 두고, 각각 안에서 최근 수정순(`updated_at` 내림차순)
- `checklists[].policy`: 1.5 정책 객체. 정책이 비활성화되어도 목록에서 빠지지 않고 `is_active: false`로 내려간다.
- `next_step`: 체크되지 않은 첫 단계. 4단계를 모두 체크했으면 `null`
- `latest_simulation`: 가장 최근 시뮬레이션 요약. 결과 전체는 `simulation_id`로 3.14를 호출해서 다시 본다.

<br/>

### 3.5 `POST /search` — 정책 검색 (상담 화면)

처리 순서: ① 사용자 프로필로 연령·지역 필터 → ② 후보 정책 안에서 RAG 검색 → ③ 답변과 근거를 정책별로 묶어 반환

**요청**
```json
{
  "user_id": "3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f",
  "question": "퇴소하고 나서 매달 받을 수 있는 돈이 있나요?"
}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| user_id | string (UUID) | O | |
| question | string | O | 1~500자 |

**응답 200 — 근거가 있는 경우**
```json
{
  "answer_status": "answered",
  "answer": "보호가 끝난 뒤 매달 지원금을 받을 수 있는 자립수당이 있어요. 자세한 조건은 공식 원문에서 꼭 확인해 주세요.",
  "results": [
    {
      "policy_id": 3,
      "policy_key": "P001",
      "name": "자립수당",
      "agency": "보건복지부",
      "category": "living_cost",
      "checked_at": "2026-09-28",
      "is_outdated": false,
      "eligibility": { "status": "match", "age": "match", "region": "match" },
      "target_description": "지원 대상 및 자격 조건",
      "support_content": "지원 내용",
      "support_amount": "월 OO만 원",
      "support_period": "보호종료 후 O년",
      "original_text": "정책 원문 전체",
      "easy_text": "쉬운 말 변환문",
      "source_url": "https://...",
      "evidences": [
        { "content": "근거 원문 스니펫 1" },
        { "content": "근거 원문 스니펫 2" }
      ]
    }
  ]
}
```

**응답 200 — 근거가 없는 경우**
```json
{
  "answer_status": "no_evidence",
  "answer": null,
  "results": []
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `answer` | `answer_status = "no_evidence"` (근거 없음) |
| `results[].support_amount`, `results[].support_period` | 원문에 금액·기간 정보가 없음. 해당 줄 숨김 |

- 한 번의 질문에 한 번의 답변을 돌려준다. 이전 대화 내용은 서버에 저장하지 않는다.
- `answer`는 AI 담당의 RAG 함수가 만든 문장을 그대로 전달한다 (4장).
- 상담 화면의 답변 아래에는 `results`의 정책 카드를 함께 보여준다. 정책 카드는 6.2 매핑을 따른다.
- 답변의 "체크리스트 바로가기" 버튼은 해당 정책의 `policy_id`로 3.9를 호출한다.
- `answer_status = "no_evidence"`: 경고 스타일 UI로 "확인할 수 있는 공식 정보가 없어요" 안내 (계획서 7.3)
- `excluded` 정책은 `results`에 포함되지 않음
- RAG 처리 시간이 몇 초 걸릴 수 있어 요청 타임아웃은 **30초** 권장

<br/>

### 3.6 `GET /policies/{policy_id}` — 정책 상세

| 쿼리 | 필수 | 설명 |
|---|---|---|
| user_id | X | 넘기면 `eligibility`를 계산해서 포함, 없으면 `eligibility: null` |

**응답 200**
```json
{
  "policy_id": 3,
  "policy_key": "P001",
  "name": "자립수당",
  "agency": "보건복지부",
  "contact": "129",
  "category": "living_cost",
  "region": "전국",
  "age_min": null,
  "age_max": null,
  "target_description": "지원 대상 및 자격 조건",
  "support_content": "지원 내용",
  "support_amount": "월 OO만 원",
  "support_period": "보호종료 후 O년",
  "apply_method": "신청 방법 및 절차",
  "required_docs": ["신분증", "통장 사본"],
  "apply_url": "https://...",
  "source_url": "https://...",
  "original_text": "정책 원문 전체",
  "easy_text": "쉬운 말 변환문",
  "checked_at": "2026-09-28",
  "is_outdated": false,
  "eligibility": { "status": "match", "age": "match", "region": "match" }
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `contact` | 문의처 없음 |
| `age_min`, `age_max` | 나이 하한·상한 없음 |
| `support_amount`, `support_period` | 원문에 금액·기간 정보가 없음. 해당 줄 숨김 |
| `apply_url` | 공식 신청 페이지 없음 |
| `eligibility` | 쿼리에 `user_id`를 넘기지 않음 |

- 시뮬레이션 결과의 "관련 정책 보기"에서도 이 API로 해당 정책을 바로 표시한다.
- 서비스 노출 대상이 아닌 정책은 404 `POLICY_NOT_FOUND`
- 위 값은 형식 예시이며, 실제 내용은 데이터 담당의 `policies.json`을 따른다.

<br/>

### 3.7 `GET /simulations/criteria` — 시뮬레이션 기준표

**응답 200**
```json
{
  "criteria_version": "2026-10-v1",
  "items": [
    { "key": "housing",   "label": "주거비", "minimum": 400000 },
    { "key": "food",      "label": "식비",   "minimum": 250000 },
    { "key": "transport", "label": "교통비", "minimum": 60000 },
    { "key": "telecom",   "label": "통신비", "minimum": 40000 },
    { "key": "other",     "label": "기타",   "minimum": 100000 }
  ]
}
```

- 슬라이더 항목과 순서는 이 응답을 그대로 사용한다.
- 위 금액은 **형식 예시**이며, 실제 값은 데이터 담당의 기준표(5장)를 따른다.
- 소득 프리셋 버튼의 금액은 프론트엔드에서 정한다.

<br/>

### 3.8 `POST /simulations` — 시뮬레이션 제출

**요청**
```json
{
  "user_id": "3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f",
  "total_income": 900000,
  "allocations": {
    "housing": 300000,
    "food": 300000,
    "transport": 100000,
    "telecom": 50000,
    "other": 100000
  }
}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| user_id | string (UUID) | O | |
| total_income | int | O | 1 이상 |
| allocations | object | O | 5개 항목 모두 필수, 각각 0 이상 |

- **5개 항목 합계 ≤ `total_income`** 이어야 한다. 총소득보다 크면 422 `SUM_EXCEEDS_INCOME`
- 프론트엔드에서는 합계가 총소득을 넘으면 제출 버튼을 비활성화한다.

**응답 201**
```json
{
  "simulation_id": 5,
  "criteria_version": "2026-10-v1",
  "total_income": 900000,
  "allocations": {
    "housing": 300000,
    "food": 300000,
    "transport": 100000,
    "telecom": 50000,
    "other": 100000
  },
  "remaining": 50000,
  "shortages": [
    { "item": "housing", "label": "주거비", "input": 300000, "minimum": 400000, "gap": 100000 }
  ],
  "alternatives": [
    {
      "label": "남은 금액과 식비로 주거비 보완",
      "allocations": { "housing": 400000, "food": 250000, "transport": 100000, "telecom": 50000, "other": 100000 },
      "remaining": 0,
      "remaining_shortages": []
    },
    {
      "label": "남은 금액과 교통비·통신비로 주거비 보완",
      "allocations": { "housing": 400000, "food": 300000, "transport": 60000, "telecom": 40000, "other": 100000 },
      "remaining": 0,
      "remaining_shortages": []
    }
  ],
  "related_policies": [
    { "policy_id": 7, "name": "(예시) 주거 지원 정책", "agency": "(예시) 담당기관", "category": "housing" }
  ],
  "created_at": "2026-10-02T11:00:00+09:00"
}
```

- `remaining`: 남은 금액 (총소득 − 배분 합계)
- `shortages`: 입력값이 기준 최소값보다 적은 항목. 부족 항목 하이라이트에 사용
- `alternatives`: 부족 항목이 있을 때 **최대 3개**. 상황에 따라 0개나 1개일 수 있다 (예: 남은 금액만으로 부족분이 채워지면 1개, 남은 금액도 옮길 항목도 없으면 0개). 각 대안의 배분 합계도 총소득 이하
  - 남은 금액이 있으면 먼저 부족 항목에 배정하고, 그래도 부족하면 기준보다 많이 배분된 항목에서 옮긴다.
  - `remaining`: 그 대안을 적용한 뒤 남는 금액
  - `remaining_shortages`: 그 대안을 적용해도 남는 부족 항목 (`shortages`와 같은 형식). 소득이 기준 최소값 합계보다 적으면 비어 있지 않을 수 있음
  - 부족 항목이 없으면 `shortages`와 `alternatives` 모두 빈 배열
- `related_policies`: 부족 항목과 연결된 분야의 정책 중 현재 서비스 노출 대상인 것. "관련 정책 보기"는 3.6 정책 상세로 이동
  - 검색(3.5)과 같이 사용자 기준 조건 판정(1.3)이 `excluded`인 정책은 빼고, 없으면 빈 배열
- 이 응답에는 `null`이 되는 필드가 없다.
- `label`(항목 이름)은 1.2 배분 항목 이름을 그대로 사용

<br/>

### 3.9 `POST /checklists` — 체크리스트 조회 또는 생성

"이 정책 신청 준비하기", 상담 답변의 "체크리스트 바로가기"를 누르거나 체크리스트 화면에 처음 들어올 때 호출한다. 해당 사용자·정책의 체크리스트가 있으면 그대로 반환하고, 없으면 새로 만든다.

**요청**
```json
{
  "user_id": "3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f",
  "policy_id": 3
}
```

**응답**: 새로 만들면 **201**, 이미 있으면 **200**. 본문은 3.10과 같다.

- 이미 있는 체크리스트는 정책이 비활성화됐어도 **200**으로 그대로 반환한다 (1.5).
- 새로 만들 때는 서비스 노출 대상 정책만 가능하다. 아니면 404 `POLICY_NOT_FOUND`

<br/>

### 3.10 `GET /checklists/{checklist_id}` — 체크리스트 상세

| 쿼리 | 필수 | 설명 |
|---|---|---|
| user_id | O | 체크리스트 주인 확인용. 다른 사용자의 체크리스트면 404 `CHECKLIST_NOT_FOUND` |

**응답 200**
```json
{
  "checklist_id": 12,
  "policy": {
    "policy_id": 3,
    "name": "자립수당",
    "agency": "보건복지부",
    "category": "living_cost",
    "contact": "129",
    "apply_url": "https://...",
    "checked_at": "2026-09-28",
    "is_active": true
  },
  "progress": { "checked_steps": 2, "total_steps": 4, "percent": 50 },
  "document_progress": { "checked": 1, "total": 2 },
  "steps": [
    {
      "item_id": 101,
      "step_key": "target_check",
      "label": "대상 확인",
      "is_checked": true,
      "checked_at": "2026-10-02T10:10:00+09:00",
      "detail": {
        "target_description": "지원 대상 및 자격 조건",
        "easy_text": "쉬운 말 변환문"
      },
      "documents": []
    },
    {
      "item_id": 102,
      "step_key": "condition_check",
      "label": "조건 확인",
      "is_checked": true,
      "checked_at": "2026-10-02T10:15:00+09:00",
      "detail": {
        "target_description": "지원 대상 및 자격 조건",
        "eligibility": { "status": "match", "age": "match", "region": "match" }
      },
      "documents": []
    },
    {
      "item_id": 103,
      "step_key": "doc_prepare",
      "label": "서류 준비",
      "is_checked": false,
      "checked_at": null,
      "detail": null,
      "documents": [
        { "item_id": 105, "label": "신분증", "is_checked": true, "checked_at": "2026-10-02T10:20:00+09:00" },
        { "item_id": 106, "label": "통장 사본", "is_checked": false, "checked_at": null }
      ]
    },
    {
      "item_id": 104,
      "step_key": "apply",
      "label": "신청",
      "is_checked": false,
      "checked_at": null,
      "detail": {
        "apply_method": "신청 방법 및 절차",
        "apply_url": "https://..."
      },
      "documents": []
    }
  ]
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `policy.contact`, `policy.apply_url` | 1.5와 같음 |
| `steps[].checked_at`, `steps[].documents[].checked_at` | 체크하지 않은 항목 |
| `steps[].detail` | "서류 준비" 단계 (상세 설명 대신 `documents` 사용) |
| `steps[].detail.easy_text` | 정책이 서비스 노출 대상이 아님 (`policy.is_active = false`). 검증되지 않은 쉬운 말은 보여주지 않음 |
| `steps[].detail.apply_url` | 공식 신청 페이지 없음 |

- `policy`: 1.5 정책 객체. 정책이 비활성화되어도 체크리스트는 계속 조회되며 `policy.is_active: false`로 내려간다.
- `steps`는 항상 4개, 대상 확인 → 조건 확인 → 서류 준비 → 신청 순서
- `progress`(단계 진행률) = 체크된 단계 수 / 4. 홈 "지금 할 일" 카드와 같은 기준
- `document_progress`(서류 진행률) = 체크된 서류 수 / 전체 서류 수. 체크리스트 화면의 "준비 서류 n/m" 표시용
- 서류를 모두 체크해도 "서류 준비" 단계는 자동 체크되지 않으며, 사용자가 직접 체크한다.
- 단계를 눌렀을 때 펼치는 상세 설명은 `detail`을 사용한다. "서류 준비" 단계는 `documents`를 체크박스로 표시한다.
- 하단 "공식 신청 바로가기" 버튼은 `policy.apply_url`, "문의" 문구는 `policy.contact`를 사용한다. 값이 `null`이면 숨긴다.

<br/>

### 3.11 `PATCH /checklists/{checklist_id}/items/{item_id}` — 항목 체크 토글

단계와 서류 항목 모두 이 API로 체크한다. 체크할 때마다 호출하되, 연속 입력은 프론트엔드에서 디바운스(약 300ms) 처리를 권장한다.

| 쿼리 | 필수 | 설명 |
|---|---|---|
| user_id | O | 체크리스트 주인 확인용 |

**요청**
```json
{ "is_checked": true }
```

**응답 200**
```json
{
  "item": {
    "item_id": 106,
    "item_type": "document",
    "step_key": "doc_prepare",
    "label": "통장 사본",
    "is_checked": true,
    "checked_at": "2026-10-02T10:25:00+09:00"
  },
  "progress": { "checked_steps": 2, "total_steps": 4, "percent": 50 },
  "document_progress": { "checked": 2, "total": 2 }
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `item.checked_at` | 체크하지 않은 상태 |

- `item.item_type`: 1.2 코드값 (`step` / `document`)
- `progress`, `document_progress`를 같이 돌려주므로 진행률 바는 이 값으로 바로 갱신한다.
- 같은 값으로 다시 보내도 에러 없이 현재 상태를 반환한다.
- 체크를 해제하면 `checked_at`은 `null`이 된다.
- 항목을 바꾸면 체크리스트의 `updated_at`도 갱신된다 (홈 "지금 할 일" 정렬 기준).

<br/>

### 3.12 `PATCH /users/{user_id}` — 회원정보 수정

퇴소 처리(상태 변경), D-day 목표일 설정, 입력 정보 수정에 사용한다. 바꿀 필드만 보낸다.

**요청 예시 1 — 퇴소 처리**
```json
{ "status": "left_care" }
```

**요청 예시 2 — 퇴소 후 목표일 설정**
```json
{ "d_date": "2027-03-01" }
```

| 필드 | 규칙 |
|---|---|
| name, birth_date, phone, region, status, d_date | 3.2와 같은 규칙. 보낸 필드만 바뀐다 |
| phone, d_date | `null`을 보내면 값을 지운다 |
| name, birth_date, region, status | `null`을 보낼 수 없음 (422) |
| stage | 보낼 수 없음 (서버 계산) |

- 바꿀 필드가 하나도 없으면 422 `VALIDATION_ERROR`
- 스코프 아웃 "회원정보 수정/삭제" 중 **수정**은 프론트엔드 요청(퇴소 처리·목표일 설정)으로 데모 범위에 포함한다. 삭제는 계속 스코프 아웃

**처리 규칙**
- `status` 또는 `birth_date`가 바뀌면 `stage`를 다시 계산한다.
- `status`가 퇴소 후(`left_care`)로 바뀌면 `d_date`를 비운다. 같은 요청에 `d_date`를 함께 보내면 그 값을 목표일로 저장한다.

**응답 200**: 3.2 응답과 같은 형식 (수정 후 값, null 가능 필드도 같음)

<br/>

### 3.13 `GET /users/{user_id}/growth` — 성장 기록

기존 데이터(사용자, 체크리스트, 시뮬레이션)를 모아 보여주는 화면용이다. 새 테이블 없이 조회만 한다.

**응답 200**
```json
{
  "user": {
    "user_id": "3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f",
    "name": "김지민",
    "stage": "youth",
    "status": "leaving_soon",
    "d_date": "2026-11-12",
    "d_day": 39,
    "created_at": "2026-09-20T09:30:00+09:00"
  },
  "summary": {
    "checklist_count": 2,
    "completed_checklist_count": 1,
    "simulation_count": 3
  },
  "checklists": [
    {
      "checklist_id": 12,
      "policy": {
        "policy_id": 3,
        "name": "자립수당",
        "agency": "보건복지부",
        "category": "living_cost",
        "contact": "129",
        "apply_url": "https://...",
        "checked_at": "2026-09-28",
        "is_active": true
      },
      "progress": { "checked_steps": 4, "total_steps": 4, "percent": 100 },
      "document_progress": { "checked": 2, "total": 2 },
      "next_step": null,
      "created_at": "2026-09-25T14:00:00+09:00",
      "updated_at": "2026-10-02T10:15:00+09:00"
    }
  ],
  "simulations": [
    {
      "simulation_id": 5,
      "total_income": 900000,
      "remaining": 50000,
      "shortage_count": 1,
      "criteria_version": "2026-10-v1",
      "created_at": "2026-10-01T20:00:00+09:00"
    }
  ]
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `user.d_date`, `user.d_day` | 3.2와 같음 |
| `checklists[].policy.contact`, `checklists[].policy.apply_url` | 1.5와 같음 |
| `checklists[].next_step` | 4단계를 모두 체크함 (완료) |

- 3.4와 같이 `stage`를 다시 계산하고, 저장된 값과 다르면 갱신한다.
- `user.created_at`: 온보딩한 시각 (함께한 기간 표시 등에 사용)
- `summary.completed_checklist_count`: 4단계를 모두 체크한(`next_step == null`) 체크리스트 수
- `checklists`: 전체 (개수 제한 없음). 정렬은 3.4와 같음. 없으면 빈 배열
- `simulations`: 전체 요약 (개수 제한 없음), 최신순. 없으면 빈 배열. 결과 전체는 `simulation_id`로 3.14를 호출해서 본다.
- 저장한 정책(찜)은 데모 범위 밖이라 포함하지 않는다 (`db-schema.md` 7장).

<br/>

### 3.14 `GET /simulations/{simulation_id}` — 시뮬레이션 결과 조회

홈의 `latest_simulation`이나 성장 기록(3.13)에서 지난 결과를 다시 볼 때 사용한다.

| 쿼리 | 필수 | 설명 |
|---|---|---|
| user_id | O | 시뮬레이션 주인 확인용. 다른 사용자의 기록이면 404 `SIMULATION_NOT_FOUND` |

**응답 200**: 3.8 응답과 같은 형식

- 저장된 입력값과 판정 결과(`shortages`, `alternatives`)를 그대로 돌려준다. 제출 당시의 `criteria_version` 기준이다.
- `related_policies`는 조회할 때 다시 거른다. 그 사이 비활성화된 정책과 사용자 기준 `excluded` 정책은 빠진다 (3.8과 같은 규칙).

<br/>

## 4. 내부 연동 규약 (백엔드 ↔ AI 담당)

`/search` API는 백엔드가 만들고, 그 안에서 AI 담당이 `src/rag/`에 만든 함수를 호출한다. AI 담당은 아래 형식으로 함수를 제공한다.

```python
def search_policies(question: str, candidate_policy_keys: list[str], top_k: int = 5) -> dict:
    ...
```

**입력**

| 인자 | 설명 |
|---|---|
| question | 사용자 질문 원문 |
| candidate_policy_keys | 백엔드가 연령·지역 필터와 서비스 노출 조건으로 걸러낸 정책 키 목록 (예: `["P001", "P007"]`). 이 목록 안에서만 검색 |
| top_k | 가져올 근거 청크 최대 개수 |

**출력**

```python
{
    "status": "answered",            # 또는 "no_evidence"
    "answer": "근거 기반 답변 문장",     # no_evidence면 None
    "evidences": [
        {"policy_key": "P001", "content": "근거 원문 스니펫", "score": 0.82}
    ]
}
```

- 근거가 없으면 답변을 만들지 않고 `status: "no_evidence"`, `answer: None`, `evidences: []`로 반환 (계획서 7.3)
- 답변에서 지원 금액·자격·신청 가능 여부를 근거 없이 만들지 않는다 (계획서 7.3).
- 백엔드는 `evidences`를 `policy_key`별로 묶어서 정책 정보와 함께 3.5 응답을 만든다.
- 후보 목록이 비어 있으면 백엔드가 함수를 호출하지 않고 바로 `no_evidence`로 응답한다.
- 함수가 완성되기 전까지 백엔드는 같은 형식의 목(mock) 함수로 개발한다.

<br/>

## 5. 시뮬레이션 기준표 형식 (백엔드 ↔ 데이터 담당)

`docs/simulation-criteria.json`은 아래 형식으로 작성한다. 백엔드는 이 파일을 읽어서 3.7, 3.8에 사용한다.

```json
{
  "version": "2026-10-v1",
  "source_note": "산정 근거 요약 (예: 통계청 OO 자료, 2025년 기준)",
  "items": {
    "housing":   { "label": "주거비", "minimum": 400000, "policy_categories": ["housing"] },
    "food":      { "label": "식비",   "minimum": 250000, "policy_categories": ["living_cost"] },
    "transport": { "label": "교통비", "minimum": 60000,  "policy_categories": ["living_cost"] },
    "telecom":   { "label": "통신비", "minimum": 40000,  "policy_categories": ["living_cost"] },
    "other":     { "label": "기타",   "minimum": 100000, "policy_categories": ["living_cost", "finance"] }
  }
}
```

| 키 | 규칙 |
|---|---|
| version | 20자 이내 문자열. 기준값을 바꾸면 버전도 변경 |
| items | 5개 항목(`housing`, `food`, `transport`, `telecom`, `other`) 모두 필수 |
| minimum | 월 기준 최소 금액 (원, 정수) |
| policy_categories | 부족할 때 연결할 정책 분야. 1.2 `category` 코드값 |

- 위 금액과 분야 연결은 **형식 예시**이며, 실제 값은 데이터 담당이 근거 자료로 산정한다.

<br/>

## 6. 화면별 API 매핑 (피그마 시안 기준)

### 6.1 온보딩

| 화면 요소 | 처리 |
|---|---|
| 이름 | `name` (50자 이내) |
| 생년월일 | `birth_date` (`YYYY-MM-DD`). 만 나이와 성장 단계는 서버가 계산 |
| 성별 | 서버로 보내지 않음 |
| 핸드폰 번호 | `phone` (하이픈 제거, 선택). "인증" 버튼은 데모에서 동작하지 않음 |
| 집 (시/도) | 아래 표처럼 약칭으로 변환해 `region`으로 전송. 시/군/구는 보내지 않음 |
| 지금 어떤 단계에 계신가요? | 시설 거주 → `in_care`, 퇴소 예정 → `leaving_soon`, 퇴소 후 → `left_care` |
| (퇴소 예정일 / 목표일 입력이 있으면) | `d_date` |
| 시작하기 | 3.2 호출 → `user_id` 저장 → 홈 이동 |

| 화면 표기 | `region` |
|---|---|
| 서울특별시 | `서울` |
| 부산광역시 | `부산` |
| 대구광역시 | `대구` |
| 인천광역시 | `인천` |
| 광주광역시 (옛 행정구역) | `광주` |
| 대전광역시 | `대전` |
| 울산광역시 | `울산` |
| 세종특별자치시 | `세종` |
| 경기도 | `경기` |
| 강원특별자치도 | `강원` |
| 충청북도 | `충북` |
| 충청남도 | `충남` |
| 전북특별자치도 | `전북` |
| 전라남도 (옛 행정구역) | `전남` |
| 전남광주통합특별시 | 기존 광주 지역(자치구)이면 `광주`, 기존 전남 지역(시·군)이면 `전남` |
| 경상북도 | `경북` |
| 경상남도 | `경남` |
| 제주특별자치도 | `제주` |

- 광주광역시와 전라남도는 2026년 7월 1일 **전남광주통합특별시**로 통합됐다. DB 지역 코드는 바꾸지 않고, 화면에서 통합특별시를 하나로 보여주는 경우 시/군/구 선택값으로 `광주`/`전남`을 나눠 보낸다 (8장).

### 6.2 정책 카드

상담 화면의 검색 결과(3.5 `results[]`)와 정책 상세(3.6)에서 같은 필드로 그린다.

| 화면 요소 | 필드 |
|---|---|
| 분야 태그 (예: 주거) | `category` |
| 정책명 | `name` |
| 기관 · OOOO.OO.OO 기준 | `agency`, `checked_at` (`is_outdated`면 경고) |
| 쉬운 말로 보기 토글 | 켜면 `easy_text`, 끄면 `original_text` |
| 지원 대상 & 자격요건 | `target_description` |
| 지원 혜택 & 내용 | `support_content` (있으면 `support_amount`, `support_period` 함께 표시) |
| 근거 원문 보기 | 검색 결과에서는 `evidences[].content`, 정책 상세에서는 `original_text` |
| 공식 원문 링크 | `source_url` |
| 조건 판정 | `match`면 "기본 조건 해당 · 세부 조건은 공식 원문에서 확인", `needs_check`면 "조건 확인 필요" 표시 (1.3) |
| 이 정책 신청 준비하기 | `policy_id`로 3.9 호출 |

### 6.3 홈

| 화면 요소 | 처리 |
|---|---|
| "OOO님, 오늘 할 일부터 챙겨볼까요?" | 3.4 `user.name` |
| 자립까지 D-n | 3.4 `user.d_day` (`null`이면 숨김) |
| 상단 단계 표시 (자립준비청년) | 3.4 `user.stage` |
| 검색창 | 상담 화면으로 이동해 3.5 호출 |
| 지금 할 일 카드 | 3.4 `checklists[0]` (3.4 설명 참고). "이어서 작성하기" → 3.10 |
| 생활비 시뮬레이션 진입 | 3.7 → 3.8 (시안에 진입 버튼이 아직 없음, 8장) |
| 지난 시뮬레이션 결과 다시 보기 | 3.4 `latest_simulation.simulation_id`로 3.14 |
| 성장 기록 | 3.13 |

### 6.4 상담

| 화면 요소 | 처리 |
|---|---|
| 첫 인사 메시지, "청년들이 자주 묻는 주제" | 앱에 고정 문구로 둠. 주제를 누르면 그 문장으로 3.5 호출 |
| 질문 입력 → 답변 | 3.5. 로딩 표시 후 `answer` 표시 |
| 답변 아래 정책 정보 | `results[]`를 6.2 정책 카드로 표시 (정책명·기관·확인일·원문 링크·근거 원문) |
| 체크리스트 바로가기 버튼 | 3.9 |
| 근거 없음 | `answer_status = "no_evidence"`면 경고 스타일 안내 |

### 6.5 체크리스트

| 화면 요소 | 처리 |
|---|---|
| 체크리스트 목록 | 3.4 `checklists` 전체 (진행 중 → 완료 순). 항목을 누르면 3.10 |
| 분야 태그, 정책명 | `policy.category`, `policy.name` |
| 운영 종료 표시 | `policy.is_active = false`면 "더 이상 운영되지 않는 정책"으로 표시하고 정책 상세(3.6)로 이동하지 않음 |
| 준비 서류 n/m 진행 바 | `document_progress` |
| 단계 목록 (4단계) | `steps[]`, 단계 진행률은 `progress` (시안에 단계 목록이 아직 없음, 8장) |
| 서류 카드 이름·체크 | `steps[doc_prepare].documents[]`, 체크는 3.11 |
| 공식 신청 바로가기 | `policy.apply_url` |
| 문의 | `policy.contact` |

<br/>

## 7. 데모에서 백엔드 연동이 없는 UI

아래 요소는 이번 데모 범위(핵심 기능 1~4번) 밖이라 API가 없다. 화면에서 숨기거나 고정 더미 데이터로 보여준다. 추가 기능을 진행하게 되면 그때 API를 추가한다.

| 화면 | 요소 |
|---|---|
| 온보딩 | 성별, 시/군/구, 휴대폰 번호 인증 (번호는 저장만 함) |
| 홈 | 상단 단계 전환(드롭다운으로 다른 단계 보기), 알림, 지금 할 일 카드의 마감 D-day, 다음 일정, AI와 실전 준비하기(부동산 계약 실전 연습), 서류 첨삭 도우미, 맞춤 진로 탐색, 자립 역량 진단, 필수 자립 지식 |
| 정책 카드 | 마감 D-day, 접수 및 신청 기간, 찜(하트), 비교 담기 |
| 체크리스트 | 서류별 발급처 설명, 정부24 바로가기, 공식 서식 다운로드, 서류 파일 첨부·재첨부, AI 가이드 문구 |
| 상담 | 이어지는 대화(빠른 답변 버튼), 음성 입력, 첨부(+) |
| 정책 비교 | 화면 전체 (추가 기능 5번) |
| 앱 추가 화면 (앱 안에서 처리) | 퇴소 D-day 타임라인의 할 일 목록·완료 체크 (기준일은 3.4 `user.d_date`/`d_day` 사용), 저축 목표 (달성 시점을 앱에서 계산하고 로컬 저장), 주거비 비교 (앱에 고정한 기준값으로 계산), 다음 할 일 추천 (3.4 `checklists[].next_step`, `user.d_day`, `latest_simulation`과 앱 로컬 데이터를 조합한 규칙 기반 추천). 데모 이후 타임라인 체크·저축 목표 저장 API를 추가하고 추천 로직을 서버로 옮기는 방향으로 검토 |
| 추가 제안 기능 | AI 상담 채팅 중 고민·진로 상담, AI 롤플레잉 시뮬레이션, 글쓰기·서류 작성 도우미. 핵심 기능 1~4번 완료 후 여유 시 API 추가 (AI 상담 채팅의 정책 검색 부분은 3.5를 그대로 사용) |

<br/>

## 8. 확인 필요 사항

| 대상 | 내용 |
|---|---|
| 프론트엔드 | ✅ 확인 완료: 앱 추가 화면 중 성장 기록은 3.13 API 추가, 나머지(D-day 타임라인, 저축 목표, 주거비 비교, 다음 할 일)는 앱 안에서 처리 (7장) |
| AI 담당 | 4장 함수 형식으로 제공 가능한지, 함수 위치(`src/rag/` 안의 모듈 이름) |
| 데이터 담당 | 5장 기준표 형식으로 작성 가능한지. 권장 비율 추가 여부는 위 첫 번째 항목 결정에 따름 |
| 데이터 담당 | 광주·전남 통합(전남광주통합특별시) 전체를 대상으로 하는 정책이 데이터에 있으면 지역 코드 처리 방식 협의 (데모에서는 `광주`·`전남` 코드 유지) |

<br/>

## 부록. 응답 필드 타입 및 null 여부

Kotlin 모델 작성용 표다. **null** 칸이 `O`인 필드만 `null`이 올 수 있고 (nullable 타입으로 선언), 빈칸은 항상 값이 있다.

| 타입 표기 | JSON 값 | Kotlin 예시 |
|---|---|---|
| string | 문자열 | `String` |
| string (UUID) | `"3f2a9c1e-..."` | `String` |
| string (date) | `"2026-10-04"` | `String` (또는 `LocalDate`) |
| string (datetime) | `"2026-10-04T09:30:00+09:00"` | `String` (또는 `OffsetDateTime`) |
| int | 정수 (금액·개수·나이·일수) | `Int` |
| long | 정수 ID (`policy_id`, `checklist_id`, `item_id`, `simulation_id`) | `Long` |
| boolean | `true` / `false` | `Boolean` |
| array<X> | 목록. 비어 있으면 `[]` | `List<X>` |
| object | 아래 표의 객체 | data class |

### 공통 객체

**Eligibility** (`eligibility`, 1.3)

| 필드 | 타입 | null |
|---|---|---|
| status | string | |
| age | string | |
| region | string | |

**ChecklistPolicy** (`policy`, 1.5)

| 필드 | 타입 | null |
|---|---|---|
| policy_id | long | |
| name | string | |
| agency | string | |
| category | string | |
| contact | string | O |
| apply_url | string | O |
| checked_at | string (date) | |
| is_active | boolean | |

**Progress** (`progress`)

| 필드 | 타입 | null |
|---|---|---|
| checked_steps | int | |
| total_steps | int | |
| percent | int | |

**DocumentProgress** (`document_progress`)

| 필드 | 타입 | null |
|---|---|---|
| checked | int | |
| total | int | |

**NextStep** (`next_step`)

| 필드 | 타입 | null |
|---|---|---|
| step_key | string | |
| label | string | |

**Allocations** (`allocations`)

| 필드 | 타입 | null |
|---|---|---|
| housing | int | |
| food | int | |
| transport | int | |
| telecom | int | |
| other | int | |

**Shortage** (`shortages[]`, `remaining_shortages[]`)

| 필드 | 타입 | null |
|---|---|---|
| item | string | |
| label | string | |
| input | int | |
| minimum | int | |
| gap | int | |

**Error** (모든 에러 응답, 1.1)

| 필드 | 타입 | null |
|---|---|---|
| error.code | string | |
| error.message | string | |

### 3.1 `GET /health`

| 필드 | 타입 | null |
|---|---|---|
| status | string | |

### 3.2 · 3.3 · 3.12 사용자 (User)

| 필드 | 타입 | null |
|---|---|---|
| user_id | string (UUID) | |
| name | string | |
| birth_date | string (date) | |
| age | int | |
| phone | string | O |
| region | string | |
| status | string | |
| stage | string | |
| d_date | string (date) | O |
| d_day | int | O |
| created_at | string (datetime) | |
| updated_at | string (datetime) | |

### 3.4 `GET /users/{user_id}/dashboard`

| 필드 | 타입 | null |
|---|---|---|
| user | object | |
| user.user_id | string (UUID) | |
| user.name | string | |
| user.stage | string | |
| user.status | string | |
| user.d_date | string (date) | O |
| user.d_day | int | O |
| checklists | array<object> | |
| checklists[].checklist_id | long | |
| checklists[].policy | ChecklistPolicy | |
| checklists[].progress | Progress | |
| checklists[].next_step | NextStep | O |
| checklists[].updated_at | string (datetime) | |
| latest_simulation | object | O |
| latest_simulation.simulation_id | long | |
| latest_simulation.shortage_count | int | |
| latest_simulation.created_at | string (datetime) | |

### 3.5 `POST /search`

| 필드 | 타입 | null |
|---|---|---|
| answer_status | string | |
| answer | string | O |
| results | array<object> | |
| results[].policy_id | long | |
| results[].policy_key | string | |
| results[].name | string | |
| results[].agency | string | |
| results[].category | string | |
| results[].checked_at | string (date) | |
| results[].is_outdated | boolean | |
| results[].eligibility | Eligibility | |
| results[].target_description | string | |
| results[].support_content | string | |
| results[].support_amount | string | O |
| results[].support_period | string | O |
| results[].original_text | string | |
| results[].easy_text | string | |
| results[].source_url | string | |
| results[].evidences | array<object> | |
| results[].evidences[].content | string | |

### 3.6 `GET /policies/{policy_id}`

| 필드 | 타입 | null |
|---|---|---|
| policy_id | long | |
| policy_key | string | |
| name | string | |
| agency | string | |
| contact | string | O |
| category | string | |
| region | string | |
| age_min | int | O |
| age_max | int | O |
| target_description | string | |
| support_content | string | |
| support_amount | string | O |
| support_period | string | O |
| apply_method | string | |
| required_docs | array<string> | |
| apply_url | string | O |
| source_url | string | |
| original_text | string | |
| easy_text | string | |
| checked_at | string (date) | |
| is_outdated | boolean | |
| eligibility | Eligibility | O |

### 3.7 `GET /simulations/criteria`

| 필드 | 타입 | null |
|---|---|---|
| criteria_version | string | |
| items | array<object> | |
| items[].key | string | |
| items[].label | string | |
| items[].minimum | int | |

### 3.8 · 3.14 시뮬레이션 결과 (Simulation)

| 필드 | 타입 | null |
|---|---|---|
| simulation_id | long | |
| criteria_version | string | |
| total_income | int | |
| allocations | Allocations | |
| remaining | int | |
| shortages | array<Shortage> | |
| alternatives | array<object> | |
| alternatives[].label | string | |
| alternatives[].allocations | Allocations | |
| alternatives[].remaining | int | |
| alternatives[].remaining_shortages | array<Shortage> | |
| related_policies | array<object> | |
| related_policies[].policy_id | long | |
| related_policies[].name | string | |
| related_policies[].agency | string | |
| related_policies[].category | string | |
| created_at | string (datetime) | |

### 3.9 · 3.10 체크리스트 상세 (Checklist)

| 필드 | 타입 | null |
|---|---|---|
| checklist_id | long | |
| policy | ChecklistPolicy | |
| progress | Progress | |
| document_progress | DocumentProgress | |
| steps | array<object> | |
| steps[].item_id | long | |
| steps[].step_key | string | |
| steps[].label | string | |
| steps[].is_checked | boolean | |
| steps[].checked_at | string (datetime) | O |
| steps[].detail | object | O |
| steps[].documents | array<object> | |
| steps[].documents[].item_id | long | |
| steps[].documents[].label | string | |
| steps[].documents[].is_checked | boolean | |
| steps[].documents[].checked_at | string (datetime) | O |

`steps[].detail`은 단계마다 들어 있는 필드가 다르다. 해당 단계에 없는 필드는 응답에 키가 없으므로, 앱에서는 아래 필드를 모두 nullable로 둔 객체 하나로 받으면 된다.

| 필드 | 타입 | 들어 있는 단계 | null |
|---|---|---|---|
| detail.target_description | string | `target_check`, `condition_check` | |
| detail.easy_text | string | `target_check` | O (정책이 노출 대상이 아니면) |
| detail.eligibility | Eligibility | `condition_check` | |
| detail.apply_method | string | `apply` | |
| detail.apply_url | string | `apply` | O |
| (detail 자체) | — | `doc_prepare` | `null` |

### 3.11 `PATCH /checklists/{checklist_id}/items/{item_id}`

| 필드 | 타입 | null |
|---|---|---|
| item | object | |
| item.item_id | long | |
| item.item_type | string | |
| item.step_key | string | |
| item.label | string | |
| item.is_checked | boolean | |
| item.checked_at | string (datetime) | O |
| progress | Progress | |
| document_progress | DocumentProgress | |

### 3.13 `GET /users/{user_id}/growth`

| 필드 | 타입 | null |
|---|---|---|
| user | object | |
| user.user_id | string (UUID) | |
| user.name | string | |
| user.stage | string | |
| user.status | string | |
| user.d_date | string (date) | O |
| user.d_day | int | O |
| user.created_at | string (datetime) | |
| summary | object | |
| summary.checklist_count | int | |
| summary.completed_checklist_count | int | |
| summary.simulation_count | int | |
| checklists | array<object> | |
| checklists[].checklist_id | long | |
| checklists[].policy | ChecklistPolicy | |
| checklists[].progress | Progress | |
| checklists[].document_progress | DocumentProgress | |
| checklists[].next_step | NextStep | O |
| checklists[].created_at | string (datetime) | |
| checklists[].updated_at | string (datetime) | |
| simulations | array<object> | |
| simulations[].simulation_id | long | |
| simulations[].total_income | int | |
| simulations[].remaining | int | |
| simulations[].shortage_count | int | |
| simulations[].criteria_version | string | |
| simulations[].created_at | string (datetime) | |
