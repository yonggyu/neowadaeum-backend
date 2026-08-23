# CLAUDE.md — 너와다음 Backend

> 이 파일은 **백엔드 레포지토리 루트**에 위치한다. Claude Code는 모든 작업 전에 이 문서를 읽고, 여기 명시된 규칙을 프로젝트 헌법으로 취급한다.
> 프론트엔드는 **별도 레포지토리**다. 이 문서는 프론트엔드 구현을 다루지 않는다. **AI 파이프라인은 이 레포에 포함된다.**

| | |
|---|---|
| 문서 버전 | v1.1 |
| 작성일 | 2026-08-22 (v1.1 분할: 2026-08-23) |
| 상위 문서 | `너와다음.md` v2.1 (기획서), `backend-requirements.md` v0.3 (요구사항) |
| 레포 | `neowadaeum-backend` (API 서버 + AI Gateway + Safety + Admin) |
| 관련 레포 | `neowadaeum-frontend` (별도) |

---

## 문서 구성 — §12 · §13 은 별도 파일이다 (v1.1)

| 파일 | 내용 | 로드 |
|---|---|---|
| **`CLAUDE.md`** (이 파일) | §0~§11, §14, §15 | **항상** |
| **`docs/tasks.md`** | **§12** 작업 목록 + MVP 수직 슬라이스 순서 | 해당 작업만 |
| **`docs/corrections.md`** | **§13** 상위 문서 검증 결과 · 정정 사항 | 해당 항목만 |

**사용 규칙 — 두 파일은 전문을 로드하지 않는다. 해당 항목만 인용해 전달한다.**

`B-23` 을 수행한다면 `docs/tasks.md` 에서 그 행 하나와 의존 작업만 가져온다. 64개 표 전체가 아니다.
`§13-1` 이 걸리면 `docs/corrections.md` 에서 그 절만 가져온다.

분할은 **이동만** 했다(v1.1). 두 파일의 내용은 §12 · §13 원문 그대로이며,
`§12` · `§13` 이라는 참조 표기도 그대로 쓴다 — 가리키는 곳만 바뀌었다.

> **왜 나눴나.** 합쳐서 104KB 였고 매 세션 30~40k 토큰이 로드됐다. §12 는 지금 하는 작업 한 줄만,
> §13 은 걸리는 항목 하나만 필요하다. 나머지는 매번 값을 치르면서 쓰이지 않았다.

---

## 0. Claude Code 작업 규약 (먼저 읽을 것)

### 0.1 작업 시작 전 체크리스트

1. **이슈 번호를 확인했는가?** 이슈 없이 코드를 쓰지 않는다. 이슈·브랜치·커밋·PR의 **시점**은 §8.1에 정의되어 있다.
2. **작업 번호(`B-xx`)를 확인했는가?** §12의 작업 정의를 그대로 따른다. 정의에 없는 범위를 임의로 넓히지 않는다.
3. **§6 불변 규칙(Invariants)을 위반하지 않는가?** 위반이 불가피하면 코드를 쓰지 말고 먼저 보고한다.
4. **§7 보안 규칙에 걸리는 파일을 만들거나 수정하는가?** 특히 `*.yml` 신규 생성은 §7.2를 먼저 확인한다.
5. **§13에 "미해결"로 표기된 항목에 손대는가?** 임의로 결정하지 말고, §13의 **기본 채택안**을 따르되 PR 본문에 그 사실을 명시한다.

### 0.2 하지 말아야 할 것

- 상위 문서(`너와다음.md`, `backend-requirements.md`)의 결정을 코드에서 조용히 뒤집지 않는다. 이견이 있으면 PR 본문 또는 이슈 코멘트로 제기한다.
- 요구사항 ID(`R4.2`, `P7` 등)가 붙은 규칙을 구현할 때는 **해당 ID를 코드 주석 또는 테스트 이름에 남긴다.** 추적 가능성이 이 프로젝트의 핵심이다.
- 테스트 없이 세이프티·과금·상태 변경 로직을 머지하지 않는다.
- 스텁/목업으로 "일단 통과"시키고 TODO만 남기는 것을 금지한다. 미구현이면 `UnsupportedOperationException`을 던지고 이슈를 만든다.

### 0.3 코드 주석 규약

```java
// [R4.2] 수치 필드는 min/max/maxDeltaPerTurn으로 clamp한다. AI 값을 그대로 신뢰하지 않는다.
```

테스트 이름:

```java
@Test void R4_2_affinity_delta_over_limit_is_clamped() { ... }
```

---

## 1. 프로젝트 개요

**너와다음**은 사용자가 선택지로 스토리를 이끌어가는 AI 인터랙티브 스토리 플랫폼이다. 한국 서비스.

핵심 성질 4가지 — 아키텍처의 모든 결정이 여기서 파생된다.

| # | 성질 | 귀결 |
|---|---|---|
| 1 | **본문이 매 턴 AI로 생성된다** | 지연(15~25초)·비용·비결정성이 상수다. 캐싱 불가, 동기 응답 예산 28초 |
| 2 | **플레이 중 자유입력이 없다** | 사용자 입력면은 `choiceId` 하나뿐. 프롬프트 인젝션 표면이 극히 좁다 |
| 3 | **서비스 전체가 15세 이용가 단일 등급** | 세이프티 임계값이 상수 1개. 작품별 분기를 만들지 않는다 |
| 4 | **UGC(사용자 작품)를 전제로 설계한다** | 스키마는 처음부터 UGC를 포함하되, 기능 오픈은 P2 |

### 1.1 레포 경계

이 레포가 소유하는 것:

- REST API 서버 (인증 / 라이브러리 / 세션 / 턴 / 히스토리 / 저작 / 신고 / 관리자)
- **AI Gateway**: Provider 추상화, 어댑터(Anthropic / OpenAI / Ollama), 프롬프트 조립, 토큰 예산, 호출 로그
- **Story Engine**: GameState, Chapter 판정, Ending 판정, 요약 압축
- **Safety Service**: L0~L3 검수, 블록리스트, 정규화기
- DB 마이그레이션, 배치(집계·재스캔·만료), 관리자 도구 API

이 레포가 소유하지 않는 것:

- 프론트엔드 화면, 디자인 시스템 (→ `neowadaeum-frontend`)
- 인프라 프로비저닝(IaC), 결제
- 이미지 생성 파이프라인 (P3, 미확정 — §13)

---

## 2. 기술 스택

> 상위 문서에 스택 명시가 없어 이 문서에서 확정한다. 변경은 ADR(§9.5)을 거친다.
> **갱신 이력**: 2026-08-22 — Spring Boot 3.5는 2026-06-30 EOL(보안 패치 종료)이므로 4.1로 상향. 이에 따라 HTTP 클라이언트·로깅·ORM 항목을 함께 정정했다.

### 2.1 확정 스택

| 영역 | 선택 | 근거 |
|---|---|---|
| 언어 / 런타임 | **Java 21 (LTS)** | Virtual Thread로 AI 호출 블로킹 비용 완화. Boot 4의 최소 요구는 17, 일급 지원은 25이지만 팀 JDK 표준을 21로 둔다 |
| 프레임워크 | **Spring Boot 4.1.x** (Spring Framework 7) | 3.5는 EOL. `application.yml` 기반 설정 규칙(§7)이 전제 |
| 빌드 | **Gradle (Kotlin DSL)** | 단일 모듈로 시작, 필요 시 멀티 모듈 분리 |
| 모듈 경계 | **Spring Modulith** (`-core` + `-test`만) | §5.4 의존 규칙을 테스트로 강제한다. 문서로만 있는 경계는 반드시 깨진다. **`-jpa` 스타터는 쓰지 않는다 — §2.5** |
| DB | **PostgreSQL 16** (Docker) | 요구사항이 `jsonb`·partial unique index를 전제(§13-9, R7.4). 로컬 설치 금지 — §2.5 |
| 마이그레이션 | **Flyway** | 스토어별 **4세트** 분리 (§5.3) |
| 캐시 / 락 | **Redis 7** (Docker) | Idempotency, Rate limit, 동시 생성 락, 쿨다운 카운터 |
| ORM | **Spring Data JPA (Hibernate 7)** | 동적 쿼리 라이브러리는 **보류** — §2.3 |
| 인증 | **Spring Security 7 + OAuth2 Client + OAuth2 Resource Server** | Google 로그인은 Client, 자체 발급 JWT 검증은 Resource Server의 Nimbus 디코더를 재사용한다 |
| HTTP 클라이언트 | **`RestClient` + `@HttpExchange`** | Boot 4의 HTTP Service Client. WebFlux 의존성 없이 블로킹 호출이 되고 Virtual Thread와 함께 쓰면 25초 대기를 감당한다. **WebClient / WebFlux를 추가하지 않는다** |
| 문서화 | 수기 `docs/openapi.yaml` (+ springdoc은 검증용) | 계약 우선(§12 B-06). springdoc의 Boot 4 대응 버전을 확인한 뒤 붙인다 |
| 테스트 | **JUnit 5 + Testcontainers + WireMock** | Boot 4에서 JUnit 4는 제거됐다. Provider는 WireMock 고정 응답 |
| 로깅 | **Spring Boot 구조화 로깅 내장** (`logging.structured.format.console=ecs`) | 별도 JSON 인코더 의존성을 추가하지 않는다 |
| 메트릭 | **Actuator + Prometheus** | 턴 지연·토큰·비용·차단율 (B-48) |
| 스케줄러 | **`@Scheduled` + ShedLock** | Spring Batch / Quartz는 과하다 |
| 컨테이너 | **Docker / docker-compose** | 앱 실행 시 Postgres·Redis가 자동 기동된다 (§2.5). 로컬 DB 설치 금지 |

**금지**: Lombok `@Data` 무분별 사용(엔티티), `@SneakyThrows`, 필드 주입(`@Autowired` 필드), `System.out.println`.

### 2.2 Spring Boot 4 전환에 따른 주의사항

Boot 3 기준 예제 코드를 그대로 붙이면 컴파일되지 않는 지점들이다.

| 항목 | 주의 |
|---|---|
| **자동설정 패키지 이동** | 모듈화로 자동설정 클래스가 기능별 패키지로 옮겨졌다. 예: `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` → **`org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`**. ★ **`spring.autoconfigure.exclude`에 존재하지 않는 FQCN을 써도 Spring은 오류를 내지 않고 조용히 무시한다.** 제외가 실제로 적용됐는지 반드시 동작으로 확인한다 |
| **Jackson 3** | 패키지가 `com.fasterxml.jackson.*` → **`tools.jackson.*`**. AI 출력 파서(B-21)와 camelCase 설정(§9.1)에 직접 영향 |
| **Hibernate 7** | 엔티티 매핑·쿼리 동작에 미세한 변경이 있다. 마이그레이션 후 통합 테스트로 확인 |
| **JUnit 4 제거** | `@RunWith`, `junit:junit` 사용 금지 |
| **Spring Security 7** | 기본값이 바뀌었다. 설정은 람다 DSL로만 작성 |
| **Undertow 제거** | 내장 서버는 Tomcat 사용 |
| **JSpecify null-safety** | `@Nullable` 계열 애노테이션이 JSpecify 기준으로 통일됐다. §9.3과 함께 지킨다 |

### 2.3 보류 — 동적 쿼리 라이브러리

