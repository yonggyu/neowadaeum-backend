---
name: migration
description: Checklist for Flyway migration, entity, repository, and DataSource work across the four stores.
disable-model-invocation: true
argument-hint: "[store] optional"
---

# /migration $ARGUMENTS

Flyway · Entity · Repository · DataSource 작업의 체크리스트다. 상세 규칙은 `.claude/rules/persistence.md`.

## 1. 대상 스토어 확정

| 스토어 | 스키마 | 담는 것 |
|---|---|---|
| Identity | `identity` | 회원 · 인증 · 동의 · 생년월일 |
| Catalog | `catalog` | 작품 · 버전 · 챕터 · 엔딩 + Authoring |
| Session | `play` | 세션 · 턴 · 스냅샷 · 요약 |
| Prompt Log | `promptlog` | 요청/응답 원문 · usage · 감사 로그 |

**어느 스토어인지 정하지 않고 테이블을 만들지 않는다.** 나중에 옮기려면 마이그레이션 + DataSource 재배정 + 데이터 이관이 함께 온다.

## 2. 마이그레이션 파일

```bash
ls src/main/resources/db/migration/<store>/     # 마지막 번호 확인
```

- 파일명 `V<마지막+1>__<snake_case>.sql`. **번호는 스토어별로 독립**이며 이슈 번호가 아니다.
- 다른 브랜치가 같은 번호를 쓰고 있지 않은지 확인한다. 충돌하면 리베이스로 다시 매긴다.
- **이미 머지된 마이그레이션을 수정하지 않는다.** 체크섬이 어긋나 기동이 막힌다.

## 3. 경계 확인 — 넷 다 본다

- [ ] **스키마 간 FK가 없다.** 다른 스토어의 id를 참조한다면 FK 없이 컬럼만 둔다.
- [ ] **스키마 간 JOIN 쿼리가 없다.** 필요하면 파사드로 조합한다.
- [ ] **비-Identity 스토어에 `user.id`를 저장하지 않는다.** `player_ref`(UUID)만.
- [ ] 계정 권한이 자기 스키마로 한정된다. 위반은 로컬에서 `42501`로 터진다.

## 4. 엔티티 · Repository

- **`@Primary` DataSource를 만들지 않는다.** EMF가 1벌이 되면 JPQL로 크로스 스키마 조인이 뚫린다.
- 엔티티를 추가한다면 **스토어별 EMF 등록(B-05-1, #20)이 선행**인지 확인한다.
- 동적 쿼리 라이브러리를 임의로 추가하지 않는다 — ADR이 먼저다.

## 5. 롤백 / 전진 전략

- 무엇이 만들어지는지, 되돌리려면 무엇을 해야 하는지 **PR 본문에 적는다.**
- 로컬 초기화는 `docker compose down -v`. init 스크립트는 볼륨이 비어 있을 때만 돈다.
- 파괴적 변경(컬럼 삭제 · 타입 변경)이면 전진 전략(새 컬럼 → 이중 쓰기 → 이관 → 제거)을 먼저 적는다.

## 6. 검증

```bash
./gradlew integrationTest
```

Testcontainers가 로컬과 **같은 이미지 · 같은 init 스크립트**로 스키마 4개 · 계정 4개를 만든다.
마이그레이션이 실제로 적용되는지, 크로스 스키마 FK가 0건인지, 계정 권한이 좁혀져 있는지를 본다.

## 7. 금지

- 마이그레이션에 **시크릿·실데이터**를 넣지 않는다.
- 시드 데이터는 `docker/postgres/init`이 아니라 **Flyway**로 관리한다.
- 테스트용 yml을 만들지 않는다.
