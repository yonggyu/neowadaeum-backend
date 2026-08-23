# 불변 규칙과 보안 규칙 — `CLAUDE.md` §6 · §7

> `CLAUDE.md` 에서 옮겨온 원문이다(v1.2 분할, #35). **내용을 고치지 않았다.**
> 루트에는 I-1~I-20 이 1줄 압축형으로, 보안은 hard-stop 만 남아 있다. **전문은 여기가 정본이다.**
>
> 항상 로드되는 것은 `CLAUDE.md` 뿐이다. 이 파일은 **필요한 절만 인용해 읽는다.**

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

# API 계약(B-06). 공개 계약 문서이며 설정·시크릿 파일이 아니다 — 스키마와 예시값뿐이다.
# 이 한 파일만 예외다. docs/ 아래 다른 yaml 은 그대로 무시된다.
!docs/openapi.yaml

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
| `docs/openapi.yaml` | **추적한다** (이슈 #36). API 계약은 공개 문서이며 설정·시크릿 파일이 아니다 — 스키마와 예시값뿐이다. **이 한 파일만이고 `docs/` 아래 다른 yaml 은 무시된다** |
| `src/main/resources/application*.yml` | **추적하지 않는다.** 대신 `application.yml.template`을 커밋하고 로컬·CI에서 복사해 쓴다 |
| 테스트 설정 | **yml 파일을 만들지 않는다.** Testcontainers + `@DynamicPropertySource`로 런타임 주입한다 |
| Flyway 마이그레이션(`.sql`) | 추적 대상. 시크릿·실데이터를 넣지 않는다 |
| `docker/postgres/init/*.sh` | 추적 대상. 비밀번호는 환경변수로만 받는다 (§2.5) |
| `gradle/wrapper/gradle-wrapper.properties` | **추적한다.** `*.properties` 규칙에 걸리면 clone 후 `./gradlew`가 동작하지 않는다. 시크릿이 아니라 빌드 메타데이터이므로 `!gradle.properties`와 의도가 같다 |

> **`.gitignore`에 줄 끝 주석을 쓰지 않는다.** git은 `#`을 **줄 첫 글자일 때만** 주석으로 본다. `!.github/**/*.yml   # 설명`이라고 쓰면 주석까지 패턴의 일부가 되어 예외가 통째로 무효화된다. 설명은 반드시 **윗줄**에 단독으로 쓴다. 이 규칙을 어기면 조용히 실패하므로 리뷰에서 잡기 어렵다.

**YAML은 기본적으로 추적하지 않는다. 명시적으로 승인된 공개 설정·계약 파일만 최소 범위로 예외 처리한다.**

현재 승인된 예외는 이 3종(패턴 4개)뿐이다.

```
docker-compose.yml
docker-compose.*.yml
.github/**/*.yml
docs/openapi.yaml
```

**다른 `.yml` / `.yaml` 예외를 승인 없이 추가하지 않는다.** 넓히지도 않는다 — `docs/**/*.yaml` 같은
디렉터리 단위 허용은 이 규칙이 막으려는 것 자체다.

> `docs/openapi.yaml` 예외(이슈 #36)의 근거는 `.github/**` 와 같다 — **구조적으로 시크릿을 담을 이유가 없는 파일**이다. OpenAPI 스펙에는 스키마와 예시값만 들어간다. 다만 §15가 이 파일을 "런타임 진실의 원천"으로 규정하므로, 여기에 실제 토큰·키·운영 도메인을 예시로 적는 PR은 반려한다(S-11).
>
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