원래 QueryDSL을 명시했으나, **Hibernate 7 + Boot 4 조합에서의 지원 상태가 불확실**하므로 도입을 보류한다.

- **지금**: Spring Data JPA 메서드 + JPQL로 시작한다.
- **결정 시점**: Library 섹션 조회(B-15)나 관리자 검수 큐(B-55)에서 동적 조건이 실제로 복잡해질 때.
- **선택지**: QueryDSL(포크 포함) / jOOQ / Spring Data Specification.
- **절차**: ADR(§9.5)로 결정한다. 그 전까지 어떤 PR도 동적 쿼리 라이브러리를 임의로 추가하지 않는다.

### 2.4 Spring AI를 쓰지 않는 이유

Spring AI는 Anthropic·OpenAI·Ollama 어댑터를 제공하지만 **턴 생성 경로에는 쓰지 않는다.**

I-3은 "직렬화 직전 페이로드 화이트리스트 검증 후 위반 시 요청 중단"을 요구한다. Spring AI는 와이어 페이로드를 내부에서 조립하므로 그 시점을 우리가 소유하지 못한다. I-7(SYSTEM 레이어 불변)도 마찬가지다. 따라서 B-22 / B-23의 어댑터는 `RestClient`로 직접 구현해 페이로드 전체를 서버가 소유한다.

요약·아웃라인 초안처럼 세이프티 노출도가 낮은 경로에 한해 나중에 재검토할 수 있다. 도입하려면 ADR + 와이어 레벨에서 I-3을 강제하는 훅이 함께 있어야 한다.

### 2.5 로컬 실행 — Docker Compose 자동 기동

**로컬에 PostgreSQL·Redis를 직접 설치하지 않는다.** `spring-boot-docker-compose`(Initializr의 *Docker Compose Support*)가 앱 기동 시 `docker compose up`을 대신 실행하고, 컨테이너가 healthy가 될 때까지 기다린 뒤 애플리케이션을 띄운다. `./gradlew bootRun`이든 IDE Run 버튼이든 동일하게 동작한다. 종료 시 컨테이너를 어떻게 할지는 설정으로 정한다.

의존성은 반드시 `developmentOnly` 스코프여야 한다(Initializr가 자동으로 넣는다). 운영 jar에 포함되면 안 된다.

#### 파일명 주의

Spring이 인식하는 이름은 `compose.yaml` / `compose.yml` / `docker-compose.yaml` / `docker-compose.yml` 넷이다. **반드시 `docker-compose.yml`을 쓴다.** §7.2의 `.gitignore`가 예외로 추적하는 이름이 이것 하나뿐이라, `compose.yaml`로 만들면 파일이 통째로 커밋에서 빠진다.

#### `docker-compose.yml`

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: neowadaeum-postgres
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      TZ: UTC
      # 아래 4개는 init 스크립트가 CREATE ROLE 에 쓴다.
      # 넘기지 않으면 PASSWORD '' 로 생성돼 4개 계정 접속이 전부 실패한다.
      IDENTITY_DB_PASSWORD: ${IDENTITY_DB_PASSWORD}
      CATALOG_DB_PASSWORD: ${CATALOG_DB_PASSWORD}
      PLAY_DB_PASSWORD: ${PLAY_DB_PASSWORD}
      PROMPTLOG_DB_PASSWORD: ${PROMPTLOG_DB_PASSWORD}
    ports:
      - "${POSTGRES_PORT}:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7-alpine
    container_name: neowadaeum-redis
    ports:
      - "${REDIS_PORT}:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  postgres-data:
```

- 값은 전부 `${VAR}`다. 실제 값은 `.env`에 두고, Docker Compose가 compose 파일과 같은 디렉터리의 `.env`를 자동으로 읽는다 (§7.1).
- `healthcheck`가 없으면 Spring이 포트만 열린 상태에서 진행해 첫 커넥션이 깨진다. **생략 금지.**
- `$${...}`의 이중 달러는 Compose 치환을 피해 컨테이너 셸에 그대로 넘기기 위한 것이다. 오타가 아니다.

#### 4개 스키마 초기화

§5.3의 4-스토어 분리는 **컨테이너 1개 안의 스키마 4개**로 시작한다. 초기화 스크립트를 `docker/postgres/init/01-init-schemas.sh`에 둔다. `.sql`이 아니라 `.sh`인 이유는, `.sql` 파일은 환경변수를 치환하지 않아 계정 비밀번호를 하드코딩하게 되기 때문이다(§7.1 위반).

```bash
#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE SCHEMA IF NOT EXISTS identity;
  CREATE SCHEMA IF NOT EXISTS catalog;
  CREATE SCHEMA IF NOT EXISTS play;
  CREATE SCHEMA IF NOT EXISTS promptlog;

  CREATE ROLE identity_user  LOGIN PASSWORD '${IDENTITY_DB_PASSWORD}';
  CREATE ROLE catalog_user   LOGIN PASSWORD '${CATALOG_DB_PASSWORD}';
  CREATE ROLE play_user      LOGIN PASSWORD '${PLAY_DB_PASSWORD}';
  CREATE ROLE promptlog_user LOGIN PASSWORD '${PROMPTLOG_DB_PASSWORD}';

  GRANT ALL ON SCHEMA identity  TO identity_user;
  GRANT ALL ON SCHEMA catalog   TO catalog_user;
  GRANT ALL ON SCHEMA play      TO play_user;
  GRANT ALL ON SCHEMA promptlog TO promptlog_user;
EOSQL
```

계정을 스키마별로 나누는 것이 §5.3의 "각 계정은 자기 스키마에만 권한을 갖는다"를 로컬에서부터 강제한다. 스키마 간 JOIN을 쓴 코드는 로컬에서 바로 권한 오류로 터진다 — 운영에 가서 발견하는 것보다 낫다.

> **초기화 스크립트는 볼륨이 비어 있을 때 한 번만 실행된다.** 스크립트를 고쳤다면 `docker compose down -v`로 볼륨을 지우고 다시 띄워야 반영된다. 시드 데이터(B-45)는 여기 넣지 말고 Flyway로 관리한다.

#### `application-local.yml.template` 설정

```yaml
spring:
  docker:
    compose:
      enabled: true
      file: docker-compose.yml
      lifecycle-management: start-only   # 앱을 꺼도 DB는 살려 둔다
      skip:
        in-tests: true                   # 테스트는 Testcontainers를 쓴다 (§10)
```

- `start-only`를 쓰는 이유: 기본값 `start-and-stop`이면 앱을 끌 때마다 컨테이너가 내려가 재기동이 느려진다. 컨테이너를 정리하려면 `docker compose down`을 직접 친다.
- `skip.in-tests`는 기본값이 `true`다. 명시해 두는 편이 의도가 드러난다.

#### ★ 커넥션 정보는 자동 주입되지 않는다

`spring-boot-docker-compose`의 `ServiceConnection`은 **기본 DataSource 하나**(`spring.datasource.*`)만 자동 설정한다. 이 프로젝트는 DataSource가 4개이고 전부 직접 정의한 `@Bean`이므로, **자동 주입 대상이 아니다.**

따라서 이 기능은 **컨테이너 수명 관리 용도로만** 쓰고, 접속 정보는 직접 적는다.

```yaml
app:
  datasource:
    identity:
      url: ${IDENTITY_DB_URL}       # jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=identity
      username: ${IDENTITY_DB_USER}
      password: ${IDENTITY_DB_PASSWORD}
    catalog:   { url: ${CATALOG_DB_URL},   username: ${CATALOG_DB_USER},   password: ${CATALOG_DB_PASSWORD} }
    play:      { url: ${PLAY_DB_URL},      username: ${PLAY_DB_USER},      password: ${PLAY_DB_PASSWORD} }
    promptlog: { url: ${PROMPTLOG_DB_URL}, username: ${PROMPTLOG_DB_USER}, password: ${PROMPTLOG_DB_PASSWORD} }
