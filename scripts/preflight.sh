#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 푸시 전 로컬 게이트 (ADR-0004, §8.9)
#
# CI 가 잡을 것을 로컬에서 먼저 잡는다. 푸시 -> 대기 -> 실패 -> 수정 왕복을 없애는 것이 목적이다.
# 여기서 통과하면 CI 3잡이 통과할 가능성이 높다 — 같은 것을 돌린다.
#
#   ./scripts/preflight.sh
#
# ※ 이것은 게이트의 **대체가 아니라 예고**다. 머지 차단의 근거는 여전히 CI 다 (§8.9).
#   --no-verify 처럼 우회할 수 있는 것을 신뢰 근거로 삼지 않는다.
#
# ※ Windows 툴체인을 쓴다면(§29 — 툴체인과 경로를 같은 쪽에) 같은 것을 이렇게 돌린다:
#     gradlew.bat test integrationTest
#     gitleaks protect --staged --redact --no-banner
# ─────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")/.."

step() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }
fail() { printf '\n\033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

step "테스트 (test + integrationTest)"
# integrationTest 는 Docker 데몬을 요구한다 (§10, ADR-0001).
./gradlew test integrationTest || fail "테스트 실패. 푸시하지 않는다."

step "시크릿 스캔 (스테이징된 변경)"
if ! command -v gitleaks >/dev/null 2>&1; then
  # 조용히 건너뛰지 않는다. 스캔을 돌리지 않은 것과 통과한 것은 다르다 (S-1).
  fail "gitleaks 가 설치되어 있지 않다.
  이 단계를 건너뛰면 이 스크립트는 시크릿에 대해 아무것도 보장하지 않는다.
  설치: https://github.com/gitleaks/gitleaks/releases
  (CI 는 전체 이력을 스캔한다. 로컬은 스테이징된 변경만 본다.)"
fi
# --redact 를 생략하지 않는다. 탐지된 값이 터미널과 스크롤백에 남는다 (S-3, S-11).
gitleaks protect --staged --redact --no-banner || fail "시크릿이 탐지됐다.
  커밋하지 않는다. 이미 푸시된 값이라면 삭제가 아니라 로테이션이 먼저다 (§7.1-4)."

step "pre-commit 훅 설치 확인 (S-1)"
# S-1 은 훅과 CI 를 **둘 다** 요구한다. 훅은 .git/hooks 에 있어 클론으로 전파되지 않으므로
# 레포의 .githooks 를 가리키게 해야 한다 (#26). 막지 않고 알리기만 한다 — 이 스크립트의
# 목적은 예고이지 게이트가 아니다 (§8.9).
if [ "$(git config --get core.hooksPath || true)" != ".githooks" ]; then
  printf '\033[33m  ! pre-commit 훅이 설치되어 있지 않다. 설치: git config core.hooksPath .githooks\033[0m\n'
fi

printf '\n\033[32m✓ 통과. 푸시해도 된다.\033[0m\n'
printf '  남은 것: PR 본문 작성 · diff 전수 확인(§8.9) · CI 3잡\n'
