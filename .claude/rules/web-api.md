---
paths:
  - "src/main/java/com/neowadaeum/common/web/**/*.java"
  - "src/main/java/com/neowadaeum/common/error/**/*.java"
  - "src/test/java/com/neowadaeum/common/**/*.java"
---

# web-api — 컨트롤러 · 공통 응답 · 에러

## 계약

- 경로는 `/api/v1/...`. 상위 문서 명세의 표기를 그대로 따른다(억지로 kebab-case로 바꾸지 않는다).
- 요청/응답 JSON은 **camelCase**, DB 컬럼은 **snake_case**. 변환은 Jackson 설정으로 일괄 처리한다.
- 시간은 전부 **UTC ISO-8601**(`2026-08-22T11:20:00Z`). **만 나이 계산만 KST**다.
- **nullable 필드는 키를 생략하지 않고 `null`로 명시한다** (`speakerName` · `sceneImage` · `endingId` · `reachRate`). 프론트가 키 존재 여부로 분기하지 않게 한다.
- `docs/openapi.yaml`이 런타임 진실의 원천이다. 계약을 바꾸면 함께 고친다.
- **성공 응답은 AI 고지 문구를 싣거나, 왜 싣지 않는지 말한다** (#291, §13-52). 2xx 의 JSON 본문 스키마는 `noticeText` 를 `required` 로 갖거나 `x-notice-exempt: '<이유>'` 를 적는다. **기본이 "싣는다"** 이므로 새 응답을 만들면서 둘 다 하지 않으면 `OpenApiContractTests` 가 깨진다 — 표시를 잊어서 조용히 새던 것이 #257 · #284 · #289 였다.
- **단위가 있는 수치는 계약이 단위를 말한다** (#275, §13-53). 이름이 `Rate` · `Percent` · `Ms` · `Micro` · `Tokens` · `Bytes` · `Cost` 로 끝나는 수치 필드는 `description` 에 단위를 적고, 비율은 `minimum` · `maximum` · `examples` 까지 갖는다. **카운터(`turnNo` · `order`)는 대상이 아니다** — 거기까지 강제하면 규칙이 소음이 된다.

## 에러 응답

**항상 동일 형태다.**

```json
{ "error": "TURN_CONFLICT", "message": "...", "details": { } }
```

- 클라이언트는 `error` 코드로 문구를 매핑한다. **서버 `message`를 UI에 그대로 쓴다고 가정하지 않는다.**
- **스택트레이스·SQL·내부 경로를 절대 노출하지 않는다** (S-6). `code` + 안전한 `message`만.
- `ErrorCode` enum은 `docs/engineering-guide.md`의 에러 코드 카탈로그와 **정확히 일치**해야 하며 `ErrorCodeTests`가 강제한다. 코드를 추가·삭제하면 표와 테스트를 함께 고친다.
- 429는 셋으로 구분한다: `RETRY_COOLDOWN` / `RATE_LIMITED` / `QUOTA_EXCEEDED`. **하나로 합치지 않는다.**
- `INTERNAL_ERROR`는 예상하지 못한 예외의 폴백이다. `CONTEXT_BUDGET_EXCEEDED`를 폴백으로 재사용하지 않는다.

## 계층

- **Controller는 요청 검증과 DTO 변환만 한다.** 비즈니스 로직을 넣지 않는다.
- Service가 트랜잭션 경계다. 조회에는 `@Transactional(readOnly = true)`를 기본 적용한다.
- **트랜잭션 안에서 외부 HTTP를 호출하지 않는다.**

## 로깅

구조화 로그만 쓴다. 문장형 로그와 `System.out.println`을 금지한다.

```java
log.info("turn.generated sessionId={} turnNo={} provider={} latencyMs={}", ...);
```

**프롬프트 원문·응답 원문·API 키·토큰·이메일을 남기지 않는다** (S-3).