```

`.env`의 `POSTGRES_PORT`와 URL의 포트가 어긋나면 조용히 다른 DB에 붙는다. `.env.example`에 둘을 나란히 적어 두고 함께 바꾸게 한다.

#### 실행

```bash
cp .env.example .env          # 값을 채운다
cp src/main/resources/application.yml.template src/main/resources/application.yml
./gradlew bootRun             # Postgres·Redis가 자동으로 뜬다
```

Docker Desktop(또는 데몬)이 꺼져 있으면 기동이 실패한다. 이때 나는 오류는 애플리케이션 버그가 아니다.

#### ★ B-05 이전에는 앱이 끝까지 뜨지 않는다

DataSource Bean 4개는 B-05의 산출물이다. 그 전까지는 클래스패스에 Spring Data JPA와 PostgreSQL 드라이버가 있는데 `spring.datasource.url`이 없어, **컨테이너는 뜨지만 애플리케이션 기동이 실패한다.**

접속 정보를 `spring.datasource.*`에 더미로 채워 넣지 않는다. B-05에서 `app.datasource.*` 4벌로 옮길 때 지워야 할 흔적이 남고, 최악의 경우 커밋된 채로 살아남는다.

대신 `application.yml.template`에 **한시적 제외**를 명시하고, 제거를 B-05의 완료 조건에 넣는다.

```yaml
spring:
  autoconfigure:
    exclude:
      # TODO(B-05): DataSource Bean 4개 정의 후 이 블록을 통째로 삭제한다.
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
```

- **B-01 완료 조건**은 "컨테이너 자동 기동 + 스키마 4개 생성"까지다. 앱이 초록으로 뜨는 것은 B-01의 조건이 아니다.
- **B-02~B-04**는 이 제외 덕분에 `/actuator/health` 200을 볼 수 있다.
- **B-05**에서 위 블록을 지우지 않으면 DataSource가 4개 정의돼 있어도 JPA가 붙지 않는다. B-05의 DoD에 "제외 블록 삭제 확인"이 들어가는 이유다.
- **B-05는 이 한시적 제외를 지우는 대신 JPA 자동설정 2종(`HibernateJpaAutoConfiguration` / `DataJpaRepositoriesAutoConfiguration`)과 `FlywayAutoConfiguration`을 영구 제외한다.** 자동설정은 EntityManagerFactory와 Flyway를 각각 1벌만 만드는데, §5.3은 스토어별 4벌을 요구한다. 스토어별 EMF/TransactionManager 4벌은 **B-05-1**이다.

#### 제외만으로 부족한 경우 — `EntityManager`를 무조건 요구하는 자동설정

Flyway·Hibernate 자동설정은 DataSource 빈이 없으면 조건이 걸려 조용히 물러난다. **전부 그런 것은 아니다.** DataSource 유무와 무관하게 `EntityManager`를 요구하는 자동설정이 클래스패스에 있으면 컨텍스트가 뜨지 않는다.

**확인된 사례**: `spring-modulith-starter-jpa`가 끌어오는 `JpaEventPublicationAutoConfiguration`.

이 프로젝트는 **모듈 경계 검증 목적으로만** Spring Modulith를 쓴다(§2.1). 모듈 간 통신은 이벤트가 아니라 Facade다(§5.4). 이벤트 발행 레지스트리는 쓰지 않으므로 **`spring-modulith-starter-jpa`를 의존성에서 제거한다.** `spring-modulith-starter-core`와 `spring-modulith-starter-test`만 남기면 `ApplicationModules.verify()`는 그대로 동작한다.

> Initializr에서 *Spring Modulith*와 *Spring Data JPA*를 함께 고르면 `-jpa` 스타터가 자동으로 딸려 온다. 우리가 요청한 적 없는 기능이므로 **한시적 제거가 아니라 영구 제거다.** B-05에서 되돌리지 않는다.
>
> 나중에 Modulith 이벤트 발행을 도입하려면(예: 요약 압축 B-34, `ending_stat` 갱신 B-39의 비동기 경로) ADR(§9.5)을 거친다.

같은 유형의 문제가 또 나오면 **제외를 하나 더 추가하기 전에 그 의존성이 정말 필요한지 먼저 묻는다.** 제외 목록이 길어지는 것은 의존성 구성이 잘못됐다는 신호다.

---

## 3. 용어 사전 (Glossary)

이 표의 용어만 사용한다. 코드·커밋·API·문서에서 동의어를 만들지 않는다.

### 3.1 콘텐츠

| 용어 | 코드 식별자 | 정의 |
|---|---|---|
| **작품** | `Story` | 플레이 단위 콘텐츠. 공식(`official`) 또는 사용자(`user`) 저작 |
| **작품 버전** | `StoryVersion` | 작품의 불변 스냅샷. 승인 시마다 새 버전 발행. **세션은 버전에 고정된다** |
| **챕터** | `ChapterDef` | 사전 정의된 막(幕). 번호·제목·진입 조건·최소/최대 턴 |
| **엔딩** | `EndingDef` | 사전 정의된 결말. 번호·라벨·도달 조건·에필로그 |
| **기본 엔딩** | `is_default` | 어떤 조건에도 걸리지 않을 때 사용하는 폴백 엔딩. 작품당 **정확히 1개** |
| **시크릿 엔딩** | `is_secret` | 총계(`totalEndings`)에서 제외되는 엔딩 |
| **캐릭터** | `Character` | 작품 등장인물. 페르소나 프롬프트 소유 |
| **도달률** | `reachRate` | 특정 엔딩 도달 비율. 완주 50건 미만이면 `null` |

### 3.2 플레이

| 용어 | 코드 식별자 | 정의 |
|---|---|---|
| **세션** | `PlaySession` | 한 사용자의 한 작품 1회 플레이. provider/model이 고정됨 |
| **턴** | `Turn` | 1회의 생성 단위. 본문 + 선택지 + 사용자의 선택 |
| **턴 번호** | `turnNo` | 세션 내 1부터 증가. **낙관적 잠금 키** |
| **선택지 ID** | `choiceId` | **서버가 발급**하는 불투명 식별자. 클라이언트는 이것만 전송 |
| **게임 상태** | `GameState` | 챕터·턴·장소·시간·호감도·플래그·인벤토리의 구조화 JSON |
| **스냅샷** | `GameStateSnapshot` | 턴마다 1행 저장되는 GameState. 덮어쓰지 않는다 |
| **요약** | `StorySummary` | 오래된 턴을 압축한 서사. append-only |
| **인터스티셜** | — | 챕터 전환 시 클라이언트가 삽입하는 2.5초 화면. 서버는 `chapterChanged`만 준다 |
| **테스트 세션** | `is_test_session` | UGC 미리보기(3턴) 또는 관리자 디버그용 세션 |

### 3.3 AI

| 용어 | 코드 식별자 | 정의 |
|---|---|---|
| **Provider** | `StoryProvider` | AI 벤더 추상화. `anthropic` / `openai` / `ollama-local` |
| **Gateway** | `AiGateway` | Provider 앞단. 페이로드 화이트리스트 검증·재시도·fallback·로깅 담당 |
| **프롬프트 레이어** | — | `SYSTEM → WORLD → CHARACTER → GAME STATE → SUMMARY → RECENT TURNS → USER ACTION → OUTPUT SPEC` |
| **플랫폼 레이어** | — | `SYSTEM` / `OUTPUT SPEC`. **작품이 덮어쓸 수 없다** (P7) |
| **작품 레이어** | — | `WORLD` / `CHARACTER`. `StoryVersion`이 소유 |
| **stateChanges** | — | AI가 제안하는 상태 변화. 서버가 화이트리스트 필터 + clamp |
| **제안값** | `*Suggested` | `chapterAdvanceSuggested` / `endingSuggested`. **참고만 하고 판정은 서버가 한다** |

### 3.4 세이프티 / 저작

| 용어 | 정의 |
|---|---|
| **L0** | 입력 중 실시간 검수 (UGC 드래프트 필드, debounce 800ms) |
| **L1** | 제출 시 검수 (UGC 전체 + 관리자 자유입력) |
| **L2** | AI 응답 직후 검수 (본문 + 선택지). **생성 모델과 별개 판정기** |
| **L3** | 상시 검수 (신고 · 랜덤 샘플링) |
| **precheck** | L0의 API 이름. `POST /authoring/drafts/{id}/precheck` |
| **블록리스트** | IP 제목·캐릭터명·실존인물·금지 표현 목록. 운영 중 갱신 가능 |
| **정규화** | 공백 제거 + 자모 분리 + 유사 문자/숫자 치환. 공백 삽입·숫자 치환·자모 혼용 우회를 차단한다 |
| **player_ref** | 회원당 1개의 UUID. 회원정보와 무관. **비-Identity 스토어는 이것만 저장** |

### 3.5 금지 동의어

| 쓰지 말 것 | 쓸 것 |
|---|---|
| `episode`, `act` | `chapter` |
| `scene` (턴 의미로) | `turn` |
| `userId` (비-Identity 스토어에서) | `playerRef` |
| `progressPercent` | `chapterNo` / `totalChapters` / `progressHint` |
| `ageRating` 컬럼 | 상수 응답값 `"15세 이용가"` |
| `story` (본문 문자열 의미로) | `paragraphs[]` |

---

## 4. 서비스 핵심 플로우

### 4.1 가입 → 연령 게이트

```
OAuth(Google) 또는 이메일 가입
  → 생년월일 수집
  → 만 15세 미만? ── yes → 403 AGE_RESTRICTED (계정 미생성)
                     └ no → 약관·개인정보·AI고지 동의 수집
  → consent_log 기록 (version, agreed_at, ip_hash)
  → player_ref 발급
  → 토큰 발급
```

- 만 나이는 **요청 시각(KST) 기준**으로 계산한다. `birth_date` 원본을 저장하고 나이를 캐시하지 않는다 (생일 경과 처리).
- `birthDate` 또는 `consents[]` 누락 → `400 CONSENT_REQUIRED`.
- AI 사전 고지 노출 이력도 기록한다 (R11.3, §13-13).

### 4.2 탐색 → 세션 시작

```
GET /library         → 장르, 섹션(추천/장르별/사용자작품), 이어하기 목록
GET /stories/{id}    → 작품 소개, 세계관, 캐릭터, 내 세션 유무
POST /stories/{id}/sessions
  → active 세션 중복 확인 (작품당 1개)
  → story.current_version_id를 story_version_id로 고정
  → provider_id / model_id 고정 (P4)
  → GameState 초기화 (state_schema 기본값)
  → 턴 1 생성 (§4.3과 동일 파이프라인, 단 choiceId 검증 생략)
```

### 4.3 턴 처리 파이프라인 (**시스템의 심장**)

```
 1. POST /sessions/{id}/turns  { choiceId, turnNo }   [Idempotency-Key 헤더]
 2. 검증
      소유자 일치 / session.status == active
      / session.turn_no == 요청 turnNo          → 불일치 시 409 TURN_CONFLICT
      / choiceId ∈ 직전 턴의 choices            → 아니면 400 INVALID_CHOICE
      / 해당 choice.disabled == false           → 아니면 400 INVALID_CHOICE
      / story.review_status != suspended        → 아니면 423 STORY_SUSPENDED
      / 동시 생성 락 획득 (계정당 1개)          → 실패 시 409 CONCURRENT_GENERATION
      / 쿨다운·쿼터 확인                        → 초과 시 429
 3. 직전 턴에 chosen_choice_id / chosen_at 기록
 4. 컨텍스트 조립 (§4.4) → 페이로드 화이트리스트 검증 (R12.1) → 위반 시 요청 중단
 5. Provider 호출 (타임아웃 25s)                → 초과 시 504 GENERATION_TIMEOUT
 6. 파싱 → 스키마 검증 → 실패 시 1회 재요청     → 재실패 시 502 PROVIDER_ERROR
 7. Safety L2 검수
      즉시차단 카테고리 → 재생성 없이 422 SAFETY_BLOCKED
      그 외 blocked     → 재생성 1회 → 여전히 blocked면 422 SAFETY_BLOCKED
 8. stateChanges 화이트리스트 필터 → clamp → GameState 갱신 → 스냅샷 저장
 9. Chapter 전환 판정 (서버 단독)
10. Ending 판정 (서버 단독)
11. turn 저장 (신규 turnNo = 직전 + 1), session.turn_no++, savedAt 기록
12. 락 해제 → 응답 반환
13. [비동기] 요약 압축 / ai_call_log 기록 / ending_stat 큐 적재
```

**턴 번호 계약 — 혼동 금지**

- 요청 `turnNo` = **지금 화면에 떠 있는 턴의 번호** (= 사용자가 선택지를 고른 그 턴)
- 응답 `turnNo` = **새로 생성된 턴의 번호** (= 요청값 + 1)
- 실패(2·5·6·7단계) 시 `session.turn_no`는 **변하지 않는다.** 8단계 이전이므로 GameState도 그대로다 (R6.4, R6.6).

### 4.4 컨텍스트 조립

| 레이어 | 내용 | 토큰 상한 |
|---|---|---|
| SYSTEM | 플랫폼 공통 지시 + 세이프티 + 15세 등급 지시 | (WORLD·CHARACTER 포함 합계 1,200) |
| WORLD | `story_version.world_prompt` | ↑ |
| CHARACTER | `character.persona_prompt` (표시 순서대로) | ↑ |
| GAME STATE | 현재 GameState JSON | 300 |
| SUMMARY | `story_summary` 최신 1건 | 600 |
| RECENT TURNS | 최근 N턴 (§13-2 참조) | 1,500 |
| USER ACTION | 선택한 선택지 텍스트 | (OUTPUT SPEC 포함 합계 200) |
| OUTPUT SPEC | 출력 JSON 스키마 지시 | ↑ |
| **합계 목표** | | **≤ 4,000** |

- 상한 초과 시: RECENT TURNS를 오래된 것부터 잘라낸다 → 그래도 초과하면 SUMMARY 재압축 → 그래도 초과하면 `500 CONTEXT_BUDGET_EXCEEDED`로 실패시키고 알람. **조용히 잘라내고 진행하지 않는다.**
- UGC 작품의 `world_prompt + persona_prompt` 합계는 **1,000토큰 하드 제한** (R4.9). 저장 시점에 검증한다.

### 4.5 Chapter 전환

```
현재 챕터 min_turns 충족? ── no → 유지
        └ yes → 다음 챕터 entry_condition 평가 (GameState 기반, 결정론적)
                  ├ 만족 → 전환
                  └ 불만족 → 현재 챕터 max_turns 도달? ── yes → 강제 전환
                                                        └ no  → 유지
