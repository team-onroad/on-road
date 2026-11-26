# 정책 신청 서류 양식 (서버 양식 · 연결표)

정책 신청 때 내는 서류 중 **공식 양식이 있는 것**을 서버(`data/forms/`)에 두고, 앱에서 바로 작성할 수 있게 하려는 데이터입니다.
백엔드 연결까지 되어 있습니다 (아래 '백엔드 연결'). 앱 화면은 아직입니다.

**쓰는 곳: 정책 추천 → 정책을 골라 "지원하기"(신청) 화면의 필요 서류 작성에만** (팀 결정 11/26).
진로 로드맵 · 맞춤 신청 로드맵 · 상담 화면에는 넣지 않는다 (그쪽의 `has_form` · "신청서 쓰기"는 지금처럼 `find_form` 그대로).

## 들어 있는 것

| 파일 | 내용 |
|---|---|
| `data/forms/{form_id}.json` · `.pdf` · `.source.txt` · `.extracted.json` | 양식 35개 (정책 31개에 연결). formguide.build 로 만든 칸 · 값 자리(`box`)와 원본 PDF (기존 `P001-apply` 와 같은 형식) |
| `data/forms/sources.json` | 양식마다 공식 출처(법령 · 고시 · 지침, 서식 번호, 주소)와 정책 연결 규칙 (사람이 고침) |
| `data/forms/index.json` | `python -m collector.forms_index` 가 만드는 연결표 (양식 ↔ 정책 ↔ 필요 서류) |
| `src/collector/forms_index.py` · `tests/test_forms_index.py` | 연결표 만들기 · 테스트 |

양식 출처: 국가법령정보센터 별표 · 서식(아동복지법 시행규칙, 국토부 훈령, 사회보장급여 공통서식 고시)과
보건복지부 「2026년 아동분야 사업안내」 1 · 2권 서식. 모두 공공 서식이라 서버에 두고 내려받게 해도 된다.

| 양식 | 출처 | 연결 정책 |
|---|---|---|
| 자립정착금 사용계획서 `mohw-settle-plan` | 사업안내 2권 Ⅰ-4호 | G009 G012 G015 G021 G024 G030 G041 G042 G044 G046 G048 G080 |
| 사회보장급여 신청(변경)서 `welfare-apply` (**자립정착금 신청서 대신**) | 공통서식 고시 별지 1 | G024 G035 G041 G042 G048 G080 G030 |
| 디딤씨앗통장 지원 신청서 · 적립 및 사용계획서 · 적립금 사용 신청서 | 사업안내 2권 Ⅱ-1 · 12 · 8호 | G084 |
| 자립지원계획서 `mohw-independence-plan` | 사업안내 2권 Ⅰ-1호 | G030 |
| 자립수당 위임장 `mohw-allowance-proxy` · 복지급여 계좌 변경 신청서 `mohw-account-change` | 사업안내 2권 Ⅳ-6 · 2호 | P001 (계좌 변경은 G007 G034 도) |
| 보호종료 후 사후관리 동의서 `mohw-aftercare-consent` | 사업안내 2권 Ⅴ-5 · 5-1호 | G018 |
| 가정위탁보호동의서 · 가정위탁 가족 동의서 · 아동통합정보시스템 조회 동의서 | 사업안내 1권 Ⅲ-2 · 3 · 3의1호 | G053 |
| 가정위탁보호 신청서 `child-foster-apply` · 범죄경력 조회 동의서 `child-crime-consent` | 아동복지법 시행규칙 별지 1 · 9호 | G057 G075 / G053 |
| 퇴소 신청서 · 재보호조치 신청서 · 보호기간 연장 승인신청서 | 아동복지법 시행규칙 별지 10의3 · 10의5 · 25호 | 정책 공통 (`scope: common`) |
| 전세주택지원 신청서 2종 · 전세임대주택 공급신청서 | 국토부 훈령 | W001 / G031 |
| (기존) 자립수당 신청서 `P001-apply` | 서현 등록 | P001 · G034 (연결표에만 추가, 파일은 그대로) |
| 금융정보 등 제공 동의서 `welfare-finance-consent` | 공통서식 고시 별지 1의3 | 정책 공통 (사회보장급여 신청과 함께) |
| 진도 만원주택 별첨: 개인정보 · 금융정보 동의서, 중복선정 불가 확인서, 우선공급 청년 신청 · 세대구성 확인서, 취창업 입주 동의서, 위임장 `jindo-*` | 전남개발공사 공고(2026. 11. 17.) 별첨 2~6 · 11 · 12 | G032 |
| 청년도전 참여 신청서 · 개인정보 동의서(청년용) `youth-apply` · `youth-privacy-consent` | 고용노동부 2026 청년도전지원사업 시행지침 서식 1 | G028 G033 (+ '현장 작성'만 적힌 G019 G023 G026) |
| 상담원 문답표 · 중장기 적합판단 질문지 `youth-counsel-survey` · `youth-program-fit` | 〃 붙임 1 · 별지 2 | G025 G028 G033 / G033 |
| 취(창)업 인센티브 지급 신청서 `youth-incentive-apply` (참여 뒤 내는 부수 서류) | 〃 서식 7 | 청년도전 5개 |
| 부동산 중개보수 청구서 · 개인정보 동의서 `gg-*` | 경기부동산포털 서식 | G029 |

