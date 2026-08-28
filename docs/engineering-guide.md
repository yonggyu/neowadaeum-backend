# 엔지니어링 가이드 — `CLAUDE.md` §1~§5 · §9 · §10 · §11 · §14

> `CLAUDE.md` 에서 옮겨온 원문이다(v1.2 분할, #35). **내용을 고치지 않았다.**
> 제품 개요 · 기술 스택 · 용어 · 핵심 플로우 · 아키텍처 · 코딩 컨벤션 · 테스트 · 에러 코드 · 자주 하는 실수.
>
> 항상 로드되는 것은 `CLAUDE.md` 뿐이다. 이 파일은 **필요한 절만 인용해 읽는다.**

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
play      ← catalog::query(O), ai::provider(O), safety::l2(O), identity(X)   // ADR-0005
authoring ← catalog(파사드 O), safety(O)
ai        ← (도메인 모듈 참조 X). 순수 DTO만 입출력받는다
safety    ← (도메인 모듈 참조 X). 블록리스트는 common/spi 로 주입받는다 (ADR-0002)
batch     ← common만. 배치 로직은 데이터 소유 모듈이 common/spi 를 구현한다 (ADR-0003)
admin     ← 도메인 전 모듈(O). batch 는 제외한다 — 아래 참조
config    ← 전 모듈(O). 배선 지점이며 도메인 로직을 갖지 않는다
```

- `ai` 패키지는 **도메인 엔티티를 알지 못한다.** `TurnRequest` / `TurnResult` DTO만 주고받는다. 이것이 R12.1(회원 식별정보 미포함)을 구조적으로 보장한다.
- **모듈 간 의존은 모듈 전체가 아니라 `@NamedInterface` 로 노출된 계약 패키지 하나씩만 열린다** (ADR-0005, ADR-0006). 방향은 둘로 갈린다.
  - **`play → catalog :: query` · `play → safety :: l2`** 는 단방향이다 (ADR-0005). §4.3 파이프라인이 7단계에서 L2 를 부르므로 §5.2 가 지정한 `play/orchestrator` 위치에서 그 호출이 성립해야 한다. **역방향(`safety → play`)은 허용하지 않는다.**
  - **턴 생성은 반대다 — `ai → play :: port`** (ADR-0006). 계약(`TurnGenerationPort` · `GeneratedTurn`)을 `play` 가 소유하고 `ai` 가 구현한다. **계약의 모양은 그것을 저장하고 응답하는 쪽에서 나오기 때문이다.** `play` 는 `ai` 를 참조하지 않으며, 한 줄이라도 남으면 양방향이 된다 — 그래서 시간 초과·스키마 소진 예외도 `play/port` 에 있다.
  - `ai` 에 열린 것은 **DTO 와 인터페이스뿐인 계약 패키지 하나**다. `play` 의 엔티티·Repository 는 닫혀 있고, I-3 의 구조적 보장은 그대로다.
- 모듈 간 호출은 `XxxFacade` 인터페이스로만. 다른 모듈의 Repository·Entity를 직접 참조하면 리뷰에서 반려한다.
- **`safety`와 `batch`는 파사드가 아니라 SPI를 쓴다.** 둘 다 자기가 필요한 데이터의 소유자가 아니면서 그 데이터를 참조할 수 없는 모듈이다. 인터페이스는 `common/spi`에 두고 **데이터를 소유한 모듈이 구현**하며, 호출자는 주입받는다. 방향이 반대라는 점이 요점이다 — `safety → authoring`이 아니라 `authoring → common/spi ← safety`다.
- **SPI 미주입 시 동작**: 구현 빈이 없으면 **부팅 실패**, 런타임 조회 실패는 **차단(fail-closed)** + `ERROR` + 알람. 세이프티에서 fail-open은 장애가 곧 검수 우회다 (I-2, ADR-0002).
- **`admin ← batch`를 허용하지 않는 이유**: S-10(감사 로그 파기) 때문에 `batch → admin`이 필요해지는 순간 양방향이 되어 순환이다. B-02가 `admin → batch`를 미리 끊어 그 가능성을 없앴다. `admin`의 "전 모듈"은 **도메인 모듈 전부**를 뜻하며 `batch`·`config`는 포함하지 않는다 (ADR-0003).

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
| 403 | `STORY_LIMIT_REACHED` | 계정당 작품 개수 상한 (B-60) | 날이 바뀌어도 늘지 않는다 — 기다리라고 안내하지 않는다 |
| 403 | `FORBIDDEN` | 소유자 아님 / 권한 없음 | |
| 404 | `NOT_FOUND` | 리소스 없음 | |
| 409 | `TURN_CONFLICT` | `turnNo` 불일치 | 현재 턴으로 동기화 |
| 409 | `CONCURRENT_GENERATION` | 계정당 동시 생성 1개 초과 | 대기 안내 |
| 409 | `SESSION_ALREADY_ACTIVE` | 작품당 active 세션 중복 | 이어하기 유도 |
| 409 | `ALREADY_EXISTS` | 같은 것이 이미 있다 (B-49 블록리스트) | 운영자에게만 나간다 |
| 409 | `REVIEW_NOT_PENDING` | 검수 대기 중이 아닌 작품에 판정이 왔다 (B-55) | 운영자에게만 나간다 |
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