```

- **AI 응답에서 챕터를 추정하지 않는다** (R7.1). `chapterAdvanceSuggested`는 로그로만 남긴다.
- 전환 시 응답에 `chapterChanged: true` + 새 `chapterNo` / `chapterTitle`.
- 진행 표시는 `progressHint: "Chapter 4 / 전체 6장"`. **`progressPercent`는 만들지 않는다.**

### 4.6 Ending 판정

```
매 턴 Chapter 판정 이후
  → ending_def를 ending_no 오름차순으로 순회
  → condition 최초 매칭에서 종료 선언
  → 매칭 없음 & 마지막 챕터 max_turns 도달 → is_default 엔딩으로 종료
  → 종료 시: choices: [], isEnding: true,
             session.status = completed, completed_at 기록
```

- `endingSuggested`가 와도 조건이 매칭되지 않으면 **무시한다** (R7.9). AI 임의 종료 불가.
- `endingIndex` / `totalEndings`는 `is_secret = false`인 엔딩만 카운트 (R7.11).
- `reachRate`는 `total_completed_count >= 50`일 때만, 미만이면 `null` (R7.12).
- `stats`: `chapters` = 도달 챕터 수, `turns` = 최종 turn_no, `choices` = `chosen_choice_id IS NOT NULL` 카운트.

### 4.7 Resume

```
GET /sessions/{id}/resume
  → sessionState 판정
      story_version_id != story.current_version_id → version_changed
      status == expired (90일 무활동)              → expired
      soft delete 됨                               → deleted
      story.review_status == suspended             → story_suspended (읽기 전용)
      그 외                                        → valid
  → lastSceneSummary / lastChoiceText / chapterNo / turnNo 반환
```

### 4.8 세이프티 차단

```json
{
  "error": "SAFETY_BLOCKED",
  "message": "이 방향으로는 이야기를 이어갈 수 없어요.",
  "turnNo": 12,
  "recoverable": true,
  "actions": ["choose_other", "leave"]
}
```

- **`retry` 액션을 절대 넣지 않는다** (R9.5). 동일 입력 → 동일 결과 → 무한 루프.
- **차단 사유를 구체적으로 노출하지 않는다** (R9.6). 어떤 표현이 걸렸는지 알려주면 우회를 학습시킨다.
- 서버 내부 기록(`turn.safety_verdict`, `ai_call_log.safety_flags`)에는 상세 사유를 남긴다.

### 4.9 UGC 저작 파이프라인 (P2)

```
① 기본정보 → ② 세계관 → ③ 캐릭터 → ④ 챕터·엔딩(AI 초안) → ⑤ 미리보기(3턴)
     └────────── 각 단계 입력 중 precheck (L0, debounce 800ms) ──────────┘
  → 제출 (L1 전수 검사)
  → 자동 검수 ── reject → rejected (카테고리만 표시, 블록리스트 항목은 비공개)
              └ pass  → visibility == public? ── no  → approved (즉시 게시)
                                               └ yes → 인간 검수 큐 → approved | rejected
  → 승인 시 story_version 발행
```

- `blocked` 상태에서 다음 단계 진행은 **서버가 거부한다** (R8.3). 클라이언트 검증에만 의존하지 않는다.
- 승인 후 수정은 새 버전이 되고 재검수를 거친다. **진행 중 세션은 기존 버전을 계속 참조한다** (R8.8, R2.1).
- `unlisted → public` 승격 시 **인간 검수를 재트리거**한다.

### 4.10 신고 → 사후 관리 (L3)

```
POST /reports (turn 또는 story 대상)
  → 동일 신고자 중복 제외한 누적 3건 → story.review_status = suspended, 검수 큐 적재
  → suspended 작품의 진행 중 세션: 읽기 전용 (새 턴 거부, 기존 기록 열람 허용)
  → 승인 후에도 랜덤 샘플링 검수 수행
  → 블록리스트 갱신 시 승인된 UGC 전체 재스캔 배치
```

---

## 5. 아키텍처

### 5.1 컴포넌트

```
[Client] ──HTTPS/JSON──▶ [API Server]
                              │  인증 · 세션 · 턴 오케스트레이션 · 세이프티 게이트
    ┌─────────────────────────┼─────────────────────────┐
    ▼                         ▼                         ▼
[Identity Store]        [Catalog Store]          [Session Store]
회원·인증·동의·생년월일   작품·버전·챕터·엔딩       세션·턴·스냅샷·요약
   ※ 물리 분리           [Authoring Store]
                        드래프트·검수·신고
    ┌────────────────────────────────────────────────────┐
    ▼                    ▼                    ▼          ▼
[Prompt Log Store]  [AI Gateway]       [Safety Service]  [Review Queue]
요청/응답 원문·usage  Provider Adapter   L0~L3 검수        UGC 인간 검수 워커
   ※ 물리 분리
```

### 5.2 패키지 구조

```
com.neowadaeum
├── common/            공통 응답·에러·시간·ID·정규화 유틸
│   ├── error/         ErrorCode enum, GlobalExceptionHandler
│   ├── web/           ApiResponse, 공통 필터, Idempotency 인터셉터
│   ├── support/       Clock, UuidGenerator, TextNormalizer
│   └── spi/           모듈 간 계약. 구현은 데이터를 소유한 모듈에 있다 (ADR-0002, ADR-0003)
├── identity/          회원·OAuth·동의·연령 게이트
├── catalog/           작품·버전·챕터·엔딩·캐릭터·장르
├── authoring/         UGC 드래프트·검수·신고·블록리스트 ★소유 (ADR-0002)
├── play/              세션·턴·히스토리
│   ├── engine/        GameStateEngine, ChapterEngine, EndingEngine
│   └── orchestrator/  TurnOrchestrator (§4.3 파이프라인)
├── ai/                ★ AI 파이프라인
│   ├── gateway/       AiGateway, PayloadWhitelistValidator, FallbackChain
│   ├── provider/      StoryProvider 인터페이스 + anthropic/ openai/ ollama/
│   ├── prompt/        PromptAssembler, 레이어 빌더, TokenBudget
│   ├── schema/        TurnOutput DTO, 파서, 스키마 검증
│   └── log/           AiCallLogWriter
├── safety/            L0~L3 판정기, 카테고리 정책 (블록리스트는 common/spi 로 조회만 — ADR-0002)
├── admin/             디버그·롤백·재생성·검수 큐·감사 로그
├── batch/             스케줄링·실행만. 로직은 데이터 소유 모듈이 구현한다 (ADR-0003)
└── config/            DataSource 4분할, Security, RestClient, Redis, Modulith
```

### 5.3 스토어 물리 분리

기획서 8장의 3분할 + Authoring을 **별도 PostgreSQL 스키마 + 별도 DataSource**로 구현한다. 로컬에서는 컨테이너 1개 안의 스키마 4개로 시작한다 (§2.5).

| 스토어 | 스키마 | DataSource Bean | Flyway 경로 |
|---|---|---|---|
| Identity | `identity` | `identityDataSource` | `db/migration/identity` |
| Catalog + Authoring | `catalog` | `catalogDataSource` | `db/migration/catalog` |
| Session | `play` | `playDataSource` | `db/migration/play` |
| Prompt Log | `promptlog` | `promptLogDataSource` | `db/migration/promptlog` |

**규칙**

- **스키마 간 FK를 만들지 않는다.** 참조는 애플리케이션 레벨에서만.
- **스키마 간 JOIN 쿼리를 금지한다.** 필요하면 파사드 호출로 조합한다.
- 비-Identity 스키마는 `user.id`를 **절대 저장하지 않는다.** `player_ref`(UUID)만 저장한다.
- 운영 환경에서는 스키마별 DB 계정을 분리하고, 각 계정은 자기 스키마에만 권한을 갖는다.
- 초기에는 동일 인스턴스 내 스키마 분리로 시작하고, 트래픽/규제 요구에 따라 인스턴스 분리로 승격한다. **애플리케이션 코드는 승격 시 변경이 없어야 한다** (이것이 스키마 간 JOIN 금지의 이유다).

### 5.4 모듈 간 의존 규칙

```
identity  ← (참조 없음)
catalog   ← identity(X)  // player_ref만 받는다
play      ← catalog(파사드 O), identity(X)
authoring ← catalog(파사드 O), safety(O)
ai        ← (도메인 모듈 참조 X). 순수 DTO만 입출력받는다
safety    ← (도메인 모듈 참조 X). 블록리스트는 common/spi 로 주입받는다 (ADR-0002)
batch     ← common만. 배치 로직은 데이터 소유 모듈이 common/spi 를 구현한다 (ADR-0003)
admin     ← 도메인 전 모듈(O). batch 는 제외한다 — 아래 참조
config    ← 전 모듈(O). 배선 지점이며 도메인 로직을 갖지 않는다
```

- `ai` 패키지는 **도메인 엔티티를 알지 못한다.** `TurnRequest` / `TurnResult` DTO만 주고받는다. 이것이 R12.1(회원 식별정보 미포함)을 구조적으로 보장한다.
- 모듈 간 호출은 `XxxFacade` 인터페이스로만. 다른 모듈의 Repository·Entity를 직접 참조하면 리뷰에서 반려한다.
- **`safety`와 `batch`는 파사드가 아니라 SPI를 쓴다.** 둘 다 자기가 필요한 데이터의 소유자가 아니면서 그 데이터를 참조할 수 없는 모듈이다. 인터페이스는 `common/spi`에 두고 **데이터를 소유한 모듈이 구현**하며, 호출자는 주입받는다. 방향이 반대라는 점이 요점이다 — `safety → authoring`이 아니라 `authoring → common/spi ← safety`다.
- **SPI 미주입 시 동작**: 구현 빈이 없으면 **부팅 실패**, 런타임 조회 실패는 **차단(fail-closed)** + `ERROR` + 알람. 세이프티에서 fail-open은 장애가 곧 검수 우회다 (I-2, ADR-0002).
- **`admin ← batch`를 허용하지 않는 이유**: S-10(감사 로그 파기) 때문에 `batch → admin`이 필요해지는 순간 양방향이 되어 순환이다. B-02가 `admin → batch`를 미리 끊어 그 가능성을 없앴다. `admin`의 "전 모듈"은 **도메인 모듈 전부**를 뜻하며 `batch`·`config`는 포함하지 않는다 (ADR-0003).

---

## 6. 불변 규칙 (Invariants)

**이 규칙을 깨는 PR은 무조건 반려된다.** 예외가 필요하면 ADR을 먼저 작성한다.

| # | 규칙 | 출처 |
|---|---|---|
| **I-1** | 선택지는 서버가 발급한 `choiceId`로만 제출된다. 클라이언트가 보낸 `text`는 어떤 경우에도 신뢰하지 않는다 | P1 |
| **I-2** | AI 응답은 Safety L2 통과 전까지 사용자에게 도달하지 않는다 | P2 |
| **I-3** | AI 요청 페이로드에 회원 식별정보(이메일·이름·소셜 ID·IP·생년월일·`player_ref`)를 포함하지 않는다. 직렬화 직전 화이트리스트 검증 후 위반 시 요청 중단 | P3, R12.1 |
| **I-4** | 세션은 생성 시 provider/model에 고정되며 중간 변경 불가 | P4 |
| **I-5** | 모든 턴은 GameState 스냅샷과 함께 저장된다. 스냅샷·요약을 덮어쓰지 않는다 | P5, R2.6 |
| **I-6** | `turnNo`는 낙관적 잠금 키다. 불일치 시 409 | P6 |
| **I-7** | `SYSTEM` / `OUTPUT SPEC` 프롬프트 레이어는 작품이 덮어쓸 수 없다 | P7 |
| **I-8** | UGC는 검수 승인 없이 어떤 경로로도 타인에게 노출되지 않는다 | P8 |
| **I-9** | `chapter` / `turn`은 AI가 변경할 수 없다. 서버 전용 필드 | R4.3 |
| **I-10** | Chapter 전환과 Ending 선언은 서버가 GameState로 판정한다. AI 제안값은 참고만 | R7.1, R7.9 |
| **I-11** | `disabled` / `disabledReason`은 서버가 판정한다. AI에게 맡기지 않는다 | R5.6 |
| **I-12** | Safety L2는 생성 모델과 **별개의 판정기**로 수행한다. 자기 검열에 의존하지 않는다 | R9.1 |
| **I-13** | Safety L2는 provider와 무관하게 **항상 서버에서** 수행한다. 무검열 로컬 모델을 붙여도 15세 등급이 유지되어야 한다 | R3.4 |
| **I-14** | Provider 선택 권한은 관리자 전용. 사용자에게 노출하지 않는다 | R3.2 |
| **I-15** | **게임 로직에 난수를 도입하지 않는다.** 성공/실패 판정·분기·엔딩 결정은 전부 GameState 기반 결정론적 평가 | R11.7 |
| **I-16** | 유료 재화 도입 시 **정액 소모만** 허용한다. 확률 결합(가챠·배수 지급·확률 소멸) 구조를 서버에 구현하지 않는다. 무료 재화도 동일 | R11.5~R11.8 |
| **I-17** | 관리자 자유입력도 Safety L1을 거친다. 무검열 통로를 만들지 않는다 | R14.1 |
| **I-18** | 사용자 소유 세션에 자유입력을 허용하지 않는다. `is_test_session = true`에서만 | R14.3 |
| **I-19** | `story.age_rating` 컬럼을 만들지 않는다. 단일 상수 응답 | R10.1 |
| **I-20** | 도달률은 배치 갱신. 실시간 계산 금지 | R2.7 |

### 6.1 I-15 / I-16 보강 — 난수 금지의 실무 범위

"난수 금지"는 다음을 뜻한다.

- 금지: 판정 결과, 분기 선택, 엔딩 결정, 상태 변화량, 재화 획득·손실에 `Random` 사용
- 허용: 요청 ID·UUID 생성, 지터를 넣은 재시도 백오프, A/B 실험 버킷팅, 랜덤 샘플링 검수(L3)
- AI 자체의 비결정성(temperature)은 난수 판정이 아니다. 다만 **AI 출력을 상태 변화의 최종 권한으로 쓰지 않는다** — 서버 clamp가 최종 결정권을 갖는다.

---

## 7. 보안 규칙 (필수)

> 이 절은 팀 규칙이며 예외가 없다. 위반은 머지 차단 사유다.

### 7.1 원칙

1. **민감 정보는 절대 소스에 커밋하지 않는다.**
2. 실제 값은 `.env`로 관리한다. `application.yml`에는 `${DB_URL}`, `${DB_PASSWORD}` 형태의 **플레이스홀더만** 둔다.
3. **`${VAR:실제값}` 기본값 패턴을 금지한다.** 실키가 기본값으로 박혀 있으면 로컬에서 Docker 이미지를 굽는 순간 이미지에 함께 박힌다. 이 형태가 키 유출을 만든다.
4. **이미 노출된 자격 증명은 로테이션한다.** 커밋을 되돌리는 것으로는 유출이 취소되지 않는다.
5. **이미 커밋된 파일은 `.gitignore` 추가만으로 빠지지 않는다.** `git rm --cached <파일>`로 추적을 끊는다.

### 7.2 `.gitignore` (그대로 사용)

```gitignore
# ── 설정 / 시크릿 ─────────────────────────────
*.yml
*.yaml
!docker-compose.yml
!docker-compose.*.yml

