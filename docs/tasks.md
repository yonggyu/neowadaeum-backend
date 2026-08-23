# 작업 목록 — `CLAUDE.md` §12

> **이 파일은 `CLAUDE.md` §12 를 그대로 옮긴 것이다.** 분할 시점(v1.1)에 내용을 고치지 않았다.
> 이후의 순서 재배열은 아래 "MVP 수직 슬라이스" 절에만 추가로 기록한다 — **작업 정의는 손대지 않는다.**
>
> 규칙·불변 규칙(§6)·보안 규칙(§7)의 진실의 원천은 여전히 `CLAUDE.md` 다.
> 이 파일은 **해당 작업 항목만 인용해 전달한다.** 전문을 매번 로드하지 않는다.

---

## 12. 구현 우선순위 — 작업 목록

각 작업은 **이슈 1개 + 브랜치 1개**에 대응한다. Claude Code에게는 작업 번호로 지시한다: *"B-23 수행"*.

의존(`⇐`)이 완료되지 않은 작업은 시작하지 않는다.

### 단계 0 — 레포 셋업 (개발 순서 ①)

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-01** | 레포 초기화 & 보안 규칙 적용 | `.gitignore`(§7.2), `.env.example`, `application.yml.template`, `docker-compose.yml` + `docker/postgres/init/01-init-schemas.sh`(§2.5), `README.md`. Initializr 잔재(`compose.yaml`, `application.properties`, `HELP.md`) 제거 | ① `git status`의 `*.yml`이 `docker-compose.yml` + `.github/**` 뿐 ② `git check-ignore -v gradle/wrapper/gradle-wrapper.properties`가 무시되지 않음 ③ `./gradlew bootRun`으로 **Postgres·Redis 컨테이너 기동 + 스키마 4개 생성** 확인. **앱이 끝까지 뜨는 것은 조건이 아니다(§2.5)** | — |
| **B-02** | Gradle 스켈레톤 & 패키지 구조 | §5.2 패키지 트리, Spring Boot 4.1 부팅, actuator health, **Spring Modulith 경계 검증 테스트** | `./gradlew bootRun`으로 기동, `/actuator/health` 200, `ApplicationModules.verify()` 통과, **`spring-modulith-starter-jpa` 미포함 확인(§2.5)** | B-01 |
| **B-03** | 공통 웹 계층 | `ErrorCode` enum(§11 전체), `GlobalExceptionHandler`, 공통 응답, 요청 ID MDC, 구조화 로깅 | §11 모든 코드가 enum에 존재하고 핸들러 테스트 통과 | B-02 |
| **B-04** | Git/CI 파이프라인 | `.github/workflows/ci.yml`(빌드·테스트·gitleaks), `.gitmessage.txt`, `.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/` 4종 + `config.yml`, 라벨 생성, 브랜치 보호 문서 | PR 생성 시 CI 3잡 통과. 시크릿 심어 스캔 실패 확인. 이슈 생성 시 4종 폼만 보이고 빈 이슈가 막힘 | B-01 |
| **B-04-1** | 연결 이슈 자동 종료 워크플로 | `.github/workflows/close-linked-issues.yml` | `backend` 대상 PR 머지 시 닫기 키워드로 연결된 이슈가 코멘트와 함께 닫힘. 머지 없이 닫은 PR은 무동작, 이미 닫힌 이슈는 코멘트 중복 없음, 없는 번호는 잡 성공. 액션이 커밋 SHA로 고정 | B-04 |
| **B-05** | 4-스토어 DataSource 분리 + Flyway | §5.3의 DataSource 4개, Flyway 4세트, 스키마별 계정 | 통합 테스트에서 4개 스키마 마이그레이션 성공. 크로스 스키마 FK 0건. **`spring.autoconfigure.exclude`의 `DataSourceAutoConfiguration` 블록 삭제 확인(§2.5)** | B-02 |
| **B-05-1** | 스토어별 EntityManagerFactory / TransactionManager 4벌 | EMF 4벌, TransactionManager 4벌, 스토어별 `@EnableJpaRepositories`, `packagesToScan` 고정 | 한 EMF에 다른 모듈 패키지가 들어가면 실패하는 테스트. 크로스 스키마 조인이 매핑 단계에서 거부됨. **B-07의 선행 조건** | B-05 |

