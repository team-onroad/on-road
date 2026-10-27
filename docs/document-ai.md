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
| value_source | `profile` 미리 채움 / `user` 직접 작성 / `handwrite` 민감 칸(주민등록번호·계좌·서명): 앱에 입력하지 않고 양식에 손으로 / `office` 담당 공무원 기재란 |
| writable | false 면 입력·AI 도우미 불가, 진행률(`progress.total`)에서 제외. 카드에 "양식에 직접 작성" 표시 |
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
 "files": {"pdf": "/api/documents/3/file?format=pdf", "docx": "/api/documents/3/file?format=docx"}}
```

- `checklist`: 연결된 항목이 없으면 null
- 파일: `files` 경로에 `&user_id=` 를 붙여 GET. 내보내기 전이면 409 NOT_EXPORTED
- 파일 내용: 제목 + 칸 이름/내용 표. 민감 칸은 "(양식에 직접 작성)", 공무원 칸은 "(담당자 기재란)"으로 비워 둔다. 원본 양식 배치 그대로 채우는 기능은 다음 단계(업로드 양식 좌표 인식)

<br/>

## 4. 다음 단계

- 양식 업로드(OCR): 사진·PDF → formguide 추출·검사 → `source_type: "upload"` 문서
- 원본 양식 위에 값 채워 내보내기 (PDF 좌표)
- 대화 중 사용자 정보 변경 확인 카드 (두 AI 공통)
