---
name: task
description: Execute one neowadaeum B-xx task with minimal context and required verification.
disable-model-invocation: true
argument-hint: "[B-xx] [issue-number optional]"
---

# /task $ARGUMENTS

`$ARGUMENTS`의 첫 토큰이 작업 번호(`B-26`, `S-3` 등), 둘째가 있으면 이슈 번호다.
작업 번호가 없으면 **묻고 멈춘다.** 추측해서 시작하지 않는다.

**전체를 읽지 말고 `rg`로 위치를 찾은 뒤 해당 범위만 읽는다.** 이미 읽은 파일을 이유 없이 다시 읽지 않는다.

## 1. 상태 확인

```bash
git branch --show-current && git status --short
```

`backend` / `dev` / `main` 위라면 **작업 브랜치를 먼저 만든다** — `<타입>/#<이슈>-<슬러그>`.
이슈 번호를 받지 못했다면 사용자에게 확인한다. **이슈 없이 코드를 쓰지 않는다.**

## 2. 작업 정의 — 해당 행만

```bash
rg -n "B-26" docs/tasks.md
```

찾은 위치에서 **그 작업의 행, 직접 의존 작업, DoD, R/P/I ID**만 읽는다. 64개 표 전체를 읽지 않는다.
MVP 수직 슬라이스(ADR-0004)에 해당하면 슬라이스 절의 순서와 복귀 조건도 본다.

## 3. 정정 사항 — 관련 항목만

작업 정의가 `§13-N`을 가리킬 때만:

```bash
rg -n "13-3" docs/corrections.md
```

해당 절만 읽는다. **전체를 읽지 않는다.** `[결정 필요]` 항목에 손댄다면 기본 채택안을 따르고 PR 본문에 명시한다.

## 4. ADR — 실제로 관련된 것만

```bash
rg -ln "<키워드>" docs/adr
```

## 5. 코드 규칙 — 건드릴 경로의 rule 파일

건드릴 모듈에 해당하는 `.claude/rules/*.md`를 읽는다 (`CLAUDE.md`의 표 참조).
`play/**`면 `play.md`, 마이그레이션이면 `persistence.md`.

## 6. 기존 구현 탐색

**구현 전에 반드시 검색한다.** `src/` 전체를 읽지 않는다.

```bash
rg -n "GameState|stateChanges" src/main/java --type java -l
```

탐색 범위가 넓거나 어떤 파일이 관련되는지 불명확하면 **`explorer` 서브에이전트**에 위임한다.
2~3개 파일만 읽으면 되는 일에는 띄우지 않는다.

## 7. 계획 — 짧게

```
변경 파일:
위험 요소:
실행할 테스트:
```

장문의 계획 문서를 만들지 않는다.

## 8. 구현

- **B-xx 범위를 벗어나지 않는다.** 다른 문제를 발견하면 그 자리에서 고치지 않고 **이슈 후보로만 보고**한다.
- 미구현이면 스텁으로 통과시키지 말고 `UnsupportedOperationException`을 던진다.
- 요구사항 ID를 주석 또는 테스트 이름에 남긴다.
- 컴파일이 통과하는 시점마다 커밋한다. 컴파일이 깨진 상태로 커밋하지 않는다.

## 9. 최소 테스트 → 빠른 전체

```bash
./gradlew test --tests "*GameStateEngineTests"   # 가장 작은 관련 테스트
./gradlew test                                    # 통과하면 빠른 전체
```

## 10. 통합 테스트 — 필요할 때만

DB · Redis · 트랜잭션 · 마이그레이션 · 스프링 배선을 건드렸을 때만:

```bash
./gradlew integrationTest
```

**순수 단위 변경이나 DTO 변경에서 매번 Testcontainers를 띄우지 않는다.**

실패 로그가 길면 **`test-runner` 서브에이전트**에 위임하고 root cause만 받는다.

## 11. preflight

```bash
./scripts/preflight.sh
```

Windows 툴체인이면 `gradlew.bat test integrationTest` + `gitleaks protect --staged --redact`.

## 12. 셀프 리뷰

```bash
git diff --stat        # 먼저 규모를 본다
git diff <파일>        # 그다음 파일별로
```

확인 항목: **B-xx 범위 초과 / I-1~I-20 위반 / R·P ID 누락 / 시크릿 / 크로스 스키마 JOIN·FK / 트랜잭션 안 외부 HTTP / 누락된 테스트 / 디버그 잔재.**

diff가 크거나 독립 검토가 필요하면 **`reviewer` 서브에이전트**를 쓴다.

## 13. 보고 — 네 줄

```
변경:
테스트:
preflight:
남은 문제:
```

길게 설명하지 않는다. 없으면 "없음".
