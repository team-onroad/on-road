# 문서 AI · 라우터 API (3.25 ~ 3.31)

상담 AI(정책 검색·상담)와 문서 AI(서류 항목별 공동 작성)를 나누고, 라우터가 질문을 담당 AI로 보낸다.
공통 규칙(에러 형식, KST, snake_case)은 [api.md](api.md) 1장을 따른다.

## 0. 라우터

| 화면 | 순서 | 결과 |
|---|---|---|
| 상담 (`POST /chat`) | 위기 키워드(규칙) → 서류 작성 요청인지(LLM) → 정책/고민(기존 분류) | `crisis` / `document` / `policy` / `counsel` |
| 문서 (`POST /documents/{id}/assist`) | 위기 키워드(규칙) → 지금 칸 요청인지(LLM) | `crisis` / `field` / `policy` / `counsel` |

- "자립수당 신청서 써야 해" → `document` (문서 AI로 핸드오프). "자립수당 받으려면 무슨 서류 필요해?" → `policy` (작성 요청이 아니라 정보 질문)
- 문서 화면에서 "소득 기준이 뭐야?" → `policy`: 상담 AI의 정책 검색(`/search`와 같은 결과)을 그대로 돌려준다. 칸은 바뀌지 않는다
- 문서 화면에서 "지원 동기 예시 써 줘" → `field` ('지원'이 들어가도 칸 요청)
- LLM 은 `.env` 의 `LLM_MODE` 를 따른다 (mock: `app/llm_mock.py` 규칙, real: `src/docwriter/llm.py`)

### `POST /chat` 변경 (3.15)

응답에 `type: "document"` 와 `handoff` 필드가 추가됐다. 다른 type 에서는 `handoff: null`.

```json
{
  "type": "document",
  "message": "서류 작성은 문서 도우미에서 칸마다 함께 써 볼게요.",
  "search": null, "contacts": [],
  "handoff": {
    "context": "자립수당 신청서 써야 해",
    "candidates": [
      {"source_type": "policy_form", "source_id": null, "policy_id": 1, "title": "자립수당"},
      {"source_type": "template", "source_id": "allowance", "policy_id": null, "title": "자립수당 신청서"}
    ]
  }
}
```

- 후보가 1개면 바로, 여러 개면 사용자가 고른 뒤 `POST /documents` 에 후보 값 + `context` 를 그대로 보낸다
- 메시지와 맞는 양식이 없으면 사용자 단계(teen·youth)에 맞는 앱 양식 전체를 후보로 준다
- 정책 신청서(`policy_form`)는 `data/forms/` 에 항목 JSON(formguide 결과)이 있는 정책만 후보가 된다

### 프로필 변경 확인 카드 (`profile_changes`, 상담·문서 AI 공통)

`POST /chat`, `POST /documents/{id}/interview`, `POST /documents/{id}/assist` 응답에 `profile_changes` 가 추가됐다 (위기 응답은 빈 목록).
메시지에 프로필과 다른 값이 바뀌었다는 말과 함께 나오면 후보를 준다 (`app/profile_signal.py`, 규칙만, LLM 에 보내지 않음).

```json
"profile_changes": [
  {"field": "region", "label": "사는 지역", "current": "서울", "proposed": "부산", "proposed_label": "부산"}
]
```

| field | 찾는 말 (예) | proposed |
|---|---|---|
| `name` | "개명했는데 정서현으로 해 줘", "이름 바꿨어 정서현이야" | 새 이름 (이름 칸에 답하면 칸에도 새 이름만 적는다) |
| `region` | "부산으로 이사했어요", "대구광역시에 살게 됐어요" | 지역 코드 |
| `phone` | "번호 010-9876-5432로 바꿨어요" | `01098765432` |
| `status` | "퇴소했어요" → `left_care`, "곧 퇴소 예정이에요" → `leaving_soon` | 상태 코드 |
| `d_date` | 퇴소 예정 + 날짜 ("2027년 2월 28일 퇴소 예정") | `2027-02-28` |

- 앱은 확인 카드("내 정보를 바꿀까요? 사는 지역: 서울 → 부산")를 띄우고, 사용자가 승인할 때만 `PATCH /users/{id}` 에 `{field: proposed}` 를 보낸다. 서버는 자동으로 바꾸지 않는다
- "부산 정책 알려줘"처럼 바뀌었다는 말(이사·바꿨·퇴소했 등)이 없으면 후보가 아니다

