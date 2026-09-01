---
paths:
  - "src/test/**"
---

# testing

## 구분과 태그

| 종류 | 대상 | 도구 |
|---|---|---|
| 단위 | 엔진 · 정규화기 · 파서 · 토큰 계산 | JUnit 5 |
| 통합 | Repository · 트랜잭션 · 마이그레이션 | Testcontainers |
| 계약 | Provider 어댑터 | WireMock 고정 응답 |
| E2E | 턴 파이프라인 전체 | `FixedStoryProvider` |

- `@Tag("container")` → `integrationTest` (Docker 필요). `@Tag("nightly")` → `nightlyTest` (ADR-0001).
- 태그가 없으면 `test`에서 돈다. **컨테이너가 필요한 테스트에 태그를 빠뜨리면 빠른 루프가 Docker를 요구하게 된다.**
- 컨테이너 통합 테스트는 `ContainerTestBase`를 상속한다. **클래스마다 `@TestPropertySource`나 `@MockitoBean`을 더 붙이지 않는다** — 캐시 키가 갈라져 컨텍스트가 그 수만큼 새로 뜬다.

## 금지

- **테스트에서 실제 AI를 호출하지 않는다.** E2E는 `FixedStoryProvider`가 `src/test/resources/scenarios/*.json`을 읽어 정해진 순서로 응답한다. AI 비결정성을 테스트에 들이지 않는다.
- **`application-test.yml`을 만들지 않는다.** `@DynamicPropertySource` / `DynamicPropertyRegistrar`로 런타임 주입한다.
- JUnit 4를 쓰지 않는다 (`@RunWith`, `junit:junit`). Boot 4에서 제거됐다.
- 실패 메시지·예외에 접속 URL이나 자격 증명이 새지 않는지 확인한다. **"있어야 할 것"만 단언하면 값이 새어도 통과한다** — `doesNotContain`을 함께 건다 (S-11, S-3).

## 이름

요구사항 ID를 이름에 남긴다.

```java
@Test void R4_2_affinity_delta_over_limit_is_clamped() { ... }
```

절 번호 기반은 `S<절>_<항>_` (예: `S5_3_no_cross_schema_foreign_keys`). 표기 정리는 이슈 #23.

## 필수 테스트

머지 불가 조건인 **필수 테스트 14종**은 `docs/engineering-guide.md`의 §10.1에 있다. 실행 시점 분류(PR 필수 / nightly / 미도래)는 `docs/adr/0001-mvp-test-execution-policy.md`.

**작업에 해당하는 항목만 조회한다** — `/task`가 `docs/tasks.md`의 DoD와 함께 가져온다. 전체 목록을 매번 읽지 않는다.

## 골든 파일

프롬프트 조립 결과는 골든 파일로 고정한다. 프롬프트가 바뀌면 **diff가 리뷰에 노출되어야** 한다.
