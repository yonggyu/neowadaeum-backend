# CLAUDE.md — 너와다음 Backend (v1.2)

사용자가 선택지로 이야기를 이끄는 **AI 인터랙티브 스토리 플랫폼**의 백엔드다. 한국 서비스, 15세 이용가 단일 등급.
본문은 매 턴 AI가 생성한다 — 지연·비용·비결정성이 상수다. 플레이 중 자유입력은 없고 사용자 입력면은 `choiceId` 하나뿐이다.

> **이 파일만 항상 로드된다.** 나머지는 필요할 때 경로로 찾아 읽는다. `@` import를 쓰지 않는 이유가 이것이다.

## Source of Truth

```
CLAUDE.md(헌법) > docs/corrections.md > docs/openapi.yaml > backend-requirements.md > 너와다음.md
```

`docs/corrections.md`(구 §13)가 상위 문서를 정정한다. 충돌하면 정정본이 이긴다.

### 비공개 원문 — `docs/internal/`

**요구사항 원문은 존재한다. 레포에 커밋되지 않을 뿐이다.** `docs/internal/` 은 `.gitignore` 대상이며(S-11, 공개 레포) 로컬에만 있다.

| 파일 | 내용 |
|---|---|
| `docs/internal/backend-requirements.md` | **R 조항 원문.** §2 도메인 모델(테이블 정의) · §3 Provider · §4 컨텍스트/GameState · §5 프롬프트·출력 스키마 · §6 턴 파이프라인 · §7 Chapter/Ending · §8 UGC · §9 세이프티 |
| `docs/internal/너와다음.md` | 기획서 |
| `docs/internal/13-12-ugc-review-policy.md` | UGC 검수 정책(§13-12) |

**탐색 규칙**

1. **스키마·조건 문법·임계값·정책이 필요하면 `docs/internal/` 을 먼저 읽는다.** 공개 문서의 인용문으로 대신하지 않는다 — 인용은 요약이라 컬럼이 빠져 있다.
2. `docs/internal/` 에 파일이 없으면 **레포 밖도 확인한다** (`~/Downloads`, `~/Documents`). 찾으면 `docs/internal/` 로 복사해 고정하고 그 사실을 보고한다.
3. **원문에 없는 값·문법·정책은 추측하지 않는다.** `docs/corrections.md` 에 `[결정 필요]` 로 올리고 기본 채택안을 PR 본문에 명시한다.
4. **`docs/internal/` 의 내용을 공개물에 옮기지 않는다** — 커밋·이슈·PR·코드 주석 어디에도. 인용이 필요하면 **조항 번호로만 지목한다**(예: "R4.2 의 기본 델타 상한"). 구현에 쓴 공개 가능한 결정만 `docs/corrections.md` 또는 ADR 에 남긴다 (S-11).

`docs/openapi.yaml` 은 아직 없다 — B-06 이다. **없는 문서를 근거로 결론을 내지 않는다.**

## 작업 시작 전

1. **이슈 번호를 확인했는가.** 이슈 없이 코드를 쓰지 않는다. `git status` · 현재 브랜치를 먼저 본다.
2. **작업 번호(`B-xx`)를 확인했는가.** `docs/tasks.md`의 정의를 그대로 따른다. **정의에 없는 범위를 넓히지 않는다.**
3. **불변 규칙을 위반하는가.** 불가피하면 코드를 쓰지 말고 먼저 보고한다.
4. **`docs/corrections.md`의 `[결정 필요]` 항목에 손대는가.** 임의로 정하지 말고 기본 채택안을 따르되 PR 본문에 명시한다.
5. **작업 중 다른 문제를 발견하면 그 자리에서 고치지 않는다.** 새 이슈 후보로만 보고한다.

**`/task B-xx [이슈번호]`가 1~5를 자동으로 수행한다.** 사용자가 `S-3 착수`처럼 작업 번호를 지목하면 **슬래시 입력 없이도 이 스킬로 진입한다.**
이슈가 없으면 스킬이 `docs/tasks.md` 정의로 이슈를 열고 그 번호로 브랜치를 판다 — **"이슈 없이 코드를 쓰지 않는다"는 그대로다.** 확보 방법이 바뀐 것뿐이다.

## 불변 규칙 I-1 ~ I-20 (전문: `docs/invariants-and-security.md`)

**이 규칙을 깨는 PR은 무조건 반려된다.** 예외가 필요하면 ADR(`docs/adr/`)을 먼저 쓴다.

