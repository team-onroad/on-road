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
| 409 | `DOCUMENT_WRITTEN` | 문서 AI 로 써서 내보낸 서류 항목을 직접 체크 해제·해당 없음으로 바꾸려 함 (서류 작성 화면에서 칸을 다시 고치면 풀림) |
| 404 | `SIMULATION_NOT_FOUND` | 시뮬레이션 기록이 없거나 요청한 사용자의 것이 아님 |
| 422 | `VALIDATION_ERROR` | 필수값 누락, 코드값 오류, 형식 오류 (예: 미래 생년월일, 휴대폰 번호 형식) |
| 422 | `SUM_EXCEEDS_INCOME` | 시뮬레이션 배분 합계가 총소득보다 큼 |
| 413 | `PAYLOAD_TOO_LARGE` | 요청 본문이 너무 큼. 사진·PDF 올리기(`/documents/upload`, `/documents/risk-check`)는 14MB, 나머지는 1MB (본문을 다 받기 전에 막음) |
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
| `needs_check` | **조건 확인 필요**. 시·군·구 정책(담당기관이 "서울특별시 서초구"처럼 시·군·구)이고 사용자와 시/도가 같을 때 지역이 `needs_check`. 사용자 지역은 시/도까지라 같은 시·군·구인지 알 수 없음 (db-schema.md 5.3) |
| `excluded` | 기본 조건 미해당 (검색 결과에는 나오지 않음) |

- 자격 여부는 "신청 가능"으로 확정하지 않는다 (계획서 7.3). 보호종료 여부·소득 등은 필터하지 않으므로 공식 원문 확인을 안내한다.
- `care` (보호받던 곳): 정책 이름 · 대상에 보호받던 곳(아동복지시설 · 가정위탁 · 청소년복지시설)이 나오고 사용자 `care_type`이 있을 때만 `match` · `excluded`. 이름에 청소년복지시설만 나오면 청소년복지시설 출신만, 자립준비청년 · 아동복지시설 · 가정위탁이 나오면 그 출신만, 가정위탁 전용 이름(시설 언급 없음)은 가정위탁 출신만. `excluded`면 목록에서 빠진다 (가족을 위한 아동 정책은 그대로)
- `leave` (보호 상태): 대상 문장에 보호종료 · 퇴소 · 자립준비가 들어간 정책(퇴소해야 받는 정책)만 값이 있다. 사용자가 아직 보호 중(`in_care` · `leaving_soon`)이면 `after_leave`(지금은 해당 안 됨, 퇴소 후 신청), 퇴소했으면(`left_care`) `now`. 18세 이상으로 아직 보호 중(보호연장)이고 대상에 "퇴소일을 연장한 … 포함", "보호연장아동 포함"처럼 연장한 사람도 적혀 있으면 `now` (맞춤 신청 로드맵 3.32 에서도 '지금 신청'). 지금 보호받는 아동 전용 정책이나 보호 상태와 상관없는 정책은 `null`. `status`는 나이 · 지역만 본 값이라, 앱은 `after_leave`면 "보호가 끝난 뒤 신청"을 따로 보여 준다

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
| 신청 로드맵 | GET | `/users/{user_id}/policy-roadmap` | 받을 수 있는 정책을 신청 시점별로, 월 합계·최대 총액 (3.32) |
| 진로 로드맵 | GET | `/users/{user_id}/career-roadmap` | 취업·진학·직업훈련별 할 일, 연습·서류 연결, 관련 정책, 공식 사이트 (3.33) |
| 같은 종류 정책 | GET | `/users/{user_id}/same-kind/{policy_id}` | 같은 종류(자립정착금 등) 전체, 다른 지역 것까지. `{kind, kind_title, rule, total, picked: {policy_id, name, region, amount_text, reason} \| null, others: [{policy_id, name, region, amount_text, mine}]}`. 받는 돈이 큰 순, 금액을 모르면 뒤. 묶이지 않는 정책이면 404 `SAME_KIND_NOT_FOUND` |
| 추천 정책 | GET | `/users/{user_id}/recommended-policies` | 홈 '나에게 맞는 지원 정책', 추천 순서대로 최대 8개 (3.34) |
| 저장 · 받는 중 | POST · GET · DELETE | `/users/{user_id}/saved-policies` | 정책 저장, 지금 받고 있는 정책 표시(`status: receiving`, `started_on`), 목록 · 취소 (3.36) |
| 교육 이수 · 자격증 | POST · GET · DELETE | `/users/{user_id}/credentials`, `/users/education-options` | 이수한 교육 · 취득한 자격증 등록 · 목록 · 삭제. 로드맵 의무교육 표시와 이력서 초안에 쓴다 (3.37) |
| 정책 검색 | POST | `/search` | 질문 → 조건 필터 → RAG 답변 + 근거 (상담 화면) |
| 정책 상세 | GET | `/policies/{policy_id}` | 정책 카드·상세 정보 |
| 쉬운 말 | GET | `/policies/{policy_id}/easy-text` | 단계별(L1~L7) 쉬운 말 보기 (3.23) |
| 신청서 도우미 | GET | `/policies/{policy_id}/form-guide` | 신청서 칸별 안내·미리 채운 값 (3.24) |
| 시뮬레이션 | GET | `/simulations/criteria` | 배분 기준표 조회 |
| 시뮬레이션 | POST | `/simulations` | 배분 제출 → 부족 판정, 대안, 관련 정책 |
| 시뮬레이션 | GET | `/simulations/{simulation_id}` | 지난 시뮬레이션 결과 다시 보기 (3.14) |
| 체크리스트 | POST | `/checklists` | 체크리스트 조회 또는 생성 (get-or-create) |
| 체크리스트 | GET | `/checklists/{checklist_id}` | 체크리스트 상세 조회 |
| 체크리스트 | PATCH | `/checklists/{checklist_id}/items/{item_id}` | 단계·서류 항목 체크 토글 |
| 문서 AI | POST | `/documents` | 서류 작성 시작 (3.25, [document-ai.md](document-ai.md)) |
| 문서 AI | GET | `/forms/common` | 공통 양식 목록: 퇴소 · 재보호 · 보호연장 신청서 등 정책과 상관없이 직접 내는 서류. 누르면 `POST /documents` 에 `source_type: policy_form`, `form_id` 만 (`policy_id` 없이) ([policy-forms.md](policy-forms.md)) |
| 문서 AI | GET | `/users/{user_id}/documents` | 내 서류 목록 (3.26) |
| 문서 AI | GET | `/documents/{document_id}` | 작성 화면 (3.27) |
| 문서 AI | PATCH | `/documents/{document_id}/fields/{field_id}` | 칸 수정·완료 (3.28) |
| 문서 AI | POST | `/documents/{document_id}/assist` | 프롬프트 → 선택한 칸 작성 (3.29) |
| 문서 AI | GET | `/documents/{document_id}/check` | 내보내기 전 검사 (3.30) |
| 문서 AI | POST | `/documents/{document_id}/export` | 내보내기·체크리스트 체크 (3.31) |
| 진로 외부 정보 | GET | `/career/exams?cert=` | 자격증 시험 일정, 큐넷 (3.35) |
| 진로 외부 정보 | GET | `/career/trainings?keyword=&region=` | 내일배움카드 훈련과정, 고용24 (3.35) |
| 진로 외부 정보 | GET | `/career/job-info?name=` | 직업 정보, 커리어넷 (3.35) |
| 진로 외부 정보 | GET | `/career/postings?keyword=&region=` | 채용 공고, 고용24 (3.35) |
| 진로 외부 정보 | GET | `/career/trainings/detail?id=&round=&institution=` | 훈련과정 소개, 고용24 (3.35) |
| 진로 외부 정보 | GET | `/career/duties?keyword=` | 직무 정보, 고용24 직무데이터사전 (3.35) |

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
- `care_type`: 보호받던 곳. `child_facility`(아동복지시설: 양육시설 · 공동생활가정 등) · `foster`(가정위탁) · `youth_facility`(청소년복지시설: 청소년쉼터 · 자립지원관 등) · null(모름). 가입(3.1) · 수정(3.3)에서 받고, 수정에서 null 이면 지운다. 보호받던 곳이 다른 제도는 추천 · 검색 · 로드맵에서 빠진다 (`eligibility.care`)
- `interest_code` · `interest_tags`: 흥미 탐색 상위 유형("RA", R · I · A · S · E · C 1~3글자)과 고른 관심 분야(최대 3개, 하나에 20자, `#` · 공백 · 중복은 정리). 수정(3.3)에서 `interest_tags`는 보낸 목록으로 통째로 바꾸고(null 불가, 빈 목록이면 지움), `interest_code`는 null · 빈 문자열이면 지운다
- `district`: 거주 시 · 군 · 구 ("서초구", 시 · 군 · 구로 끝나는 이름). 없으면 `null`. 수정(3.3)에서 `null` · 빈 문자열이면 지운다. `region` 을 바꾸면서 `district` 를 보내지 않으면 지워진다. 있으면 다른 시 · 군 · 구 정책은 목록 · 검색에서 빠진다
- `family`: 자녀 · 동생 목록. 요청 항목은 `relation`(`child` / `sibling`), `gender`(`male` / `female` / `null`), `birth_date`(YYYY-MM-DD). 응답에는 `age`(만 나이)가 더 붙는다. 수정(3.3)에서는 보낸 목록으로 통째로 바뀐다 (빈 목록이면 모두 지움, `null` 불가, 10명까지). 가족 중 한 명의 나이가 맞는 아동 대상 정책(나이 상한 17세 이하)은 목록 · 검색에 `for_family = true` 로 나온다. `gender` 는 저장만 한다 (정책 데이터에 성별 조건이 없음)
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
      ],
      "summary": {
        "sections": [
          { "label": "지원 대상", "items": ["보호종료 전 2년 이상 연속 보호", "만기·연장 종료: 종료 후 5년 이내"] },
          { "label": "지원 내용", "items": ["월 50만 원, 매월 20일 지급"] }
        ],
        "notice": "나이와 지역만으로는 받을 수 있는지 확정할 수 없습니다. 담당 기관에 꼭 확인하시기 바랍니다.",
        "verified": false
      }
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
| `results[].summary` | 카드 요약이 아직 없거나 자동 검사를 통과하지 못함. `target_description` 등 기존 필드로 표시 |
| `results[].summary.notice` | 안내 문구 없음. 숨김 |

