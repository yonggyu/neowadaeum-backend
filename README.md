# 너와다음 Backend

AI 인터랙티브 스토리 플랫폼 **너와다음**의 백엔드 레포지토리다.
API 서버 + AI Gateway + Safety + Admin을 소유한다. 프론트엔드는 별도 레포(`neowadaeum-frontend`)다.

> **작업 전에 [`CLAUDE.md`](./CLAUDE.md)를 반드시 읽는다.** 이 문서는 실행 방법만 다루고,
> 규칙·불변 규칙(§6)·보안 규칙(§7)·작업 목록(§12)의 진실의 원천은 `CLAUDE.md`다.

---

## 요구 환경

| | |
|---|---|
| JDK | 21 (LTS) |
| Docker | Docker Desktop 또는 Docker 데몬이 **실행 중**이어야 한다 |
| DB / Cache | **로컬에 설치하지 않는다.** 앱 기동 시 컨테이너가 자동으로 뜬다 (§2.5) |

## 최초 1회 셋업

### 0. 레포 위치 — **툴체인과 경로를 같은 쪽에 둔다** (Windows + WSL 사용자만 해당)

느린 것은 특정 경로가 아니라 **툴체인과 경로가 엇갈린 조합**이다.
`/mnt/c/Users/...` 는 **WSL 이 읽을 때만** 9p 프로토콜을 탄다. Windows 입장에서 `C:\Users\...` 는
네이티브 NTFS 경로이고 아무 손해가 없다.

| 조합 | `./gradlew test` (`--rerun`) | `./gradlew integrationTest` | |
|---|---|---|---|
| **Windows 툴체인 → `C:\...`** | 4.4s | 11.8s | ✅ **이 프로젝트의 기본** |
| WSL 툴체인 → `~/...` (ext4) | 3.0s | 8.8s | ✅ 조금 더 빠르다 |
| WSL 툴체인 → `/mnt/c/...` (9p) | 16.8s | 35.9s | ❌ **섞인 조합. 3~4배 느리다** |

> WSL 쪽은 `n=5`(순서 무작위, 중앙값), Windows 쪽은 `n=3`(Gradle 데몬 워밍업 후).

**이 프로젝트는 Windows 툴체인 + `C:\` 를 기본으로 한다.** IntelliJ 가 Windows 쪽 JDK 와 Gradle 로
빌드하므로 그 조합이 자연스럽고, 위 표대로 충분히 빠르다.

**지키면 되는 규칙은 하나다.**

- 레포를 `C:\...` 에 뒀다면 → **빌드·테스트도 Windows 쪽에서** 돌린다 (IntelliJ, `gradlew.bat`)
- 레포를 WSL 홈(`~/...`)에 뒀다면 → **빌드·테스트도 WSL 안에서** 돌린다
- **WSL 셸에서 `/mnt/c` 의 `./gradlew` 를 돌리는 것만 피한다.** 이것이 위 표의 마지막 줄이다

macOS · Linux 네이티브 환경에는 해당하지 않는다.

### 1. 설정 파일

```bash
cp .env.example .env
# .env 의 값을 채운다.
#   ※ POSTGRES_PORT 와 *_DB_URL 의 포트가 어긋나면 조용히 다른 DB에 붙는다. 함께 맞춘다.

