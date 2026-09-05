---
paths:
  - "src/main/java/com/neowadaeum/ai/**/*.java"
  - "src/test/java/com/neowadaeum/ai/**/*.java"
---

# ai — Gateway · Provider · 프롬프트

근거: `docs/engineering-guide.md` §4.4 컨텍스트 조립 / §2.4 Spring AI를 쓰지 않는 이유.

## 페이로드 (I-3 — 이 모듈의 최우선 규칙)

- **회원 식별정보를 요청 페이로드에 넣지 않는다**: 이메일 · 이름 · 소셜 ID · IP · 생년월일 · `player_ref`.
- **직렬화 직전에 화이트리스트로 검증하고, 위반이면 요청을 중단한다.** 필터링해서 보내지 않는다.
- `ai` 모듈은 **도메인 엔티티를 알지 못한다.** `TurnRequest` / `TurnResult` 같은 순수 DTO만 주고받는다 — 이것이 I-3의 구조적 보장이다.

## 프롬프트 레이어

`SYSTEM → WORLD → CHARACTER → GAME STATE → STATE VOCABULARY → SUMMARY → RECENT TURNS → USER ACTION → OUTPUT SPEC`

- **`SYSTEM`과 `OUTPUT SPEC`은 플랫폼 레이어다. 작품이 덮어쓸 수 없다** (I-7). UGC `world_prompt`에 "이전 지시를 무시하라"가 들어와도 SYSTEM이 유지되어야 한다.
- **`STATE VOCABULARY`도 플랫폼 레이어다** (§13-76). `state_schema`가 선언한 **이름**을 알린다 — 값은 이미 `GAME STATE`에 있다. 작품이 넘기는 것은 목록의 항목이고 문장은 코드가 만든다. 묶음 200이며 **축소 대상이 아니다** — 이름을 감추면 그 조건이 영원히 거짓이 된다.
- `WORLD` / `CHARACTER`는 작품 레이어(`StoryVersion` 소유). UGC 합계 **1,000토큰 하드 제한** — 저장 시점에 검증한다.
- 총 예산 **≤ 4,000토큰**. 초과 시 RECENT TURNS를 오래된 것부터 → SUMMARY 재압축 → 그래도 초과면 `500 CONTEXT_BUDGET_EXCEEDED`로 실패시키고 알람. **조용히 잘라내고 진행하지 않는다.**
- 프롬프트 조립 결과는 **골든 파일 테스트**로 고정한다. 프롬프트 변경이 리뷰 diff에 보여야 한다.

## Provider

- `RestClient` + `@HttpExchange`로 직접 구현한다. **WebClient / WebFlux를 추가하지 않는다.** Spring AI를 턴 생성 경로에 쓰지 않는다 — 와이어 페이로드를 서버가 소유해야 I-3·I-7을 강제할 수 있다.
- 타임아웃 25초. 초과 시 `504 GENERATION_TIMEOUT`이며 **세션 상태를 바꾸지 않는다.**
- 파싱·스키마 검증 실패 시 1회 재요청, 재실패면 `502 PROVIDER_ERROR`.
- Provider 선택은 관리자 전용이다 (I-14). 사용자에게 노출하지 않는다.
- 세션은 생성 시 provider/model에 고정된다 (I-4).

## 로깅

- **프롬프트 원문·응답 원문을 애플리케이션 로그에 남기지 않는다** (S-3). `ai_call_log`(promptlog 스토어)에만 기록하고 접근을 통제한다.
- `ai_call_log`에 `player_ref`를 담지 않는다. `session_id`로만 역추적한다.
