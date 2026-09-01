---
name: verify
description: Run the smallest verification that covers the current changes, then preflight.
disable-model-invocation: true
argument-hint: ""
---

# /verify

현재 변경에 맞는 검증을 **최소 비용으로** 실행한다. 무조건 전부 돌리지 않는다.

## 1. 무엇이 바뀌었나

```bash
git status --short && git diff --stat
```

## 2. 관련 테스트부터

변경된 클래스에 대응하는 테스트만 먼저 돌린다.

```bash
./gradlew test --tests "*<변경한클래스>Tests"
```

## 3. 빠른 전체

```bash
./gradlew test
```

## 4. 통합 — 아래에 해당할 때만

- `config/**` · 엔티티 · Repository · `db/migration/**` · `docker/postgres/**`
- 트랜잭션 경계 · 스프링 배선 · 프로퍼티 바인딩
- `src/test/**`의 `@Tag("container")` 클래스

```bash
./gradlew integrationTest
```

해당 없으면 **건너뛰고 그 사실을 보고한다.** 순수 단위 변경에 Testcontainers를 띄우지 않는다.

## 5. preflight

```bash
./scripts/preflight.sh
```

`gitleaks`가 없으면 실패한다 — **건너뛰지 말고 그대로 보고한다.** 스캔을 돌리지 않은 것과 통과한 것은 다르다.

## 6. 실패 처리

**긴 Gradle 로그를 대화에 다시 붙여넣지 않는다.** 리포트 XML에서 필요한 것만 꺼낸다.

```bash
rg -n 'tests=|failures=|errors=' build/test-results/test/TEST-*.xml
```

출력이 길거나 원인이 불분명하면 **`test-runner` 서브에이전트**에 위임한다.

## 7. 보고

```
실행: <돌린 것 / 건너뛴 것과 이유>
결과: PASS | FAIL
원인: <FAIL 일 때만, 핵심만>
```
