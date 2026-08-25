# 상위 문서 검증 결과 — `CLAUDE.md` §13

> **이 파일은 `CLAUDE.md` §13 을 그대로 옮긴 것이다.** 분할 시점(v1.1)에 내용을 고치지 않았다.
>
> **구현 시 이 문서가 상위 문서보다 우선한다.** 충돌 우선순위는 `CLAUDE.md` §15 를 따른다:
> `docs/corrections.md` > `openapi.yaml` > `backend-requirements.md` > `너와다음.md`
>
> 이 파일은 **해당 항목만 인용해 전달한다.** 전문을 매번 로드하지 않는다.
> §13-12 는 여전히 비공개 내부 문서(`docs/internal/13-12-ugc-review-policy.md`)를 가리킨다.
>
> **데이터 모델 정정은 원문 v0.4 에 반영됐다 (2026-08-23).** §13-1 · §13-4 · §13-9 · §13-15 의
> 스키마 관련 결정은 `backend-requirements.md` §2 본문에 접혀 들어갔다. **여기 항목을 지우지 않는다** —
> 결정의 근거와 경위는 이 파일에만 있고, 그것이 없으면 다음 사람이 같은 논의를 반복한다.
> 두 문서가 어긋나면 여전히 **이 파일이 이긴다.**

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

### 13-15. `[결정 필요]` `story.slug` 의 유일성이 명시되지 않았다

`backend-requirements.md` §2.3 의 `story` 정의에 `slug` 가 있으나 **유일성 제약을 명시하지 않는다.** 유일하지 않은 slug 는 URL 식별자로 기능하지 못하고, 조회는 어느 작품을 돌려줄지 정해지지 않은 상태가 된다.

**기본 채택안 (S-4 에서 적용)**: `UNIQUE (slug)`.

`[결정 필요]` 작품 삭제·비공개 전환 후 같은 slug 재사용을 허용할지. 허용하려면 유일성을 "살아 있는 행 기준"(partial unique)으로 좁혀야 한다. B-15(Library) 조회 경로가 생기는 시점에 정한다.

### 13-16. `[해소]` `ending_def.condition` 과 `is_default` 의 관계 — **결정됨 (2026-08-23)**

R2.2 는 *"정확히 1개는 `is_default = true`"*, R7.7 은 *"어떤 조건도 매칭되지 않고 마지막 챕터 `max_turns` 에 도달하면 `is_default` 엔딩으로 종료"* 라고 하지만, ① 기본 엔딩이 `condition` 을 가질 수 있는지 ② 일반 엔딩이 `condition` 없이 존재할 수 있는지 **어느 쪽도 규정하지 않았다.**

②가 허용되면 그 엔딩은 R7.6 의 `ending_no` 순회에서 **항상 최초 매칭**이 되어 뒤의 엔딩을 전부 가린다.

**결정**

| # | 규칙 | 강제 위치 |
|---|---|---|
| 1 | `is_default = true` 인 엔딩은 **조건 판정에 참여하지 않는** fallback 이다 | 엔진 (S-7) |
| 2 | `is_default = false` → `condition` 을 **반드시 갖는다** | DB CHECK |
| 3 | `is_default = true` → `condition` 을 **갖지 않는다** | DB CHECK |
| 4 | 기본 엔딩은 **R7.7 의 시점에만** 선택된다 — 마지막 챕터에서 `max_turns` 에 도달했고 조건부 엔딩이 하나도 매칭되지 않았을 때 | 엔진 (S-7) |
| 5 | 엔딩 집합에 기본 엔딩은 **정확히 1개** (R2.2 유지) | `≤ 1` DB partial unique / `≥ 1` 애플리케이션 |

2·3 은 `CHECK (is_default = (condition IS NULL))` 하나로 표현된다 — `catalog/V4__ending_default_contract.sql`.

**규칙 4 를 시점 없이 읽지 않는다.** *"아무 조건도 만족하지 않으면 기본 엔딩"* 을 매 턴 적용하면 초반 턴은 어떤 조건도 만족하지 않으므로 **모든 세션이 1턴에 끝난다.** 발동 시점은 R7.7 이 규정한 그것이고, 규칙 4 는 그 시점에서의 **선택 순서**를 말한다.

**하한(`≥ 1`)은 DB 로 강제하지 않는다.** 행의 부재는 CHECK 로 볼 수 없고, 트리거로 막으면 작성 도중의 정상 상태(엔딩을 아직 안 만든 `draft`)까지 거부하게 된다. 검증 시점은 **작품이 플레이 가능한 상태로 전환될 때**다 — 그 순간이 R2.2 의 *"엔딩 없이 무한히 진행되는 세션 방지"* 가 실제로 의미를 갖는 시점이다.

**구현 위치는 `B-56` 게시 & 버전 발행이다** — *"승인 시 `story_version` 발행"* 이 곧 "플레이 가능해지는" 순간이다. 추적은 **#54**. S-7 은 폴백 부재를 만나면 실패시킨다(`EndingEngine`).