# .github 은 설정 전용이며 시크릿을 담지 않는다. CI·이슈 템플릿·PR 설정이 여기 있다.
!.github/**/*.yml

*.properties
!gradle.properties
# 없으면 clone 후 ./gradlew 가 동작하지 않는다. 시크릿이 아니라 빌드 메타데이터다.
!gradle/wrapper/gradle-wrapper.properties

.env
.env.*
!.env.example

# 공개 레포에 올리지 않는 내부 문서 (S-11, §13-12). 별도 비공개 백업으로 관리한다.
docs/internal/

# ── 키 / 인증서 ──────────────────────────────
*.pem
*.p8
*.p12
*.jks
*.keystore
*-key.json
secrets/

# ── 빌드 산출물 ──────────────────────────────
build/
out/
bin/
.gradle/
*.log

# ── IDE ──────────────────────────────────────
.idea/
*.iml
.vscode/
```

**주의 — `*.yml` 전면 무시의 부작용과 처리**

| 파일 | 처리 |
|---|---|
| `docker-compose.yml` | **추적한다** (팀 규칙 명시 예외). 값은 전부 `${VAR}`로, 실제 값은 `.env`에서 주입 |
| `.github/**/*.yml` | **추적한다.** 워크플로(`workflows/*.yml`)가 없으면 CI가 존재할 수 없고, 이슈 템플릿(`ISSUE_TEMPLATE/*.yml`)이 없으면 §8.6이 동작하지 않는다. 시크릿은 `${{ secrets.* }}`만 사용 |
| `src/main/resources/application*.yml` | **추적하지 않는다.** 대신 `application.yml.template`을 커밋하고 로컬·CI에서 복사해 쓴다 |
| 테스트 설정 | **yml 파일을 만들지 않는다.** Testcontainers + `@DynamicPropertySource`로 런타임 주입한다 |
| Flyway 마이그레이션(`.sql`) | 추적 대상. 시크릿·실데이터를 넣지 않는다 |
| `docker/postgres/init/*.sh` | 추적 대상. 비밀번호는 환경변수로만 받는다 (§2.5) |
| `gradle/wrapper/gradle-wrapper.properties` | **추적한다.** `*.properties` 규칙에 걸리면 clone 후 `./gradlew`가 동작하지 않는다. 시크릿이 아니라 빌드 메타데이터이므로 `!gradle.properties`와 의도가 같다 |

> **`.gitignore`에 줄 끝 주석을 쓰지 않는다.** git은 `#`을 **줄 첫 글자일 때만** 주석으로 본다. `!.github/**/*.yml   # 설명`이라고 쓰면 주석까지 패턴의 일부가 되어 예외가 통째로 무효화된다. 설명은 반드시 **윗줄**에 단독으로 쓴다. 이 규칙을 어기면 조용히 실패하므로 리뷰에서 잡기 어렵다.

**예외는 이 2종(`docker-compose.yml`, `.github/**`)뿐이다.** 다른 `.yml` 예외를 승인 없이 추가하지 않는다.

> `.github/**` 확대는 팀 규칙(`docker-compose.yml`만 예외)에 대한 추가 예외다. 근거는 `.github` 디렉터리가 순수 설정만 담고 시크릿을 담을 이유가 구조적으로 없다는 것이다. 이 디렉터리에 값이 박힌 시크릿을 넣는 PR은 반려한다.

### 7.3 `application.yml.template` 규칙

```yaml
# ✅ 올바름 — 플레이스홀더만
spring:
  datasource:
    identity:
      url: ${IDENTITY_DB_URL}
      username: ${IDENTITY_DB_USER}
      password: ${IDENTITY_DB_PASSWORD}

ai:
  providers:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
```

```yaml
# ❌ 금지 — 기본값 패턴. 이미지에 키가 박힌다
ai:
  providers:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:sk-ant-api03-실제키가여기}
```

기본값이 정말 필요한 경우(비민감 설정에 한함)에도 **시크릿 성격 값에는 절대 쓰지 않는다.** 판단이 애매하면 기본값 없이 두고, 부팅 시 실패하게 한다. **조용히 잘못된 값으로 뜨는 것보다 안 뜨는 게 낫다.**

### 7.4 `.env.example` (커밋 대상)

```dotenv
# 값은 비워 둔다. 형식만 보여준다.

# ── Docker Compose (§2.5) ──
POSTGRES_DB=
POSTGRES_USER=
POSTGRES_PASSWORD=
POSTGRES_PORT=
REDIS_PORT=

# ── DataSource 4종 — URL의 포트는 POSTGRES_PORT와 반드시 일치시킨다 ──
IDENTITY_DB_URL=
IDENTITY_DB_USER=
IDENTITY_DB_PASSWORD=
CATALOG_DB_URL=
CATALOG_DB_USER=
CATALOG_DB_PASSWORD=
PLAY_DB_URL=
PLAY_DB_USER=
PLAY_DB_PASSWORD=
PROMPTLOG_DB_URL=
PROMPTLOG_DB_USER=
PROMPTLOG_DB_PASSWORD=

REDIS_URL=
JWT_SECRET=
GOOGLE_OAUTH_CLIENT_ID=
GOOGLE_OAUTH_CLIENT_SECRET=
ANTHROPIC_API_KEY=
OPENAI_API_KEY=
OLLAMA_BASE_URL=
ADMIN_ALLOWED_CIDR=
```

### 7.5 추가 보안 요건 (이 문서에서 신설)

| # | 요건 |
|---|---|
| **S-1** | pre-commit 훅에 `gitleaks`를 건다. CI에도 시크릿 스캔 잡을 둔다. 둘 다 실패 시 머지 차단 |
| **S-2** | Docker 빌드 시 `--build-arg`로 시크릿을 넘기지 않는다(이미지 레이어에 남는다). 런타임 환경변수로만 주입 |
| **S-3** | 로그에 프롬프트 원문·API 키·토큰·이메일을 남기지 않는다. `ai_call_log`(별도 스토어)만 원문을 보관하고 접근 통제한다 |
| **S-4** | Admin API는 `role=admin` + IP 허용목록 + 2FA를 모두 요구한다 (R14.6) |
| **S-5** | `ai_call_log` / `story_draft.payload` 열람은 **열람 감사 로그**를 남긴다 (R12.3) |
| **S-6** | 에러 응답에 스택트레이스·SQL·내부 경로를 절대 노출하지 않는다. `code` + 안전한 `message`만 |
| **S-7** | 모든 외부 입력(UGC 텍스트 포함)은 저장 전 길이·문자셋 검증. `world_prompt`는 토큰 상한(R4.9)도 검증 |
| **S-8** | 신고 API·precheck·회원가입에 IP 기준 rate limit을 별도로 건다 (계정 기준만으로는 봇을 못 막는다) |
| **S-9** | `player_ref`를 API 응답에 노출하지 않는다. UGC 작성자는 `authorDisplayName`(닉네임)만 노출 |
| **S-10** | 프롬프트 로그 90일 / 감사 로그 3년 / 세션·턴은 탈퇴 시 삭제 또는 익명화. 파기 배치를 실제로 구현하고 테스트한다 (R12.4) |
| **S-11** | **이 레포는 공개다.** 커밋 메시지·이슈·PR 본문·코드 주석·문서 전부가 즉시 세계에 읽힌다. 다음을 적지 않는다: 세이프티 우회 방법, 블록리스트 실제 항목, 정규화를 뚫는 표기 예시, 아직 막지 못한 취약점의 재현 절차, 운영 도메인·IP·계정 체계. 시크릿이 한 번이라도 푸시됐다면 **삭제가 아니라 로테이션**이다 (§7.1-4) |

---