| # | 규칙 |
|---|---|
| **I-1** | 선택지는 서버 발급 `choiceId`로만 제출된다. 클라이언트가 보낸 `text`는 어떤 경우에도 신뢰하지 않는다 |
| **I-2** | AI 응답은 Safety L2 통과 전까지 사용자에게 도달하지 않는다 |
| **I-3** | AI 페이로드에 회원 식별정보(이메일·이름·소셜 ID·IP·생년월일·`player_ref`) 금지. 직렬화 직전 화이트리스트 검증, 위반 시 요청 중단 |
| **I-4** | 세션은 생성 시 provider/model에 고정. 중간 변경 불가 |
| **I-5** | 모든 턴은 GameState 스냅샷과 함께 저장한다. **스냅샷·요약을 덮어쓰지 않는다**(append-only) |
| **I-6** | `turnNo`는 낙관적 잠금 키다. 불일치 시 409 |
| **I-7** | `SYSTEM` / `OUTPUT SPEC` 프롬프트 레이어는 작품이 덮어쓸 수 없다 |
| **I-8** | UGC는 검수 승인 없이 어떤 경로로도 타인에게 노출되지 않는다 |
| **I-9** | `chapter` / `turn`은 AI가 변경할 수 없다. 서버 전용 필드 |
| **I-10** | Chapter 전환과 Ending 선언은 **서버가 GameState로 판정**한다. AI 제안값은 참고만 |
| **I-11** | `disabled` / `disabledReason`은 서버가 판정한다. AI에게 맡기지 않는다 |
| **I-12** | Safety L2는 **생성 모델과 별개의 판정기**로 수행한다. 자기 검열에 의존하지 않는다 |
| **I-13** | Safety L2는 provider와 무관하게 **항상 서버에서** 수행한다. 무검열 로컬 모델을 붙여도 15세 등급이 유지된다 |
| **I-14** | Provider 선택 권한은 관리자 전용. 사용자에게 노출하지 않는다 |
| **I-15** | **게임 로직에 난수를 도입하지 않는다.** 판정·분기·엔딩은 전부 GameState 기반 결정론적 평가 |
| **I-16** | 재화는 **정액 소모만**. 확률 결합(가챠·배수 지급·확률 소멸)을 서버에 구현하지 않는다. 무료 재화도 동일 |
| **I-17** | 관리자 자유입력도 Safety L1을 거친다. 무검열 통로를 만들지 않는다 |
| **I-18** | 사용자 소유 세션에 자유입력 금지. `is_test_session = true`에서만 |
| **I-19** | `story.age_rating` 컬럼을 만들지 않는다. 단일 상수 응답 |
| **I-20** | 도달률은 배치 갱신. 실시간 계산 금지 |

> I-15 보강 — 금지는 판정·분기·엔딩·상태 변화량·재화에 `Random`을 쓰는 것이다. 요청 ID·UUID, 재시도 지터, A/B 버킷팅, L3 랜덤 샘플링은 허용. AI의 temperature는 난수 판정이 아니지만 **AI 출력을 상태 변화의 최종 권한으로 쓰지 않는다** — 서버 clamp가 최종 결정권을 갖는다.

## 아키텍처 경계

```
Controller → Service → Domain → Repository      계층 역행 금지
모듈 간 호출은 XxxFacade 로만                    다른 모듈의 Repository·Entity 직접 참조 = 반려
스키마 간 JOIN · FK 금지                         참조는 애플리케이션 레벨에서만
비-Identity 스토어는 user.id 를 저장하지 않는다   playerRef(UUID)만
ai 모듈은 도메인 엔티티를 모른다                  순수 DTO 입출력만 (I-3의 구조적 보장)
safety · batch 는 파사드가 아니라 common/spi 를 쓴다   구현은 데이터 소유 모듈 (ADR-0002/0003)
트랜잭션 안에서 외부 HTTP(Provider) 호출 금지     짧은 TX → 외부 호출 → 짧은 TX
```

스토어 4개(`identity` / `catalog` / `play` / `promptlog`)는 별도 스키마 · 별도 계정 · 별도 DataSource · 별도 Flyway 경로다.

## 설계 원칙 — Clean Code / Pragmatic Clean Architecture

이 프로젝트는 장기 확장을 전제로 한다. 새 기능을 추가할 때 기존 핵심 도메인의 변경 범위를 최소화할 수 있도록 책임과 의존성을 명확히 분리한다.

**단, 미래의 가능성만을 이유로 추상화를 미리 만들지 않는다.** 클린 아키텍처를 명분으로 한 오버엔지니어링은 금지한다.

### 의존성