<br/>

## 1. 엔드포인트

| 번호 | 메서드 | 경로 | 설명 |
|---|---|---|---|
| 3.25 | POST | `/documents` | 서류 작성 시작 (조회 또는 생성) |
| 3.26 | GET | `/users/{user_id}/documents` | 내 서류 목록 (최근 수정순) |
| 3.27 | GET | `/documents/{document_id}?user_id` | 작성 화면 전체 (칸 목록·진행률) |
| 3.28 | PATCH | `/documents/{document_id}/fields/{field_id}?user_id` | 칸 수정·완료·다시 열기 |
| 3.28-1 | POST | `/documents/{document_id}/fields/{field_id}/undo?user_id` | 칸 되돌리기 (1단계) |
| 3.29 | POST | `/documents/{document_id}/assist` | 상단 프롬프트 → 선택한 칸에 바로 채움 |
| 3.30 | GET | `/documents/{document_id}/check?user_id` | 내보내기 전 검사 |
| 3.31 | POST | `/documents/{document_id}/export` | 내보내기 (체크리스트 서류 항목 체크) |
| 3.31-1 | GET | `/documents/{document_id}/file?user_id&format=pdf\|docx` | 내보낸 파일 받기 |
| 3.32 | POST | `/documents/{document_id}/interview` | 대화로 채우기: AI 가 빈 칸을 하나씩 묻고 답을 채움, 다 채우면 바로 내보내기 |
| 3.34 | POST | `/documents/upload` | 양식 사진·PDF 올려서 작성 시작 (AI 가 칸을 읽음) |

확인 순서는 체크리스트 API 와 같다: 사용자(404 USER_NOT_FOUND) → 문서(404 DOCUMENT_NOT_FOUND, 다른 사람 문서 포함) → 칸(404 FIELD_NOT_FOUND) → 본문(422).

<br/>

## 2. 화면 동작

```
┌ 상단: 문서 작성 도우미 프롬프트 ("지금 작성 중: 신청 사유") ┐
│ 칸 카드 × N  (라벨, 작성란, 완료 버튼)                       │
└ 하단: 진행률 progress.completed / progress.total, 내보내기  ┘
```

1. 칸 카드를 누르면 선택 상태 → 프롬프트 입력 → `POST /assist` (`field_id` = 선택한 칸)
2. 응답 `route`
   - `field`: `action` 별로 처리. `tip` 은 작성란 위 안내(`field.tip`)만 바뀌고, `outline`·`draft`·`revise` 는 작성란이 바뀐다
   - `policy`: 정책 검색 결과(`search`)를 시트나 말풍선으로 보여 준다. 칸은 그대로
   - `counsel`·`crisis`: `message`(+ `contacts`) 표시
3. `segments[].source == "ai"` 구간은 AI 초안 색으로 표시. 사용자가 그 구간을 고치면 앱에서 `user` 로 바꿔 `PATCH segments` 로 저장
4. 완료 버튼 → `PATCH {"is_completed": true}` → 응답 `next_field_id` 칸을 자동 선택
5. 모두 완료 → `GET /check` → 문제가 없으면 `POST /export` → `files` 경로로 PDF·DOCX 받기

<br/>

## 3. 상세

### 3.25 `POST /documents`