> **B-05-1이 왜 필요한가.** B-05는 DataSource 4개까지를 범위로 하고 JPA 자동설정을 **제외**한다. `@Primary` DataSource 하나로 자동설정을 통과시키면 EntityManagerFactory가 1벌 생기고, B-07 이후 네 스키마의 엔티티가 그 하나에 묶여 **JPQL 한 줄로 크로스 스키마 조인이 가능해진다.** B-05의 FK 검증은 FK만 보므로 이 경로를 잡지 못한다. 그래서 EMF 분할을 별도 작업으로 세우고 **B-07의 선행 조건**으로 둔다. 엔티티가 먼저 생기면 자동설정이 붙을 자리를 찾게 되고 그 우회로 되돌아간다.

> **B-04-1이 왜 필요한가.** 기본 브랜치는 `main`인데 작업 머지는 `backend`로 간다(§8.2). GitHub는 **기본 브랜치로 머지된** PR의 닫기 키워드만 처리하므로 `feat/* → backend` 머지에서는 이슈가 열린 채 남는다. 사람이 매번 기억해야 하는 절차는 반드시 빠지므로 워크플로로 대신한다.

### 단계 1 — API 계약 & 데이터 모델 (개발 순서 ②③)

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-06** | OpenAPI 계약 확정 | `docs/openapi.yaml`(수기 작성, 계약 우선), springdoc 연동 | 상위 문서 13장의 모든 엔드포인트·필드가 스펙에 존재. **§13의 정정 사항 반영** | B-03 |
| **B-07** | Identity 스키마 & 엔티티 | `user`, `oauth_identity`, `consent_log`, `ai_notice_impression`(신설) | 마이그레이션 + 엔티티 매핑 테스트 | B-05-1 |
| **B-08** | Catalog 스키마 & 엔티티 | `story`, `story_version`, `character`, `chapter_def`, `ending_def`, `genre`, `story_genre`, `author_profile`(신설), `ending_stat` | **§13-1 정정 반영**: `character`/`chapter_def`/`ending_def`는 `story_version_id`를 FK로 갖는다. `is_default` partial unique index 존재 | B-05-1 |
| **B-09** | Session 스키마 & 엔티티 | `play_session`, `turn`, `game_state_snapshot`, `story_summary` | 작품당 active 세션 1개 partial unique index. 스냅샷·요약에 `deleted_at` 존재(롤백용) | B-05-1 |
| **B-10** | Authoring 스키마 & 엔티티 (P0: 스키마만) | `story_draft`, `story_review`, `content_report`, `blocklist_entry` | 마이그레이션 통과. **기능 구현은 B-40 이후.** `blocklist_entry`는 **authoring 소유 / catalog 스키마**(ADR-0002) | B-05-1 |
| **B-11** | Prompt Log / Audit 스키마 | `ai_call_log`(신설 정의 §13-4), `admin_audit_log`, `access_audit_log`, `service_config` | 별도 스키마·별도 DataSource로 분리 확인 | B-05-1 |

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
| **B-30** | Safety L2 판정기 | 별개 모델 호출 + 블록리스트 조회 SPI(`common/spi`) + 정규화기, 카테고리별 정책(즉시차단 vs 재생성 1회) | I-12, I-13 테스트. 즉시차단에서 재생성 미발생 확인. **SPI 미주입 시 부팅 실패 · 조회 실패 시 차단**(ADR-0002) | B-24 |
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
| **B-39** | `ending_stat` 배치 집계 | 스케줄 배치(batch) + **집계 SPI 를 catalog·play 가 구현**(`common/spi`) | 실시간 계산 경로 0건(I-20). **batch 가 catalog·play 를 직접 참조하지 않음**(ADR-0003) | B-29 |

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
| **B-49** | 블록리스트 관리 | `POST /admin/blocklist`, 정규화 저장, 운영 중 갱신, **authoring 의 `common/spi` 조회 구현** | R2.5, R9.4. **갱신 → 캐시 무효화 경로가 존재**(ADR-0002) | B-31, B-40 |
| **B-50** | precheck (L0) | `POST /authoring/drafts/{id}/precheck` — `{state, findings:[{field, span, kind, message}]}` | span 정확도 테스트. 분당 20회 제한 | B-49, B-24 |
| **B-51** | 드래프트 CRUD | `POST/PATCH /authoring/drafts` 5단계 저장 | `blocked` 상태에서 서버가 다음 단계 거부(R8.3) | B-50, B-10 |
| **B-52** | 챕터·엔딩 AI 초안 | `POST /authoring/drafts/{id}/outline` — 챕터 5 + 엔딩 3 | 초안 결과도 검수 대상(R7.15). 조건은 템플릿 선택만(R7.16) | B-51, B-24 |
| **B-53** | 미리보기 세션 | `POST /authoring/drafts/{id}/preview` — `is_test_session`, 3턴 자동 종료 | §13-5 결정에 따라 임시 버전 발행 방식 구현 | B-52, B-32 |
| **B-54** | 제출 & 자동 검수 (L1) | `POST /authoring/drafts/{id}/submit`, 상태 머신 | 반려 사유는 카테고리만 노출(R8.7) | B-53 |
| **B-55** | 인간 검수 큐 | `GET /admin/reviews`, `POST /admin/reviews/{id}/verdict` | `public`은 인간 검수 필수(R8.6). `unlisted→public` 재검수 트리거 | B-54, B-40 |
| **B-56** | 게시 & 버전 발행 | 승인 시 `story_version` 발행, 진행 중 세션 영향 없음 | §10.1-12 통과 | B-55 |
| **B-57** | 신고 API (L3) | `POST /reports`, 누적 3건 자동 정지, 중복 신고자 제외 | R8.9. IP 기준 rate limit(S-8) | B-54 |
| **B-58** | 정지 처리 & 읽기 전용 | `suspended` 세션 읽기 전용, `423 STORY_SUSPENDED`, `story_suspended` resume 상태 | R8.10, R13.3 | B-57, B-17 |
| **B-59** | 사후 검수 배치 | 랜덤 샘플링(R8.11), 블록리스트 갱신 시 승인작 재스캔(R9.4), **재스캔 SPI 를 authoring 이 구현** | 배치 실행 결과가 검수 큐에 적재 — **적재 주체는 authoring**(ADR-0003) | B-55 |
| **B-60** | UGC 비용 통제 | 일일 `draftOutline` 호출·미리보기 턴·작품 개수 상한 | R8.12 | B-52, B-38 |