## 8. Git 규칙 — 이슈 기반 브랜치 (필수)

### 8.1 작업 리듬 — 언제 무엇을 하는가

```
  이슈 생성        브랜치 분기      커밋 ×N        Draft PR       Ready PR      머지
      │                │              │              │              │           │
  코드 한 줄도     이슈 번호를    컴파일이 통과   첫 푸시 직후   DoD 전 항목   리뷰 1인
  쓰기 전에        받은 직후      하는 시점마다   (선택)         충족 시점     승인 후
```

| 행위 | 시점 | 최소 조건 |
|---|---|---|
| **이슈 생성** | 코드를 한 줄이라도 쓰기 전 | §12의 작업 번호 또는 재현 가능한 문제 |
| **브랜치 분기** | 이슈 번호를 받은 직후 | 최신 `backend`에서 분기 |
| **커밋** | 논리 단위가 끝날 때마다 | **컴파일 통과** |
| **푸시** | 최소 하루 1회, 작업 종료 시 필수 | — |
| **Draft PR** | 브랜치 첫 푸시 직후 (권장) | — |
| **Ready PR** | 이슈의 완료 조건(DoD)을 전부 만족 | CI 초록 + 템플릿 전 섹션 작성 |
| **머지** | 리뷰 승인 후 | 시크릿 스캔 통과 |

이슈 없이 브랜치를 만들지 않는다. 브랜치 없이 커밋하지 않는다. **`backend` / `dev` / `main`에 직접 푸시하지 않는다** (브랜치 보호 규칙으로 강제).

#### 이슈를 여는 시점

- **작업**: §12의 `B-xx` **하나 = 이슈 하나**가 기본 단위다. 서로 다른 `B-xx`를 한 이슈로 묶지 않는다.
- **쪼개는 기준**: 예상 작업량이 반나절을 넘거나 PR diff가 400줄을 넘을 것 같으면 하위 이슈로 나눈다. 번호는 `B-32-1`, `B-32-2`로 표기한다.
- **버그**: 발견 즉시. "나중에 정리해서 올리겠다"가 유실의 주된 원인이다.
- **세이프티**: 발견 즉시, 무조건 P0. 진행 중이던 작업을 멈추고 먼저 연다.
- **결정 필요**: §13의 `[결정 필요]` 항목에 부딪히면 `decision.yml`로 이슈를 열고, 기본 채택안으로 진행하되 PR 본문에 그 사실을 남긴다.

> **작업 도중 다른 문제를 발견하면 그 자리에서 고치지 않는다.** 새 이슈를 열고 지금 브랜치는 원래 범위만 끝낸다. 예외는 그것을 고치지 않으면 현재 작업이 완성되지 않는 경우뿐이며, 이때는 PR 본문에 범위 확장 사유를 적는다.

#### 브랜치를 분기하는 시점

```bash
git switch backend && git pull        # 항상 최신 backend에서
git switch -c feat/#12-turn-orchestrator
```

- 분기 기준(base)은 **언제나 최신 `backend`**다. 다른 `feat/*`에서 분기하지 않는다.
- **브랜치 수명은 3일을 넘기지 않는다.** 넘어간다면 이슈를 잘못 쪼갠 것이다.
- 3일 이상 살아 있는 브랜치는 매일 `backend`를 받아 충돌을 미리 해소한다.

#### 커밋하는 시점

커밋은 "이 상태로 되돌아와도 문제없다"는 저장점이다. 기능이 완성될 때까지 기다리지 않는다.

**최소 조건은 컴파일 통과 하나다.** 테스트 실패 상태의 커밋은 허용한다(작업 중). **컴파일이 깨진 상태는 커밋하지 않는다** — 이분 탐색으로 원인을 찾을 때 그 커밋이 방해가 된다.

한 작업 안에서 자연스러운 커밋 단위:

```
1. Flyway 마이그레이션 추가
2. 엔티티 · DTO 추가
3. 도메인 · 서비스 로직
4. 컨트롤러 · 엔드포인트
5. 테스트
6. OpenAPI · 문서 갱신
```

- **리팩터링과 기능 추가를 한 커밋에 섞지 않는다.** 리뷰어가 무엇이 의도된 변경인지 구분할 수 없다.
- 포맷팅만 바뀐 변경은 별도 커밋으로 분리한다: `chore(common): spotless 적용`
- 한 커밋이 200줄을 크게 넘으면 쪼갤 수 있는지 먼저 검토한다.

**절대 커밋하지 않는 상태**

| 상태 | 이유 |
|---|---|
| 시크릿·API 키·실제 접속 정보 포함 | §7.1. 되돌려도 유출은 취소되지 않는다 |
| 예외 2종 외의 신규 `*.yml` | §7.2 |
| 컴파일 실패 | 이분 탐색 방해 |
| 디버그 출력·주석 처리된 코드 덩어리 | 리뷰 노이즈 |
| `System.out.println` | §2 금지 항목 |

#### 푸시하는 시점

- **작업을 마치면 반드시 푸시한다.** 로컬에만 있는 코드는 유실되고, 리뷰도 받을 수 없다.
- 최소 하루 1회. `feat/*`는 force push를 허용하되, 다른 사람이 그 브랜치를 보고 있으면 `--force-with-lease`를 쓴다.

#### PR을 여는 시점

**두 시점이 있다. 혼동하지 않는다.**

| | Draft PR | Ready for review |
|---|---|---|
| 언제 | 브랜치 첫 푸시 직후 | DoD 전 항목 충족 시 |
| 목적 | 방향이 맞는지 조기 확인, CI 조기 노출 | 리뷰·머지 |
| 필수 | 아니오(권장) | 예 |

**Draft PR을 특히 권장하는 작업**: `ai` · `safety` · `play/engine` 스코프. 프롬프트 구조나 판정 로직은 방향이 틀리면 되돌리는 비용이 크다. 절반쯤 왔을 때 한 번 보이는 편이 싸다.

**Draft → Ready 전환 조건** (하나라도 미충족이면 Draft 유지)

- [ ] 이슈의 완료 조건(DoD) 전 항목 충족
- [ ] CI 초록 (빌드 · 테스트 · 시크릿 스캔)
- [ ] PR 템플릿 전 섹션 작성 — 해당 없으면 "해당 없음"에 체크
- [ ] 최신 `backend`를 반영해 충돌 없음
- [ ] **본인이 diff를 처음부터 끝까지 한 번 읽었다**

PR diff가 400줄을 넘으면 리뷰 품질이 급락한다. 넘으면 쪼갤 수 있는지 검토한다. 마이그레이션·생성 파일이 많아 불가피하면 PR 본문에 그 사유를 적는다.

### 8.2 브랜치 모델

```
feat/*        →     backend      →      dev        →      main
 (작업)          (작업 공간)        (통합 검증)         (릴리스)
```

| 브랜치 | 성격 | 보호 |
|---|---|---|
| `main` | 릴리스. 태그(`v0.1.0`)를 붙인다 | 직접 푸시 금지, PR + 승인 1인 이상 |
| `dev` | 통합 검증. backend/frontend 통합 지점 | 직접 푸시 금지, CI 통과 필수 |
| `backend` | 백엔드 작업 공간(long-lived) | 직접 푸시 금지, PR 필수 |
| `feat/*` | 실제 작업 브랜치(short-lived) | — |

> 레포가 분리되어 있으므로 이 레포에는 `frontend` 브랜치가 없다. `frontend` 브랜치는 프론트 레포에 동일 구조로 존재하며, 두 레포의 `dev`에서 각각 통합 검증한 뒤 릴리스 시점을 맞춘다.

### 8.3 브랜치 네이밍

```
feat/#12-turn-orchestrator
fix/#31-turn-conflict-race
refactor/#44-prompt-assembler
chore/#07-gitleaks-hook
docs/#02-openapi-contract
test/#58-ending-engine
```

형식: `<타입>/#<이슈번호>-<영문-소문자-슬러그>`

타입: `feat` `fix` `refactor` `chore` `docs` `test` `perf` `hotfix`

### 8.4 커밋 메시지

Conventional Commits + 이슈 참조.

```
feat(play): turn 낙관적 잠금 및 409 응답 추가 (#12)

- session.turn_no 불일치 시 TURN_CONFLICT 반환
- 현재 턴 상태를 응답에 동봉 (R6.1)
- Idempotency-Key 중복 요청 대기 처리 (R6.2)

Refs: R6.1, R6.2, P6
```

- 스코프는 패키지명을 쓴다: `identity` `catalog` `authoring` `play` `ai` `safety` `admin` `batch` `common` `infra`
- **작은 단위로 자주 커밋한다.** 한 커밋이 200줄을 크게 넘으면 쪼갤 수 있는지 먼저 검토한다.
- 커밋 본문에 관련 요구사항 ID(`Refs:`)를 남긴다.

**커밋 템플릿** — 레포 루트의 `.gitmessage.txt`를 쓴다. 클론 직후 1회 설정한다.

```bash
git config commit.template .gitmessage.txt
```

- `#`로 시작하는 줄은 커밋 시 자동으로 제거되므로, 체크리스트가 메시지에 남지 않는다.
- IntelliJ에서 커밋 창을 쓰면 템플릿이 적용되지 않는다. **터미널에서 `git commit`(옵션 없이)** 을 치거나, IntelliJ의 *Settings > Version Control > Commit* 에서 템플릿 사용을 켠다.
- 설정은 로컬 전용이라 클론할 때마다 다시 해야 한다. README의 온보딩 절차에 넣는다.

### 8.5 PR 규칙

| 구간 | 머지 방식 | 요구 조건 |
|---|---|---|
| `feat/*` → `backend` | **Squash merge** | CI 통과, 시크릿 스캔 통과, 리뷰 1인 (1인 개발 중에는 §8.9의 대체 장치) |
| `backend` → `dev` | **Merge commit** | CI 통과, 통합 테스트 통과 |
| `dev` → `main` | **Merge commit + 태그** | QA 완료, 마이그레이션 검토 완료 |
| `hotfix/*` → `main` | Squash | 사후에 `dev`, `backend`로 백머지 **필수** |

**PR 템플릿**은 `.github/pull_request_template.md`에 있다. 다음 섹션으로 구성된다.

| 섹션 | 목적 |
|---|---|
| 관련 이슈 / 작업 번호 | `Closes #`, `B-xx` |
| 변경 요약 | 리뷰어가 diff를 읽기 전 맥락 확보 |
| 요구사항 추적 | 구현한 R·P ID, 관련 I 규칙, §13 미해결 항목 처리 여부 |
| 보안 | 시크릿·`*.yml`·기본값 패턴·로그 노출·`user.id` 저장 |
| 테스트 | §10.1 필수 테스트 충족 여부 |
| DB 마이그레이션 | 대상 스토어, 롤백 계획, 스키마 간 FK 없음 |
| API 변경 | `openapi.yaml` 갱신, 파괴적 변경 시 프론트 레포 이슈 |
| AI / 세이프티 | 프롬프트 골든 파일, 페이로드 화이트리스트, 난수 금지 |

문서를 두 곳에 두면 갈라지므로 **여기에 사본을 두지 않는다.** 항목을 고칠 때는 템플릿 파일만 고친다.

> `pull_request_template.md`는 모든 PR에 동일하게 적용된다. `feat/*→backend`와 `dev→main`은 성격이 다르므로, 해당 없는 섹션은 "해당 없음"에 체크하고 넘어간다. 비워 두지 않는다.