```json
{"user_id": "uuid", "source_type": "template", "source_id": "scholarship",
 "policy_id": null, "checklist_item_id": 12, "context": "장학금 신청서 써야 해"}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| source_type | O | `template`: 앱 서류 양식(`GET /forms/templates`) / `policy_form`: 정책 신청서(formguide 항목) |
| source_id | template 일 때 | 양식 ID (`scholarship`, `resume-form` …) |
| policy_id | policy_form 일 때 | 정책 ID. template 에 같이 보내면 관련 정책으로 기록 |
| checklist_item_id | X | 체크리스트 **서류** 항목 ID. 내보내기 때 이 항목을 체크한다. 내 체크리스트의 서류 항목이 아니면 404 ITEM_NOT_FOUND |
| context | X | 상담 AI 핸드오프의 `context` (500자 이하). 문서 AI 가 참고한다 |

- 같은 양식은 사용자마다 하나: 새로 만들면 201, 이미 있으면 200 (보낸 `checklist_item_id`·`context` 로 갱신)
- 만들 때 미리 채움: 프로필(이름·생년월일·연락처, 정책 신청서는 시/도) → `source: "profile"`, 다른 서류에서 완료한 같은 칸(성명·연락처·주소·학력·자격증·자기소개 등) → `source: "reused"`, `reused_from` 에 그 서류 제목. 미리 채워도 완료는 사용자가 누른다
- 에러: 404 FORM_NOT_FOUND / POLICY_NOT_FOUND / FORM_GUIDE_NOT_FOUND(정책 신청서 항목 없음) / ITEM_NOT_FOUND, 422 VALIDATION_ERROR

**응답 (문서 객체, 3.27 도 같음)**

```json
{
  "document_id": 3, "title": "장학금 신청서", "source_type": "template", "source_id": "scholarship",
  "policy_id": null, "checklist_item_id": 12, "status": "writing", "context": "장학금 신청서 써야 해",
  "progress": {"completed": 2, "total": 7}, "next_field_id": "address",
  "fields": [
    {
      "field_id": "reason", "label": "신청 사유", "easy_label": "장학금이 필요한 이유", "section": null,
      "guide": "지금 상황과 장학금을 어디에 쓸지 솔직하게 써요.", "caution": "과장하지 말고 사실만 써요. …",
      "example": null, "outline": ["지금 나의 상황", "장학금이 필요한 이유", "받게 되면 어디에 쓸지", "앞으로의 계획"],
      "multiline": true, "essay": true, "required": true, "input_type": "text",
      "value_source": "user", "writable": true,
      "value": "저는 지금 혼자 살고 있습니다.\n[학교 이름]에서 …",
      "segments": [{"text": "저는 지금 혼자 살고 있습니다.", "source": "user"},
                   {"text": "\n[학교 이름]에서 …", "source": "ai"}],
      "tip": null, "reused_from": null, "is_completed": false, "completed_at": null,
      "can_undo": true, "warnings": ["[학교 이름] 빈칸을 채워 주세요."]
    }
  ],
  "notices": ["AI가 쓴 문장은 색으로 표시돼요. …"],
  "exported_at": null, "created_at": "…", "updated_at": "…"
}
```

| 필드 | 설명 |
|---|---|
| value_source | `profile` 미리 채움 / `user` 직접 작성 / `handwrite` 민감 칸(주민등록번호·서명): 앱에 입력하지 않고 양식에 손으로. 계좌번호는 `user` 칸(`input_type: "account"`)으로 받아 양식에 채우되 LLM 에는 보내지 않음(작성 도우미는 고정 안내만) / `office` 담당 공무원 기재란 |
| writable | false 면 입력·AI 도우미 불가, 진행률(`progress.total`)에서 제외. 카드에 "양식에 직접 작성" 표시 |
| input_type | `text` `date` `phone` `address` `checkbox` 등. `checkbox` 는 `options` 중에서 고르게 한다 |
| options | 체크박스 칸 선택지 (원본 양식의 `[ ]선택지`). 고른 값을 `value` 로 보낸다 (여러 개는 `", "`). 선택지에 없는 값이면 `warnings` 에 표시되고 내보낼 수 없다 |
| segments | 작성란 내용을 출처별로 나눈 것. `value` = segments 의 text 를 이어 붙인 값 |
| tip | "어떤 내용을 넣으면 좋을까?" 안내. 작성란 위에 표시 (값에는 안 들어감) |
| warnings | 남은 `[대괄호 빈칸]`, 날짜·전화번호 형식. 완료 전 카드에 표시 |
| next_field_id | 아직 완료하지 않은 첫 쓰기 칸. 없으면 null (모두 완료) |
| notices | 항상 표시 (정책 신청서가 사람 검수 전이면 맨 앞에 안내 추가) |

### 3.28 `PATCH /documents/{id}/fields/{field_id}?user_id`

`value`(전부 사용자 글) 또는 `segments`(AI 구간 유지) 중 하나, 그리고/또는 `is_completed`.

```json
{"segments": [{"text": "제 계획은 ", "source": "user"}, {"text": "꾸준히 공부하는 것입니다.", "source": "ai"}]}
{"value": "열심히 하겠습니다.", "is_completed": true}
{"is_completed": false}
```

- 응답: `{"field": 칸, "progress", "next_field_id", "status"}`
- 완료한 칸은 잠긴다: 내용 수정·AI 도우미 → 409 FIELD_COMPLETED. `{"is_completed": false}` 로 다시 연다 (같은 요청에 새 내용 가능)
- 내보낸 뒤 칸을 다시 열면 `status` 가 `writing` 으로 돌아간다 (체크리스트 체크는 그대로)
- 내용은 3000자 이하. `writable: false` 칸은 422 FIELD_NOT_WRITABLE
- `undo`: 직전 내용으로 1단계 되돌림. 되돌릴 것이 없으면 409 NOTHING_TO_UNDO, 완료한 칸은 409 FIELD_COMPLETED

### 3.29 `POST /documents/{id}/assist`

```json
{"user_id": "uuid", "field_id": "reason", "prompt": "예시 문장 써 줘", "mode": "append"}
```

| 필드 | 설명 |
|---|---|
| prompt | 1~500자 |
| mode | 칸에 이미 내용이 있을 때 `append`(이어 쓰기, 기본) / `replace`(덮어쓰기). `revise` 는 항상 칸 전체를 고친 결과로 바꾼다 |

**응답**

```json
{"route": "field", "action": "draft", "field": { 칸 }, "progress": {"completed": 2, "total": 7},
 "message": null, "search": null, "contacts": []}
