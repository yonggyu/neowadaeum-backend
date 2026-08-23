---
name: task
description: Execute one neowadaeum task (B-xx or S-x) end to end — issue, branch, implementation, verification, self-review, PR. Use whenever the user names a task number and asks to start it ("S-3 착수", "B-26 해줘", "/task B-26 41"), with or without an issue number.
argument-hint: "[B-xx|S-x] [issue-number optional]"
---

# /task $ARGUMENTS

`$ARGUMENTS`의 첫 토큰이 작업 번호(`B-26`, `S-3` 등), 둘째가 있으면 이슈 번호다.
슬래시 명령이 아니라 사용자 지시문("S-3 착수")에서 진입했다면 **그 지시문의 작업 번호를 그대로 쓴다.**
작업 번호를 특정할 수 없으면 **묻고 멈춘다.** 추측해서 시작하지 않는다.

**이슈 번호는 묻지 않는다 — 1단계에서 찾거나 만든다.** 그 외 `CLAUDE.md`의 안전 규칙과 순서는 그대로다.

**전체를 읽지 말고 `rg`로 위치를 찾은 뒤 해당 범위만 읽는다.** 이미 읽은 파일을 이유 없이 다시 읽지 않는다.

## 1. 상태 확인 · 이슈 확보

```bash
git branch --show-current && git status --short
gh issue list --state open --limit 50
```

**이슈 없이 코드를 쓰지 않는다.** 다음 순서로 번호를 확보한다.

1. 인자로 이슈 번호를 받았으면 그것을 쓴다.
2. 아니면 위 목록에서 **이 작업 번호의 이슈를 찾는다.** 제목·본문의 `B-xx` / `S-x` 로 대조한다.
3. 그래도 없으면 **직접 만든다.** 지어내지 말고 **2~5단계를 먼저 수행해 정의를 읽은 뒤** 돌아와 채운다.

```bash
gh issue create \
  --title "[S-3] FixedStoryProvider — 시나리오 파일 기반 결정론 Provider" \
  --label task --label P0 --label "area:ai" \
  --body-file <본문파일>
```

- 제목은 `[<작업번호>] <docs/tasks.md 의 작업명>`.
- 본문은 `.github/ISSUE_TEMPLATE/task.yml`의 필수 항목에 대응시킨다 —
  **작업 번호 · 우선순위 · 영역 · 목표 · 산출물 · 완료 조건(DoD) · 의존 작업 · 관련 요구사항 ID.**
- 내용은 `docs/tasks.md`(+ 슬라이스 절)의 정의를 옮긴 것이다. **없는 DoD 를 창작하지 않는다.**
- 라벨은 `docs/git-workflow.md` §8.6 의 값만 쓴다.
- **S-11 — 이 레포는 공개다.** 세이프티 우회 방법·블록리스트 실제 항목·운영 도메인·미수정 취약점 재현 절차를 이슈에 적지 않는다.
- 만든 뒤 번호를 확인하고, **그 번호를 사용자에게 알린다.**

이슈 번호가 정해지면 브랜치를 판다. `backend` / `dev` / `main` 위에서는 **반드시** 먼저 분기한다.

```bash
git switch backend && git pull
git switch -c feat/#<이슈>-<영문-소문자-슬러그>
```

타입은 `feat` `fix` `refactor` `chore` `docs` `test` `perf` 중 작업 성격에 맞는 것 (§8.3).

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

## 13. PR

셀프 리뷰까지 끝났으면 푸시하고 PR 을 연다. **base 는 언제나 `backend`** — `dev` / `main` 로 직접 열지 않는다.

```bash
git push -u origin HEAD
gh pr create --base backend --draft \
  --title "feat(play): S-3 <요약>" \
  --body-file <본문파일>
```

- 본문은 `.github/pull_request_template.md` 의 **전 섹션**을 채운다. 해당 없으면 "해당 없음"에 체크하고 비워 두지 않는다.
- 관련 이슈 칸에 `Closes #<이슈>` 와 작업 번호.
- **Draft 로 연다.** 아래를 전부 만족할 때만 `gh pr ready` 로 전환한다 (§8.1).
  - [ ] 이슈 DoD 전 항목 충족
  - [ ] CI 초록 (빌드 · 테스트 · 시크릿 스캔)
  - [ ] PR 템플릿 전 섹션 작성
  - [ ] 최신 `backend` 반영, 충돌 없음
  - [ ] diff 를 처음부터 끝까지 한 번 읽었다 (12단계)
- `ai` · `safety` · `play/engine` 스코프는 **절반쯤 왔을 때 Draft 를 먼저 열어 둔다.**
- diff 400줄을 넘으면 쪼갤 수 있는지 먼저 검토하고, 불가피하면 사유를 본문에 적는다.
- `[결정 필요]` 항목에 손댔다면 기본 채택안을 따랐다는 사실을 본문에 남긴다.
- **머지는 하지 않는다.** Ready 전환 이후는 사람의 판단이다.

## 14. 보고 — 다섯 줄

```
이슈:
변경:
테스트:
preflight:
PR:
남은 문제:
```

길게 설명하지 않는다. 없으면 "없음".
