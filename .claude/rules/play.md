---
paths:
  - "src/main/java/com/neowadaeum/play/**/*.java"
  - "src/test/java/com/neowadaeum/play/**/*.java"
---

# play — 세션 · 턴 · 엔진

근거: `docs/engineering-guide.md` §4.3 턴 파이프라인 / §4.5 Chapter / §4.6 Ending / §4.7 Resume.

## 턴 번호 계약 — 혼동 금지

- 요청 `turnNo` = **지금 화면에 떠 있는 턴** (사용자가 선택지를 고른 그 턴)
- 응답 `turnNo` = **새로 생성된 턴** (요청값 + 1)
- 실패 시 `session.turn_no`를 **변경하지 않는다.** GameState도 그대로다.

## 서버가 판정하는 것 — AI에게 넘기지 않는다

- **Chapter 전환과 Ending 선언은 GameState로 판정한다** (I-10). `chapterAdvanceSuggested` · `endingSuggested`는 **로그로만** 남긴다.
- `disabled` / `disabledReason`은 서버가 판정한다 (I-11). P0에서는 항상 `false` / `null`이며 API 필드는 유지한다.
- `choiceId`는 서버가 발급한다 (I-1). 클라이언트가 보낸 `text`를 신뢰하지 않는다.
- Ending 미매칭 + 마지막 챕터 `max_turns` 도달 → `is_default` 엔딩. **무한 진행을 만들지 않는다.**
- `endingIndex` / `totalEndings`는 `is_secret = false`만 센다. `reachRate`는 완주 50건 미만이면 `null` (I-20 — 배치 값 조회, 실시간 계산 금지).

## GameState

- AI `stateChanges`를 그대로 병합하지 않는다. **화이트리스트 필터 → clamp → 병합** 순서다.
- `chapter` / `turn`은 AI가 못 바꾼다 (I-9). AI가 보내와도 무시한다.
- `state_schema`에 없는 키는 무시한다. clamp 기본 ±5.
- 허용 연산자: `<numericPath>: delta`, `flags.add/remove`, `inventory.add/remove`, `location`, `timeOfDay`. **이 외 키는 무시.**
- **스냅샷과 요약은 append-only** (I-5). UPDATE 하지 않는다 — 롤백이 불가능해진다.

## 결정론

- 판정·분기·엔딩·상태 변화량에 `Random`을 쓰지 않는다 (I-15).
- 조건 평가기 연산자: `all` `any` `not` `gte` `gt` `lte` `lt` `eq` `has` `turnGte`. 미정의 키 참조는 `false` + 경고 로그.

## 트랜잭션

**Provider 호출을 트랜잭션 안에서 하지 않는다.** 짧은 TX → 외부 호출 → 짧은 TX. 25초짜리 트랜잭션은 커넥션 풀을 고갈시킨다.

## 세션

- 세션은 생성 시 `story_version_id` · provider · model에 고정된다 (I-4). 진행 중 세션은 새 버전에 영향받지 않는다.
- 작품당 active 세션 1개(partial unique index). `restart=true`는 기존 active를 `abandoned`로 전환 후 신규 생성.
- 자유입력은 `is_test_session = true`에서만 (I-18).