- 자립정착금 신청서는 지자체 자체 양식이라 공개된 곳이 없어(정부24 · 자치법규 · 지자체 누리집 11/26 확인) 행정복지센터 공통 신청서로 대신한다 (팀 결정).
  연결표에 `substitute: true` 와 `notice`("지자체마다 자체 신청서를 쓸 수 있어 … 창구에서 다른 양식을 주면 옮겨 적어요")가 있다
- 공고 첨부 양식(진도 · 경기)은 공고가 새로 나오면 바뀔 수 있다 (`sources.json` 에 공고 날짜 · 주소). 진도 만원주택은 정부24 온라인 신청만 받아
  입주 신청서는 정부24 화면에서 쓰고, 앱 양식은 첨부할 확인서 · 동의서용
- **보완 필요 (연결표에서 뺌)**: 진도 입주 신청서(별첨 1)와 경기 중개보수 대리수령 신청서는 formguide 가 칸을 거의 못 뽑았다
  (칸 이름이 세로로 갈라진 표 · 같은 이름 칸이 신청자 / 대리수령자로 두 번 → 검사가 "원문에 없는 칸"으로 버림, 5개 · 3개만 남음).
  formguide 칸 이름 검사를 보완하면 다시 넣는다 (원본: 진도 공고 별첨서식.zip, 경기부동산포털 서식)
- 모두 `checked: false` (사람이 원본과 대조 전). 디딤씨앗 적립금 사용 신청서 · 적립 및 사용계획서는 체크칸 기호 `❏` 를 formguide 가
  체크박스로 못 읽어 값 자리가 적다 (못 찾은 칸은 내보낼 때 별지에 적힘)

## 지금 앱에 영향 없음

지금 `ai_content.find_form(policy_key)` 는 `policy_key` 가 같은 양식을 그 정책의 "신청서"로 바로 연다 (정책 하나에 양식 하나).
새 양식 JSON 의 `policy_key` 는 실제 정책 키가 아닌 **`form:{form_id}`** 로 두어 지금 화면(정책 상세 · 상담 · 로드맵의 `has_form`)은
그대로다 (정책 99개 모두 `find_form` 결과가 전과 같음: P001 → P001-apply 만). 정책과의 연결은 `index.json` 에만 있다.

## index.json 형식