```

| action | 예시 요청 | 결과 |
|---|---|---|
| tip | "이 항목엔 어떤 내용을 넣으면 좋을까?" | `field.tip` 에 안내. 작성란은 그대로 |
| outline | "문장 구조 추천해 줘" | 작성란에 문단 틀 (`1. 지금 나의 상황: [ … ]`) |
| draft | "예시 문장 써 줘" | 작성란에 초안 |
| revise | "더 짧게", "좀 더 구체적으로" | 작성란 전체를 고친 결과로 |

- 바뀐 칸은 `can_undo: true` (되돌리기 버튼)
- AI 는 사용자가 말하지 않은 사실(학교·날짜·경력·숫자)을 지어내지 않고 `[학교 이름]` 같은 빈칸으로 남긴다. 빈칸이 남으면 내보낼 수 없다
- LLM 에 보내는 것: 칸 이름·안내·권장 순서, 지금 칸 내용, 요청, 성장 단계·상태, `context`, 같은 서류의 다른 서술형 칸 최대 3개. **이름·생년월일·연락처·주소·민감 칸 값은 보내지 않는다**
- 에러: 409 FIELD_COMPLETED, 422 FIELD_NOT_WRITABLE / VALUE_TOO_LONG(이어 쓰기 결과가 3000자 초과 → 덮어쓰기로 다시), 503 AI_UNAVAILABLE / RAG_UNAVAILABLE

### 3.30 `GET /documents/{id}/check?user_id`

```json
{"ready": false,
 "problems": [{"field_id": "reason", "label": "신청 사유", "reason": "placeholder_left",
               "message": "'신청 사유': [학교 이름] 빈칸을 채워 주세요."}],
 "warnings": ["다른 서류 「입사지원서 (기본형)」의 '주소'와 내용이 달라요. 맞는지 확인해 주세요."]}
```

- `problems.reason`: `not_completed` / `required_empty` / `placeholder_left` / `invalid_format`(날짜·전화번호). 하나라도 있으면 내보낼 수 없다
- `warnings`: 다른 서류에서 완료한 같은 칸과 값이 다름. 내보내기는 가능

### 3.31 `POST /documents/{id}/export`

```json
{"user_id": "uuid"}
```

- 검사에 문제가 있으면 422 EXPORT_NOT_READY (`GET /check` 로 목록 확인)
- 성공하면 `status: "exported"`, 연결된 체크리스트 서류 항목을 체크한다 (대화 해석 없이 완료·내보내기 상태로만)

```json
{"document": { 문서 객체 },
 "checklist": {"checklist_id": 5, "item_id": 12, "label": "장학금 신청서", "is_checked": true},
 "warnings": [],
 "files": {"pdf": "/api/documents/3/file?format=pdf", "docx": "/api/documents/3/file?format=docx"},
 "pdf_layout": "original"}
