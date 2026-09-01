---
paths:
  - "src/main/java/com/neowadaeum/config/**/*.java"
  - "src/test/java/com/neowadaeum/config/**/*.java"
  - "src/main/resources/db/migration/**/*.sql"
  - "docker/postgres/**"
  - "src/main/resources/application.yml.template"
---

# persistence — 4스토어 · Flyway · 설정

근거: `docs/engineering-guide.md` §5.3, ADR-0004. 워크플로는 `/migration`.

## 4스토어

| 스토어 | 스키마 | DataSource 빈 | Flyway 경로 |
|---|---|---|---|
| Identity | `identity` | `identityDataSource` | `db/migration/identity` |
| Catalog + Authoring | `catalog` | `catalogDataSource` | `db/migration/catalog` |
| Session | `play` | `playDataSource` | `db/migration/play` |
| Prompt Log | `promptlog` | `promptLogDataSource` | `db/migration/promptlog` |

- **스키마 간 FK를 만들지 않는다.** 참조는 애플리케이션 레벨에서만.
- **스키마 간 JOIN 쿼리를 금지한다.** 필요하면 파사드 호출로 조합한다.
- **비-Identity 스키마는 `user.id`를 저장하지 않는다.** `player_ref`(UUID)만.
- 각 계정은 자기 스키마에만 권한을 갖는다. 위반은 로컬에서 `42501`로 즉시 터진다 — 이것이 1차 방어선이다.
- `flyway_schema_history`도 스키마별로 분리한다. 한 곳에 모으면 이력 테이블이 크로스 스키마 참조점이 된다.
- URL에 `?currentSchema=<스키마>`가 없으면 부팅이 실패한다. 빠지면 모든 테이블이 조용히 다른 스키마에 생긴다.
- **인스턴스 분리로 승격해도 애플리케이션 코드는 변경이 없어야 한다.** 위 두 금지의 이유가 이것이다.

## EntityManagerFactory

**`@Primary` DataSource를 만들지 않는다.** 후보가 하나가 되는 순간 `@ConditionalOnSingleCandidate` 자동설정이 EMF를 1벌 만들고, 여러 스키마의 엔티티가 거기 묶여 **JPQL 한 줄로 크로스 스키마 조인이 뚫린다.** FK 검증은 이 경로를 잡지 못한다. 스토어별 EMF/TransactionManager는 B-05-1(#20)이며 **B-07의 선행 조건**이다.

## 마이그레이션

- 파일명 `V<번호>__<snake_case>.sql`. 번호는 **스토어별로 독립** — 그 스토어의 마지막 번호 + 1이며 이슈 번호가 아니다.
- 충돌 시 리베이스로 번호를 다시 매긴다. **이미 머지된 마이그레이션은 수정하지 않는다** — 체크섬이 어긋난다.
- 실패 증상: `outOfOrder=false` + `validateOnMigrate=true`라 같은 스토어에 `V2__`가 둘이면 **두 번째 머지 이후 기동이 막힌다.** 애플리케이션 버그처럼 보이지만 원인은 번호다.
- 시드 데이터는 `docker/postgres/init`이 아니라 **Flyway**로 관리한다. init 스크립트는 볼륨이 비어 있을 때 한 번만 실행된다.
- 마이그레이션에 시크릿·실데이터를 넣지 않는다.

## 설정 파일

- `application*.yml`은 추적하지 않는다. `application.yml.template`만 커밋하고 복사해 쓴다.
- **테스트용 yml을 만들지 않는다.** Testcontainers + `@DynamicPropertySource` / `DynamicPropertyRegistrar`로 런타임 주입한다.
- **`${VAR:실제값}` 기본값 패턴 금지.** 값이 없으면 부팅을 실패시킨다.
- `spring.autoconfigure.exclude`에 이름을 추가하면 **동작으로 검증한다.** 존재하지 않는 FQCN을 써도 Spring은 오류 없이 무시한다 — 적혀 있다는 것 자체는 아무것도 보장하지 않는다.