### 단계 9 — 운영 / 배포 (개발 순서 ⑬)

| # | 작업 | 산출물 | 완료 조건 | 의존 |
|---|---|---|---|---|
| **B-61** | 데이터 파기 배치 | 프롬프트 로그 90일, 세션 만료 90일, 탈퇴 시 삭제·익명화, `player_ref` 매핑 파기. **파기 SPI 를 ai·play·identity·admin 이 각각 구현**(ADR-0003) | R12.4, R12.5. **실제로 지워지는지 테스트.** 약관 문구와 파기 주기 일치 확인 | B-11 |
| **B-62** | 탈퇴 & UGC 예외 처리 | 탈퇴 시 공개 UGC 처리 정책 구현 | §13-9 결정에 따름. 약관 문구와 일치 | B-61, B-56 |
| **B-63** | 배포 파이프라인 | 이미지 빌드(시크릿 미포함, S-2), 마이그레이션 순서, 롤백 절차 | 스테이징 무중단 배포 1회 성공 | B-04 |
| **B-64** | 운영 런북 | `docs/runbook/` — Provider 장애, 세이프티 오탐 급증, 비용 폭주, 유출 대응 | 각 시나리오별 1페이지 | B-48 |

### 12.1 개발 순서 ①~⑬ 매핑

| 개발 순서 | 작업 |
|---|---|
| ① 백엔드 기술/구조 설계 | B-01 ~ B-05-1 |
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

---

# MVP 수직 슬라이스 (ADR-0004, 이슈 #32)

> **위 §12 의 작업 정의는 하나도 바뀌지 않았다. 바뀐 것은 순서뿐이다.**
> 제외된 작업은 취소가 아니라 **연기**이며, 각각 복귀 조건을 아래에 적었다.

## 왜 재배열하는가

§12 는 수평 순서다 — 스키마 5개 → 인증 6개 → AI 8개 → 엔진. 각 층이 완결되지만
**어느 시점에도 "돌아가는 것"이 없고**, 문서의 핵심 가정이 B-32 까지 한 번도 검증되지 않는다.