```

- `checklist`: 연결된 항목이 없으면 null
- 파일: `files` 경로에 `&user_id=` 를 붙여 GET. 내보내기 전이면 409 NOT_EXPORTED
- `pdf_layout: "original"`: 정책 신청서에 원본 양식 PDF(`data/forms/{form_id}.pdf`)와 칸별 값 자리(`box`)가 있으면 **원본 양식 위에 작성 내용을 채운다**
  - 표 칸은 칸 이름 오른쪽 빈 셀, 없으면 아래 셀에 쓴다. `년 월 일` 칸·줄은 숫자를 각 글자 앞에 맞춰 쓴다
  - 체크박스 칸은 고른 선택지 앞 `[ ]` 안에 체크 표시를 그린다
  - 같은 칸 이름이 여러 번 나오면(대상아동·대리인·보호기관의 "성명", "전화:") 묶음 이름·번호 구역으로 구분한다
  - 칸에 다 안 들어가면(7pt까지 줄여도) 칸에 "별지 참조"를 쓰고 마지막 별지 쪽에 전체 내용을 적는다. 값 자리를 못 찾은 칸도 별지로
  - 민감 칸·공무원 기재란·서명란은 비워 둔다 (출력 후 손으로)
- `pdf_layout: "table"`: 원본 양식이 없는 앱 양식은 제목 + 칸 이름/내용 표 문서. 민감 칸은 "(양식에 직접 작성)", 공무원 칸은 "(담당자 기재란)"
- DOCX 는 원본 배치를 옮길 수 없어 항상 표 문서


### 3.32 `POST /documents/{id}/interview` — 대화로 채우기

AI 가 아직 완료하지 않은 칸을 양식 순서대로 하나씩 묻고, 답을 칸에 채운다. 다 채우면 검사하고 통과하면 바로 내보낸다(3.31과 같음: PDF·체크리스트 체크).

```json
{"user_id": "uuid", "message": "서울특별시 OO구 OO로 12"}
```

- `message` 가 없으면 지금 물어볼 칸만 돌려준다 (시작·이어 하기). 1~500자
- 진행 상태(지금 묻는 칸·건너뛴 칸·확인 기다리는 칸)는 서버가 문서에 저장한다 (마이그레이션 `0005`). 칸 화면(3.28)에서 직접 고쳐도 대화는 이어진다

**응답**

```json
{"route": "field", "message": "적었어요.",
 "field": { 방금 채운 칸 (3.27 칸 객체) },
 "question": {"field_id": "reason", "label": "신청 사유", "text": "'장학금이 필요한 이유'에 쓸 내용을 편하게 말해 주세요. …",
              "input_type": "text", "options": null, "current_value": null, "awaiting_confirm": false, "can_skip": false},
 "progress": {"completed": 5, "total": 7}, "done": false,
 "export": null, "check": null, "search": null, "contacts": []}