> **정정 (2026-08-23).** 이 항목의 초판은 구현 위치를 **`B-49`(작성 도구)** 라고 적었다. **틀렸다** — `B-49` 는 *블록리스트 관리*다. 저작 경로는 `B-51`(드래프트 CRUD) · `B-54`(제출·자동 검수) · `B-55`(인간 검수) · `B-56`(게시·버전 발행)으로 나뉘고, "플레이 가능한 상태로 전환"에 해당하는 것은 **`B-56`** 뿐이다.
>
> 같은 오기가 `catalog/V2__catalog_core.sql` 의 주석에도 있다. **그 파일은 머지되어 고칠 수 없다** — Flyway 체크섬은 주석까지 포함하므로 한 글자만 바꿔도 기존 DB 의 기동이 막힌다(`persistence.md`). 마이그레이션 주석이 아니라 **이 항목이 기준**이다.

---

### 13-17. `[해소]` 세션 시작 시 GameState 초기값 — **결정됨 (2026-08-23)**

`engineering-guide` §4.2 는 세션 시작 절차에 *"GameState 초기화 (`state_schema` 기본값)"* 을 넣는다. 그런데 **`state_schema` 에는 기본값을 선언할 자리가 없다** — R4.2 가 규정하는 것은 `min` · `max` · `maxDeltaPerTurn` 뿐이다. 가리키는 대상이 없는 문장이었다.

**결정**

| 시점 | 동작 | 구현 |
|---|---|---|
| 세션 시작 | 수치·플래그·인벤토리가 **비어 있는** GameState. `chapter = 1` · `turn = 0` | `GameState.initial()` (S-5) |
| 첫 수치 변화 | 그 수치는 **`min` 을 기준**으로 델타 상한과 범위를 적용한다 | `GameStateEngine` (S-5) |

**`state_schema` 에 초기값 필드를 만들지 않는다.** 원문에 없는 스키마를 발명하지 않는다. 작품마다 "호감도 30에서 시작" 같은 요구가 실제로 생기면 그때 B-08(Catalog 스키마)에서 필드를 더한다 — **지금 만들면 쓰이지 않는 컬럼이 하나 생기고, 그 기본값이 무엇인지가 또 미정으로 남는다.**

**`0` 을 기준으로 쓰지 않는 이유가 이 결정의 핵심이다.** `min` 이 양수인 스키마에서 `0` 은 **범위 밖 값**이다. 그리고 조건 평가는 "값이 없다"와 "0 이다"를 구분한다 — 없는 수치는 `lt` 계열에서도 `false` 다(S-6). 두 상태를 같게 만들면 **도달하면 안 되는 엔딩이 열린다.**

§4.2 의 *"`state_schema` 기본값"* 은 이 결정으로 읽는다 — 기본값은 스키마가 선언하는 값이 아니라 **스키마의 `min` 이 정하는 하한**이다.

---

### 13-18. `[결정 필요]` 토큰을 어떻게 세는지 정의되어 있지 않다

`backend-requirements.md` §4.3 은 레이어별 상한(1,200 / 300 / 600 / 1,500 / 200)과 입력 합계 목표 4,000 을 정하고, R4.9 는 UGC `world_prompt` + `persona_prompt` 합계를 1,000 토큰으로 하드 제한한다. **그러나 이 "토큰"을 무엇으로 세는지는 어디에도 없다.**

정하지 않으면 두 가지가 성립하지 않는다. 조립기가 축소를 시작할 시점을 알 수 없고(§4.4), 저장 시점의 UGC 길이 검증(R4.9, B-51)이 무엇을 재는지 알 수 없다.

**어려운 점은 정답이 하나가 아니라는 것이다.** 토큰화는 벤더마다 다르다 — 한 벤더의 토크나이저를 붙이면 다른 벤더에서는 틀린 값이 된다. 라이브러리 추가는 새 의존성이고(§0.1 의 착수 전 확인), 벤더 API 로 세는 것은 매 턴 네트워크 호출이다.

**기본 채택안 (B-20 에서 적용)**: **벤더 없는 보수적 근사.** ASCII 는 4자에 1토큰, 그 밖의 문자는 **글자마다 1토큰**으로 센다. 계산은 `TokenCounter` 뒤에 두어 한 곳에만 존재한다.

방향을 이렇게 잡은 이유는 **틀리는 쪽을 고를 수 있기 때문**이다. 과소 추정은 예산을 넘긴 요청을 실제로 보내 Provider 오류와 비용을 낳고, 과대 추정은 컨텍스트를 조금 덜 싣는다. 되돌릴 수 없는 쪽은 전자다.

`[결정 필요]` **B-22 에서 벤더 계산으로 교체할지.** 실 Provider 가 붙으면 그 벤더의 토큰화를 쓸 수 있다. 교체하면 축소 시점이 달라지므로 예산 테스트를 함께 본다. 벤더가 둘 이상이 되는 시점(B-23)에는 "어느 벤더 기준으로 예산을 잡는가"가 다시 질문이 된다 — 세션이 provider 에 고정되므로(I-4) 세션의 provider 기준으로 세는 것이 기본 방향이다.

---