| 가정 | 출처 | §12 순서에서의 최초 검증 | 슬라이스에서 |
|---|---|---|---|
| 컨텍스트 4,000토큰 예산 | §4.4 | B-20 (20번째) | **S-6** |
| Provider 25초 / 동기 28초 | §1, §4.3 | B-32 (32번째) | **S-9** |
| 8레이어 프롬프트 구조 | §3.3 | B-20 | **S-6** |
| L2 를 생성 모델과 별개 판정기로 (I-12) | §6 | B-30 | **S-8** |

§12 순서대로면 그 사이 31개 작업의 코드가 미검증 가정 위에 쌓인다. 가정이 틀리면 되돌리는 범위가 31개다.

## 슬라이스 순서 — B-32 까지 10개

| # | 작업 | §12 매핑 | 내용 | 의존 |
|---|---|---|---|---|
| **S-1** | play 스토어 단독 등록 | B-05 확장 | DataSource/Flyway 팩터리를 파라미터화. 등록 대상은 설정으로 정한다 | B-05 |
| **S-2** | Session 스키마 | B-09 축소 | `play_session` · `turn` · `game_state_snapshot`. `story_summary` 는 S-10 이후 | S-1 |
| **S-3** | **`FixedStoryProvider`** | **B-44 선행** | 시나리오 파일 기반 결정론 Provider. **B-22 보다 먼저 만든다** | S-1 |
| **S-4** | 시드 데이터 축소판 | B-45 축소 | Flyway 시드 SQL 1벌 — 작품 1편 / 챕터 3 / 엔딩 2 / 캐릭터 1 | S-2 |
| **S-5** | GameState 엔진 | B-26 | 화이트리스트 · clamp · `chapter`/`turn` 잠금 · 스냅샷 | S-2 |
| **S-6** | 조건 평가기 | B-27 | Condition DSL. 난수 없음 (I-15) | S-5 |
| **S-7** | Chapter · Ending 엔진 | B-28, B-29 | §4.5 · §4.6. AI 제안값 무시 | S-6 |
| **S-8** | 정규화기 + 규칙 기반 L2 | B-31 (+ B-30 축소) | 정규화 수렴 + 블록리스트 대조. **생성 모델과 별개 판정기** | S-3 |
| **S-9** | **Turn 오케스트레이터** | **B-32** | §4.3 13단계 전체 | S-5·S-7·S-8 |
| **S-10** | dev 플레이 콘솔 | B-47 | `dev` 프로파일 전용 단일 HTML | S-9 |