- 의존성은 바깥쪽에서 핵심 도메인 방향으로 흐른다.
- Domain 은 Controller · Web · 외부 Provider 의 구체 구현을 알지 않는다.
- 외부 시스템의 변경이 핵심 게임 규칙으로 전파되지 않도록 경계를 둔다.
- 모듈 간 데이터 전달에는 Entity 가 아니라 명시적인 DTO / Command / Result 를 사용한다.
- 다른 모듈의 Repository 와 Entity 를 직접 참조하지 않는다.

### 책임

- 클래스와 메서드는 하나의 명확한 책임을 갖는다.
- **Controller** — HTTP 입출력과 검증 · 위임.
- **Service / Application** — 유스케이스와 트랜잭션 경계.
- **Domain** — 게임 규칙과 상태 전이.
- **Repository** — 영속성 접근.
- 외부 Provider · SDK 의 세부 구현을 Domain 으로 누출하지 않는다.

### 확장

변경 가능성이 **실제로 존재하는** 경계에는 인터페이스를 둘 수 있다 — AI Provider · Safety 판정기 · 외부 인증 Provider · 저장소 구현 · 외부 API Client.

새 Provider 를 추가할 때 기존 게임 로직을 수정하는 대신 **새 구현을 추가하는 방식으로** 확장할 수 있어야 한다.

### 추상화 제한

**다음 이유만으로 인터페이스 · Factory · Strategy · 추상 클래스를 만들지 않는다.**

- "나중에 필요할 수도 있어서"
- 테스트하기 편할 것 같아서
- Clean Architecture 이므로
- 모든 Service · Repository 에 인터페이스가 있어야 할 것 같아서

추상화는 다음 중 하나가 있을 때 도입한다.

1. 실제 구현이 둘 이상 존재한다.
2. `B-xx` 요구사항에 명확한 교체 가능성이 정의되어 있다.
3. 외부 시스템과 핵심 도메인 사이의 경계를 보호해야 한다.
4. 테스트가 아니라 실제 설계상의 의존성 역전이 필요하다.

### 코드 품질

- 의미가 드러나는 이름을 사용한다.
- boolean 플래그로 여러 책임을 한 메서드에 넣지 않는다.
- 긴 메서드는 책임 단위로 분리한다.
- **중복 제거보다 잘못된 추상화를 만들지 않는 것을 우선한다.** 공통화는 의미가 동일할 때만 한다 — 코드 모양이 비슷하다는 이유로 합치지 않는다.
- 변경과 무관한 리팩터링은 현재 `B-xx` 에 포함하지 않는다. 필요하면 **이슈 후보로 보고한다.**

### 우선순위

**설계 원칙은 작업 범위를 확대하는 근거가 될 수 없다.**

```
불변 규칙 · 보안 > B-xx 요구사항과 DoD > 모듈 경계 > 단순하고 명확한 구현 > 재사용과 추상화
```

"더 깨끗한 구조"를 이유로 현재 작업과 관계없는 코드를 수정하지 않는다.

## 보안 hard-stop

- **시크릿을 소스에 커밋하지 않는다.** 실제 값은 `.env`에만. 이미 푸시된 자격 증명은 **삭제가 아니라 로테이션**이다.
- **`${VAR:실제값}` 기본값 패턴 금지.** 값이 없으면 부팅을 실패시킨다.
- **신규 `*.yml` / `*.yaml` 금지.** 승인된 예외는 `docker-compose*.yml` · `.github/**/*.yml` · `docs/openapi.yaml` 셋뿐이며 **승인 없이 늘리거나 넓히지 않는다.** 테스트용 yml을 만들지 않는다 — `@DynamicPropertySource`.
- **프롬프트 원문·응답 원문·API 키·토큰·이메일을 애플리케이션 로그에 남기지 않는다.** `ai_call_log`(별도 스토어)만 원문을 보관한다.
- **에러 응답에 스택트레이스·SQL·내부 경로를 노출하지 않는다.** `code` + 안전한 `message`만.
- **S-11 — 이 레포는 공개다.** 커밋·이슈·PR·주석·문서가 즉시 세계에 읽힌다. 세이프티 우회 방법, 블록리스트 실제 항목, 정규화를 뚫는 표기, 미수정 취약점의 재현 절차, 운영 도메인·계정 체계를 **적지 않는다.**

전문과 S-1~S-11은 `docs/invariants-and-security.md`.

## 개발 루프

```
inspect → minimal change → targeted test → fast test → (필요 시) integration → preflight → self-review
```

