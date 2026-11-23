# 쉬운 말 변환 (easytext)

"쉬운 말 난이도 단계 설계" 문서의 L1~L7 기준으로 정책 안내문을 변환한다. 모든 단계는 합니다체(~입니다/~습니다)로 쓰고, 설명 항목마다 문단을 나눈다.

## 흐름

1. 입력: `policies.json`의 정형 필드(대상·지원 내용·금액·기간·신청 방법·서류·문의처). 출처·쪽수가 섞인 `original_text`는 넣지 않는다.
2. 변환: 단계 기준(`levels.py`)과 용어 안내(`glossary.json`)를 넣은 프롬프트(`prompts.py`)로 LLM 이 항목별 JSON 을 만든다 → 핵심 블록 + 더 알아보기로 렌더링.
3. 자동 검사 (`checks.py`)
   - 핵심 조건: 숫자·단위·경계 단어(이상·이내·이후 등), 지급 주기, 신청 채널, 제외 조건, 서류. 용어 사전의 쉬운 말로 바꿔 쓴 것도 인정
   - 단계 기준: 핵심 블록 평균·최대 어절·문장 수(120% 허용), 문장 수, 용어 등급
   - 말투(합니다체), 원문에 없는 숫자, 자격 단정 표현
4. 실패하면 지적 사항(빠진 사실은 원문 문장과 함께)을 주고 최대 3번 다시 변환한다.
5. `--judge`: 자동 검사를 통과하면 다른 모델(`EASYTEXT_JUDGE_MODEL`, 기본 gpt-4o)이 뜻이 바뀌거나 빠진 조건을 찾는다. 지적이 있으면 재변환하고, 지적이 0건이어야 통과다.
6. 용어 풀이는 문장에 넣지 않고 결과의 `terms` 목록(용어·등급·풀이)으로 내보낸다. 앱은 이 용어를 강조 표시하고 누르면 풀이를 보여준다.
   용어 규칙: L1~L2 는 T3·T4 를 풀어 쓰고 원래 이름은 괄호로 함께 (T2 이상 연결), L3~L5 는 T3 그대로·T4 풀어 씀, L6~L7 은 원래 용어 그대로 (T3 이상 연결)
7. 결과: `data/easy_text/{policy_key}.json` 에 단계별 저장. `verified`는 사람이 원문과 대조한 뒤 `true`로 바꾼다.

## 핵심 사실 추출·카드 요약 (`facts.py`)

변환 전에 원문에서 핵심 사실을 짧은 키워드 구절로 먼저 뽑는다. 이 목록은 정책 검색 카드 요약(`/search` 의 `results[].summary`)과 쉬운 말 변환의 "빠지면 안 되는 사실" 기준으로 같이 쓴다.

1. 추출: 원문 단위(S1~)마다 대상·제외·지원·기간·신청·서류·확인 필요 항목으로 구절을 뽑는다 (구절당 45자, 서류·확인 필요는 70자 이내). 카드에 보일 구절에는 `key` 를 단다 (항목별 1~3개).
2. 자동 검사: 인용한 원문에 없는 숫자, 아무 구절도 인용하지 않은 원문 단위, 숫자·경계 단어·부정 표현 누락, 제외 조건을 exclusion 항목에 썼는지, 길이, 문장 끝·중간에서 자른 말, 괄호 조각, 출처 메모·메뉴 경로. 실패하면 지적 사항을 주고 최대 3번 다시 뽑는다.
3. `--judge`: 다른 모델이 뜻이 바뀐 구절을 찾는다 (키워드 줄임은 low 로 보게 안내).
4. 카드는 코드가 `key` 구절로 조립한다 (AI 가 문장을 새로 쓰지 않음). 결과: `data/policy_facts/{policy_key}.json`. `verified` 는 사람이 원문과 대조한 뒤 `true` 로 바꾼다.
5. 쉬운 말 변환(`easytext.convert`)은 통과한 사실 목록이 있고 원문이 그 뒤로 바뀌지 않았으면(`source_hash`) 프롬프트에 넣고, 사실의 숫자가 변환문에 빠지면 다시 변환한다.

```powershell
backend\.venv\Scripts\python -m easytext.facts P001 --judge   # 한 정책
backend\.venv\Scripts\python -m easytext.facts --all          # 모든 정책 (원문이 그대로면 건너뜀, --force 로 다시)
```

