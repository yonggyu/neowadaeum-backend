---
name: review
description: Review the current git diff against project invariants, security rules, and traceability.
disable-model-invocation: true
argument-hint: ""
---

# /review

현재 `git diff`를 검토한다. **파일을 수정하지 않는다** — finding만 낸다.

## 범위 확인

```bash
git diff --stat
git diff
```

머지된 base 대비 전체를 보려면 `git diff backend...HEAD`.

## 우선순위 — 이 순서로 본다

1. **correctness** — 이 코드가 의도대로 도는가. 경계·null·순서·예외 경로.
2. **불변 규칙 I-1~I-20** — 특히 자주 걸리는 것:
   - AI 응답의 `chapterAdvanceSuggested`로 챕터를 넘기지 않았는가 (I-10)
   - `stateChanges`를 화이트리스트·clamp 없이 병합하지 않았는가
   - 스냅샷·요약을 UPDATE 하지 않았는가 (I-5)
   - 판정에 `Random`을 쓰지 않았는가 (I-15)
   - L2를 생성 모델에 맡기지 않았는가 (I-12, I-13)
3. **security** — 시크릿 · `${VAR:기본값}` · 신규 `*.yml` · 로그에 원문/토큰/이메일 · 에러 응답에 스택트레이스 · **S-11(공개 레포에 우회 방법·블록리스트 항목)**.
4. **요구사항 추적** — R·P·I ID가 주석 또는 테스트 이름에 남아 있는가.
5. **tests** — 변경에 대응하는 테스트가 있는가. 태그가 맞는가. 실패 단언이 "있어야 할 것"만 보지 않는가.
6. **아키텍처 경계** — 계층 역행 · 모듈 간 직접 참조 · **스키마 간 JOIN/FK** · 비-Identity 스토어의 `user.id` · **트랜잭션 안 외부 HTTP**.
7. **불필요한 복잡도** — 쓰이지 않는 추상화, 범위 밖 변경(scope creep), 디버그 잔재.

**style nitpick은 마지막이다.** 그것 때문에 위 항목이 묻히지 않게 한다.

## 출력

심각도순으로 쓴다.

```
BLOCKING
- <파일>:<줄> — <무엇이 왜 문제인가> — <어떻게 고치는가>

SHOULD FIX
- ...

CONSIDER
- ...
```

**문제가 없으면 억지로 만들지 않는다.**

```
No blocking findings.
```

라고 명확히 쓰고, 확인한 범위를 한 줄 덧붙인다.

## 위임

diff가 크거나 독립적인 시각이 필요하면 **`reviewer` 서브에이전트**를 띄우고 finding만 받는다.