```

| 답 | 처리 |
|---|---|
| 답이 아닌 말 ("아니", "음", "ㅋㅋ", "막 아니", "글쎄", "아무거나") | 어느 칸이든 적지 않고 다시 묻는다. 미리 채운 값에 "아니"면 "바르게 알려 주세요", AI 초안에 "아니"면 "어떻게 고칠지 말해 주세요" |
| 유의사항 확인 칸 (`box.how == "confirm"`) | 질문 `items` 에 항목 전문(줄이지 않음)을 준다. 앱은 항목마다 체크박스를 보여 주고, 다 체크해야 "모두 확인했어요"를 보낼 수 있다 (칸별로 쓰기도 `DocField.items` 로 같은 체크 목록, 다 체크해야 완료) |
| 말풍선 줄바꿈 | 서버는 줄바꿈을 넣지 않는다. 앱(`BubbleText`)이 말풍선 폭에 맞춰 낱말 단위로, 줄 길이를 고르게 줄바꿈한다 |
| 신청 날짜 칸 ("년 월 일"·신청일·작성일) | 아직 확인 안 했으면 오늘 날짜로 미리 채우고 "'신청 날짜'는 '2026.10.28'(으)로 할까요?"로 확인만 받는다 (대화로 채우기·칸별로 쓰기 모두, 다시 열면 그날 날짜로). 다른 서류의 날짜는 가져오지 않는다 |
| 대리인 칸 (`proxy_*`, "대리인 …") | 첫 대리인 칸에서 "다른 사람이 대신 신청하나요?"를 한 번 묻는다. "아니요"(직접·본인·없어요)면 대리인 칸을 모두 비워 두고 넘어가고, "예"면 대리인 칸을 차례로 묻는다. 질문("대리 신청이 뭐야?")이면 뜻을 알려 주고 다시 묻는다 |
| 칸과 상관없는 잡담 ("나 배고파", "졸려", "심심해") | 적지 않고 LLM 도 부르지 않는다. "쓰던 내용은 저장돼 있어요. 이어서 …에 쓸 내용을 알려 주세요" |
| 이름 칸 | 말에서 이름만 뽑는다 ("제 이름은 정서현입니다" → `정서현`, "개명했는데 정서현으로" → `정서현`, "정서현으로 해 줘"·"정서현이라고 써 줘" → `정서현`). 조사((으)로·(이)라고)와 부탁 말끝(해 줘·써 주세요·바꿔 줘)은 모든 칸에서 뗀다. 못 찾으면 "이름만 알려 주세요" (`app/answer_extract.py`, 규칙만, **LLM 에 보내지 않음**) |
| 주소 칸 | 시·구·도로명·번호가 있는 부분만 뽑는다 ("저는 서울시 OO구 OO로 12에 살아요" → `서울시 OO구 OO로 12`). "서울"처럼 주소가 아니면 다시 물음 (규칙만) |
| 이메일·사업자등록번호 칸 | 형식에 맞는 부분만 (이메일 주소, 숫자 10자리 → `123-45-67890`). 없으면 다시 물음 (규칙만) |
| 일반 칸 (기관명·관계·은행 등) | 짧은 답은 말끝(요·예요·입니다)만 떼고 적는다. 대화 문장("나 OO 다녀", "다녔어요"처럼 한 낱말이어도 말끝이 문장이면)이면 LLM(`extract_answer`)이 답만 뽑아 "'…'(이)라고 적을까요?"로 확인, 답이 없으면 다시 물음 |
| 날짜 | "2026년 2월 28일", "20260228", "오늘" → `2026.02.28`. 못 알아들으면 다시 물음 |
| 전화 | 숫자만 뽑아 `010-1234-5678`. 못 알아들으면 다시 물음 |
| 체크박스 | 선택지 이름이 들어 있으면 그 선택지 ("문자메시지로 받을래" → `문자메시지`). 없으면 선택지를 다시 보여 줌 |
| 서술형 (사유·동기·계획 등) | 답 내용만으로 AI 가 문장을 써서 칸에 넣고 `awaiting_confirm: true` 로 확인을 기다림. "좋아"면 완료, 다른 말이면 그 말대로 고침 |
| 미리 채운 값 (프로필·다른 서류) | "'김민지'(으)로 할까요?" → "네"면 완료, 다른 답이면 그 값으로 |
| "해당 없음" | 필수가 아닌 칸은 비워 두고 완료 (대리인 칸 등) |
| "나중에", "몰라" | 칸을 그대로 두고 다음 칸으로. 건너뛴 칸은 맨 끝에 다시 물음 |
| 질문 ("소득 기준이 뭐야?", "날씨 어때") | 라우터로 보냄: `route: "policy"` 정책 검색 결과(`search`), `counsel` 안내, 칸에 대한 질문이면 작성 안내(`message`). 칸·질문은 그대로 |
| 짧은 칸에 대화 문장 같은 답 ("배고파서 아무것도 못 하겠다") | 띄어쓰기가 있고 말끝이 대화체(~어/야/지/요/다…)면 바로 적지 않고 "'…'(이)라고 적을까요?"로 확인. "네"면 적고, 다른 말이면 그 말을 새 답으로 본다. LLM 에 보내지 않음 |
| 짧은 칸 답 말끝 ("OO대학교요") | "요·이요·예요·입니다"는 떼고 적는다 |
| 위기 신호 | `route: "crisis"`, `contacts` |

- `done: true` 이고 검사를 통과하면 `export` 에 3.31 응답 (`files` 로 PDF 받기). 검사에 걸리면 `export: null`, `check` 에 문제 목록 → 해당 칸을 칸 화면에서 고치거나 다시 열어 대화
- 앱 화면: 상단 프롬프트를 대화 입력으로 쓰고, `question.field_id` 칸 카드를 선택 상태로 보여 주면 칸별 작성 화면과 함께 쓸 수 있다. 체크박스는 `options` 를 버튼으로
- 유의사항 확인 칸(`box.how: "confirm"`): 항목 첫 문장을 보여 주고 "확인"(또는 "네", "다 읽었어")을 받으면 완료. 앱은 `box.items` 전문을 펼쳐 보여 주면 된다
- 다 채우면 손으로 할 일을 같이 알려 준다: "출력한 뒤 주민등록번호 칸은 직접 써 주세요. 이름 옆 (서명 또는 인) 자리에 서명하거나 도장을 찍어 주세요."

### 3.33 양식 특수 자리 (formguide/layout.py, 내보내기)

양식마다 반복되는 세 가지 자리를 LLM 없이 좌표로 찾는다. `python -m formguide.layout {form_id}` 로 기존 항목 JSON 에 다시 넣을 수 있다.

| 자리 | 찾는 법 | 내보낼 때 |
|---|---|---|
| 계좌 줄 `____ 은행 (예금주: ____)` | 같은 줄에 "은행"·"예금주" 가 있는 셀 | 은행 이름은 "은행" 글자 바로 앞에 오른쪽 맞춤 ("국민은행" → "국민"), 예금주는 "예금주:" 뒤. 계좌번호 칸이 따로 없으면 그 줄 위(없으면 아래) 빈 자리에 계좌번호(`above_line`). 줄 위아래가 좁으면(구판 양식) 별지에 |
| `은행 (예금주: )` 한 칸으로 뽑힌 경우 | 칸 이름에 "은행"·"예금주" 가 같이 있으면 | "은행" 칸 + "예금주" 칸(프로필 이름)으로 나눔 |
| 유의사항 "확인 (√ 체크)" 열 | "확인" 머리글 셀 아래 같은 열의 `[ ]` 셀 2개 이상 | 칸 `notice_confirm` 하나(필수, 선택지 "확인")로 넣고 항목마다 모두 체크 |
| 서명란 `신청인 성명: ____ (서명 또는 인)` | 칸 이름에 "성명" 이 있는 서명(`signature`)·민감 칸 | 이름은 프로필로 채우고, 서명·도장은 손으로. 양식에 "(서명 또는 인)" 글자가 없으면 이름 오른쪽에 그려 넣음 (`box.sign_mark`) |

- 계좌번호: 대화에서 받아 숫자·-만 남김 (10~16자리). 이 칸의 답·질문은 라우터·작성 도우미(LLM)에 보내지 않는다. 주민등록번호는 지금처럼 손으로
- HWP 에서 바꾼 PDF 의 `\x00`(띄어쓰기 자리)은 공백으로 본다

<br/>

### 3.34 `POST /documents/upload` — 올린 양식 (사진·PDF)

기관에서 받은 종이 양식을 찍거나 PDF 로 올리면 AI 가 칸을 읽어 문서를 만든다. 이후는 다른 서류와 같다 (대화로 채우기·칸별로 쓰기·내보내기).

요청 (JSON)

| 필드 | 필수 | 설명 |
|---|---|---|
| user_id | O | |
| file_base64 | O | JPG·PNG·WEBP 사진 또는 PDF (10MB 이하). HEIC 는 422 UPLOAD_INVALID |
| file_name | X | 제목을 못 읽었을 때 제목으로 씀 (IMG_1234 같은 이름은 "올린 양식") |
| checklist_item_id | X | 체크리스트 서류 항목. 내보내면 그 항목을 체크한다 |
| policy_id | X | |

동작

- PDF 에 글자가 있으면 글자만 LLM 에 보낸다. 사진·스캔 PDF 는 쪽 이미지(최대 3쪽, 긴 변 1600px로 다시 그림 → 위치 정보 없음)를 보낸다
- LLM 은 인쇄된 칸 이름만 읽고, 사진에 적힌 개인 정보 값은 옮기지 않는다 (프롬프트). 올린 파일은 저장하지 않는다
- 칸 이름이 양식 글자에 없으면 버린다(지어낸 칸 방지). 접수번호·담당자 같은 기관 칸도 버린다
- 주민등록번호·서명 → 손으로 쓰는 칸, 계좌번호 → account 칸, 성명·연락처 → 프로필로 미리 채움, 사유·계획 → 서술형
- 같은 파일을 다시 올리면 쓰던 문서를 연다 (200, LLM 다시 안 부름). 새로 만들면 201
- 원본 위에 채울 자리가 없어 내보내면 칸 이름·내용 표 PDF(`pdf_layout: "table"`). 받은 양식에 옮겨 적는다
- 칸을 못 찾으면 422 FORM_UNREADABLE, LLM 오류는 503 AI_UNAVAILABLE
- DB: `documents.source_type` 에 `upload` 추가 (alembic 0006)

## 4. 다음 단계

- 양식 업로드(OCR): 사진·PDF → formguide 추출·검사 → `source_type: "upload"` 문서
- 대화 중 사용자 정보 변경 확인 카드 (두 AI 공통)
