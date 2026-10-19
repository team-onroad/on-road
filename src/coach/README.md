# AI 상담·롤플레잉 (coach)

백엔드의 AI 상담 채팅(3.15)·AI 롤플레잉(3.16~3.19)이 쓰는 실제 LLM 함수. 함수 형식은 목 함수(`src/backend/app/llm_mock.py`)와 같다.

| 함수 | 하는 일 |
|---|---|
| `classify_intent` | 상담 메시지가 정책 질문(policy)인지 고민 상담(counsel)인지 분류. 정책이면 백엔드가 정책 검색(RAG)으로 넘긴다 |
| `counsel_reply` | 성장 단계(아동·청소년·청년)에 맞는 말투·길이로 상담 답변. "더 쉽게 말해 줘"는 앞 답변을 더 쉽게 다시 말함 |
| `roleplay_reply` | 상황(예: 집주인 통화)의 상대 역할. 대사 흐름을 참고하되 사용자 말에 먼저 반응, 흐름이 끝나면 마무리 |
| `polish_sentences` | 서류 문장 다듬기. 문장 수가 달라지면 원래 문장 그대로 |

안전 장치
- 위기 신호는 백엔드(`app/crisis.py`)가 LLM 전에 키워드로 먼저 걸러 도움 연락처를 보여 준다
- 상담 답변에 금액(“50만 원”)이나 자격 단정이 있으면 고정 안내 문장으로 바꾼다 (계획서 7.3)
- 문장 끝 반말("~구나", "~어때?")은 해요체로 고친다
- 이름·전화번호·생년월일은 LLM 에 보내지 않는다. 대화 내용은 로그·예외 메시지에 넣지 않는다
- JSON 이 아닌 출력은 한 번 다시 요청하고, 그래도 안 되면 백엔드가 503 AI_UNAVAILABLE 로 안내한다

## 사용

`.env` 에 `LLM_MODE=real` 과 `OPENAI_API_KEY` 를 넣고 백엔드를 다시 띄운다 (기본 `mock`). 모델은 `COACH_MODEL` (기본 gpt-4o-mini).

```powershell
backend\.venv\Scripts\python -m pytest coach/tests    # src 폴더에서, 가짜 LLM 테스트
```
