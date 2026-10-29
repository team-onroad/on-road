# AI 롤플레잉: 같은 상황 다른 전개 · 돌발 상황 · 피드백

api.md 3.16~3.19 에 더한 것. 기존 필드는 그대로라 예전 앱도 동작한다.

## 데이터 (`src/backend/app/roleplay_scenarios.json`)

| 필드 | 설명 |
|---|---|
| `checkpoints[].example` | 놓쳤을 때 보여 줄 "이렇게 말해 보세요" 문장 |
| `variants[]` | 같은 상황의 다른 전개. `variant_id`, `title`(앱에 보이는 이름), `situation`(상대 역할이 따를 전개, LLM 에 줌, 빈 문자열이면 기본), `lines`(비면 기본 대사), `checkpoints`(이 전개에서 더 볼 것), `better` |
| `surprises[]` | 돌발 상황. `surprise_id`, `level`(NORMAL·HARD), `line`(상대가 할 말), `checkpoint`(대처했는지) |

## 흐름

1. `GET /coach/roleplay/{id}/opening?stage=&variant_id=`
   - 전개는 무작위 (`variant_id` 를 주면 그 전개, 다시 해 보기용)
   - 돌발 상황: 쉬움 0개 · 보통 1개(NORMAL 만) · 어려움 2개(HARD 먼저)
   - 응답에 `variant_id`, `situation`(기본이면 null), `surprise_ids`, `surprise_count` 추가. 돌발 내용은 미리 알려 주지 않는다
2. `POST /reply`, `POST /feedback` 에 `variant_id`, `surprise_ids` 를 그대로 보낸다 (서버는 저장하지 않음, 모르는 id 는 무시)
3. 대사 흐름: 전개의 대사(없으면 기본) → 실전 압박 질문 섞기 → 돌발 상황을 두 번째 대사부터 사이사이에 끼움.
   LLM 에는 `[이번 전개]`, `[돌발 상황]` 을 함께 주고, 사용자가 얼버무리면 한 번 더 확인하게 한다

## 피드백 (`POST /feedback`, LLM 안 씀)

| 필드 | 설명 |
|---|---|
| `items[]` | `label`, `ok`, `kind`(basic·situation·surprise), `example` |
| `score` | 0~100. 항목 맞춘 비율 × 90 (돌발 상황 대처는 2배) + 끝까지 대화하면 10 |
| `grade` | 85↑ 아주 잘했어요 · 60↑ 잘했어요 · 그 밑 한 번 더 해 봐요 |
| `situation` | 이번 전개 이름 |
| `retry_lines` | 놓친 항목의 예시 문장 (다시 해 볼 때) |
| `better` | 상황 공통 + 이번 전개의 고친 말 예시 |