cp src/main/resources/application.yml.template src/main/resources/application.yml
```

`.env` 와 `application.yml` 은 **커밋되지 않는다**(§7.2). 실제 값은 절대 소스에 넣지 않는다.

### 2. pre-commit 훅

```bash
git config core.hooksPath .githooks
```

스테이지된 변경에서 시크릿을 찾는다(S-1). **한 번 클론당 한 번** 해야 한다 — `.git/hooks` 는
클론으로 전파되지 않는다. `gitleaks` 가 없으면 훅은 경고만 하고 넘어가며, 막는 근거는 여전히 CI 다.

## 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

**`dev` 프로파일을 지정해야 뜬다.** 결정론 Provider(`FixedStoryProvider`) · dev 플레이 콘솔 ·
계약 문서 경로가 전부 `dev & !prod` 이고, Provider 가 하나도 등록되지 않으면 기동이 멈춘다.
표현식이 셋 다 같으므로 **`dev` 하나만 켜면 전부 해결된다.**

> **의도된 설계다.** dev 전용 경로는 *"명시적으로 켤 때만 존재"* 해야 한다(ADR-0004). 프로파일
> 지정을 빠뜨린 배포에서 그것들이 조용히 살아나는 것을 막는다.
>
> 인증은 프로파일과 무관하다. **`dev` 에서도 토큰 없이는 401** 이다 — 고정 `player_ref` 우회는
> B-12 가 제거했다(#34).

`spring-boot-docker-compose`가 `docker compose up`을 대신 실행하고, `healthcheck`가 통과할 때까지
기다린 뒤 애플리케이션을 띄운다. IDE의 Run 버튼도 동일하게 동작한다.

- `lifecycle-management: start-only` — 앱을 꺼도 컨테이너는 살아 있다. 정리하려면 `docker compose down`.
- Docker 데몬이 꺼져 있으면 기동이 실패한다. **이 오류는 애플리케이션 버그가 아니다.**

## 4개 스키마

§5.3의 4-스토어 분리는 **컨테이너 1개 안의 스키마 4개**로 시작한다.

| 스토어 | 스키마 | 계정 | 마이그레이션 |
|---|---|---|---|
| Identity | `identity` | `identity_user` | `db/migration/identity` |
| Catalog + Authoring | `catalog` | `catalog_user` | `db/migration/catalog` |
| Session | `play` | `play_user` | `db/migration/play` |
| Prompt Log | `promptlog` | `promptlog_user` | `db/migration/promptlog` |

DataSource·Flyway·이력 테이블(`flyway_schema_history`)이 스토어마다 따로 있다.
**각 계정은 자기 스키마에만 권한을 갖는다.** 스키마 간 JOIN 을 쓴 코드는 로컬에서 곧바로 권한 오류로
터진다 — 운영에서 발견하는 것보다 낫다. 스키마 간 FK 도 만들지 않는다(§5.3).

접속 URL 에는 `?currentSchema=<스키마>` 가 반드시 있어야 한다. 없으면 부팅이 실패한다.
빠진 채로 뜨면 모든 테이블이 조용히 다른 스키마에 만들어진다.

### 마이그레이션 파일 명명 규칙

| | |
|---|---|
| 파일명 | `V<번호>__<snake_case>.sql` |
| 번호 | **스토어별로 독립.** 이슈 번호가 아니라 **그 스토어의 마지막 번호 + 1** |
| 충돌 | 리베이스로 번호를 다시 매긴다. **이미 머지된 마이그레이션은 수정하지 않는다** |

번호가 스토어별로 독립인 이유는 이력 테이블이 스키마마다 따로 있기 때문이다.
`identity/V2__`와 `play/V2__`는 서로 무관하다. **같은 스토어 안에서만 겹치면 안 된다.**

> **실패 시 증상.** Flyway 기본값이 `outOfOrder=false` · `validateOnMigrate=true`다.
> 두 브랜치가 같은 스토어에 각각 `V2__`를 만들어 차례로 머지되면, **두 번째 머지 이후 기동이
> 순서·체크섬 검증 실패로 막힌다.** 증상은 애플리케이션 버그처럼 보이지만 원인은 번호다.
> 나중에 머지된 쪽의 파일을 **새 번호로 다시 매기고 재기동**한다. 이미 적용된 마이그레이션의
> 내용이나 번호를 고치면 체크섬이 어긋나 더 나빠진다.

초기화는 `docker/postgres/init/01-init-schemas.sh`가 담당하며, **볼륨이 비어 있을 때 한 번만** 실행된다.
스크립트나 `.env`의 스키마 계정 비밀번호를 고쳤다면 아래로 다시 만든다.

```bash
docker compose down -v && ./gradlew bootRun
```

시드 데이터(B-45)는 이 스크립트가 아니라 **Flyway**로 관리한다.

## 테스트

```bash
./gradlew test              # 빠른 루프 — 컨테이너 없이 도는 것만
./gradlew integrationTest   # 컨테이너 테스트 (Docker 필요)
./gradlew test integrationTest   # 전부. CI 가 이렇게 돈다
```

**태그로 나뉘어 있다.** `container` 는 "Docker 가 필요한가", `nightly` 는 "PR 마다 돌 만한가"다.
둘은 직교하며, 한 테스트가 둘 다일 수 있다.

| 태스크 | 도는 것 | Windows + `C:\` | WSL + `~/` |
|---|---|---|---|
| `test` | 컨테이너도 nightly 도 아닌 것 | 4.4s | 3.0s |
| `integrationTest` | `@Tag("container")` | 11.8s | 8.8s |
| `nightlyTest` | `@Tag("nightly")` | 대상 0건 (B-32 이후 생긴다) | — |

코드 한 줄만 고친 증분 실행은 어느 쪽이든 **약 1~2초**다. 위 수치는 `--rerun` 기준이다.

나눈 이유는 하나다. 저장할 때마다 30초를 기다리게 되면 결국 테스트를 덜 돌린다.

**검증을 줄인 것이 아니다.** CI 는 `test integrationTest` 를 모두 돌리고(§8.9 — CI 가 승인 리뷰를
대체한다), nightly 는 별도 워크플로가 매일 돌린다. 어떤 항목도 "안 돌린다"가 되지 않는다.
분류 근거 · 승격 시점 · 복귀 조건은 [`docs/adr/0001-mvp-test-execution-policy.md`](docs/adr/0001-mvp-test-execution-policy.md).

**PR 을 올리기 전에 한 번은 `./gradlew test integrationTest` 를 돌린다.**

컨테이너 테스트에 관해:

- Docker 데몬이 필요하다. WSL 에서 돌린다면 Docker Desktop 의 WSL 통합을 켠다.
  (`docker` CLI 가 PATH 에 없어도 `/var/run/docker.sock` 만 있으면 Testcontainers 는 동작한다.)
- 테스트 컨테이너는 로컬과 **같은 이미지 태그**(`docker-compose.yml` 에서 읽는다)와
  **같은 초기화 스크립트**를 쓴다. 스키마 4개·계정 4개가 그대로 만들어진다.
- 컨테이너는 1개다. 스토어마다 컨테이너를 띄우면 계정 권한 경계가 검증되지 않는다.
- docker-compose 는 건너뛴다(`spring.docker.compose.skip.in-tests: true`).
  `application-test.yml` 을 만들지 않는다 — `@DynamicPropertySource` 로 런타임 주입한다(§7.2).

### 더 빠르게 — Ryuk 끄기 (선택, 로컬 전용)

Testcontainers 는 Ryuk 이라는 정리용 컨테이너를 함께 띄운다. 로컬에서 끄면 실행마다 약 0.5초를
아낀다(8.69s → 8.15s, `n=5` 중앙값).

```bash
# 셸 환경에 둔다. .env 가 아니다 — .env 는 애플리케이션 설정이지 테스트 실행 환경이 아니다 (§7.1).
export TESTCONTAINERS_RYUK_DISABLED=true
```

> **CI 에서는 절대 끄지 않는다.** Ryuk 은 JVM 이 비정상 종료했을 때 남는 컨테이너를 치우는 장치다.
> 러너에서 끄면 크래시한 실행의 고아 컨테이너가 쌓인다. 로컬에서는 `docker ps` 로 눈에 보이고
> 직접 지울 수 있지만 러너에서는 그렇지 않다. 이 비대칭이 의도된 것이다.

WSL 안에서 테스트를 돌리는 경우에만 해당한다. Windows 쪽에서 돌린다면 같은 변수를
`setx` 또는 시스템 환경 변수로 둔다.

0.5초짜리 조정이다. **위 "최초 1회 셋업 0" 의 조합부터 맞추는 것이 먼저다** — 그쪽은 20초 이상이다.

## 보안 규칙 (요약, 원문은 §7)

- 민감 정보를 소스에 커밋하지 않는다. 실제 값은 `.env`에만 둔다.
- `${VAR:실제값}` 기본값 패턴 금지. 값이 없으면 부팅이 실패해야 한다.
- 새 `*.yml`을 만들지 않는다. 예외는 `docker-compose.yml`과 `.github/**/*.yml`(CI·이슈 템플릿·PR 설정) 둘뿐이다.
- 이미 커밋된 파일은 `.gitignore` 추가만으로 빠지지 않는다 — `git rm --cached <파일>`.
- 노출된 자격 증명은 **로테이션한다.** 커밋을 되돌려도 유출은 취소되지 않는다.

## 기여

이슈 → 브랜치 → 작은 단위 커밋 → PR (§8). `main` / `dev` / `backend`에 직접 푸시하지 않는다.

```
feat/#12-turn-orchestrator
fix/#31-turn-conflict-race
```

---

## 라이선스

© 2026 너와다음. All rights reserved.
이 저장소는 열람 목적으로 공개되어 있으며, 별도 라이선스를 부여하지 않습니다.