```json
{
  "forms": {
    "mohw-settle-plan": {
      "title": "자립정착금 사용계획서", "scope": "policy", "checked": false, "fields": 17,
      "source": {"kind": "guideline", "name": "2026년 아동분야 사업안내(2권)", "form_no": "Ⅰ. 보호아동 자립지원 서식 4호", "url": "…"},
      "policy_keys": ["G009", "G012", "…"],
      "docs": [{"policy_key": "G012", "doc_name": "자립정착금 사용계획서(1차, 2차)"}, "…"]
    },
    "welfare-apply": {"…": "…", "substitute": true, "notice": "행정복지센터 복지 신청에 쓰는 공통 신청서예요. …"}
  },
  "by_policy": {"G012": [{"form_id": "mohw-settle-plan", "doc_name": "자립정착금 사용계획서(1차, 2차)"}]},
  "unmatched_docs": ["작성 양식처럼 보이는데 아직 서버 양식이 없는 서류 이름", "…"]
}
```

- `by_policy[정책키]`: 그 정책의 서버 양식들. `doc_name` 은 그 정책 `required_docs[].name` 과 **글자 그대로 같다** (null 이면 서류 목록에는
  없지만 그 정책에 쓰는 양식 — 예: G084 디딤씨앗통장은 원문 서류 칸이 비어 있음)
- `scope: common`: 특정 정책 서류는 아니지만 보호 중 · 퇴소 사용자가 직접 내는 서류 (`policy_keys` 비어 있음)

## 백엔드 연결

1. **양식 찾기** (`app/ai_content.py`): `policy_forms(policy_key)` 가 `index.json` 의 `by_policy` 를 읽어
   `[{form_id, title, doc_name, checked, notice}]` 를 준다 (양식 파일이 없는 것은 뺌). 양식 파일은 `load_form(form_id)` 로 `{form_id}.json` 을 읽는다.
   기존 `find_form(policy_key)` 는 그대로 (P001 → P001-apply)
2. **체크리스트(신청 준비) 응답** (`GET /checklists/{id}`): 서류 항목마다 `forms` (서류 이름이 `doc_name` 과 같은 양식),
   응답에 `extra_forms` (`doc_name: null` 인 양식). 정책이 노출 대상이 아니면 둘 다 비어 있다.
   로드맵 · 상담의 `has_form` 은 바꾸지 않았다
3. **서류 작성 시작** (`POST /documents`): `source_type: policy_form` 에 `form_id` 를 보내면 그 양식으로 시작한다.
   그 정책에 연결된 양식(또는 그 정책의 기존 신청서)이 아니면 404 `FORM_NOT_FOUND`. `form_id` 가 없으면 지금처럼 정책 신청서.
   연결표 양식은 여러 정책 · 여러 서류(1차 · 2차 사용계획서)에 같이 쓰여서 문서 `source_id` 를 `{form_id}@i{체크리스트 항목 ID}`
   (항목 없이 시작하면 `{form_id}@p{정책 ID}`)로 저장해 서류마다 따로 쓴다. 그 정책의 기존 신청서(P001-apply)는 지금처럼 `form_id` 그대로.
   내보내기는 `@` 앞의 `form_id` 로 `data/forms/{form_id}.pdf` 위에 채운다
4. **안내**: `substitute` 양식으로 만든 문서는 `notices` 맨 앞에 연결표의 `notice`, `checked: false` 는 지금처럼 "원본과 대조하지 않은" 안내
5. 테스트: `tests/test_writer_api.py` (서류별 `forms` · `extra_forms`, `form_id` 로 시작, 다른 정책 양식 404, P001 기존 흐름)

## 다시 만들기 · 양식 추가

```
cd src
python -m formguide.build "양식.pdf" --policy-key G012 --form-id 새-양식-id   # 칸 · 값 자리 (OPENAI_API_KEY, 양식 하나 약 100~200원)
# data/forms/sources.json 에 출처 · 연결 규칙(match: 서류 이름 정규식 / policies: 정책 키) 추가
python -m collector.forms_index        # 연결표 다시 만들기 + 양식 JSON 의 policy_key 를 form:{id} · source 를 출처 주소로 맞춤
```