현재 상태 (2026-10-19, P001, EASYTEXT_MODEL=gpt-4o, --judge): 자동 검사 + 2차 검수 통과 (2번 시도). 사람 검수(`verified`) 전.
gpt-4o-mini 는 출처 메모를 그대로 옮기거나 문장을 중간에서 자르는 일이 많아 gpt-4o 를 권장한다.

## 정부24 원문 표기 (검사기가 같은 값으로 보는 것)

원문과 변환문 양쪽에 똑같이 맞춘 뒤 비교한다 (`checks.expand_dates` · `normalize`)
- 날짜: `'18.8.` · `` `24.2.9. `` → 2018년 8월 · 2024년 2월 9일, `2024.01.01` → 2024년 1월 1일, `'20년 …, 21년 …` 의 21년 → 2021년
- 금액: `15백만원` → 1500만원, `2.4천원` → 2400원, `2,000천원` → 200만원. 전화번호는 숫자만
- 숫자 목록 `3, 6, 9, 12월` 은 붙이지 않는다. `1세대` 는 나이가 아니다. `한 번` · `하루` 는 `1회` · `1일` 로 인정
- 같은 내용이 여러 칸에 되풀이된 줄(같은 줄 · 다른 줄에 통째로 들어 있음 · 숫자가 같고 거의 같은 줄)은 한 번만 센다.
  다른 조건에 같은 값이 쓰인 것은 그대로 센다. 사업 예산 줄은 세지 않는다

## 여러 정책 한꺼번에 (`batch.py`) · 다시 검사 (`recheck.py`)

```powershell
backend\.venv\Scripts\python -m easytext.batch --collected --dry-run     # 할 일 · 예상 비용 · 시간만 (API 없음)
backend\.venv\Scripts\python -m easytext.batch --collected --limit 20    # 수집 정책 앞에서 20개 (사실 → L6 변환, 2차 검수 켬)
backend\.venv\Scripts\python -m easytext.batch G084 G018 --levels L5 L6 --workers 4
backend\.venv\Scripts\python -m easytext.recheck --judge               # 저장된 결과를 지금 검사기로 다시 검사 (검사기를 고친 뒤)
```

- `batch`: 4개씩 동시에. 이미 통과했고 원문이 그대로면 건너뛰고, 원문이 바뀌면(수집기가 새로 씀) 사실 · 변환을 다시 한다.
  같은 원문으로 이미 실패한 것은 기본으로 건너뛴다 (또 돌려도 대개 다시 실패해 비용만 듦) — `--retry-failed` 로 다시.
  한 정책이 오류가 나도 나머지는 계속한다. 예상: 사실 약 80원 · 단계 하나 약 100원, 정책당 약 35초 (gpt-4o, 11/22 실측)
- `recheck`: 앱은 저장된 `passed` 가 true 인 것만 보여 준다. 검사기를 고친 뒤 옛 검사기의 `passed=false` 가 남아
  멀쩡한 변환문이 안 보이던 것을, 글은 그대로 두고 검사만 다시 해 고친다. 2차 검수를 못 받은 것만 `--judge` 로 한 번씩.
  원문 인용 검사(`missing_units` · `unit_gaps`)는 변환 초안이 있어야 해서 다시 못 하므로 변환 때 결과를 이어받는다

## 용어 지표 · 사전 보강 후보 (`evaluate.py` · `term_candidates.py`)

- L6 · L7 은 공식 용어를 그대로 쓰고 앱이 사전 풀이를 연결하므로, 이 단계의 '용어 감소율'은 핵심 블록이 짧아진 몫이다.
  평가는 단계 묶음별(L1~L5 · L6~L7) · 합계 기준으로 따로 세고, 밀도(1,000자당 용어 수)와 T4 감소율(L1~L5 에서 바꿔야 하는 말)을 함께 낸다
- `python -m easytext.term_candidates --collected`: 변환문에 나오는데 사전에 없는 말 목록 (행정 용어 꼴 먼저). 사람이 골라 glossary.json 에 넣는다

## 서비스 노출 칸에 반영 (`publish.py`)

```powershell
backend\.venv\Scripts\python -m easytext.publish --dry-run   # 무엇이 바뀌는지만
backend\.venv\Scripts\python -m easytext.publish             # policies.json 의 easy_text · easy_text_verified 에 반영
cd backend; .venv\Scripts\python -m db.seed                  # DB 에 반영 (기존 시드)
```