- **범위 밖 문제는 고치지 말고 이슈 후보로 보고한다.**
- 미구현이면 스텁으로 통과시키지 말고 `UnsupportedOperationException`을 던지고 이슈를 만든다.
- 요구사항 ID(`R4.2`, `P7`, `I-9`)를 코드 주석 또는 테스트 이름에 남긴다 — 예: `R4_2_affinity_delta_over_limit_is_clamped()`.

## 명령

```bash
./gradlew test              # 빠른 루프. 컨테이너도 nightly 도 아닌 것
./gradlew integrationTest   # @Tag("container") — Docker 필요
./gradlew nightlyTest       # @Tag("nightly") — ADR-0001 분류
./scripts/preflight.sh      # test + integrationTest + gitleaks. 푸시 전
```

가장 작은 관련 테스트부터: `./gradlew test --tests "*GameStateEngineTests"`.
**Windows 툴체인이면 `gradlew.bat`.** 툴체인과 레포 경로를 같은 쪽에 둔다(`README.md`).

## Git

이슈 → 브랜치(`<타입>/#<번호>-<슬러그>`) → 커밋(컴파일 통과 시점마다) → PR.
**`backend` / `dev` / `main`에 직접 푸시하지 않는다.** 커밋은 Conventional Commits + `Refs:` + 이슈 번호.
PR 400줄 · 브랜치 수명 3일. 상세는 `docs/git-workflow.md`.

## 문서 index — 필요할 때 해당 절만 읽는다

| 문서 | 언제 |
|---|---|
| `docs/tasks.md` | `B-xx` 정의 · DoD · 의존, MVP 수직 슬라이스 순서 |
| `docs/corrections.md` | 상위 문서 정정(구 §13). `[결정 필요]` 항목 |
| `docs/invariants-and-security.md` | I-1~I-20 전문, §7 보안 규칙 전문(S-1~S-11) |
| `docs/engineering-guide.md` | 제품 개요 · 기술 스택 · 용어 사전 · 핵심 플로우 · 아키텍처 · 코딩 컨벤션 · 테스트 규칙 · 에러 코드 · 자주 하는 실수 |
| `docs/git-workflow.md` | Git 규칙 전문(§8) |
| `docs/adr/` | 기술 결정 이력. 0001 테스트 실행 정책 / 0002 블록리스트 소유 / 0003 batch 경계 / 0004 수직 슬라이스 / 0005 오케스트레이터 의존 |
| `docs/openapi.yaml` | API 계약 — 런타임 진실의 원천. **아직 없다(B-06).** `.gitignore` 예외는 처리됨(#36) |
| `README.md` | 로컬 실행 · 스키마 4개 · 마이그레이션 명명 규칙 |

## 코드 영역별 규칙 — `.claude/rules/`

해당 경로의 파일을 다룰 때 **그 규칙 파일을 읽는다.**

| 파일 | 대상 |
|---|---|
| `.claude/rules/play.md` | `play/**` — 턴 파이프라인 · GameState · Chapter/Ending |
| `.claude/rules/ai.md` | `ai/**` — 페이로드 화이트리스트 · 프롬프트 레이어 · 토큰 예산 |
| `.claude/rules/safety.md` | `safety/**` — L0~L3 · 차단 응답 |
| `.claude/rules/identity.md` | `identity/**` — playerRef 경계 · 연령 게이트 · 동의 |
| `.claude/rules/catalog-authoring.md` | `catalog/**` `authoring/**` — 버전 고정 · UGC 노출 |
| `.claude/rules/persistence.md` | `config/**` `db/migration/**` `docker/postgres/**` — 4스토어 · Flyway |
| `.claude/rules/web-api.md` | `common/web` `common/error` · 컨트롤러 — API 계약 |
| `.claude/rules/testing.md` | `src/test/**` — 테스트 구분 · 태그 · 금지 사항 |

## 워크플로 — `.claude/skills/`

| 명령 | 용도 |
|---|---|
| `/task B-xx [이슈번호]` | 이슈 확보·생성 → 브랜치 → 작업 정의 조회 → 최소 탐색 → 구현 → 검증 → 셀프 리뷰 → Draft PR. **작업 번호 지시만으로도 자동 진입** |
| `/verify` | 변경 성격에 맞는 최소 검증만 실행 |
| `/review` | 현재 diff를 심각도순으로 검토 |
| `/migration` | Flyway · Entity · Repository · DataSource 작업 체크리스트 |

서브에이전트: `explorer`(넓은 탐색) · `test-runner`(긴 실패 로그 격리) · `reviewer`(독립 diff 검토).
**2~3개 파일만 읽으면 되는 일에 에이전트를 띄우지 않는다.**
