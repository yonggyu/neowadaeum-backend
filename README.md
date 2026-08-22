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

```bash
cp .env.example .env
# .env 의 값을 채운다.
#   ※ POSTGRES_PORT 와 *_DB_URL 의 포트가 어긋나면 조용히 다른 DB에 붙는다. 함께 맞춘다.

cp src/main/resources/application.yml.template src/main/resources/application.yml
```

`.env` 와 `application.yml` 은 **커밋되지 않는다**(§7.2). 실제 값은 절대 소스에 넣지 않는다.

## 실행

```bash
./gradlew bootRun
```

`spring-boot-docker-compose`가 `docker compose up`을 대신 실행하고, `healthcheck`가 통과할 때까지
기다린 뒤 애플리케이션을 띄운다. IDE의 Run 버튼도 동일하게 동작한다.

- `lifecycle-management: start-only` — 앱을 꺼도 컨테이너는 살아 있다. 정리하려면 `docker compose down`.
- Docker 데몬이 꺼져 있으면 기동이 실패한다. **이 오류는 애플리케이션 버그가 아니다.**

## 4개 스키마

§5.3의 4-스토어 분리는 **컨테이너 1개 안의 스키마 4개**로 시작한다.

| 스토어 | 스키마 | 계정 |
|---|---|---|
| Identity | `identity` | `identity_user` |
| Catalog + Authoring | `catalog` | `catalog_user` |
| Session | `play` | `play_user` |
| Prompt Log | `promptlog` | `promptlog_user` |

초기화는 `docker/postgres/init/01-init-schemas.sh`가 담당하며, **볼륨이 비어 있을 때 한 번만** 실행된다.
스크립트나 `.env`의 스키마 계정 비밀번호를 고쳤다면 아래로 다시 만든다.

```bash
docker compose down -v && ./gradlew bootRun
```

시드 데이터(B-45)는 이 스크립트가 아니라 **Flyway**로 관리한다.

## 테스트

```bash
./gradlew test
```

테스트는 docker-compose를 건너뛰고 **Testcontainers**를 쓴다(`spring.docker.compose.skip.in-tests: true`).
`application-test.yml`을 만들지 않는다 — `@DynamicPropertySource`로 런타임 주입한다(§7.2).

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