- 정책은 DB 의 `is_active AND easy_text_verified` 일 때만 서비스에 나온다 (docs/db-schema.md 4장). 변환 결과 파일만으로는 안 나온다
- 노출 기준: 자동 검수 통과(`passed`). 사람 검수(`verified`)는 기다리지 않는다 (사람 검수 전이면 앱이 '검토 중' 안내)
- 기본 단계(L6), 없으면 가장 가까운 통과 단계의 글을 `easy_text` 에. 통과한 단계가 없거나 원문이 바뀌면 `easy_text_verified=false`
- 자동 수집 정책(api · crawler)만 고친다. 저장 전에 `db/seed.py` 검사를 그대로 돌린다
- 순서: 수집(`collector`) → 변환(`batch`) → 반영(`publish`) → 시드. 수집기가 원문이 바뀐 정책을 다시 쓰면 숨김으로 돌아가니 다시 변환 · 반영

## 사람 검수표 (`review.py`)

```powershell
backend\.venv\Scripts\python -m easytext.review --collected                    # 검수표 → data/eval_results/easy_text_review_날짜.md
backend\.venv\Scripts\python -m easytext.review --approve G020 G018:L7 G095:card  # 원문과 대조해 맞으면 verified 표시
backend\.venv\Scripts\python -m easytext.review --revoke G020:L6                  # 표시 취소
```

- 검수표 ① 앱에 안 보이는 것(막힌 이유 · 고치는 법) ② 통과했지만 사람 검수 전(주의할 것 먼저). 정책마다 원문 · 변환문 · 카드 요약을 나란히
- `--approve G020` 은 그 정책의 통과한 것 모두. 통과 전이거나 원문이 바뀐 것에는 표시하지 않는다 (고친 뒤 `recheck`)
- '인용 기록 없음': 11/23 전 `recheck` 가 변환 때 원문 인용 검사 결과를 지워 남아 있지 않은 것 → 빠진 조건이 없는지 꼼꼼히
- 이 표시는 `data/` 파일의 `verified` 만 바꾼다. DB 의 `policies.easy_text_verified` (노출 여부)는 따로


```powershell
backend\.venv\Scripts\python -m easytext.convert P001                     # 기본 L6
backend\.venv\Scripts\python -m easytext.convert P001 --levels L2 L5 L6 --judge
backend\.venv\Scripts\python -m pytest easytext/tests                     # 가짜 LLM 테스트
```

`.env` 선택 설정: `EASYTEXT_MODEL`(기본 gpt-4o-mini), `EASYTEXT_JUDGE_MODEL`(기본 gpt-4o)

## 현재 상태 (2026-10-19, P001, EASYTEXT_MODEL=gpt-4o, --judge)

- L1~L7 모두 자동 검사 + 2차 검수 통과 (각 3~4번 시도). 사람 검수(`verified`) 전.
- 누락 방지 장치: 조건 단위로 나눈 원문(S1~S36) 인용 확인, 인용 문장 안의 핵심 요소(숫자·용어·부정 표현) 확인,
  같은 숫자는 조건마다, 조건부 서류·우편/팩스 조건, 신청 주체, core 필수 항목, 2차 검수(오탐 자동 제거)
- 코드가 직접 고치는 것(뜻을 바꾸지 않는 것만): 안전한 표현 바꿔 쓰기, 조건부 서류에 원문 조건 붙이기,
  조건 없는 중복 서류 문장 빼기, core 길이 맞추기(문장 위치 이동), 중복 문장 제거, 자격 확정 불가 안내
- 용어는 문장에 괄호 풀이를 넣지 않고 `terms` 목록으로 내보냄 (앱이 사전 풀이로 표시)
- 자동 검사·2차 검수를 통과해도 사람 검수가 필요하다 (핵심 블록 문장이 어색한 경우 등).

## 협의 필요

- 핵심 조건 대조 규칙은 데이터 담당의 `scripts/eval/easy_text_verify.py`와 맞춘다 (지금은 이 모듈이 임시로 가짐)
- 단계별 변환문 저장 위치(DB 표·API)는 백엔드와 협의. 지금은 JSON 파일
- 용어 사전(`glossary.json`)은 P001 기준 초안이라 사람이 확정해야 함
