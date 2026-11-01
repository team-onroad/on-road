# 자립 준비도 · 활동 기록 · 롤플레잉 힌트

## 활동 기록 `POST /api/users/{user_id}/activities` (201)

앱에서 한 실전 연습 한 번, 한 달 살아보기 한 번을 남긴다 (대화 내용은 보내지 않음). 테이블 `activity_logs` (alembic 0007).

| kind | ref | data |
|---|---|---|
| `practice` | 연습 상황 id (필수) | `good`, `missed` (0~100), `score` (선택) |
| `month_life` | 없음 | `income`, `left`, `shortage`, `used_emergency` (만원), `home` (STUDIO·PUBLIC·SHARE), `good`, `bad`, `bad_events` (아쉬운 선택을 한 일 id) |

응답 `{"activity_id": 1}`. 형식 오류 422, 사용자 없음 404.

## 자립 준비도 `GET /api/users/{user_id}/readiness`

6개 영역 × 4개 항목. 영역 점수 = 한 항목 비율 × 100, 전체 점수 = 영역 평균.

| 영역 | 항목 |
|---|---|
| housing 주거 | 원룸 계약 연습 · 전입신고 연습 · 집주인 수리 요청 연습 · 주거 정책 신청 준비 시작 |
| money 돈 | 생활비 계획 · 부족 없는 최근 계획 · 은행 계좌 연습 · 모자람 없이 한 달 버티기 |
| job 일 | 알바 면접 · 취업 면접 · 밀린 알바비 · 고용센터 상담 연습 |
| documents 서류 | 서류 작성 시작 · 서류 내보내기 · 정책 신청 준비 시작 · 신청 준비 완료 |
| safety 안전 | 수상한 전화 연습 · 놓친 것 없이 대처 · 휴대폰 개통 연습 · 최근 한 달 살아보기에서 사기 안 당함 |
| life 생활 | 병원 진료 연습 · 층간소음 연습 · 한 달 살아보기 1번 · 2번 |

- 연습 기록은 activity_logs(practice) + 기록에 남긴 roleplay_results 둘 다 본다
- `level`: 85↑ 자립 준비 완료 · 60↑ 거의 다 왔어요 · 30↑ 준비 중 · 그 밑 첫걸음
- `areas[].next_step`: 그 영역에서 아직 안 한 첫 항목으로 가는 길 (`kind`, `label`, `query`, `scenario_id` — 롤플레잉 `next_steps` 와 같은 모양)
- `weakest`, `next_steps`: 점수 낮은 영역 순서 (최대 3개)

## 롤플레잉 힌트 `POST /api/coach/roleplay/{scenario_id}/hint`

대화 중 "뭐라고 하지?". LLM 을 쓰지 않고 체크 항목 규칙으로 아직 안 챙긴 것 하나를 고른다.

- 요청: `history`, `stage`, `variant_id`, `surprise_ids` (reply 와 같은 값)
- 순서: 방금 상대가 한 돌발 대사(낱말 2개 이상 겹침)의 대처 → 이번 전개 → 기본 → 나머지 돌발
- 응답: `done`, `label`, `example`, `kind`(basic·situation·surprise·closing), `remaining`. 다 챙겼으면 `closing`(마무리 인사)
