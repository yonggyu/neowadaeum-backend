---
paths:
  - "src/main/java/com/neowadaeum/catalog/**/*.java"
  - "src/main/java/com/neowadaeum/authoring/**/*.java"
  - "src/test/java/com/neowadaeum/catalog/**/*.java"
  - "src/test/java/com/neowadaeum/authoring/**/*.java"
---

# catalog · authoring — 작품 · 버전 · UGC 검수

두 모듈을 한 파일에 둔 이유: **같은 스키마(`catalog`)를 쓰고 같은 DataSource를 공유한다**(§5.3). 버전 규칙과 노출 규칙이 맞물려 있어 따로 읽으면 절반만 보인다.

## 버전 고정 — `docs/corrections.md` §13-1 정정 반영

- `character` / `chapter_def` / `ending_def`는 **`story_version_id`를 FK로 갖는다.** `story_id`는 조회 편의용 비정규화 컬럼일 뿐이다.
- **새 버전 발행 시 세 테이블을 복제한다.** 이걸 빠뜨리면 작성자가 캐릭터 성격이나 엔딩 조건을 고칠 때 **진행 중인 모든 세션이 즉시 영향받는다.**
- `ending_stat`은 **`(story_id, ending_no)` 기준**으로 집계한다. `ending_id`로 집계하면 버전 발행 때마다 도달률이 리셋된다.
- 승인 후 수정은 새 버전이 되고 재검수를 거친다. **진행 중 세션은 기존 버전을 계속 참조한다.**

## 제약

- `is_default` 엔딩은 작품당 정확히 1개 — `CREATE UNIQUE INDEX ... ON ending_def(story_version_id) WHERE is_default`.
- `is_default`와 `is_secret`은 동시에 true일 수 없다 (CHECK).
- `content_report`는 `UNIQUE(reporter_ref, target_type, target_id)` — 동일인 반복 신고로 자동 정지를 유발할 수 없게 한다.
- `story.age_rating` 컬럼을 만들지 않는다 (I-19). 상수 `"15세 이용가"`를 응답한다.

## UGC 노출 (I-8)

- **검수 승인 없이 어떤 경로로도 타인에게 노출되지 않는다.** `pending` / `rejected` / `private`는 타인 조회에 뜨지 않는다 — 목록·검색·직접 접근 전부.
- `blocked` 상태에서 다음 단계 진행은 **서버가 거부한다.** 클라이언트 검증에만 의존하지 않는다.
- 반려 사유는 **카테고리만** 노출한다. 블록리스트 항목은 비공개다.
- `unlisted → public` 승격은 **인간 검수를 재트리거**한다.
- `review_status` 전이: `draft → pending → (auto_rejected | in_review) → (approved | rejected)`, `approved → suspended → (approved | rejected)`, **`approved → (in_review | approved)`** (재제출 — 같은 작품에 새 버전, §13-40). `auto_rejected`는 내부 기록용이고 사용자에게는 `rejected`로 표시한다.

## 작성자 표시

`author_profile(player_ref PK, display_name, updated_at)`이 Catalog에 있다. **`player_ref`를 응답에 노출하지 않는다** — `authorDisplayName`만.

## 블록리스트 소유 (ADR-0002)

`blocklist_entry`는 **authoring 소유**이고 catalog 스키마에 있다. safety는 `common/spi`로 읽기만 한다. 갱신 경로(운영 중 갱신)와 **캐시 무효화**는 authoring의 책임이다.

## 미리보기 (`docs/corrections.md` §13-5)

미리보기 시점에는 `story`도 `story_version`도 없다. `review_status='draft'` / `visibility='private'`로 `story`를 즉시 생성하고 임시 `story_version`을 발행한다. 승인 시 정식 버전으로 승격한다.