### 8.6 이슈 템플릿

`.github/ISSUE_TEMPLATE/`에 **YAML 이슈 폼** 4종을 둔다. `config.yml`로 빈 이슈 생성을 막았으므로 반드시 하나를 고르게 된다.

| 파일 | 용도 | 자동 라벨 |
|---|---|---|
| `task.yml` | §12의 `B-xx` 작업. 작업 번호·우선순위·영역·산출물·완료 조건·의존 작업·요구사항 ID가 **필수 입력** | `task` |
| `bug.yml` | 동작 결함. 영향 범위(세이프티·과금·데이터 무결성·개인정보)를 먼저 고르게 해 P0을 즉시 식별한다 | `bug` |
| `safety.yml` | 검수 오탐·미탐·파이프라인 결함. **우회 방법을 적지 않았다는 확인 체크가 필수(required)** | `safety`, `P0` |
| `decision.yml` | §13의 `[결정 필요]` 해소, ADR이 필요한 기술 선택. 차단 여부와 막고 있는 작업을 명시 | `needs-decision` |

**설계 의도**

- `task.yml`의 필수 항목은 §12 표의 열과 1:1로 대응한다. 이슈 본문만 보고 Claude Code에게 그대로 넘길 수 있어야 한다.
- `bug.yml`은 원인이 아니라 **영향 범위**를 먼저 묻는다. 세이프티·과금·데이터 무결성·개인정보 중 하나라도 걸리면 P0으로 취급한다.
- `safety.yml`은 원문 대신 **세션 ID·턴 번호·`ai_call_log` id**로 지목하게 한다. 이슈 본문은 영구히 남고 레포는 공개로 전환될 수 있다 (R9.6, S-3).
- `config.yml`의 `contact_links`에서 보안 취약점을 GitHub Security Advisory로 유도한다. **인증 우회·개인정보 노출·자격 증명 유출은 공개 이슈로 올리지 않는다.**

`config.yml`의 `OWNER` 자리는 레포 생성 후 실제 조직/계정명으로 치환한다.

**라벨** (레포 생성 시 미리 만들어 둔다)

| 종류 | 값 |
|---|---|
| 우선순위 | `P0` `P1` `P2` `P3` |
| 영역 | `area:identity` `area:catalog` `area:authoring` `area:play` `area:ai` `area:safety` `area:admin` `area:batch` `area:common` `area:infra` |
| 상태 | `blocked` `needs-decision` `task` `bug` `safety` |

### 8.7 상위 브랜치로 올리는 시점

`feat/* → backend`는 작업마다 일어나지만, **그 위 두 구간은 상시가 아니다.** 아무 때나 올리면 통합 검증과 릴리스의 의미가 사라진다.

#### `backend` → `dev`

| | |
|---|---|
| 시점 | **주 1회 이상**, 그리고 다음 중 하나가 발생할 때 |
| 트리거 | ① 마일스톤(§12.2) 하나가 닫힐 때 ② 프론트가 붙을 API 계약이 확정·변경될 때 ③ 프론트가 통합 테스트를 요청할 때 |
| 조건 | `backend`에서 **전체 테스트 + E2E(B-44) 통과** |
| 방식 | Merge commit (히스토리 보존) |

계약이 바뀌었는데 `dev`에 올리지 않으면 프론트가 낡은 계약으로 작업하게 된다. **API 변경이 포함된 PR을 `backend`에 머지했다면 그 주 안에 `dev`로 올린다.**

#### `dev` → `main`

| | |
|---|---|
| 시점 | 마일스톤 완료 + QA 통과 시 |
| 조건 | ① §12.2 마일스톤 DoD 충족 ② PC/Mobile QA 완료 ③ 마이그레이션 롤백 계획 검토 완료 ④ 런북(B-64) 해당 항목 존재 |
| 방식 | Merge commit + **태그**(`v0.1.0`) |

`main`은 "지금 배포되어 있거나 배포 가능한 것"만 담는다. 기능이 완성됐다는 이유로 올리지 않는다.

#### `hotfix/*` → `main`

| | |
|---|---|
| 시점 | 운영 장애·보안 사고 즉시 |
| 조건 | 최소 수정. 리팩터링·기능 추가를 함께 넣지 않는다 |
| 사후 | **24시간 내에 `dev`와 `backend`로 백머지.** 이걸 빠뜨리면 다음 릴리스에서 같은 버그가 부활한다 |

자격 증명 유출이면 코드 수정보다 **로테이션이 먼저다** (§7.1-4). 핫픽스 PR은 그다음이다.

### 8.8 부트스트랩 예외 (B-01 ~ B-04)

`backend` 브랜치와 CI가 존재하기 전에는 이슈 기반 흐름을 적용할 수 없다. 다음 구간만 예외로 둔다.

| 작업 | 처리 |
|---|---|
| B-01 (레포 초기화) | 이슈·PR 없이 `main`에 직접 커밋 가능 |
| B-02 이후 | `dev` / `backend` 브랜치를 만들고 **브랜치 보호 규칙을 켠 뒤** 정상 흐름 진입 |
| B-03, B-04 | 정상 흐름. 단 B-04 완료 전이라 CI가 없으므로 로컬 `./gradlew test` 통과로 대신한다 |

**B-04(CI 파이프라인)가 끝나는 순간부터 예외는 종료된다.** 이후 어떤 작업도 이슈·브랜치·PR을 건너뛰지 않는다.

### 8.9 1인 개발 조정

이 프로젝트는 상당 기간 1인 + Claude Code 체제로 진행된다. 이 절은 그 상황에 맞춘 조정이며, **완화가 아니라 대체**다.

#### 끄는 것

| 규칙 | 처리 | 이유 |
|---|---|---|
| 승인 리뷰 1인 이상 | **끈다** | GitHub는 본인 PR 자가 승인을 허용하지 않는다. 켜면 본인이 본인 작업에 막혀 아무것도 머지할 수 없다 |
| Draft PR 단계 | **선택** | 조기 CI 노출 목적이면 유지, 방향 확인 목적이면 불필요 |
| `task` 이슈 본문 상세 작성 | **간소화 가능** | §12에 이미 정의된 `B-xx`는 "§12 참조"로 갈음하고, **§12와 달라진 부분만** 적는다 |

`bug` / `safety` / `decision` 이슈는 간소화하지 않는다. 이 셋은 §12에 정의가 없으므로 이슈 본문이 유일한 기록이다.

#### 승인 리뷰를 대체하는 것

**리뷰어 대신 시간 간격을 쓴다.** PR을 연 직후에 읽는 diff는 방금 쓴 코드라 눈이 미끄러진다.

| 대체 장치 | 규칙 |
|---|---|
| **시간 간격 셀프 리뷰** | PR을 연 뒤 **최소 30분**, 가능하면 다음 날 diff를 처음부터 끝까지 읽는다. 이걸 하지 않은 PR은 머지하지 않는다 |
| **CI 필수 status check** | B-04 이후 빌드·테스트·gitleaks를 **필수 조건**으로 건다. 승인 리뷰를 뺀 자리를 이것이 메운다 |
| **에이전트 산출물 전수 확인** | Claude Code가 작성한 PR은 사람이 diff 전체를 읽는다. 에이전트는 잘못된 지시에 반문하지 않는다 |

#### 작업 단위와 대기 시간 조정 (ADR-0004)

64개 작업 × (이슈 + 브랜치 + PR + 30분 대기 + CI 왕복)은 그 자체로 큰 비용이다. **30분 대기만 64회면 32시간이다.** 아래는 그 조정이며, **완화가 아니라 재배분이다.**

| 항목 | 조정 |
|---|---|
| **수직 슬라이스 하나 = 이슈 하나** | §8.1의 "`B-xx` 하나 = 이슈 하나"에 대한 예외. 근거는 ADR-0004. **PR 400줄 제한은 그대로 유지한다** — 실질적인 크기 제어는 이쪽이고, 이슈 단위를 키운 만큼 더 중요해진다 |
| **30분 셀프 리뷰 간격** | 스코프별 차등. `ai` · `safety` · `play/engine`은 **유지**, `config` · `infra` · 스캐폴딩은 **면제** |
| **푸시 전 로컬 게이트** | `./scripts/preflight.sh` — `test` + `integrationTest` + `gitleaks protect --staged`. 푸시→대기→실패→수정 왕복을 없앤다 |

**면제하지 않는 것 — "에이전트 산출물 diff 전수 확인".** 이것은 대기 시간이 아니라 **읽는 행위**이고, 실패 비용이 가장 크다. 에이전트는 잘못된 지시에 반문하지 않는다. 스코프와 무관하게 사람이 diff를 처음부터 끝까지 읽는다.

> 로컬 게이트는 **CI의 대체가 아니라 예고**다. 머지 차단의 근거는 여전히 CI다. `--no-verify`로 우회할 수 있는 것을 신뢰의 근거로 삼지 않는다.

#### 그래도 유지하는 것

- **PR 없이 `backend`/`dev`/`main`에 푸시하지 않는다.** PR은 리뷰 장치이기 이전에 **CI 게이트**다. 직접 푸시는 시크릿 스캔을 건너뛴다
- 이슈 → 브랜치 → 커밋 → PR 순서 (§8.1)
- 브랜치 수명 3일, PR 400줄 (§8.1) — 혼자일수록 브랜치가 길어지므로 더 필요하다
- `Closes #` 로 PR과 이슈를 잇는다 — 3개월 뒤 "왜 이렇게 했는지"의 유일한 근거다
- §13 `[결정 필요]` 항목에 부딪히면 `decision.yml` 이슈를 연다

#### 절대 완화하지 않는 것

시크릿 스캔 · 세이프티 이슈 P0 · 커밋 전 검증(§8.1) · S-11(공개 레포 전제).
이 넷은 인원수와 무관하게 실패 비용이 되돌릴 수 없는 항목이다.

#### 브랜치 보호 설정값

| 브랜치 | PR 필수 | 승인 리뷰 | status check | force push |
|---|---|---|---|---|
| `main` | O | **끔** | B-04 이후 필수 | 금지 |
| `dev` | O | **끔** | B-04 이후 필수 | 금지 |
| `backend` | O | **끔** | B-04 이후 필수 | 금지 |

**팀원이 합류하는 시점에 승인 리뷰를 다시 켠다.** 이 절은 그때 삭제한다.

---

## 9. 코딩 컨벤션

### 9.1 API

- 경로: `/api/v1/...`, kebab-case 아님 — 상위 문서 명세를 그대로 따른다(`/authoring/drafts/{id}/precheck`).
- 요청/응답 JSON: **camelCase**. DB 컬럼: **snake_case**. 변환은 Jackson 설정으로 일괄 처리한다.
- 에러 응답은 **항상 동일 형태**:
  ```json
  { "error": "TURN_CONFLICT", "message": "...", "details": { } }
  ```
- 클라이언트는 `error` 코드로 문구를 매핑한다. **서버 `message`를 UI에 그대로 쓴다고 가정하지 않는다.**
- 시간은 전부 **UTC ISO-8601**(`2026-08-22T11:20:00Z`). 만 나이 계산만 KST 기준.

### 9.2 계층

```
Controller  → 요청 검증, DTO 변환만. 비즈니스 로직 금지
Service     → 트랜잭션 경계. 도메인 조합
Domain      → 엔티티, 값 객체, 도메인 규칙
Repository  → 영속화만
Facade      → 모듈 간 유일한 통로
```

