# CLAUDE.md — 너와다음 Backend

> 이 파일은 **백엔드 레포지토리 루트**에 위치한다. Claude Code는 모든 작업 전에 이 문서를 읽고, 여기 명시된 규칙을 프로젝트 헌법으로 취급한다.
> 프론트엔드는 **별도 레포지토리**다. 이 문서는 프론트엔드 구현을 다루지 않는다. **AI 파이프라인은 이 레포에 포함된다.**

| | |
|---|---|
| 문서 버전 | v1.0 |
| 작성일 | 2026-08-22 |
| 상위 문서 | `너와다음.md` v2.1 (기획서), `backend-requirements.md` v0.3 (요구사항) |
| 레포 | `neowadaeum-backend` (API 서버 + AI Gateway + Safety + Admin) |
| 관련 레포 | `neowadaeum-frontend` (별도) |

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
| 모듈 경계 | **Spring Modulith** | §5.4 의존 규칙을 테스트로 강제한다. 문서로만 있는 경계는 반드시 깨진다 |
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
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

- **B-01 완료 조건**은 "컨테이너 자동 기동 + 스키마 4개 생성"까지다. 앱이 초록으로 뜨는 것은 B-01의 조건이 아니다.
- **B-02~B-04**는 이 제외 덕분에 `/actuator/health` 200을 볼 수 있다.
- **B-05**에서 위 블록을 지우지 않으면 DataSource가 4개 정의돼 있어도 JPA가 붙지 않는다. B-05의 DoD에 "제외 블록 삭제 확인"이 들어가는 이유다.

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
│   └── support/       Clock, UuidGenerator, TextNormalizer
├── identity/          회원·OAuth·동의·연령 게이트
├── catalog/           작품·버전·챕터·엔딩·캐릭터·장르
├── authoring/         UGC 드래프트·검수·신고·블록리스트
├── play/              세션·턴·히스토리
│   ├── engine/        GameStateEngine, ChapterEngine, EndingEngine
│   └── orchestrator/  TurnOrchestrator (§4.3 파이프라인)
├── ai/                ★ AI 파이프라인
│   ├── gateway/       AiGateway, PayloadWhitelistValidator, FallbackChain
│   ├── provider/      StoryProvider 인터페이스 + anthropic/ openai/ ollama/
│   ├── prompt/        PromptAssembler, 레이어 빌더, TokenBudget
│   ├── schema/        TurnOutput DTO, 파서, 스키마 검증
│   └── log/           AiCallLogWriter
├── safety/            L0~L3 판정기, 블록리스트, 카테고리 정책
├── admin/             디버그·롤백·재생성·검수 큐·감사 로그
├── batch/             ending_stat 집계, 세션 만료, UGC 재스캔, 로그 파기
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
safety    ← (도메인 모듈 참조 X)
admin     ← 전 모듈(O)
```

- `ai` 패키지는 **도메인 엔티티를 알지 못한다.** `TurnRequest` / `TurnResult` DTO만 주고받는다. 이것이 R12.1(회원 식별정보 미포함)을 구조적으로 보장한다.
- 모듈 간 호출은 `XxxFacade` 인터페이스로만. 다른 모듈의 Repository·Entity를 직접 참조하면 리뷰에서 반려한다.

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
| 502 | `PROVIDER_ERROR` | Provider 실패/파싱 2회 실패 | Error 화면 |
| 504 | `GENERATION_TIMEOUT` | 25초 초과 | Error 화면 (다시 시도/다른 선택/나중에) |

`RETRY_COOLDOWN`·`RATE_LIMITED`·`QUOTA_EXCEEDED`는 모두 429다. **클라이언트는 `code`로 구분해야 하므로 셋을 하나로 합치지 않는다.**

---

## 12. 구현 우선순위 — 작업 목록

각 작업은 **이슈 1개 + 브랜치 1개**에 대응한다. Claude Code에게는 작업 번호로 지시한다: *"B-23 수행"*.

의존(`⇐`)이 완료되지 않은 작업은 시작하지 않는다.

### 단계 0 — 레포 셋업 (개발 순서 ①)

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-01** | 레포 초기화 & 보안 규칙 적용 | `.gitignore`(§7.2), `.env.example`, `application.yml.template`, `docker-compose.yml` + `docker/postgres/init/01-init-schemas.sh`(§2.5), `README.md`. Initializr 잔재(`compose.yaml`, `application.properties`, `HELP.md`) 제거 | ① `git status`의 `*.yml`이 `docker-compose.yml` + `.github/**` 뿐 ② `git check-ignore -v gradle/wrapper/gradle-wrapper.properties`가 무시되지 않음 ③ `./gradlew bootRun`으로 **Postgres·Redis 컨테이너 기동 + 스키마 4개 생성** 확인. **앱이 끝까지 뜨는 것은 조건이 아니다(§2.5)** | — |
| **B-02** | Gradle 스켈레톤 & 패키지 구조 | §5.2 패키지 트리, Spring Boot 4.1 부팅, actuator health, **Spring Modulith 경계 검증 테스트** | `./gradlew bootRun`으로 기동, `/actuator/health` 200, `ApplicationModules.verify()` 통과 | B-01 |
| **B-03** | 공통 웹 계층 | `ErrorCode` enum(§11 전체), `GlobalExceptionHandler`, 공통 응답, 요청 ID MDC, 구조화 로깅 | §11 모든 코드가 enum에 존재하고 핸들러 테스트 통과 | B-02 |
| **B-04** | Git/CI 파이프라인 | `.github/workflows/ci.yml`(빌드·테스트·gitleaks), `.gitmessage.txt`, `.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/` 4종 + `config.yml`, 라벨 생성, 브랜치 보호 문서 | PR 생성 시 CI 3잡 통과. 시크릿 심어 스캔 실패 확인. 이슈 생성 시 4종 폼만 보이고 빈 이슈가 막힘 | B-01 |
| **B-05** | 4-스토어 DataSource 분리 + Flyway | §5.3의 DataSource 4개, Flyway 4세트, 스키마별 계정 | 통합 테스트에서 4개 스키마 마이그레이션 성공. 크로스 스키마 FK 0건. **`spring.autoconfigure.exclude`의 `DataSourceAutoConfiguration` 블록 삭제 확인(§2.5)** | B-02 |

### 단계 1 — API 계약 & 데이터 모델 (개발 순서 ②③)

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-06** | OpenAPI 계약 확정 | `docs/openapi.yaml`(수기 작성, 계약 우선), springdoc 연동 | 상위 문서 13장의 모든 엔드포인트·필드가 스펙에 존재. **§13의 정정 사항 반영** | B-03 |
| **B-07** | Identity 스키마 & 엔티티 | `user`, `oauth_identity`, `consent_log`, `ai_notice_impression`(신설) | 마이그레이션 + 엔티티 매핑 테스트 | B-05 |
| **B-08** | Catalog 스키마 & 엔티티 | `story`, `story_version`, `character`, `chapter_def`, `ending_def`, `genre`, `story_genre`, `author_profile`(신설), `ending_stat` | **§13-1 정정 반영**: `character`/`chapter_def`/`ending_def`는 `story_version_id`를 FK로 갖는다. `is_default` partial unique index 존재 | B-05 |
| **B-09** | Session 스키마 & 엔티티 | `play_session`, `turn`, `game_state_snapshot`, `story_summary` | 작품당 active 세션 1개 partial unique index. 스냅샷·요약에 `deleted_at` 존재(롤백용) | B-05 |
| **B-10** | Authoring 스키마 & 엔티티 (P0: 스키마만) | `story_draft`, `story_review`, `content_report`, `blocklist_entry` | 마이그레이션 통과. **기능 구현은 B-40 이후** | B-05 |
| **B-11** | Prompt Log / Audit 스키마 | `ai_call_log`(신설 정의 §13-4), `admin_audit_log`, `access_audit_log`, `service_config` | 별도 스키마·별도 DataSource로 분리 확인 | B-05 |

### 단계 2 — 기본 기능 (개발 순서 ④) · **P0**

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-12** | 인증 — Google OAuth + JWT | `/auth/oauth/google`, `/auth/refresh`, Security 설정 | 로그인→토큰→보호 API 접근 E2E 통과 | B-07 |
| **B-13** | 가입 연령 게이트 + 동의 | 생년월일 검증(만 15세), `consent_log` 기록, `403 AGE_RESTRICTED` | 경계값 테스트(§10.1-13) 통과 | B-12 |
| **B-14** | AI 사전 고지 & 표시 | `service_config` 기반 고지 문구 API, `ai_notice_impression` 기록, 턴 응답 `isAiGenerated` | 문구가 코드에 하드코딩되지 않음(R11.1). 노출 이력 기록 확인 | B-11, B-13 |
| **B-15** | Library API | `GET /library`, `GET /library/sections/{key}` | `authorType` 반환. 공식/사용자 섹션 분리(R13.1). p95 300ms | B-08 |
| **B-16** | Story Detail API | `GET /stories/{storyId}` | `ageRating` 상수 반환. `totalEndings`는 `is_secret=false`만 | B-08 |
| **B-17** | Session 생성/조회/삭제 | `POST /stories/{id}/sessions`(+`restart=true`), `GET .../resume`, `GET .../current`, `DELETE` | `sessionState` 5종 판정 전부 테스트. restart 시 기존 active → `abandoned` | B-09, B-16 |

### 단계 3 — AI Provider (개발 순서 ⑤) · **P0~P1**

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-18** | `StoryProvider` 인터페이스 & Gateway 골격 | 인터페이스(4메서드 + capabilities), `AiGateway`, 설정 기반 Provider 등록(R3.1) | 배포 없이 활성/비활성 전환 가능 확인 | B-03 |
| **B-19** | **페이로드 화이트리스트 검증기** | `PayloadWhitelistValidator` — 직렬화 직전 필드 검사, 위반 시 요청 중단 | §10.1-5 테스트 통과. **I-3 보장** | B-18 |
| **B-20** | 프롬프트 조립기 | `PromptAssembler`, 8레이어 빌더, 플랫폼 레이어 불변화(I-7), 토큰 계산기 | 골든 파일 테스트 존재. 예산 초과 시 §4.4 순서대로 축소 | B-18 |
| **B-21** | 출력 스키마 파서 & 정규화 | `TurnOutput` DTO, JSON 파싱, 스키마 검증, 1회 재요청(R5.8), **`choiceId` 서버 발급** | `choiceId`가 `{sessionId,turnNo,order}` 기반이며 세션 내 유일·재사용 불가 | B-20 |
| **B-22** | Anthropic 어댑터 | `AnthropicStoryProvider` | WireMock 계약 테스트 통과. 25s 타임아웃·취소 동작 | B-19 |
| **B-23** | Ollama 어댑터 + fallback 체인 | `OllamaStoryProvider`, `FallbackChain`, `ai_call_log.fallback_from` 기록 | `structuredOutput=false` 경로에서 2회 재요청 후 에러(R3.3) | B-22 |
| **B-24** | 용도별 모델 분리 설정 | 턴 생성 / 요약 / 검수 / 아웃라인 각각 model 설정 | 4개 용도가 서로 다른 모델을 쓰도록 설정 가능(R3.6) | B-18 |
| **B-25** | `ai_call_log` 기록 파이프라인 | 요청/응답 원문·usage·latency·cost·safety_flags 비동기 기록 | 애플리케이션 로그에 원문이 남지 않음(S-3) | B-11, B-18 |

### 단계 4 — Story Engine (개발 순서 ⑥) · **P0**

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-26** | GameState 엔진 | 화이트리스트 필터, clamp(±5 기본), `chapter`/`turn` 잠금, 스냅샷 저장 | §10.1-2,3,4 통과 | B-09 |
| **B-27** | 조건 평가기 (Condition DSL) | `{"all":[{"gte":["affinity.yuna",30]},{"has":["flags","first_talk"]}]}` 평가기 | 연산자: `all` `any` `not` `gte` `gt` `lte` `lt` `eq` `has` `turnGte`. **난수 없음**(I-15). 미정의 키 참조 시 false + 경고 로그 | B-26 |
| **B-28** | Chapter 엔진 | `ChapterEngine` — §4.5 로직 | AI 제안값 무시 테스트. `max_turns` 강제 전환 테스트 | B-27 |
| **B-29** | Ending 엔진 | `EndingEngine` — §4.6 로직, 기본 엔딩 폴백 | §10.1-11 통과. `endingIndex`/`totalEndings` 시크릿 제외 | B-27 |
| **B-30** | Safety L2 판정기 | 별개 모델 호출 + 블록리스트 + 정규화기, 카테고리별 정책(즉시차단 vs 재생성 1회) | I-12, I-13 테스트. 즉시차단에서 재생성 미발생 확인 | B-24 |
| **B-31** | 텍스트 정규화기 | 공백 제거·자모 분리·유사 문자/숫자 치환 | 공백 삽입형 · 숫자 치환형 · 자모 혼용형이 전부 동일 정규화 값으로 수렴 (R9.2). **실제 문자열은 테스트 픽스처에만 두고 문서에 적지 않는다 (S-11)** | B-03 |
| **B-32** | **Turn 오케스트레이터** ★최우선 | `POST /sessions/{id}/turns` — §4.3 13단계 전체 | §10.1의 1,5,6,7,8,9,10번 전부 통과. 트랜잭션 내 외부 호출 0건 | B-21,26,28,29,30 |
| **B-33** | 멱등성·동시성·쿨다운 | Redis 기반 Idempotency-Key, 계정당 동시 생성 락, 연속 실패 3회 쿨다운 | R6.2, R6.5 테스트. 중복 과금 0건 | B-32 |
| **B-34** | 요약 파이프라인 (비동기) | 턴 응답 이후 비동기 압축, 600토큰 초과 시 재압축 | 사용자 대기 시간에 미포함(R4.6) 확인 | B-32, B-24 |

### 단계 5 — 나머지 조회 API · **P1~P2**

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-35** | History API | `GET /sessions/{id}/history` 역순 커서 페이지네이션 | `choiceId` 미반환. `isPending` 정의 명시 | B-32 |
| **B-36** | My Stories API | `GET /me/sessions`, `GET /me/stories` | 쿼리 파라미터 값이 §13-6 정정안을 따름 | B-17 |
| **B-37** | Landing API | `GET /landing` | `isLoggedIn` 미반환(클라이언트 판단) | B-15 |
| **B-38** | Rate limit / Quota | 턴 분당 10, precheck 분당 20, 일일 토큰 한도, IP 기준 별도 제한(S-8) | 429 3종이 코드로 구분됨 | B-33 |
| **B-39** | `ending_stat` 배치 집계 | 스케줄 배치, 50건 미만 `null` 처리 | 실시간 계산 경로 0건(I-20) | B-29 |

### 단계 6 — 관리자 · **P1**

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-40** | Admin 보안 게이트 | `role=admin` + IP 허용목록 + 2FA, `admin_audit_log` 전건 기록 | S-4, R14.5, R14.6 | B-12, B-11 |
| **B-41** | Admin Debug 콘솔 API | `GET /admin/sessions/{id}/debug` — provider·model·gameState·summary·recentTurns·raw prompt/response·usage | 열람 시 `access_audit_log` 기록(S-5) | B-40, B-25 |
| **B-42** | Admin 재생성 / 롤백 | `regenerate`, `rollback` — 스냅샷·요약 **함께** 되돌림, soft delete 보존 | R14.4 테스트. 요약만 남는 상태 재현 불가 확인 | B-41, B-34 |
| **B-43** | Admin 자유입력 | `POST /admin/sessions/{id}/turns/free` | **L1 검수 통과 필수(I-17)**, `is_test_session=true`에서만 허용(I-18), `is_admin_free_input=true` 기록 | B-42, B-30 |

### 단계 7 — 테스트 & 플레이 검증 (개발 순서 ⑦⑧)

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-44** | 결정론 E2E 하네스 | `FixedStoryProvider` + 시나리오 파일, 전체 플레이 E2E | 시작→40턴→엔딩까지 실제 AI 없이 재현 | B-32 |
| **B-45** | 시드 데이터 | 공식 작품 1편(챕터 6 / 엔딩 5 / 캐릭터 3) 마이그레이션 또는 시더 | 로컬에서 즉시 플레이 가능 | B-08 |
| **B-46** | 부하 / 타임아웃 검증 | 동시 생성 제한, 25s 타임아웃, 커넥션 풀 고갈 시나리오 | p95 실측치를 `docs/perf/` 에 기록 → 스트리밍 도입 판단 근거 | B-33 |
| **B-47** | 임시 검증 UI (dev 전용) | `dev` 프로파일에서만 서빙되는 단일 HTML 플레이 콘솔 | **`prod` 프로파일에서 404**. 프론트 레포와 무관 | B-32 |
| **B-48** | 관측성 | 구조화 로그 + 메트릭(턴 지연·토큰·비용·차단율·에러율) + 알람 | 세이프티 차단율·Provider 실패율 대시보드 존재 | B-25 |

### 단계 8 — UGC (개발 순서 ⑦ 이후, 기능 오픈은 P2)

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-49** | 블록리스트 관리 | `POST /admin/blocklist`, 정규화 저장, 운영 중 갱신 | R2.5, R9.4 | B-31, B-40 |
| **B-50** | precheck (L0) | `POST /authoring/drafts/{id}/precheck` — `{state, findings:[{field, span, kind, message}]}` | span 정확도 테스트. 분당 20회 제한 | B-49, B-24 |
| **B-51** | 드래프트 CRUD | `POST/PATCH /authoring/drafts` 5단계 저장 | `blocked` 상태에서 서버가 다음 단계 거부(R8.3) | B-50, B-10 |
| **B-52** | 챕터·엔딩 AI 초안 | `POST /authoring/drafts/{id}/outline` — 챕터 5 + 엔딩 3 | 초안 결과도 검수 대상(R7.15). 조건은 템플릿 선택만(R7.16) | B-51, B-24 |
| **B-53** | 미리보기 세션 | `POST /authoring/drafts/{id}/preview` — `is_test_session`, 3턴 자동 종료 | §13-5 결정에 따라 임시 버전 발행 방식 구현 | B-52, B-32 |
| **B-54** | 제출 & 자동 검수 (L1) | `POST /authoring/drafts/{id}/submit`, 상태 머신 | 반려 사유는 카테고리만 노출(R8.7) | B-53 |
| **B-55** | 인간 검수 큐 | `GET /admin/reviews`, `POST /admin/reviews/{id}/verdict` | `public`은 인간 검수 필수(R8.6). `unlisted→public` 재검수 트리거 | B-54, B-40 |
| **B-56** | 게시 & 버전 발행 | 승인 시 `story_version` 발행, 진행 중 세션 영향 없음 | §10.1-12 통과 | B-55 |
| **B-57** | 신고 API (L3) | `POST /reports`, 누적 3건 자동 정지, 중복 신고자 제외 | R8.9. IP 기준 rate limit(S-8) | B-54 |
| **B-58** | 정지 처리 & 읽기 전용 | `suspended` 세션 읽기 전용, `423 STORY_SUSPENDED`, `story_suspended` resume 상태 | R8.10, R13.3 | B-57, B-17 |
| **B-59** | 사후 검수 배치 | 랜덤 샘플링(R8.11), 블록리스트 갱신 시 승인작 재스캔(R9.4) | 배치 실행 결과가 검수 큐에 적재 | B-55 |
| **B-60** | UGC 비용 통제 | 일일 `draftOutline` 호출·미리보기 턴·작품 개수 상한 | R8.12 | B-52, B-38 |

### 단계 9 — 운영 / 배포 (개발 순서 ⑬)

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-61** | 데이터 파기 배치 | 프롬프트 로그 90일, 세션 만료 90일, 탈퇴 시 삭제·익명화, `player_ref` 매핑 파기 | R12.4, R12.5. **실제로 지워지는지 테스트** | B-11 |
| **B-62** | 탈퇴 & UGC 예외 처리 | 탈퇴 시 공개 UGC 처리 정책 구현 | §13-9 결정에 따름. 약관 문구와 일치 | B-61, B-56 |
| **B-63** | 배포 파이프라인 | 이미지 빌드(시크릿 미포함, S-2), 마이그레이션 순서, 롤백 절차 | 스테이징 무중단 배포 1회 성공 | B-04 |
| **B-64** | 운영 런북 | `docs/runbook/` — Provider 장애, 세이프티 오탐 급증, 비용 폭주, 유출 대응 | 각 시나리오별 1페이지 | B-48 |

### 12.1 개발 순서 ①~⑬ 매핑

| 개발 순서 | 작업 |
|---|---|
| ① 백엔드 기술/구조 설계 | B-01 ~ B-05 |
| ② API 명세 확정 | B-06 |
| ③ DB / Entity 설계 | B-07 ~ B-11 |
| ④ Backend 기본 기능 | B-12 ~ B-17 |
| ⑤ AI Provider 구현 | B-18 ~ B-25 |
| ⑥ Story Engine 구현 | B-26 ~ B-34 (+ B-35 ~ B-43) |
| ⑦ 테스트 | B-44 ~ B-46, B-48 |
| ⑧ 임시 UI로 실제 플레이 검증 | B-47 |
| ⑨~⑫ 디자인·프론트·연결·QA | (프론트 레포). 백엔드는 계약 안정화 + B-49 ~ B-60 병행 |
| ⑬ 배포 | B-61 ~ B-64 |

### 12.2 마일스톤

| 마일스톤 | 정의 | 포함 |
|---|---|---|
| **M1 — 턴이 돈다** | 시드 작품 1편을 처음부터 엔딩까지 플레이 가능 | B-01 ~ B-34, B-44, B-45, B-47 |
| **M2 — 서비스 형태** | 로그인·라이브러리·이어하기·기록·관리자 | B-35 ~ B-43, B-46, B-48 |
| **M3 — 오픈 가능** | 법적 고지·파기 배치·배포·런북 | B-61 ~ B-64 |
| **M4 — UGC 오픈** | 저작·검수·신고 | B-49 ~ B-60 |

---

## 13. 상위 문서 검증 결과 — 정정 · 보완 사항

> 두 상위 문서를 교차 검증한 결과다. **구현 시 이 절이 상위 문서보다 우선한다.**
> 각 항목의 **기본 채택안**을 따르되, `[결정 필요]` 표시가 있으면 PR 본문에 그 사실을 명시한다.

> **공개 범위 결정 (2026-08-22).** 이 절의 대부분은 설계 정정이라 공개해도 무해하며, 오히려 구현에 필수다. 다만 **§13-12만 내부 문서로 분리**했다. 자동 검수만으로 승인되는 공개 범위와 그 보완책은 그대로 악용 경로가 된다.
>
> **git 히스토리는 되돌릴 수 없다.** 공개 커밋에 한 번 들어간 내용은 나중에 지워도 포크·아카이브·스크래퍼에 남는다. 그래서 "일단 올리고 나중에 숨긴다"는 선택지가 없다. 새로운 `[위험]` 항목이 생기면 **커밋 전에** 공개 여부를 판단한다 (S-11).

### 13-1. `[모순]` 챕터·엔딩·캐릭터가 버전에 묶여 있지 않다 — **중대**

`backend-requirements.md` §2.3에서 `character` / `chapter_def` / `ending_def`는 전부 `story_id`를 FK로 갖는다. 그런데 R2.1은 세션이 `story_version_id`를 고정 참조하고, R8.8은 "수정은 새 버전이 되며 진행 중 세션은 기존 버전을 계속 참조한다"고 한다.

**현재 스키마로는 이 보장이 불가능하다.** 작성자가 캐릭터 성격이나 엔딩 조건을 수정하면 진행 중인 모든 세션이 즉시 영향을 받는다. 버전 고정이 `world_prompt` 하나에만 걸린다.

**채택안**: `character` / `chapter_def` / `ending_def`의 FK를 `story_version_id`로 변경한다. `story_id`는 조회 편의를 위한 비정규화 컬럼으로만 유지한다. 새 버전 발행 시 세 테이블을 복제한다.

**부수 영향**: `ending_stat`은 `(story_id, ending_no)` 기준으로 집계해야 한다. `ending_id`(버전마다 달라짐)로 집계하면 버전 발행 때마다 도달률이 리셋된다.

### 13-2. `[모순]` Recent Turns 턴 수가 5인지 8인지 불일치

- 기획서 §2.2: "최근 5턴 원문"
- 요구사항 R4.5: "최근 8턴을 초과하면 초과분을 요약에 병합"
- 요구사항 R4.7: "최근 5턴의 `{turnNo, chosenChoiceText, paragraphsDigest}`를 원문으로 전달"

R4.7은 "원문"이라고 하면서 `paragraphsDigest`(요약본)를 보낸다고 해 용어도 자기모순이다.

**채택안**: 두 값은 서로 다른 것을 가리키는 것으로 정리한다.

- **요약 병합 기준 = 8턴**: `turn_no - 8`보다 오래된 턴이 요약에 병합된다 (R4.5 유지)
- **프롬프트 포함 = 최근 5턴** (R4.7 유지)
- **6~8턴 구간은 완충지대**: 요약에 아직 병합되지 않았고 프롬프트에도 들어가지 않는다. 요약 압축이 비동기(R4.6)라 지연될 수 있으므로 이 완충이 필요하다.
- **필드 명칭 정정**: `paragraphsDigest` → 최근 5턴 중 **최근 2턴은 `paragraphs` 원문**, 3~5턴은 **압축본(`paragraphsDigest`)**. 1,500토큰 예산 안에서 원문 5턴은 들어가지 않는다.

`[결정 필요]` 완충 구간 크기(8)와 원문/압축 경계(2)는 B-46 실측 후 조정한다.

### 13-3. `[모순]` `disabled` 판정 근거가 존재하지 않는다 — **중대**

R5.6과 I-11은 "`disabled`는 서버가 GameState 조건으로 판정한다"고 한다. 그러나 **선택지는 AI가 매 턴 새로 생성**하며, 출력 스키마의 `choices[]`에는 `{order, text}`만 있다. 서버가 참조할 조건이 어디에도 없다. 사전 정의된 챕터·엔딩과 달리 선택지는 사전 정의되지 않는다.

**채택안 (2단계)**

- **P0**: `disabled`는 **항상 `false`**, `disabledReason`은 `null`로 반환한다. API 계약에는 필드를 유지한다(프론트 계약 안정성). 게이팅 기능 없이 출시한다.
- **P1 이후**: AI 출력 스키마에 선택적 `requires` 필드를 추가한다.
  ```json
  { "order": 3, "text": "유나에게 고백한다", "requires": { "gte": ["affinity.yuna", 50] } }
  ```
  서버는 이 조건을 **`story_version.state_schema` 화이트리스트로 검증**한 뒤(미정의 키 참조 시 조건 폐기), B-27 평가기로 판정해 `disabled`를 결정한다. **판정 주체는 여전히 서버**이므로 I-11은 유지된다.

`[결정 필요]` P1 시점에 `requires` 도입 여부.

### 13-4. `[누락]` 문서에서 참조하지만 정의되지 않은 테이블

R3.7·R9.3·R12.3·R12.4가 `ai_call_log`를 참조하고, R11.1이 `service_config`, R14.5가 `admin_audit_log`를 참조하지만 **어디에도 스키마 정의가 없다.**

**채택안** — B-11에서 아래를 정의한다.

```
ai_call_log            (promptlog 스키마)
  id, session_id(nullable), draft_id(nullable), purpose(turn|summary|safety|outline),
  provider_id, model_id, fallback_from(nullable),
  request_raw(text), response_raw(text),
  input_tokens, output_tokens, latency_ms, cost_micro,
  safety_flags(jsonb), attempt_no, created_at
  ※ player_ref를 담지 않는다. session_id로만 역추적 가능

admin_audit_log        (promptlog 스키마)
  id, admin_user_id, action, target_type, target_id,
  payload(jsonb), ip_hash, created_at

access_audit_log       (promptlog 스키마)   -- S-5
  id, admin_user_id, resource(ai_call_log|story_draft), resource_id, created_at

service_config         (catalog 스키마)
  key, value(text), updated_by, updated_at
  ※ AI 고지 문구, 세이프티 임계값 상수 등

ai_notice_impression   (identity 스키마)   -- R11.3
  id, user_id, notice_version, surface(landing|library|detail|play), shown_at
```

`genre` / `story_genre`도 컬럼이 미정의다: `genre(id, key, label, display_order)`, `story_genre(story_id, genre_id)`.

### 13-5. `[누락]` UGC 미리보기 세션의 저장 대상이 없다

R8.13은 미리보기를 `is_test_session = true` 세션으로 만든다고 하지만, 미리보기 시점에는 **`story`도 `story_version`도 아직 존재하지 않는다**(드래프트 상태). `play_session.story_id` / `story_version_id`가 NOT NULL이면 저장이 불가능하다.

**채택안**: 미리보기 시 `story`를 `review_status = 'draft'` / `visibility = 'private'`로 즉시 생성하고, 임시 `story_version`을 발행한다. 제출·승인 시 정식 버전으로 승격한다. 이 방식이면 턴 파이프라인을 그대로 재사용할 수 있고, R2.3에 의해 타인 노출도 자동 차단된다.

### 13-6. `[모순]` 세션 상태값과 API 쿼리 파라미터 불일치

`play_session.status`는 `active|completed|abandoned|expired`인데, §13.7 API는 `GET /me/sessions?status=in_progress|completed`를 쓴다. `in_progress`라는 상태는 존재하지 않는다.

**채택안**: 쿼리 파라미터를 `status=active|completed`로 정정한다. `abandoned`/`expired`는 목록에서 제외한다.

### 13-7. `[누락]` UGC 작성자 닉네임을 저장할 곳이 없다

§13.3은 `authorDisplayName`(닉네임)을 반환한다고 하지만, `user` 테이블에 닉네임 컬럼이 없고 Catalog는 `player_ref`만 갖는다. 스토어 분리 원칙상 Catalog가 Identity를 조회할 수도 없다.

**채택안**: Catalog 스키마에 `author_profile(player_ref PK, display_name, updated_at)`을 둔다. 닉네임 설정 시 Identity가 Catalog 파사드로 동기화한다. 닉네임은 회원 식별정보가 아니라 **공개 표시명**이므로 Catalog 보관이 타당하다.

### 13-8. `[모순]` `consent_log`에 고지 노출 이력을 넣는 것은 부적절

R11.3은 "사전 고지 노출 이력을 `consent_log`에 기록한다"고 하지만, `consent_log`는 `consent_type` / `agreed_at`을 갖는 **동의** 기록이다. 고지 노출은 동의가 아니라 **표시 사실**이다. 섞으면 동의 이력의 법적 증빙력이 흐려진다.

**채택안**: `ai_notice_impression` 테이블로 분리한다(§13-4). `consent_log`의 `ai_notice` 타입은 "AI 고지를 읽고 동의함"에만 쓴다.

### 13-9. `[누락]` 상태 머신·제약이 명시되지 않은 항목

| 항목 | 채택안 |
|---|---|
| `review_status` 전이 | `draft → pending → (auto_rejected \| in_review) → (approved \| rejected)`, `approved → suspended → (approved \| rejected)`. §8.3 다이어그램의 "reject"는 자동 검수 반려이므로 `auto_rejected`로 기록하고 사용자에겐 `rejected`로 표시한다 |
| `is_default` 제약 | `CREATE UNIQUE INDEX ... ON ending_def(story_version_id) WHERE is_default` |
| `is_default` + `is_secret` | 동시 true 금지. CHECK 제약 |
| 작품당 active 세션 1개 | `CREATE UNIQUE INDEX ... ON play_session(player_ref, story_id) WHERE status = 'active'` |
| `restart=true` 처리 | 기존 active 세션을 `abandoned`로 전환 후 신규 생성 |
| `choiceId` 형식 | 세션 내 유일. `{turnNo}-{order}-{shortHash}` 권장. **이전 턴의 choiceId 재사용 불가** |
| `content_report` 중복 | `UNIQUE(reporter_ref, target_type, target_id)` — 동일인 반복 신고로 자동 정지를 유발할 수 없게 한다 |
| `story_summary`·`game_state_snapshot` 롤백 | 두 테이블에 `deleted_at` 추가. R14.4가 "함께 되돌린다"를 요구하므로 soft delete가 필수다 |
| `stateChanges` 연산자 | `<numericPath>: delta`, `flags.add: []`, `flags.remove: []`, `inventory.add: []`, `inventory.remove: []`, `location`, `timeOfDay`. **이 외 키는 무시** |
| `state_schema` 템플릿 | R4.4가 "플랫폼 템플릿 중 선택"을 요구하므로 `story_version.state_template_key`(`affinity`\|`flag`\|`numeric`) 컬럼 추가 |
| `isPending`(History) | 마지막 턴이며 `chosen_choice_id IS NULL`인 경우 true |
| UGC 작성자 탈퇴 | `[결정 필요]` — 약관 확정 전까지 기본값은 **공개 UGC를 `unlisted`로 강등하고 작성자명을 "탈퇴한 사용자"로 익명화**한다 |

### 13-10. `[모순]` 구현 우선순위의 의존성 역전

`backend-requirements.md` 부록 A는 Turn API를 P0에, Session/Resume API를 P1에 둔다. **Session 생성 없이 Turn을 호출할 수 없다.** §12에서는 B-17(Session)이 B-32(Turn)보다 앞서도록 정정했다.

### 13-11. `[모순]` MVP 범위와 API 명세 불일치

기획서 §9.1은 MVP 계정을 "소셜 로그인 1종(Google)"으로 한정하지만, 요구사항 §13.1에는 `/auth/email/signup`, `/auth/email/login`이 있다. 또한 `oauth_identity`에 `email_hash`만 있어 **이메일 로그인에 필요한 이메일 원본 저장 위치가 없다.**

**채택안**: MVP는 **Google OAuth만** 구현한다(B-12). 이메일 가입은 범위 밖으로 미루고, 도입 시 `user` 테이블에 암호화된 `email` + `password_hash`를 추가한다.

### 13-12. `[내부]` UGC 공개 범위별 검수 정책

이 항목의 상세는 **공개 문서에서 제외**했다. 자동 검수만으로 승인되는 공개 범위와 그에 대한 보완책(샘플링 비율·신고 임계값)이 그대로 악용 경로가 되기 때문이다 (S-11).

- 내부 문서: `docs/internal/13-12-ugc-review-policy.md` (`.gitignore` 대상, 별도 비공개 백업)
- **B-06 / B-54 / B-55 / B-57 / B-59 착수 전 반드시 참조한다.**
- 내부 문서 없이 이 작업들을 구현하면 검수 정책이 R8.6의 기본값으로만 구현된다. 그 상태로 UGC를 오픈하지 않는다.

### 13-13. `[사실확인]` 법령 서술 검증 결과

| 문서 서술 | 검증 |
|---|---|
| AI기본법 및 시행령 2026년 1월 22일 시행 | **정확** |
| 제31조 = 사전 고지 + AI 생성 사실 표시 의무 | **정확** (인공지능 투명성 확보 의무) |
| 위반 시 시정명령·과태료 | **정확** (최대 3,000만 원 수준으로 보도됨) |
| 표시 의무 트리거 = 다운로드·공유로 서비스 밖 유통 | **정확** |
| 기계 판독 방식만 쓸 경우 다운로드 단계 최소 1회 안내 | **정확** |
| "**후속 고시**가 2026년 7월 21일부터 적용 중" | **부정확.** 2026년 7월 21일은 **개정 법률 및 개정 시행령의 시행일**이다. 투명성 확보 가이드라인은 2026년 1월에 공개됐다. 문구를 "개정 법률·시행령이 2026년 7월 21일 시행"으로 정정할 것 |
| 조문 번호 "제31조" | **재확인 필요.** 개정 법률에서 조문 번호가 유지되는지 오픈 전 법률 검토 시 확인한다 |

§11.2(게임물 등급분류) 서술은 v0.3에서 이미 정정되었고, "유료 재화 + 확률 구조가 실질 기준"이라는 결론은 I-15/I-16으로 백엔드 규칙에 반영되어 있다. **백엔드가 지금 할 일은 R11.5~R11.8 준수뿐**이라는 문서의 결론에 동의한다.

> 이 절의 법령 서술은 공개 자료 정리이며 법률 자문이 아니다. 서비스 오픈 전 변호사 검토를 받는다.

### 13-14. `[보완]` 문서에 없지만 구현에 필요한 것

| # | 항목 | 처리 |
|---|---|---|
| a | **트랜잭션 경계** | 25초 AI 호출을 트랜잭션 안에 두면 커넥션 풀이 고갈된다. §9.2 규칙으로 명시 |
| b | **컨텍스트 예산 초과 시 동작** | 조용히 자르지 않고 실패시킨다. §4.4 |
| c | **동시 생성 락 실패 응답** | `409 CONCURRENT_GENERATION` 신설 (§11) |
| d | **429 3종 구분** | `RETRY_COOLDOWN` / `RATE_LIMITED` / `QUOTA_EXCEEDED`. 문서는 앞 둘만 언급 |
| e | **턴 번호 계약** | 요청 = 현재 턴, 응답 = 신규 턴. 문서에 명시되지 않아 혼동 위험이 크다. §4.3 |
| f | **관측성** | 세이프티 차단율·Provider 실패율·토큰 비용 대시보드. B-48 |
| g | **런북** | Provider 장애·세이프티 오탐 급증·비용 폭주·자격증명 유출. B-64 |
| h | **시드 데이터** | 엔진 검증에 완결된 작품 1편이 반드시 필요하다. B-45 |
| i | **골든 파일 프롬프트 테스트** | 프롬프트 변경이 리뷰에 노출되어야 한다. B-20 |
| j | **`ai_call_log` 원문 접근 감사** | R12.3이 요구하나 테이블이 없었다. §13-4 |

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
| 기획서 | `docs/너와다음.md` (v2.1) | 제품 결정 D1~D4, 정책 |
| 백엔드 요구사항 | `docs/backend-requirements.md` (v0.3) | 요구사항 ID(R·P) 원본 |
| 와이어프레임 | `너와다음 Wireframes.dc.html` | 화면 상태 목록, 필드 근거 |
| API 계약 | `docs/openapi.yaml` | **런타임 진실의 원천** |
| ADR | `docs/adr/` | 기술 결정 이력 |
| 런북 | `docs/runbook/` | 운영 대응 절차 |

**충돌 시 우선순위**: `CLAUDE.md §13` > `openapi.yaml` > `backend-requirements.md` > `너와다음.md`

---

*이 문서는 살아 있는 문서다. §13의 `[결정 필요]` 항목이 해소되면 해당 절을 갱신하고 버전을 올린다.*
