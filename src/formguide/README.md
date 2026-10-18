# 신청서 작성 도우미 (formguide)

정책 신청서(HWP/HWPX/PDF)를 PDF 로 바꿔 작성 칸을 파악하고, 칸마다 **쉬운 설명·예시 답변·미리 채운 값**을 제공한다.
AI 는 양식 파일을 만들지 않는다. 사용자는 공식 양식이나 온라인 신청 화면에 직접 작성한다.
아직 백엔드 API 와 연결하지 않은 별도 모듈이다.

## 흐름

| 단계 | 언제 | 하는 일 |
|---|---|---|
| 0. 공고 검색 | 필요할 때 | 검색어로 공공서비스(정부24 혜택) 공고를 찾고 상세 페이지·신청방법·구비서류를 본다 (`search.py`, 공공데이터포털 API 키 필요) |
| 1. 가져오기 | 양식마다 한 번 | 파일 경로·URL, 또는 공고 페이지에서 첨부파일을 찾아 신청서로 보이는 파일 선택 (`fetch.py`, `notice.py`) |
| 2. PDF 변환 | 양식마다 한 번 | HWP/HWPX → PDF. 한글 프로그램 자동화, 없으면 LibreOffice (`convert.py`) |
| 3. 추출 | 양식마다 한 번 | PDF 글자·표 추출, 스캔본은 거부 (`extract.py`) |
| 4. 항목 초안 | 양식마다 한 번 | LLM 이 칸 이름·설명·예시·근거를 JSON 으로 (`llm.py`, `prompts.py`) |
| 5. 자동 검사 | 양식마다 한 번 | 원문에 없는 칸 버림, 민감 칸(주민등록번호·계좌·서명)은 예시 지우고 손으로 쓰게, 공무원 기재란 표시 (`validate.py`) |
| 6. 사람 확인 | 양식마다 한 번 | `data/forms/{form_id}.json` 을 원본과 대조하고 `"checked": true` 로 바꿈 |
| 7. 안내 | 앱 요청 시 | 항목 + 프로필 → 칸별 안내. 프로필 값은 LLM 에 보내지 않음 (`guide.py`) |

## 실행 (PowerShell)

```powershell
# 설치 (src\backend 에서, 백엔드 가상환경)
pip install -r ..\formguide\requirements.txt

# 항목 JSON 만들기 (src 에서). .env 의 OPENAI_API_KEY 사용
backend\.venv\Scripts\python -m formguide.build "C:\경로\자립수당_신청서.hwp" --policy-key P001 --form-id P001-apply

# 공고 검색 (.env 의 DATA_GO_KR_SERVICE_KEY 필요)
backend\.venv\Scripts\python -m formguide.search "자립수당"
backend\.venv\Scripts\python -m formguide.search "자립수당" --detail 1        # 신청방법·구비서류
backend\.venv\Scripts\python -m formguide.search "자립수당" --attachments 1   # 상세 페이지 첨부파일
# 검색 → 공고 선택 → 첨부 신청서 → 항목 JSON 까지 한 번에
backend\.venv\Scripts\python -m formguide.build --search "자립수당" --result 1 --policy-key P001 --form-id P001-apply

# 공고 페이지 첨부파일 목록 보기 (AI·API 키 불필요)
backend\.venv\Scripts\python -m formguide.notice "https://공고 페이지 주소"
# 공고 페이지에서 바로 항목 JSON 만들기 (신청서 점수 1위 자동 선택, 다른 파일은 --pick N)
backend\.venv\Scripts\python -m formguide.build --notice "https://공고 페이지 주소" --policy-key P001 --form-id P001-apply

# 안내 보기
backend\.venv\Scripts\python -m formguide.guide P001-apply --demo-user
backend\.venv\Scripts\python -m formguide.guide P001-apply --demo-user --json   # 앱 응답 형식

# 테스트 (가짜 LLM, 가상 신청서 PDF)
backend\.venv\Scripts\python -m pytest formguide/tests
```

- HWP 변환은 한글 프로그램이 설치된 Windows 에서만 된다. 처음 실행 때 한글이 파일 접근 허용 창을 띄우면 허용한다.
  변환이 안 되면 한글에서 "PDF로 저장" 한 파일을 넣으면 된다.
- 결과물: `data/forms/{form_id}.json`(항목), `{form_id}.source.txt`(대조용 원문). `data/forms/_work/`는 커밋하지 않는다.

## 안내 응답 형식 (`make_guide`)

```json
{
  "form_id": "P001-apply", "policy_key": "P001", "title": "...", "checked": false,
  "notes": ["주소지 행정복지센터에 제출"],
  "items": [
    {"field_id": "name", "label": "성명", "section": null, "how": "앱 정보로 미리 채움 (맞는지 확인)",
     "value_source": "profile", "required": true, "guide": "...", "example": "홍길동",
     "prefill": "김민지", "evidence": "성 명"}
  ],
  "notices": ["아직 사람이 원본 양식과 대조하지 않은 안내예요. ...", "예시 답변은 가상의 예시예요. ..."]
}
```

`value_source`: `profile`(프로필로 미리 채움) / `user`(직접 입력) / `handwrite`(앱에 입력하지 않고 양식에 손으로) / `office`(담당 공무원 기재란)

## 공고 검색 준비

1. [공공데이터포털](https://www.data.go.kr/data/15113968/openapi.do)에서 "행정안전부_대한민국 공공서비스(혜택) 정보" 활용신청 (무료, 이용 제한 없음)
2. 마이페이지의 **일반 인증키(Decoding)** 를 `.env` 에 `DATA_GO_KR_SERVICE_KEY=...` 로 넣는다
3. 응답 필드 이름(`서비스명`, `상세조회URL`, `구비서류` 등)은 실제 키로 처음 돌릴 때 확인한다. 다르면 `search.py` 의 `_pick` 후보 이름에 추가
4. 정부24 상세 페이지가 자바스크립트로 그려지면 첨부파일을 못 찾을 수 있다 → 그때는 `--detail` 의 구비서류·신청방법 정보만 쓰고, 신청서는 브라우저에서 받아 파일 경로로 넣는다

아동권리보장원 게시판은 프로그램 요청을 막아("유효하지 않은 요청") 검색 대상에서 뺐다.

## 협의 필요

- 백엔드 API 연결 방식 (3.20~3.22 서류 양식 API 에 "정책 신청서" 종류를 추가할지)
- 신청서 원본 파일 출처와 재배포 가능 여부 (앱은 원본을 배포하지 않고 안내만 제공)
- 사람 확인(`checked`) 담당: 데이터 담당과 분담