- 트랜잭션 안에서 **외부 HTTP 호출(AI Provider)을 하지 않는다.** 턴 파이프라인은 "짧은 트랜잭션 → 외부 호출 → 짧은 트랜잭션"으로 쪼갠다. 25초짜리 트랜잭션은 커넥션 풀을 고갈시킨다.
- `@Transactional(readOnly = true)`를 조회에 기본 적용한다.

### 9.3 null / 옵셔널

- 도메인에서 `null` 반환 금지. `Optional` 또는 예외.
- API 응답의 nullable 필드(`speakerName`, `sceneImage`, `endingId`, `reachRate`)는 **키를 생략하지 않고 `null`로 명시**한다. 프론트가 키 존재 여부로 분기하지 않게 한다.

### 9.4 로깅

```java
log.info("turn.generated sessionId={} turnNo={} provider={} latencyMs={} inputTokens={} outputTokens={}",
         ...);
```

- 구조화 로그. 문장형 로그 금지.
- **프롬프트 원문·응답 원문을 애플리케이션 로그에 남기지 않는다** (S-3). `ai_call_log`로만.
- 세이프티 차단은 `WARN`, 반복 차단(동일 세션 3회 이상)은 `ERROR` + 알람.

### 9.5 ADR

기술 선택을 바꾸거나 §6 불변 규칙에 예외를 두려면 `docs/adr/NNNN-title.md`를 먼저 작성하고 PR로 승인받는다.

```markdown
# ADR-0007: 요약 압축을 별도 큐로 분리
## 상태: 제안 | 승인 | 폐기
## 맥락
## 결정
## 결과 (되돌리기 비용 포함)
```

---

## 10. 테스트 규칙

| 종류 | 대상 | 도구 |
|---|---|---|
| 단위 | 엔진(GameState/Chapter/Ending), 정규화기, 파서, 토큰 계산 | JUnit 5 |
| 통합 | Repository, 트랜잭션, 마이그레이션 | Testcontainers(Postgres, Redis) |
| 계약 | Provider 어댑터 | WireMock 고정 응답 |
| E2E | 턴 파이프라인 전체 | `FixedStoryProvider` (결정론적 스텁) |

### 10.1 필수 테스트 (없으면 머지 불가)

1. **결정론 테스트** — 동일 GameState + 동일 Provider 응답 → 항상 동일한 Chapter/Ending 판정 (I-15)
2. **clamp 테스트** — `affinity +100` 응답이 `+5`로 잘리는가 (R4.2)
3. **화이트리스트 테스트** — `state_schema`에 없는 키가 무시되는가 (R4.1)
4. **AI 필드 잠금 테스트** — AI가 `chapter`/`turn`을 반환해도 무시되는가 (I-9)
5. **페이로드 유출 테스트** — 이메일·생년월일·`player_ref`가 AI 요청에 들어가면 요청이 중단되는가 (I-3)
6. **프롬프트 오버라이드 테스트** — UGC `world_prompt`에 "이전 지시를 무시하라"를 넣어도 SYSTEM이 유지되는가 (I-7)
7. **멱등성 테스트** — 동일 `Idempotency-Key` 동시 2회 요청 시 Provider 호출이 1회인가 (R6.2)
8. **낙관적 잠금 테스트** — 동일 `turnNo` 동시 요청 중 1건만 성공하는가 (I-6)
9. **타임아웃 테스트** — 25초 초과 시 세션 상태가 변하지 않는가 (R6.4)
10. **세이프티 차단 테스트** — 즉시차단 카테고리에서 재생성이 일어나지 않는가 (R9.2, §4.8)
11. **기본 엔딩 테스트** — 어떤 조건도 안 걸릴 때 무한 진행되지 않는가 (R2.2, R7.7)
12. **버전 고정 테스트** — 진행 중 세션이 새 버전에 영향받지 않는가 (R2.1, R8.8)
13. **연령 게이트 테스트** — 생일 경계값(만 15세 되기 하루 전/당일) (R10.2)
14. **UGC 노출 테스트** — `pending`/`rejected`/`private` 작품이 타인 조회에 절대 안 뜨는가 (I-8)

### 10.2 Provider 스텁

E2E에서 실제 AI를 호출하지 않는다. `FixedStoryProvider`가 시나리오 파일(`src/test/resources/scenarios/*.json`)을 읽어 정해진 순서로 응답한다. **AI 비결정성을 테스트에 들이지 않는다.**

프롬프트 조립 결과는 **골든 파일 테스트**로 고정한다. 프롬프트가 바뀌면 골든 파일 diff가 리뷰에 노출되어야 한다.

---

## 11. 에러 코드 카탈로그

| HTTP | code | 발생 | 클라이언트 처리 |
|---|---|---|---|
| 400 | `CONSENT_REQUIRED` | `birthDate`/`consents[]` 누락 | 동의 화면 |
| 400 | `INVALID_CHOICE` | `choiceId` 불일치 또는 disabled | 화면 새로고침 |
| 400 | `VALIDATION_ERROR` | 일반 입력 검증 실패 | 필드 오류 표시 |
| 401 | `UNAUTHENTICATED` | 토큰 없음/만료 | 재로그인 |
| 403 | `AGE_RESTRICTED` | 만 15세 미만 | 가입 불가 안내 |
| 403 | `FORBIDDEN` | 소유자 아님 / 권한 없음 | |
| 404 | `NOT_FOUND` | 리소스 없음 | |
| 409 | `TURN_CONFLICT` | `turnNo` 불일치 | 현재 턴으로 동기화 |
| 409 | `CONCURRENT_GENERATION` | 계정당 동시 생성 1개 초과 | 대기 안내 |
| 409 | `SESSION_ALREADY_ACTIVE` | 작품당 active 세션 중복 | 이어하기 유도 |
| 422 | `SAFETY_BLOCKED` | L2 차단 | **SafetyBlocked 화면 (§4.8)** |
| 423 | `STORY_SUSPENDED` | 작품 정지 | 읽기 전용 안내 |
| 429 | `RETRY_COOLDOWN` | 연속 실패 3회 (`retryAfterSeconds`) | 재시도 비활성 |
| 429 | `RATE_LIMITED` | 분당 호출 초과 | 잠시 후 재시도 |
| 429 | `QUOTA_EXCEEDED` | 일일 토큰/생성 한도 초과 | 한도 안내 |
| 500 | `CONTEXT_BUDGET_EXCEEDED` | 토큰 예산 초과 (내부 결함) | 일반 오류 |
| 500 | `INTERNAL_ERROR` | 처리되지 않은 예외 (내부 결함) | 일반 오류 |
| 502 | `PROVIDER_ERROR` | Provider 실패/파싱 2회 실패 | Error 화면 |
| 504 | `GENERATION_TIMEOUT` | 25초 초과 | Error 화면 (다시 시도/다른 선택/나중에) |

`RETRY_COOLDOWN`·`RATE_LIMITED`·`QUOTA_EXCEEDED`는 모두 429다. **클라이언트는 `code`로 구분해야 하므로 셋을 하나로 합치지 않는다.**

`INTERNAL_ERROR`는 **예상하지 못한 예외의 폴백**이다(B-03 신설). 이 코드가 없으면 처리되지 않은 예외가 프레임워크 기본 에러 본문(예외 메시지·요청 경로 포함)으로 나가 S-6을 위반한다. `CONTEXT_BUDGET_EXCEEDED`는 원인이 특정된 내부 결함이므로 폴백으로 재사용하지 않는다. **의도적으로 이 코드를 던지지 않는다** — 나가는 순간 서버 결함이며, 로그(`ERROR` + 스택트레이스)와 알람으로 추적한다.

이 표는 `common/error/ErrorCode` enum과 **정확히 일치**해야 하며, `ErrorCodeTests`가 그것을 강제한다. 코드를 추가·삭제하면 표와 테스트를 함께 고친다.

---

## 14. 자주 하는 실수 (Claude Code 주의)

| 실수 | 올바른 처리 |
|---|---|
| AI 응답의 `chapterAdvanceSuggested`로 챕터를 넘김 | 서버 엔진이 판정 (I-10) |
| AI 응답의 `choices`를 그대로 저장·반환 | `choiceId` 발급 + `disabled` 서버 판정 후 정규화 (I-1, I-11) |
| `stateChanges`를 그대로 GameState에 병합 | 화이트리스트 필터 → clamp → 병합 (R4.1, R4.2) |
| 세이프티 차단 응답에 "다시 시도" 액션 포함 | `choose_other` / `leave`만 (R9.5) |
| 차단 사유를 사용자에게 상세 노출 | 카테고리도 노출하지 않는다 (R9.6) |
| `progressPercent` 계산해서 반환 | `chapterNo` / `totalChapters` / `progressHint` (R7.5) |
| 도달률을 조회 시점에 계산 | 배치 집계값 조회. 50건 미만이면 `null` (I-20) |
| 트랜잭션 안에서 Provider 호출 | 트랜잭션 분리 (§9.2) |
| `application-test.yml` 생성 | Testcontainers + `@DynamicPropertySource` (§7.2) |
| `${API_KEY:sk-...}` 기본값 사용 | 기본값 없이. 부팅 실패시킨다 (§7.3) |
| 스키마 간 JOIN 쿼리 작성 | 파사드 호출로 조합 (§5.3) |
| 비-Identity 테이블에 `userId` 저장 | `playerRef`만 (I-3) |
| 세션 요약·스냅샷 UPDATE | append-only. 롤백 불가해진다 (I-5) |
| 엔딩 조건 평가에 `Random` 사용 | 결정론적 평가만 (I-15) |
| 신규 `*.yml` 생성 후 커밋 | §7.2 확인. 예외 2종 외 금지 |

---

## 15. 참고 문서

| 문서 | 위치 | 역할 |
|---|---|---|
| **작업 목록** | **`docs/tasks.md`** | **§12 원문 + MVP 수직 슬라이스 순서 (v1.1 분할)** |
| **정정 사항** | **`docs/corrections.md`** | **§13 원문. 구현 시 상위 문서보다 우선한다 (v1.1 분할)** |
| 기획서 | `docs/너와다음.md` (v2.1) | 제품 결정 D1~D4, 정책 |
| 백엔드 요구사항 | `docs/backend-requirements.md` (v0.3) | 요구사항 ID(R·P) 원본 |
| 와이어프레임 | `너와다음 Wireframes.dc.html` | 화면 상태 목록, 필드 근거 |
| API 계약 | `docs/openapi.yaml` | **런타임 진실의 원천** |
| ADR | `docs/adr/` | 기술 결정 이력 |
| 런북 | `docs/runbook/` | 운영 대응 절차 |

**충돌 시 우선순위**: `docs/corrections.md`(구 §13) > `openapi.yaml` > `backend-requirements.md` > `너와다음.md`

> 우선순위 자체는 v1.0 과 같다. **§13 이 `docs/corrections.md` 로 이동했을 뿐이다**(v1.1). `CLAUDE.md` 본문(§0~§11, §14)은 이 우선순위 표의 대상이 아니라 **프로젝트 헌법**이며 그 위에 있다.

---

*이 문서는 살아 있는 문서다. `docs/corrections.md`(구 §13)의 `[결정 필요]` 항목이 해소되면 해당 절을 갱신하고 버전을 올린다.*