> **S-2 가 B-05-1 의 일부를 흡수했다 (#39).** 위 표의 "S-2 ⇐ S-1" 에는 선행이 하나 빠져 있었다 —
> §12 에서 B-09 의 의존은 **B-05-1**(스토어별 EMF/TransactionManager, #20)이고 그것은 미착수다.
> 엔티티만 먼저 넣으면 JPA 자동설정이 붙을 자리를 찾아 EMF 1벌이 생기고, 네 스키마의 엔티티가 거기
> 묶여 JPQL 한 줄로 크로스 스키마 조인이 열린다(ADR-0004 결정 2의 근거와 같은 경로다).
> 그래서 **play EMF·TransactionManager 1벌을 S-2 에 포함**했다.
>
> **#20 은 닫지 않는다.** 나머지 3벌과 "다른 스토어 엔티티를 참조하는 JPQL 이 매핑 단계에서 거부된다"
> 는 검증은 **비교 대상 엔티티가 없어 지금은 성립하지 않는다.** B-07 / B-08 / B-11 복귀 시점에 완성한다.

> **S-4 가 catalog 최소 테이블을 흡수했다 (#48).** 위 표의 "S-4 ⇐ S-2" 는 시드만 가리키지만
> `catalog` 스키마에는 도메인 테이블이 하나도 없다 — `V1__baseline.sql` 은 *"도메인 테이블은
> B-08 · B-10 에서 추가한다"* 고 적힌 의도적 빈 베이스라인이고 B-08 은 제외돼 있다.
> 제외표의 대체 수단이 "Flyway 시드 SQL" 이므로 **시드가 들어갈 테이블 5종**
> (`story` · `story_version` · `chapter_def` · `ending_def` · `character`)을 S-4 에 포함했다.
>
> **B-08 은 닫지 않는다.** `genre` · `story_genre` · `ending_stat` · `service_config` ·
> `author_profile` · `content_report` · `blocklist_entry` 와 catalog 모듈의 파사드·엔티티·
> 리포지토리·조회 API 는 전부 범위 밖이며, 복귀 조건은 아래 제외표의 B-08 행 그대로다.
> 컬럼 이름과 구성은 **`backend-requirements.md` §2.3 원문을 그대로 따랐다**(비공개 원문,
> `docs/internal/`). 원문이 규정하지 않은 두 가지는 스키마로 고정하지 않고 `[결정 필요]` 로 올렸다 —
> `docs/corrections.md` §13-15(`slug` 유일성) · §13-16(`ending_def.condition` 과 `is_default` 의 관계).

## 제외한 작업과 복귀 조건

| §12 | 제외 이유 | 슬라이스에서의 대체 | **복귀 조건** |
|---|---|---|---|
| **B-06** OpenAPI | 계약을 굳히기 전에 파이프라인이 먼저 검증돼야 한다 | 없음 — dev 콘솔이 직접 호출 | **프론트가 붙기 전.** S-9 완료 후 실제 응답 형태로 작성하면 정확도가 오른다 |
| **B-07** Identity | 인증이 슬라이스 밖 | `dev` 프로파일 `playerRef` 고정 | **B-12 착수 시.** 그 전에 `prod` 활성화 차단 테스트가 있어야 한다 |
| **B-08** Catalog | 작품 데이터가 시드로 충분 | Flyway 시드 SQL | **B-15(Library) 착수 시** |
| **B-10** Authoring | UGC 는 P2 | 없음 | **B-49 착수 시** (ADR-0002 의 소유 결정 적용) |
| **B-11** Prompt Log | `ai_call_log` 는 실 Provider 가 있어야 의미가 있다 | 구조화 로그만 (원문 미기록, S-3) | **B-22 착수 시** |
| **B-12~17** 인증·조회 API | 슬라이스 밖 | `dev` 고정 `playerRef` + 시드 | **B-12 착수 시** |
| **B-22·23** 실 Provider | `FixedStoryProvider` 로 파이프라인 검증이 가능 | S-3 | **S-9 통과 직후.** 어댑터는 검증된 파이프라인에 끼우는 것이 안전하다 |

## 대체 수단의 안전 조건

**1. `dev` 프로파일 인증 우회 — `prod` 에서 활성화 불가를 테스트로 강제한다**

고정 `playerRef` 는 편의가 아니라 **인증 우회**다. B-47 이 `prod` 에서 404 여야 하는 것과 같은 이유이며,
그보다 위험하다. 우회 빈이 `prod` 에서 생성되지 않는다는 것을 테스트가 보장해야 한다.

**2. `FixedStoryProvider` 는 `prod` Provider 등록 경로에 섞이지 않는다** (R3.1, I-14)

**3. 미구현 경로는 `UnsupportedOperationException`** (§0.2)

스텁으로 "일단 통과"시키지 않는다. **이 슬라이스는 스텁이 아니라 축소된 실물이다** —
`FixedStoryProvider` 는 정해진 응답을 정확히 돌려주고, 규칙 기반 L2 는 실제로 차단한다.

## 규칙 위반 여부

| 규칙 | 확인 |
|---|---|
| **I-12** (L2 는 생성 모델과 별개 판정기) | **위반 아님.** 규칙 기반 판정기도 별개 판정기다. 모델 기반 L2(B-30)는 나중에 얹는다 |
| **I-13** (provider 무관 서버 판정) | 유지. `FixedStoryProvider` 응답도 L2 를 거친다 |
| **I-15** (난수 금지) | 유지. `FixedStoryProvider` 는 결정론이다 |
| **§0.2** (스텁 금지) | 위반 아님 — 축소된 실물. 미구현은 예외를 던진다 |
| **§10.1** | **1 · 2 · 3 · 4 · 6 · 11 이 이 슬라이스에서 전부 살아난다** |

§10.1 중 5(페이로드 유출)·7(멱등성)·8(낙관적 잠금)·9(타임아웃)·10(세이프티)·12(버전 고정)·
13(연령)·14(UGC)는 대상 코드가 슬라이스 밖이거나 ADR-0001 의 nightly 분류를 따른다.
