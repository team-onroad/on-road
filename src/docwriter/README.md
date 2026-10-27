# 문서 AI (docwriter)

백엔드 문서 AI(`docs/document-ai.md`)가 쓰는 실제 LLM 함수. 함수 형식은 목 함수(`src/backend/app/llm_mock.py`)와 같다.

| 함수 | 하는 일 |
|---|---|
| `route_message` | 라우터. 상담 화면: 서류 작성 요청(document)인지, 문서 화면: 지금 칸 요청(field)·정책 질문(policy)·고민(counsel)인지 |
| `assist_field` | 선택한 칸 작성 도우미. 요청을 tip(넣을 내용)·outline(문장 구조)·draft(예시 문장)·revise(고치기)로 나눠 결과 글을 만든다 |

안전 장치
- 위기 신호는 백엔드(`app/crisis.py`)가 LLM 전에 키워드로 먼저 거른다
- 사용자가 말하지 않은 사실은 지어내지 않고 `[학교 이름]` 같은 빈칸으로 남긴다. 빈칸이 남으면 내보내기 검사에서 막힌다
- revise 가 사용자가 남긴 빈칸을 지어낸 값으로 채우면 결과를 버리고 "빈칸을 먼저 채워 달라"는 tip 으로 바꾼다
- 이름·생년월일·연락처·주소·민감 칸 값은 받지 않는다 (백엔드가 빼고 보낸다). 작성 내용은 로그·예외 메시지에 넣지 않는다

## 사용

`.env` 에 `LLM_MODE=real` 과 `OPENAI_API_KEY` 를 넣고 백엔드를 다시 띄운다 (기본 `mock`). 모델은 `COACH_MODEL` (기본 gpt-4o-mini).

```powershell
backend\.venv\Scripts\python -m pytest docwriter/tests    # src 폴더에서, 가짜 LLM 테스트
```