- 한 번의 질문에 한 번의 답변을 돌려준다. 이전 대화 내용은 서버에 저장하지 않는다.
- `answer`는 AI 담당의 RAG 함수가 만든 문장을 그대로 전달한다 (4장).
- 상담 화면의 답변 아래에는 `results`의 정책 카드를 함께 보여준다. 정책 카드는 6.2 매핑을 따른다.
- `for_family`: `true` 면 본인 나이로는 대상이 아니지만 가족(`family`) 중 한 명의 나이가 맞는 아동 대상 정책. 앱은 본인 정책과 따로 묶는다. 이때 `eligibility.age` 는 `needs_check`
- `related`: `true` 면 답변 근거가 아니라 질문과 뜻이 가까워 덧붙인 정책(최대 3개, 답변 근거 카드 뒤). 앱은 '비슷한 정책'으로 나눠 보여 주고 근거 문장으로 쓰지 않는다. 없으면 `false`.
- `summary`: AI 담당 핵심 사실 추출 결과(`data/policy_facts/{policy_key}.json`, `src/easytext/facts.py`)로 만든 카드용 짧은 요약. 원문과 대조하는 자동 검사를 통과한 것만 준다. 항목(`sections[].label`)은 지원 대상·받을 수 없는 경우·지원 내용·지원 기간·신청 방법 순서이고, 내용이 없는 항목은 빠진다. `verified = false`면 "검토 중" 안내를 함께 표시한다.
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
  "eligibility": { "status": "match", "age": "match", "region": "match" },
  "amount_won": 500000,
  "amount_cycle": "monthly",
  "amount_text": "월 50만 원",
  "period_years": 5,
  "benefit": "월 50만 원, 매월 20일 지급",
  "related": [
    { "policy_id": 7, "policy_key": "G012", "name": "자립정착금", "agency": "서울특별시",
      "category": "living_cost", "amount_text": "2,000만 원", "benefit": "자립정착금 1인 2,000만 원" }
  ]
}
```

| null 가능 필드 | null일 때 |
|---|---|
| `contact` | 문의처 없음 |
| `age_min`, `age_max` | 나이 하한·상한 없음 |
| `support_amount`, `support_period` | 원문에 금액·기간 정보가 없음. 해당 줄 숨김 |
| `apply_url` | 공식 신청 페이지 없음 |
| `eligibility` | 쿼리에 `user_id`를 넘기지 않음 |
| `amount_won`, `amount_cycle`, `amount_text` | `support_amount`에서 금액을 읽지 못함 (없음, 1억 원 이상 등). 앱은 원문 문장을 보여 준다 |
| `period_years` | `support_period`에 "보호종료 후 N년" 같은 표현이 없음 |
| `benefit` | 카드 요약(`data/policy_facts`)이 없거나 검사를 통과하지 못함 |
| `conditions` · `exclusions` | 빈 목록 `[]` (카드 요약이 없으면). 카드 요약의 '지원 대상' · '받을 수 없는 경우' 구절 |
| `required_education` | 들어야 받을 수 있는 교육. `settlement`(자립정착금 의무교육) · `finance`(자립수당 자립교육) · null (3.37) |
| `apply_ways` · `apply_deadline` | 빈 목록 `[]` · `null` (정리본이 없으면). `data/apply_guide.json`: 신청 방법을 갈래(`way`: 방문 · 온라인 · 우편·팩스 · 전화 · 카카오톡 · 따로 신청하지 않아요 · 기관이 대신 신청해요 · 문의 · 신청)별 쉬운 문장(`lines`)으로 다시 쓴 것. 고칠 때는 `scripts/build_apply_guide.py`를 고치고 실행 |
| `apply_steps` · `doc_facts` | 빈 목록 `[]` (핵심 사실이 없으면). 신청 방법 · 필요 서류를 짧게 나눈 구절. 서류 구절의 괄호는 언제 내는지 ("방문 신청 시", "대리 신청 시"). 비어 있으면 앱은 `apply_method` · `required_docs` 원문을 정리해 보여 준다 |
| `forms` | 빈 목록 `[]` (양식이 없으면 앱은 '신청서 쓰기'를 숨긴다). 이 정책 신청에 쓰는 서버 양식 `[{form_id, title, checked, notice, doc_name}]`: 정책 자체 신청서 먼저, 그다음 연결표(`data/forms/index.json`). `doc_name` 은 그 양식으로 쓰는 `required_docs[].name` (없으면 null). 누르면 `POST /documents` 에 `source_type: policy_form`, `policy_id`, `form_id`. `form_id` 없이 시작하면 정책 자체 신청서, 없으면 연결표 첫 양식 |

- 금액 읽기 규칙 추가: 내는 돈(임대보증금 · 임대료 · 본인부담) 앞의 금액은 건너뛴다. `support_amount`에 주기가 없으면 `support_content`의 "(인/월)", "/월"로 월 지급을 알아내고, 하루 단위("1일/2.4천원")는 읽지 않는다
- 시연용 더미 정책(D001~)은 `RAG_MODE=real` 서버에서 검색 · 분야별 보기 · 로드맵 · 추천 · 관련 정책에 넣지 않는다 (id 로 여는 상세는 그대로)
- 금액 필드 (`app/money.py`, 규칙): `support_amount`는 수집할 때(`collector/convert.py` `normalize_amount`) 단위를 맞춰 저장한다 (`300천원` → `30만 원`, 1억 원 이상은 사업 예산으로 보고 비움). 응답에는 앱이 계산에 쓸 숫자를 함께 준다.
  - `amount_won`: 원 단위 숫자. `amount_cycle`: `monthly` 매달 · `yearly` 매년 · `once` 한 번 · null 알 수 없음
  - `amount_text`: 앱 표기 ("월 50만 원", "1,000만 원", "월 최대 20만 원")
- `benefit` (받는 것 한 줄): 카드 요약의 "지원 내용" 첫 항목 ("주거(오피스텔) 지원", "건강보험 본인부담금 경감 지원"). 금액 표기는 앱 표기로 맞춘다. 돈이 아닌 정책이 많아서 앱은 금액이 없으면 이 문장을 "받는 것"으로 보여 준다. `related`, 3.32 로드맵, 3.33 진로 로드맵, 상담 `plan` 항목에도 같은 값이 들어간다
- `same_kind` (같은 종류 중 이 정책의 자리): `user_id`를 넘기고, 그 사람이 받을 수 있는 같은 종류 정책(3.32 `kind`)이 2개 이상일 때만. `{kind_title, is_best, reason, best: {policy_id, name, region, amount_text}, others: [...]}`. `is_best: false` 면 `best`가 내 조건에서 더 유리한 정책이고 `reason`은 "같은 종류인 '구로형 자립정착금'이 내 조건에서 더 유리해요". 고르는 기준은 3.32 와 같다. 없으면 `null`.
- `related` (같이 보면 좋은 정책, 규칙): 같은 분야 정책 중 최대 3개. `user_id`를 넘기면 그 사람이 받을 수 있는 정책(검색 후보와 같은 기준)에서만 고르고, 금액이 있는 정책을 먼저 둔다. 자기 자신은 빠진다. 없으면 `[]`.

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
        "easy_text": "쉬운 말 변환문",
        "summary": {
          "sections": [
            { "label": "지원 대상", "items": ["보호종료 전 2년 이상 연속 보호", "18세 이후 만기·연장 보호종료: 종료 후 5년 이내"] },
            { "label": "받을 수 없는 경우", "items": ["18세 이전 원가정 복귀로 종결한 경우"] }
          ],
          "notice": "나이와 지역만으로는 받을 수 있는지 확정할 수 없습니다. 담당 기관에 꼭 확인하시기 바랍니다.",
          "verified": false
        }
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
        "eligibility": { "status": "match", "age": "match", "region": "match" },
        "summary": { "sections": [ { "label": "지원 대상", "items": ["…"] } ], "notice": "…", "verified": false }
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
        { "item_id": 105, "label": "신분증", "short_label": null, "is_checked": true, "checked_at": "2026-10-02T10:20:00+09:00" },
        { "item_id": 106, "label": "통장 사본 1부", "short_label": "통장 사본 1부 (압류방지통장 사용 시)", "is_checked": false, "checked_at": null }
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
        "apply_url": "https://...",
        "summary": null
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
| `steps[].detail.summary` | 짧은 요약이 아직 없거나 자동 검사를 통과하지 못함, 또는 정책이 노출 대상이 아님. 원래 필드(`target_description`, `apply_method`)로 표시 |
| `steps[].documents[].short_label` | 이 서류의 짧은 이름이 없음 (여러 서류를 묶어 요약한 경우 포함). `label`·`condition`으로 표시 |

- `policy`: 1.5 정책 객체. 정책이 비활성화되어도 체크리스트는 계속 조회되며 `policy.is_active: false`로 내려간다.
- `steps`는 항상 4개, 대상 확인 → 조건 확인 → 서류 준비 → 신청 순서
- `progress`(단계 진행률) = 체크된 단계 수 / 4. 홈 "지금 할 일" 카드와 같은 기준
- `document_progress`(서류 진행률) = 체크된 서류 수 / 전체 서류 수. 체크리스트 화면의 "준비 서류 n/m" 표시용
- 서류를 모두 체크해도 "서류 준비" 단계는 자동 체크되지 않으며, 사용자가 직접 체크한다.
- `detail.summary`: 3.5 `results[].summary`와 같은 형식의 짧은 요약 (`data/policy_facts/{policy_key}.json`). 대상 확인·조건 확인은 지원 대상·받을 수 없는 경우, 신청은 신청 방법 항목이며, 검색 카드와 달리 핵심 구절만 고르지 않고 전부 보여 준다. 원래 긴 글은 "자세히 보기"로 접는다.
- `documents[].short_label`: 조건까지 담은 짧은 서류 이름 (예: "위임장 (대리 신청 시)"). 있으면 `label`·`condition` 대신 보여 주고, 원래 이름은 "자세히 보기"로 접는다.
- `documents[].forms`: 이 서류를 앱에서 바로 쓸 수 있는 서버 양식 `[{form_id, title, checked, notice}]` (`data/forms/index.json`, 서류 이름이 글자 그대로 같을 때). 양식이 없으면 `[]`. `notice` 는 공식 양식이 공개되지 않아 대신 쓰는 공통 양식일 때 함께 보여 줄 안내 (자립정착금 신청서 → 사회보장급여 신청서), 아니면 null. 누르면 `POST /documents` 에 `source_type: policy_form`, `policy_id`, `form_id`, `checklist_item_id` 로 시작한다.
- `extra_forms`: 서류 목록에는 없지만 이 정책에 쓰는 양식 (원문에 서류가 따로 안 적혔거나 참여 뒤 내는 서류). 형식은 `documents[].forms` 와 같다.
- `documents[].document_id`·`document_status`: 이 서류 항목으로 연결된 문서 AI 문서 (없으면 null). `exported` 면 체크가 문서를 따르고 앱에서 직접 바꿀 수 없다(3.11 → 409 `DOCUMENT_WRITTEN`). 문서의 칸을 다시 고치면(`writing`) 체크가 풀린다.
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

### 3.23 `GET /policies/{policy_id}/easy-text` — 쉬운 말 단계별 보기

AI 담당 쉬운 말 변환 결과(`data/easy_text/{policy_key}.json`, `src/easytext/`)를 단계별로 돌려준다. 정책 카드·상세의 쉬운 말 토글에서 사용한다.

| 쿼리 | 필수 | 설명 |
|---|---|---|
| user_id | X | 있으면 만 나이로 추천 단계를 고른다 |
| level | X | `L1`~`L7`. 사용자가 직접 고른 단계. 없으면 추천 단계 |

**응답 200**

```json
{
  "policy_id": 1, "policy_key": "P001",
  "level": "L3", "level_name": "조금 쉽게", "recommended_level": "L6",
  "levels": [{"level": "L1", "level_name": "아주 쉽게"}],
  "core_text": "[받는 것]\n매월 50만 원을 ...",
  "more_text": "[받는 사람]\n...",
  "terms": [{"term": "보호종료", "gloss": "시설이나 위탁가정의 보호가 끝나는 것"}],
  "verified": false
}
```

- 단계 선택 순서: `level` → `user_id`의 만 나이 추천(L1 7세~, L2 9세~, L3 11세~, L4 13세~, L5 15세~, L6 18세~, L7 20세~) → 기본 `L6`
- 자동 검사를 통과한(`passed`) 단계만 `levels`에 나온다. 고른 단계가 없으면 가장 가까운 단계를 준다 (같으면 쉬운 쪽)
- `core_text`는 핵심 요약, `more_text`는 "더 알아보기"(없으면 null). 본문은 `[항목]` 줄로 나뉜다
- `terms`: 본문에 나오는 어려운 용어. 앱은 강조 표시하고 누르면 `gloss`를 보여준다
- `verified: false`면 사람이 원문과 대조하기 전이므로 "원문과 함께 확인해 주세요" 안내를 표시한다
- 변환 결과가 없으면 404 `EASY_TEXT_NOT_FOUND` → 앱은 기존 `easy_text`(3.6)를 그대로 보여준다. `level` 값 오류는 422

<br/>

### 3.24 `GET /policies/{policy_id}/form-guide` — 신청서 작성 도우미

AI 담당 신청서 작성 항목(`data/forms/{form_id}.json`, `src/formguide/`)을 칸별 안내로 돌려준다. 체크리스트 "서류 준비"·"신청" 단계에서 사용한다.

| 쿼리 | 필수 | 설명 |
|---|---|---|
| user_id | X | 있으면 이름·생년월일·시/도·연락처를 `prefill`로 미리 채운다 (LLM에 보내지 않음) |

**응답 200**

```json
{
  "policy_id": 1, "form_id": "P001-apply", "policy_key": "P001", "title": "자립수당 지급 신청서",
  "checked": false, "notes": ["주소지 행정복지센터에 제출"],
  "items": [
    {"field_id": "name", "label": "성명", "section": null, "how": "앱 정보로 미리 채움 (맞는지 확인)",
     "value_source": "profile", "required": true, "guide": "...", "example": "홍길동",
     "prefill": "김민지", "evidence": "성 명"}
  ],
  "notices": ["아직 사람이 원본 양식과 대조하지 않은 안내예요. ..."]
}
```

- `value_source`: `profile`(미리 채움) / `user`(직접 입력) / `handwrite`(앱에 입력하지 않고 양식에 손으로, 주민등록번호·서명. 계좌번호는 `user`·`input_type: "account"` 로 받되 LLM 에 보내지 않음) / `office`(담당 공무원 기재란)
- 민감 항목은 `example`·`prefill`이 항상 null
- 같은 정책에 양식이 여러 개면 `checked: true` 우선, 그다음 `form_id` 순
- `notices`는 항상 화면에 표시한다
- 양식이 없으면 404 `FORM_GUIDE_NOT_FOUND` → 앱은 "준비 중" 표시

<br/>

### 3.32 `GET /users/{user_id}/policy-roadmap` — 맞춤 신청 로드맵

받을 수 있는 정책(3.5 후보 필터와 같음: 나이·지역·시군구·가족, `excluded` 제외)을 신청 시점별로 묶는다. 규칙만 쓰고 AI 는 쓰지 않는다. 같은 정책이라도 사용자의 퇴소 예정일에 따라 신청 시작일이 달라진다.

**응답 200**

```json
{
  "base_date": "2027-02-28", "d_day": 96,
  "summary": {"monthly_won": 500000, "once_won": 1200000, "policy_count": 15, "match_count": 7},
  "stages": [
    {"stage": "before_leave", "title": "퇴소 전에 미리 신청", "items": [
      {"policy_id": 7, "policy_key": "G007", "name": "자립준비청년 자립수당 지급", "agency": "충청북도 보은군",
       "category": "living_cost", "eligibility": "match",
       "amount_won": 500000, "amount_cycle": "monthly", "amount_text": "월 50만 원", "benefit": "월 50만 원, 매월 20일 지급", "period_years": null,
       "max_total_won": null, "apply_from": "2027-01-29", "until": null,
       "apply_note": "퇴소 30일 전부터 미리 신청"}
    ]},
    {"stage": "at_leave", "title": "퇴소하면 바로 신청", "items": []},
    {"stage": "now", "title": "지금 신청할 수 있어요", "items": []}
  ]
}
```

| 필드 | 설명 |
|---|---|
| `base_date` | 퇴소 예정일. `status`가 `in_care`·`leaving_soon`이고 `d_date`가 오늘 이후일 때만 값이 있다. 퇴소 후(`d_date`는 목표일)거나 예정일이 지났으면 `null` |
| `d_day` | `base_date`까지 남은 일수 (`base_date`가 null 이면 null) |
| `summary.monthly_won` | `eligibility: match` 정책을 모두 받을 때의 최대 월 금액. 같은 종류(자립수당 · 자립정착금)는 가장 큰 것 하나만 센다. 앱은 "최대 월 70만 원"으로 표시 |
| `summary.once_won` | `match` 정책 중 1회 지급 최대 금액. 주기 없이 금액만 있는 정책도 1회로 보고, 같은 종류는 하나만 센다 |
| `stage` | `before_leave`: 신청 방법·대상에 "종료 30일 전" 같은 사전신청 기간이 있음. `at_leave`: 대상에 보호종료·퇴소·자립준비가 들어감. `now`: 나머지. `base_date`가 null 이면 모두 `now` |
| `apply_from` | 신청 시작일. `before_leave` = 퇴소 예정일 - N일, `at_leave` = 퇴소 예정일. 오늘보다 앞이면 오늘. `now`는 null |
| `until` | 퇴소 예정일 + `period_years`. 둘 중 하나라도 없으면 null |
| `max_total_won` | 월 금액 × 12 × 기간, 연 금액 × 기간, 1회·주기 없는 금액은 그대로. 월·연 금액인데 기간을 읽지 못하면 null |
| `age_until` · `age_max` | 나이 상한(만 18세 이상)으로 신청할 수 있는 마지막 날(만 `age_max`+1세 생일 전날)과 상한. 없으면 null |
| `region` | 정책 지역 ("전국", "인천") |
| `kind` · `kind_title` | 같은 종류 묶음 (`allowance` 자립수당, `settlement` 자립정착금, `challenge` 청년도전지원사업, `college` 대학 진학 자금, `scholarship` 장학금, `lease` 전세임대). 같은 종류가 2개 이상일 때만 값이 있다 |
| `pick_reason` | 같은 종류 중 이걸 고른 까닭 ("같은 종류 3개 중 받는 돈이 가장 커요") |
| `alternatives` | 같은 종류의 다른 정책 `[{policy_id, name, region, amount_text, eligibility}]`, 받는 돈이 큰 순서 |
| `amount_*`, `period_years`, `benefit` | 3.6 과 같음 |
| `receiving` · `started_on` | 사용자가 받는 중으로 저장한 정책이면 `true`와 받기 시작한 날 (3.36). 아니면 `false` · null |
| `summary.receiving_*` · `summary.new_*` | 받고 있는 정책 수 · 월 금액과, 그것 말고 더 받을 수 있는 월 · 1회 금액 (3.36) |
| `education` | 들어야 받을 수 있는 교육과 이수 여부 `{code, name, done, done_on, note}` (3.37). 교육 조건이 없는 정책은 null |

- 빈 단계는 `stages`에서 빠진다. 순서는 `before_leave` → `at_leave` → `now`
- 퇴소일이 있으면 퇴소일 나이로도 한 번 더 고른다. 오늘 나이로는 안 되지만 퇴소할 때 나이로는 되는 정책(만 18세 이상 조건 등)은 `at_leave`(사전신청이면 `before_leave`)에 넣고, `eligibility`도 퇴소일 기준
- 아직 보호 중(`in_care` · `leaving_soon`)인데 `d_date`가 없으면, 퇴소해야 받는 정책(사전신청 · 대상에 보호종료 · 퇴소 · 자립준비)은 `at_leave`에 `apply_from: null`, `apply_note: "퇴소하면 신청"`으로 넣는다
- 같은 종류(`kind`)는 함께 받기 어렵다고 보고 목록에 하나만 남긴다. 고르는 순서: 조건이 맞는 것 → (자립수당은 P001) → 받는 돈이 큰 것 → 내 지역 정책 → 먼저 신청 → 번호 순. 나머지는 `alternatives`
- 후보 정책 공통(3.5 · 3.32 · 3.33): 퇴소한 사용자(`left_care`)에게는 지금 보호받는 아동 전용 정책(가정위탁아동 지원 등, 이름 · 대상 문장으로 판단)을 빼고, 보호 중인 사용자에게는 소년소녀가정 전용 정책을 뺀다. 자녀 · 동생 나이가 맞는 아동 정책(`for_family`)은 그대로 둔다
- 단계 안 정렬: `match` 먼저 → `apply_from` 빠른 순 → 월 환산 금액 큰 순 → 최대 총액 큰 순
- 금액·기간은 원문에서 읽은 값이라 화면에 "공식 자료 기준, 실제 금액은 기관 확인"을 함께 표시한다
- 사용자가 없으면 404 `USER_NOT_FOUND`

<br/>

### 3.33 `GET /users/{user_id}/career-roadmap` — 진로 로드맵

진로 방향 3개(취업 준비 · 대학 진학 · 직업훈련·자격증)마다 할 일을 순서대로 준다. 할 일은 앱 기능(실전 연습, 서류 쓰기)과 공공기관 공식 사이트로 이어진다. 규칙만 쓰고 AI 는 쓰지 않는다.

**응답 200**

```json
{
  "recommended": "job",
  "paths": [
    {"path": "job", "title": "취업 준비",
     "steps": [
       {"step_id": "job-interest", "kind": "app", "title": "흥미 탐색으로 맞는 일 찾기", "detail": "어울리는 직업과 그 직업이 되는 길",
        "scenario_id": null, "template_id": null, "url": null, "phone": null, "target": "interest", "query": null},
       {"step_id": "job-careernet", "kind": "link", "title": "직업 정보 더 알아보기", "detail": "커리어넷, 직업정보, 진로검사",
        "scenario_id": null, "template_id": null, "url": "https://www.career.go.kr", "phone": null, "target": null, "query": null},
       {"step_id": "job-jobcenter", "kind": "roleplay", "title": "고용센터 상담 연습", "detail": "처음 방문해서 할 말을 미리 연습",
        "scenario_id": "jobcenter", "template_id": null, "url": null, "phone": null, "target": null, "query": null},
       {"step_id": "job-resume", "kind": "document", "title": "입사지원서 쓰기", "detail": "기본형 이력서 양식",
        "scenario_id": null, "template_id": "resume-form", "url": null, "phone": null, "target": null, "query": null}
     ],
     "policies": [
       {"policy_id": 19, "name": "청년도전지원사업 안내", "agency": "고용노동부", "category": "employment",
        "eligibility": "match", "amount_text": null, "benefit": "참여수당, 인센티브 제공"}
     ]}
  ]
}
```

| 필드 | 설명 |
|---|---|
| `recommended` | `work`가 `student` → `college`, `working` → `training`, `job_seeking` → `job`. 그 밖에는 성장 단계가 `youth`면 `job`, 아니면 `college` |
| `paths` | 항상 3개. `recommended` 방향이 맨 앞, 나머지는 job → college → training 순 |
| `steps[].guide` · `steps[].reason` | `guide`: 펼치면 보이는 '이렇게 해요' 줄 (어디서 · 무엇을 · 무엇을 챙기는지, 공식 안내에 있는 것만). `reason`: 내 조건 때문에 더하거나 강조한 할 일의 까닭 ("만 23세라 청년(만 18~34세) 대상이에요"), 없으면 `null` |
| 내 조건에 따라 더하는 할 일 | 퇴소 전(`in_care` · `leaving_soon`): 자립 상담에 까닭, 진학 방향에 `college-extend`(보호 연장 상담, `app` · `target: "chat"`, `query` = 상담 질문) · 퇴소 후(`left_care`): `{path}-aftercare`(자립지원전담기관 사후관리) · 만 18~34세: 취업 · 훈련 방향에 `{path}-kua`(국민취업지원제도) · 사는 지역: 취업 · 훈련 방향에 `{path}-local`(지역 청년 지원 검색) · `work`: 쉬는 중이고 청년도전지원사업을 받을 수 있으면 `job-challenge`, 일하는 중 · 학생이면 관련 할 일에 까닭 |
| `steps[].step_id` | 방향 안에서 바뀌지 않는 이름 (`job-jobcenter`). 앱은 이 값으로 끝낸 일과 일정을 기억한다. 방향 이름으로 시작하고 겹치지 않는다 |
| `steps[].kind` | `roleplay`: 실전 연습 화면 (`scenario_id`) · `document`: 서류 작성 시작 `POST /documents` (`source_type: "template"`, `source_id: template_id`) · `link`: 공식 사이트 (`url`, 있으면 `phone`) · `app`: 앱 화면 (`target`: `interest` 흥미 탐색, `risk` 서류 위험 찾기, `chat` 상담 — `query` 를 질문으로) · `search`: 앱 정책 검색 (`query`) |
| `policies` | 받을 수 있는 정책(3.5 후보 필터) 중 방향별 낱말이 이름·지원 내용에 들어간 것, 최대 4개. `match` 먼저, 금액 있는 것 먼저 |

- 방향별 낱말: 취업(취업·구직·도전지원·일자리·채용·면접) · 진학(대학·등록금·장학·진학·입학) · 훈련(훈련·교육비·자격·능력개발·학습·역량)
- 할 일 순서: 나를 알기(흥미 탐색 · 직업/학과 정보) → 상담(자립 상담 · 고용센터) → 준비 · 신청 → 연습 → 일을 시작한 뒤 챙길 것(근로계약서 · 통장 · 임금)
- 공식 사이트는 공공기관 누리집만 쓴다: 커리어넷(한국직업능력연구원), 고용24(고용노동부, 1350), 고용24 국민내일배움카드 발급 안내, 한국장학재단(1599-2000), 큐넷(한국산업인력공단, 1644-8000), 자립정보ON(아동권리보장원, 자립준비청년 상담센터 1855-2455), 대입정보포털 어디가(한국대학교육협의회, 1600-1615)
- 사용자가 없으면 404 `USER_NOT_FOUND`

<br/>

### 3.34 `GET /users/{user_id}/recommended-policies` — 홈 추천 정책

맞춤 신청 로드맵(3.32)과 같은 후보 · 단계로 점수를 매겨 추천 순서대로 최대 8개를 준다. 규칙만 쓴다.

```json
[
  {"policy_id": 1, "policy_key": "P001", "name": "자립준비청년 자립수당 지급", "category": "living_cost",
   "eligibility": "match", "stage": "before_leave", "when_text": "12월 1일부터 미리 신청",
   "amount_unit": "매월", "amount_value": "50만 원", "amount_text": "월 50만 원",
   "benefit": "월 50만 원, 매월 20일 지급", "score": 165}
]
```

- 점수: 조건이 맞음(`match`) +100 → 신청 시기 (퇴소 전 사전신청 60일 안 +40, 그 밖의 사전신청 +25, 지금 +30, 퇴소하면 +15) → 현금 (월 환산 2만 원당 1점, 최대 30 · 1회 금액 100만 원당 1점, 최대 15)
- `amount_unit` · `amount_value`: 현금이 아니면 null (앱은 분야 이름을 대신 보여 준다)
- 아직 보호 중이면 지금 받을 수 있는 정책 위주: `at_leave`(퇴소하면 신청)는 퇴소 예정일이 60일 안일 때만, `before_leave`(사전신청)는 신청 시작일이 60일 안일 때만 넣는다. 그 밖의 것은 3.32 로드맵에서만 보인다
- 같은 종류(3.32 `kind`)는 내게 가장 유리한 하나만 들어간다. `kind_title` · `pick_reason` · `same_kind_count`(뺀 정책 수)로 까닭을 보여 줄 수 있다. 묶인 게 없으면 null · null · 0
- 받는 중으로 저장한 정책(3.36)은 넣지 않는다 (이미 받고 있으니 새로 권하지 않는다)
- 사용자가 없으면 404 `USER_NOT_FOUND`

<br/>

### 3.35 `GET /career/*` — 진로 외부 정보

앱 안에서 시험 일정 · 훈련과정 · 직업 정보 · 채용 공고를 보여 주려고 공공 API 를 서버가 대신 부른다. 같은 질의는 6시간 동안 서버 메모리에 담아 둔다.

| 경로 | 쿼리 | 바깥 API | 서버 환경변수 |
|---|---|---|---|
| `/career/exams` | `cert` 자격증 이름 | 공공데이터포털 큐넷 국가자격 시험일정 | `QNET_SERVICE_KEY` |
| `/career/certificates` | `q` 자격증 이름 일부 | 큐넷 국가자격 종목 목록 (하루 캐시) | `QNET_SERVICE_KEY` |
| `/career/trainings` | `keyword`, `region`(선택, 시·도) | 고용24 국민내일배움카드 훈련과정 | `WORK24_TRAINING_KEY` |
| `/career/trainings/detail` | `id`, `round`, `institution` (목록 항목의 값) | 고용24 훈련과정 과정/기관정보 | `WORK24_TRAINING_KEY` |
| `/career/job-info` | `name` 직업 이름 | 커리어넷 직업백과, 키가 없으면 고용24 직업정보 | `CAREERNET_API_KEY` 또는 `WORK24_JOB_INFO_KEY` |
| `/career/duties` | `keyword` 직업 이름 등 | 고용24 직무데이터사전 (NCS 능력단위) | `WORK24_DUTY_KEY` |
| `/career/postings` | `keyword`, `region`(선택, 시·도) | 고용24 채용정보 (기업회원만 키 발급) | `WORK24_JOBS_KEY` |

공통 응답

```json
{"available": true, "source": "고용24 국민내일배움카드 훈련과정", "items": [], "message": null, "total": 2, "url": "https://www.work24.go.kr"}
```

- `available: false`: 키가 없거나 바깥 서버가 실패했다. `message` 를 그대로 보여 주면 된다 (실패한 응답은 캐시하지 않는다). 실패하면 `reason` 에 바깥 서버의 오류 사유(예: `HTTP 401`, `30 SERVICE_KEY_IS_NOT_REGISTERED_ERROR`)
- `exams`: 자격증 이름을 큐넷 종목 목록(국가자격 종목 목록 정보 API, 하루 캐시)에서 찾아 그 종목(`code`, `cert`)의 회차만 받는다. 끝나지 않은 회차만, 다가오는 날짜 순 최대 4개. 종목을 못 찾으면 `grade`(기능사 · 산업기사 · 기사 · 기능장 · 기술사, 이름 끝으로 판단) 전체 회차로 대신하고 `note` 를 준다. 등급도 없으면 items 는 빈 배열 항목: `name`, `year`, `round`, `written_apply_start/end`, `written_exam_start/end`, `written_pass`, `practical_apply_start/end`, `practical_exam_start/end`, `practical_pass` (YYYY-MM-DD 또는 null)
- `certificates`: 교육 · 자격증 화면의 '자격증 추가' 검색. 이름(띄어쓰기 · 괄호 설명 무시)에 `q` 가 든 종목, 이름이 같은 것 → 앞부분이 같은 것 → 짧은 이름 순 최대 10개. 항목: `code`(종목코드), `name`, `kind`(국가기술 · 국가전문)
- `trainings`: 오늘부터 6개월 안에 시작하는 과정, 최대 20개. `district`(구 · 군)를 주면 그 구(`same_district: true`) → 내 시 · 도(`nearby: true`) → 나머지, 같은 묶음 안에서는 시작일 순. 항목: `id`, `round`, `institution_id`, `title`, `institution`, `address`, `phone`, `start`, `end`, `cost`(실제 훈련비. 본인부담액은 과정 소개에서), `certificate`, `employment_rate`, `url`
- `trainings/detail`: `items` 대신 `course` (없으면 null). `id`, `round`, `title`, `institution`, `address`, `homepage`, `field`, `target`, `days`, `hours`, `cost`(훈련비), `support`(정부지원금), `my_cost`(본인부담액), `grade`(기관 평가등급), `contact_phone`, `facilities`, `url`(고용24 과정 화면)
- `duties`: 최대 8개. 항목: `code`(NCS 능력단위 코드), `unit`(능력단위명), `definition`, `field`, `group`(대 > 중 > 소분류), `skills`(지식·기술·태도 이름, 최대 8개)
- `job-info`: `items` 대신 `job` (없으면 null). `source` 로 커리어넷인지 고용24 인지 구분한다 (고용24 는 `url`, `orgs`, `work_life_balance` 가 비어 있고 되는 길은 `prepare.curriculum` 에 담는다). `id`, `name`, `summary`, `works`, `abilities`, `aptitudes`, `interests`, `certificates`, `majors`, `related_jobs`, `prepare {recruit, certificate, training, curriculum}`, `forecast`, `wage`, `wage_source`, `satisfaction`, `work_life_balance`, `orgs [{name, url}]`, `url`
- `postings`: 최대 20개. 내 지역 먼저, 그다음 최근 등록 순. 항목: `id`, `company`, `title`, `pay_type`, `pay`, `region`, `education`, `career`, `holiday`, `registered`, `close`, `url`(모바일 공고 주소)
- 쿼리 이름이 비었거나 40자를 넘으면 422 `VALIDATION_ERROR`

<br/>

### 3.36 `/users/{user_id}/saved-policies` — 저장한 정책 · 받고 있는 정책

정책을 관심 있어 저장(`saved`)하거나 지금 받고 있다고 표시(`receiving`)한다. 받고 있는 정책은 맞춤 신청 로드맵(3.32)에서 '지금 받고 있어요'로 보이고, 같은 종류(자립수당 · 자립정착금 등)에서는 받고 있는 것이 남는다. 홈 추천(3.34)과 상담 `plan`에는 넣지 않는다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/users/{user_id}/saved-policies` | 저장 · 상태 바꾸기. 새로 저장하면 201, 이미 저장했으면 200 |
| GET | `/users/{user_id}/saved-policies` | 저장 목록 (최근 저장 순) `{count, policies: [...]}` |
| DELETE | `/users/{user_id}/saved-policies/{policy_id}` | 저장 취소 (저장 안 한 정책이어도 204) |

**POST 요청**

```json
{"policy_id": 1, "status": "receiving", "started_on": "2025-07-01"}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `policy_id` | O | 정책 id |
| `status` | X | `saved` · `receiving`. 새로 저장할 때 없으면 `saved`. 이미 저장한 정책에 주면 그 상태로 바꾸고, 없으면 그대로 둔다 (예전 앱 동작과 같음) |
| `started_on` | X | 받기 시작한 날 (YYYY-MM-DD). `receiving` 일 때만 저장한다. 받는 중으로 다시 저장하면서 안 주면 원래 날짜를 두고, `saved` 로 바꾸면 지운다 |

**응답 (POST 항목 · GET `policies[]`)**

```json
{"policy": {"policy_id": 1, "name": "자립준비청년 자립수당 지급", "agency": "보건복지부", "category": "living_cost",
            "contact": "129", "apply_url": "https://...", "checked_at": "2026-10-12", "is_active": true},
 "saved_at": "2026-11-27T10:00:00+09:00", "status": "receiving", "started_on": "2025-07-01"}
```

- 새로 저장할 때만 노출 정책(활성 · 쉬운 말 검증)인지 본다. 없거나 숨긴 정책이면 404 `POLICY_NOT_FOUND`. 이미 저장한 것은 정책이 숨겨져도 그대로 보이고 상태를 바꿀 수 있다
- 형식 오류(모르는 `status`, 날짜 형식, 모르는 필드)는 422 `VALIDATION_ERROR`, 사용자가 없으면 404 `USER_NOT_FOUND` (422 보다 먼저)

**맞춤 신청 로드맵(3.32)에 반영되는 것**

- 받고 있는 정책: `receiving: true`, `started_on`, `apply_note: "지금 받고 있어요"`, `apply_from: null`. 퇴소 전 사용자여도 `now` 단계에 넣고, 단계 안에서는 새로 신청할 정책 뒤에 둔다
- `until`: `started_on`과 `period_years`를 알면 받기 시작한 날 + 기간 (지원이 끝나는 날)
- 같은 종류는 받고 있는 것을 먼저 남긴다 (`pick_reason`: "지금 받고 있어서 같은 종류는 따로 신청하지 않아도 돼요"). 받는 돈이 더 큰 정책이 있어도 `alternatives`로 보인다
- `summary.receiving_count` · `receiving_monthly_won`: 받고 있는 정책 수와 월 금액. `new_monthly_won` · `new_once_won`: 받고 있는 것 말고 더 받을 수 있는 월 · 1회 금액 (조건 맞음, 같은 종류는 하나만). 앱은 "지금 매달 50만 원 받고 있어요 · 30만 원 더 받을 수 있어요"처럼 보여 줄 수 있다
- 나이 · 지역이 맞지 않게 되어 후보에서 빠진 정책은 받는 중이어도 로드맵에 보이지 않는다

<br/>

### 3.37 `/users/{user_id}/credentials` — 이수한 교육 · 취득한 자격증

이수한 교육(`education`)과 취득한 자격증(`certificate`)을 등록한다. 정해진 교육 2가지(`code`)는 정책 조건과 이어진다.

| `code` | 이름 | 필요한 정책 |
|---|---|---|
| `settlement` | 자립정착금 의무교육 | 자립지원전담기관 의무교육 확인서를 내야 하는 자립정착금 (지역 정착금 대부분) |
| `finance` | 자립수당 자립교육 | 서민금융진흥원 · 아동권리보장원 온라인 자립교육 이수증(또는 재무상담 확인서)을 내는 자립수당 |

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/users/education-options` | 정해진 교육 목록 `[{code, name, description}]` (앱의 교육 추가에서 고름) |
| POST | `/users/{user_id}/credentials` | 등록. 새로 넣으면 201, 같은 종류 · 같은 이름이 있으면 날짜만 바꾸고 200 |
| GET | `/users/{user_id}/credentials?kind=` | 목록 (최근 등록 순) `{count, credentials: [...]}`. `kind`로 거를 수 있다 |
| DELETE | `/users/{user_id}/credentials/{credential_id}` | 삭제 (없는 항목이어도 204, 형식 오류는 404 `CREDENTIAL_NOT_FOUND`) |

**POST 요청**

```json
{"kind": "education", "code": "settlement", "done_on": "2026-09-01"}
{"kind": "certificate", "name": "컴퓨터활용능력 2급", "done_on": "2026-03-10"}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `kind` | O | `education` · `certificate` |
| `code` | X | `education`일 때만. 주면 `name`을 안 줘도 정해진 이름으로 저장한다 |
| `name` | △ | 1~60자 (앞뒤 공백 제거). `code`가 없으면 필수 |
| `done_on` | X | 이수한 날 · 취득한 날 (YYYY-MM-DD). 오늘 이후면 422 |

**응답 (POST 항목 · GET `credentials[]`)**

```json
{"credential_id": 1, "kind": "education", "code": "settlement", "name": "자립정착금 의무교육",
 "done_on": "2026-09-01", "created_at": "2026-11-27T10:00:00+09:00"}
```

**반영되는 곳**

- 맞춤 신청 로드맵(3.32) `education`: 이 교육이 필요한 정책(신청 서류 · 방법 · 대상 문장에 의무교육 · 이수증이 있음)에 붙는다
  - 이수 안 함: `done: false`, `note: "자립지원전담기관 의무교육을 먼저 들어야 받을 수 있어요"`
  - 이수함: `done: true`, `note: "자립정착금 의무교육을 이수했어요"`. 이수한 지 1년이 넘으면 "확인서를 지금도 인정하는지 기관에 물어보세요" (수료 후 1년이 지나면 인정하지 않는 지역이 있음)
- 정책 상세(3.6) `required_education`: 사용자와 관계없이 이 정책에 필요한 교육 `code`
- 이력서 초안(3.22 `type: resume`): 자격증은 '자격증'에 "컴퓨터활용능력 2급 (2026.03 취득)", 교육은 '경험·활동' 앞에 "자립정착금 의무교육 이수 (2026.09)"로 넣는다 (`source`: "내 자격증" · "내 교육 이수")
- 사용자가 없으면 404 `USER_NOT_FOUND` (본문 422 보다 먼저). 탈퇴하면 같이 지운다

<br/>

### 3.38 상담 정책 답변의 "내 조건으로 보면" (`POST /chat`, `/chat/stream`)

상담에서 정책 질문이면(`type: policy`) `search.answer` 끝에 이 사람 기준 판정을 붙인다. 응답 형식은 그대로이고(글만 덧붙임), 규칙으로 만든다 (AI 없음, `services/my_check.py`). `/search`(3.5)에는 붙이지 않는다.

```
…자세한 조건은 공식 원문에서 꼭 확인하시기 바랍니다.

[내 조건으로 보면]
- 서리풀 생활보조수당 지원: 나이 · 지역 조건은 맞아요. 서초구 1년 이상 거주, 기준중위소득 50% 이하인지 확인해 보세요.
- 자립수당 지급: 지금 받고 있는 지원이에요.
```

- 답에 나온 정책(`related` 제외) 앞에서 3개까지, 정책마다 한 줄
- 받는 중(3.36)이면 "지금 받고 있는 지원이에요"만
- 나이 · 지역 판정(`eligibility.status`), 가족 나이로 보는 정책(`for_family`, "동생 나이로 보는 정책이에요"), 퇴소해야 받는 정책(`leave: after_leave`)
- 앱이 판정하지 못하는 대상 조건: 카드 요약의 '지원 대상' 구절에서 나이 · 보호종료 · 대상 이름을 지우고 남는 것 2개까지 ("서초구 1년 이상 거주", "미혼 무주택자")
- 의무교육이 필요한데 안 들었으면 "자립정착금 의무교육을 먼저 들어야 해요" (3.37)
- 같은 종류 중 로드맵이 다른 정책을 골랐으면 "같은 종류 중에는 ○○(1,500만 원)이 더 유리해요" (3.32 와 같은 기준)

<br/>

### 3.39 상담 스트리밍의 "지금 하는 일" (`POST /chat/stream` status 이벤트)

`/chat/stream` 은 답보다 먼저 `status` 이벤트로 지금 하는 일을 보낸다. 앱은 "답변을 준비하고 있어요" 대신 `text` 를 보여 주고, 다음 status 가 오면 바꾼다.

```
event: status
data: {"stage": "search", "text": "관련 정책을 찾는 중"}
```

| stage | text | 언제 |
|---|---|---|
| route | 질문을 살펴보는 중 | 규칙으로 못 정해 AI 가 질문 종류를 판단할 때 |
| search | 관련 정책을 찾는 중 | 정책 검색 시작 |
| read | 정책 원문을 읽는 중 | 검색이 1.2초 넘게 걸리면 |
| write | 답을 정리하는 중 | 그 뒤로 2초 더 걸리면 |
| check | 내 조건과 맞춰 보는 중 | 답이 나와 "내 조건으로 보면"을 붙일 때 |
| document | 알맞은 서류를 찾는 중 | 서류 쓰기로 넘길 때 |
| counsel | 답을 생각하는 중 | 고민 상담 답을 만들기 시작할 때 (곧 delta 가 온다) |

- 위기 안내는 status 없이 done 하나
- 정책 검색 오류(RAG_UNAVAILABLE)도 스트림 안의 `error` 로 온다 (예전에는 HTTP 503). 사용자 없음 · 요청 형식 오류는 그대로 HTTP 오류
- status 를 모르는 앱은 무시하면 된다 (delta · done · error 는 그대로)
- 정책 낱말(지원 · 급여 · 정착금 · 등록금 · 서리풀 · 디딤씨앗 …)이 있으면 AI 분류 없이 바로 정책 검색 → 질문 칩은 모두 바로 검색 시작

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
- 근거는 찾았지만 답변 LLM 이 늦거나(12초) 실패하면 `"fallback": True` 를 붙여 근거 정책 안내 문장과 근거를 돌려준다. 백엔드는 이 결과를 캐시하지 않는다 (없으면 `False`).
- 질문 임베딩(OpenAI)이 실패해도 낱말 검색으로 근거를 찾아 같은 형식(`"fallback": True`, `score` 는 `null`)으로 돌려준다. 낱말이 충분히 맞지 않으면 `no_evidence`. Chroma 오류만 예외로 올라간다 (→ 503).
- `evidences` 는 답변 근거가 먼저이고, 그 뒤에 답변에는 안 썼지만 질문과 뜻이 가까운 후보 정책(최대 3개)이 `"related": true` 로 붙는다. 백엔드는 똑같이 정책별 카드로 묶으므로 답변 근거 카드 뒤에 비슷한 정책 카드가 나온다. 백엔드는 근거가 모두 `related` 인 정책 카드에 `results[].related = true` 를 붙여 앱이 '비슷한 정책'으로 나눠 보여 주게 한다 (3.5).
- 실제 RAG 함수에는 `search_policies.prefetch(question)` 이 달려 있다: 질문 임베딩을 백그라운드에서 미리 만든다 (상담 화면이 정책 질문인지 판단하는 동안). 목 함수에는 없다.
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
| 지원 대상 & 자격요건 | 검색 결과에서 `summary`가 있으면 `summary.sections`를 항목별 목록으로 (원문은 "자세히 보기"로 접기), 없으면 `target_description` |
| 지원 혜택 & 내용 | `summary`가 있으면 위 목록에 포함. 없으면 `support_content` (있으면 `support_amount`, `support_period` 함께 표시) |
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
| results[].summary | PolicySummary | O |
| results[].summary.sections | array<object> | |
| results[].summary.sections[].label | string | |
| results[].summary.sections[].items | array<string> | |
| results[].summary.notice | string | O |
| results[].summary.verified | boolean | |

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
| steps[].documents[].short_label | string | O |
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
| detail.summary | PolicySummary (3.5) | `target_check`, `condition_check`, `apply` | O |
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
