# ADR-0006: 턴 생성 계약의 소유 — `play/port` 가 소유하고 `ai` 가 구현한다

## 상태

승인 (2026-08-26) — 이슈 #86, #84

**ADR-0005 의 "`ai → play` 를 허용하지 않는다" 조항을 대체한다.** 그 밖의 ADR-0005 결정(`play → catalog :: query`, `play → safety :: l2`)은 그대로 유효하다.

## 맥락

B-21(#83)이 출력 스키마 파서를 만들면서 **계약이 잘못된 쪽에 있다는 것이 드러났다.**

파서는 §5.2 의 `paragraphs[{type, text}]` 와 `speakerName` 을 온전히 읽는다. 그러나 그 결과가 모듈 경계를 넘는 순간 형태가 무너졌다.

| 지점 | 무슨 일이 |
|---|---|
| `ai :: provider` 의 `TurnResult.narrative` | 통 문자열이라 **문단 구분과 종류가 사라진다** |
| `TurnPipeline` 저장 직전 | `List.of(narrative)` 로 감싸 **1개짜리 배열**을 만든다 |

**R5.1 이 금지한 것이 정확히 후자의 형태다** — 배열이라는 껍데기만 있고 문단은 하나뿐인 본문은 와이어프레임 2a 가 렌더할 수 없다. 지금 통과하는 이유는 실제 문단이 하나뿐인 결정론 시나리오만 돌기 때문이고, 실 어댑터(B-22)가 붙는 순간 **모델이 쓴 문단 구분이 매 턴 버려진다.**

**이것은 필드 하나의 타입 문제가 아니다.** `TurnResult` 의 모양은 `ai` 가 정했는데, 그 값을 저장하고 응답하는 것은 `play` 다. **정의하는 쪽과 필요로 하는 쪽이 달랐고**, 그래서 어긋남이 생겨도 어느 쪽도 그것을 자기 문제로 보지 않았다.

## 결정

**턴 생성 계약을 `play` 가 소유하고, `ai` 가 그것을 구현한다.**

```
play
 └─ port/  (@NamedInterface "port")
      TurnGenerationPort          ← play 가 내미는 요구
      GeneratedTurn · GeneratedParagraph · GeneratedChoice · ParagraphType
      TurnRequest
      GenerationTimedOutException · OutputSchemaRejectedException
           ▲
           │ implements
ai
 └─ provider/  StoryProvider extends TurnGenerationPort
 └─ gateway/   AiGateway · 데코레이터
```

```java
@ApplicationModule(allowedDependencies = { "common", "play :: port" })   // ai
@ApplicationModule(allowedDependencies = { "common", "catalog :: query", "safety :: l2" })   // play
```

### 근거

**계약의 모양은 그것을 저장하고 응답하는 쪽에서 나온다.** `GeneratedTurn` 의 필드는 `turn` 테이블에 들어갈 것과 `TurnView` 로 나갈 것이 정한다. 소유가 `play` 에 있으면 그 요구가 곧 계약이 되고, 어긋날 자리가 없다.

**"Clean Architecture 이므로"는 근거가 아니다.** CLAUDE.md 추상화 제한이 그 이유를 명시적으로 배제한다. 채택 근거는 위의 한 줄이며, 그 근거가 실제 결함(#84)으로 관측된 뒤에 내린 결정이다.

### `ai ← 도메인 모듈 참조 X` 는 깨지지 않는다

ADR-0005 가 지키려 한 성질은 *"`ai` 가 `play` 의 도메인을 알지 못한다"* 였다. 그것은 유지된다.

- 열린 것은 **계약 패키지 하나**(`play :: port`)이며, 거기에는 **DTO 와 인터페이스뿐**이다
- `play` 의 엔티티(`Turn` · `PlaySession`) · Repository · 서비스는 **여전히 닫혀 있다**
- **I-3 의 구조적 보장이 그대로다** — `TurnRequest` 에 회원 식별정보를 담을 필드가 없고, 그 위에 B-19 의 런타임 검증이 있다

### 왜 순환이 없는가

**`play → ai` 를 한 줄도 남기지 않았다.** 남아 있던 것은 셋이었고 전부 옮겼다.

| 남아 있던 참조 | 어떻게 끊었나 |
|---|---|
| `TurnPipeline` 의 `StoryProvider` 주입 | `TurnGenerationPort` 로 교체 |
| `SessionStarter` 의 `StoryProvider` 주입 | 같음 |
| `PlayTurnService` 가 잡던 AI 예외 2종 | 예외를 `play/port` 로 옮김 |

**예외 하나만 남아도 양방향이 된다** — 그래서 `GenerationTimedOutException` 과 `OutputSchemaRejectedException` 이 데코레이터의 중첩 클래스가 아니라 포트 패키지의 타입이다.

`ModuleStructureTests.ADR0006_turn_generation_is_owned_by_play_and_implemented_by_ai()` 가 두 선언을 문자열로 못박는다. `ApplicationModules.verify()` 는 **실제 참조**를 보고, 이 테스트는 **선언이 조용히 넓어지는 것**을 본다.

### 계약 패키지의 경계도 빌드가 지킨다 (#95)

위 문단의 *"열린 것은 DTO 와 인터페이스뿐인 계약 패키지 하나"* 는 **결정 당시의 사실이었을 뿐 보장이 아니었다.** Modulith 의 검사는 **모듈 단위**라 `play/port` 에 무엇을 더 넣든 `ai` 에 자동으로 보인다. 두 가지가 조용히 무너질 수 있었다.

1. **계약이 도메인을 물고 오는 것** — `play/port` 의 타입이 시그니처에 `play.domain` 을 노출하면 `ai` 가 엔티티에 **컴파일 타임으로 닿는다.** `ai` 가 계약만 보고 있어도 소용없다
2. **계약 패키지가 계약이 아닌 것을 담는 것** — 서비스가 하나 들어오면 그때부터 그것은 계약이 아니라 API 다

`PortBoundaryTests` 가 세 규칙을 강제한다 — 계약은 `play` 내부를 참조하지 않는다 · `ai` 는 `play.port` 만 본다 · 계약 패키지에는 record · interface · enum · 예외만 둔다. **규칙이 실제로 무는지 위반 코드를 넣어 확인했다.** ArchUnit 은 `spring-modulith-starter-test` 를 통해 이미 있으므로 새 의존성이 아니다.

## 고려한 대안

ADR-0005 가 이미 비교한 A · B 에 이번 C 를 더한다.

| | **A** `play → ai :: provider` | **B** `common/spi` 포트 | **C 채택** `play/port` 포트 |
|---|---|---|---|
| 계약 소유 | `ai` | `common` | **`play`** |
| 계약의 모양을 정하는 쪽 | 제공자 | 제3자 | **사용처** |
| 추가 간접층 | 없음 | 인터페이스 + 어댑터 | **없음** — `StoryProvider` 가 포트를 확장한다 |
| 순환 | 없음 | 없음 | 없음 (위 표대로 끊음) |

**A 를 유지하지 않은 이유**는 위의 "맥락"이다. A 는 성립하는 구조지만, **계약이 어긋났을 때 그것을 고칠 책임이 어느 쪽에도 없었다.**

**B 를 기각한 이유는 ADR-0005 와 같다.** `common/spi` 는 *"자기가 필요한 데이터의 소유자가 아니면서 그 데이터를 참조할 수 없는 모듈"* 을 위한 장치다 (ADR-0002, ADR-0003). 여기는 그 조건이 없고, `common` 에 두면 **계약이 다시 제3자의 것**이 되어 이번에 고치려는 문제가 그대로 남는다.

**`StoryProvider extends TurnGenerationPort` 로 둔 것이 C 의 비용을 없앴다.** 변환 어댑터가 필요 없고, 어느 벤더 어댑터든 그대로 포트 구현이 된다. B 가 지불해야 했던 "인터페이스 2 + 어댑터 2"가 여기서는 0 이다.

## 결과 (되돌리기 비용 포함)

| | |
|---|---|
| 문서 수정 | §5.4 의 `play` · `ai` 줄, ADR-0005 의 금지 조항 |
| 코드 수정 | `package-info` 2개, 주입 타입 3곳, 예외 위치 2개, 파일 이동 |
| 마이그레이션 | **판정 결과 불필요** — `turn.paragraphs` 는 JSONB 이고 저장 형식만 바뀐다. 운영 데이터가 없다 |
| 기존 테스트 수정 | `ModuleStructureTests` 단언 2줄 + 픽스처 |
| **종합** | **중간** |

### 되돌리는 방법

A 로 돌아가려면 `play/port` 의 타입을 `ai :: provider` 로 옮기고 두 `package-info` 를 되돌린다. **호출부의 형태는 바뀌지 않는다** — `play` 는 지금도 인터페이스로 받는다.

### 남는 부담

**`ai` 의 허용 의존이 둘이 됐다.** `play :: port` 외에 다른 도메인 모듈이 필요해지면 그 자체를 재검토 신호로 본다 — `ai` 가 여러 도메인 모듈을 알기 시작하면 *"도메인을 모르는 모듈"* 이라는 성질이 이름만 남는다.

**포트에 메서드를 늘리는 것도 같은 신호다.** 지금은 `providerId()` 와 `generateTurn()` 둘뿐이며, 그것이 `play` 가 실제로 쓰는 전부다. 요약(B-34)이 붙을 때 그것을 포트에 얹을지 별도 포트로 둘지는 그때 정한다 — **미리 얹으면 구현하지 않는 메서드가 생긴다** (§0.2).

## 후속

- [x] §5.4 의 `play` · `ai` 줄 수정 — 이 결정과 함께
- [x] `play/package-info.java` · `ai/package-info.java` 수정
- [x] `ModuleStructureTests` 단언 수정
- [x] `play → ai` 잔여 참조 제거
- [x] 계약 패키지의 경계를 테스트로 강제한다 (#95)
- [ ] 요약(B-34) 착수 시 포트 확장 여부를 정한다
- [ ] `ai` 에 세 번째 허용 의존이 필요해지면 이 ADR 을 재검토한다
