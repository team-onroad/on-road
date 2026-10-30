# AI 롤플레잉: 같은 상황 다른 전개 · 돌발 상황 · 피드백

api.md 3.16~3.19 에 더한 것. 기존 필드는 그대로라 예전 앱도 동작한다.

## 데이터 (`src/backend/app/roleplay_scenarios.json`)

| 필드 | 설명 |
|---|---|
| `checkpoints[].example` | 놓쳤을 때 보여 줄 "이렇게 말해 보세요" 문장 |
| `variants[]` | 같은 상황의 다른 전개. `variant_id`, `title`(앱에 보이는 이름), `situation`(상대 역할이 따를 전개, LLM 에 줌, 빈 문자열이면 기본), `lines`(비면 기본 대사), `checkpoints`(이 전개에서 더 볼 것), `better` |
| `follow_ups[]` | 연습 뒤 이어서 해 볼 것 (다른 기능 연결). `kind`, `label`, `query`, `scenario_id`, `when`(always·missed), `labels`(이 항목을 놓쳤을 때만) |
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
| `next_steps` | 이어서 해 볼 것 최대 3개. `kind`(search·practice·budget·month·forms·chat), `label`, `query`, `scenario_id`. 놓친 게 있을 때 권하는 것(`follow_ups[].when = missed`)이 먼저 |

## 상황 목록 (14개)

돌발 상황은 상황마다 6~7개 중에서 난이도만큼 무작위로 나와요 (쉬움 0 · 보통 1 · 어려움 2, 어려움은 HARD 먼저). 아동(가게)은 쉬움이라 돌발이 없어요.

| id | 상황 | 대상 | 난이도 | 전개 | 돌발 (HARD) |
|---|---|---|---|---|---|
| store | 가게에서 물건 사기 | child | 쉬움 | 기본 상황 · 찾는 물건이 다 팔림 · 거스름돈이 틀림 | - |
| bank | 은행 계좌 개설 | teen·youth | 쉬움 (youth 보통) | 기본 상황 · 한도제한 계좌로 안내받음 · 계좌 목적 증빙을 요구받음 | 6 (2) |
| community-center | 주민센터 전입신고 | youth | 보통 | 기본 상황 · 계약서를 안 가져옴 · 주거 지원도 같이 물어보기 | 7 (3) |
| landlord | 집주인에게 수리 요청 전화 | youth | 보통 | 기본 상황 · 집주인이 수리를 미룸 · 말로만 약속하려는 집주인 | 7 (3) |
| parttime | 아르바이트 면접 | teen·youth | 보통 (youth 어려움) | 기본 상황 · 근로계약서를 미루는 점장 · 근무 시간을 자꾸 바꾸려는 점장 | 7 (3) |
| school | 진학 면접 | teen | 보통 | 기본 상황 · 활동 기록 꼬리 질문 · 진로를 바꾼 이유 | 6 (2) |
| job | 취업 면접 (실전) | youth | 어려움 | 기본 상황 · 공백기를 집요하게 묻는 면접관 · 희망 연봉을 물어보는 면접관 | 7 (2) |
| realestate | 원룸 계약하기 | youth | 보통 | 기본 상황 · 오늘 안 하면 놓친다고 재촉 · 근저당이 잡힌 집 | 7 (2) |
| phone-store | 휴대폰 개통하기 | teen·youth | 보통 (teen 쉬움) | 기본 상황 · 한 달 요금만 말하는 직원 · 미성년자 개통 | 7 (2) |
| hospital | 병원 진료 받기 | teen·youth | 쉬움 (youth 보통) | 기본 상황 · 검사를 권하는 의사 · 말이 빠른 의사 | 6 (1) |
| wage | 밀린 알바비 받기 | teen·youth | 어려움 (teen 보통) | 기본 상황 · 덜 주려는 사장님 · 주휴수당 이야기 | 7 (2) |
| scam-call | 수상한 전화 대처하기 | teen·youth | 보통 | 기본 상황 · 저금리 대출 전화 · 가족·지인 사칭 문자 | 7 (3) |
| neighbor | 층간소음 이야기하기 | youth | 보통 | 기본 상황 · 아이가 있는 집 · 우리 집 아니라는 이웃 | 6 (2) |
| jobcenter | 고용센터 취업 상담 | youth | 보통 | 기본 상황 · 하고 싶은 게 없는 경우 · 생활비가 급한 경우 | 6 (1) |
