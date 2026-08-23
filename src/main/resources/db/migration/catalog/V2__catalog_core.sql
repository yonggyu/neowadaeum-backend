-- V2 — catalog 슬라이스 테이블 (S-4 / B-08 축소, #48)
--
-- 출처: docs/internal/backend-requirements.md §2.3 Catalog (비공개 원문, .gitignore 대상)
--       + docs/corrections.md §13-1 · §13-3
--       + .claude/rules/catalog-authoring.md
--
-- **컬럼 이름과 구성은 §2.3 원문을 그대로 따른다.** 이름을 바꾸면 원문을 읽는 사람과 코드를 읽는
-- 사람이 서로 다른 것을 보게 되고, 나중에 맞추려면 컬럼 rename 마이그레이션이 필요해진다.
-- genre / story_genre 는 §2.3 에 이름만 있고 컬럼 정의가 없다(§13-4 가 보완). B-15 범위이므로 넣지 않는다.
--
-- **§13-1 정정이 §2.3 을 이긴다** (CLAUDE.md 의 우선순위: corrections > backend-requirements).
-- 원문은 character · chapter_def · ending_def 가 story_id 를 갖는다고 하지만, 그러면 R2.1
-- ("세션은 story_version_id 를 고정 참조") 과 R8.8 이 성립하지 않는다 — 작성자가 엔딩 조건을 고치면
-- 진행 중인 모든 세션이 즉시 영향받는다. 셋 다 story_version_id 를 갖고, story_id 는 조회 편의용
-- 비정규화 컬럼으로만 남긴다.
--
-- 스키마 간 FK 를 만들지 않는다 (§5.3). catalog 안에서만 FK 를 건다.
-- author_ref 는 player_ref 다 — 비-Identity 스토어는 user.id 를 담지 않는다 (§2.1, I-3).
-- 시각은 전부 UTC 다 (§9.1).

-- ── 작품 ─────────────────────────────────────────────────────
-- I-19 — age_rating 컬럼을 만들지 않는다. §2.3 원문에도 없다. 단일 등급이므로 상수를 응답한다.
CREATE TABLE story (
    id                 UUID        NOT NULL,
    slug               TEXT        NOT NULL,
    title              TEXT        NOT NULL,
    cover_url          TEXT,
    hero_url           TEXT,
    short_desc         TEXT,
    description        TEXT,
    world_intro        TEXT,
    author_type        TEXT        NOT NULL,
    author_ref         UUID,
    visibility         TEXT        NOT NULL,
    review_status      TEXT        NOT NULL,
    current_version_id UUID,
    published_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT story_pkey PRIMARY KEY (id),

    -- [결정 필요] §2.3 은 slug 의 유일성을 명시하지 않는다. 유일하지 않은 slug 는 식별자로
    -- 기능하지 못하므로 UNIQUE 를 기본 채택안으로 둔다. docs/corrections.md §13-15 참조.
    CONSTRAINT story_slug_key UNIQUE (slug),

    CONSTRAINT story_author_type_check CHECK (author_type IN ('official', 'user')),
    CONSTRAINT story_visibility_check CHECK (visibility IN ('private', 'unlisted', 'public')),
    CONSTRAINT story_review_status_check
        CHECK (review_status IN ('draft', 'pending', 'auto_rejected', 'in_review',
                                 'approved', 'rejected', 'suspended')),

    -- §2.3 — short_desc(≤40자). 카드 레이아웃이 이 길이를 전제한다.
    CONSTRAINT story_short_desc_length_check CHECK (short_desc IS NULL OR char_length(short_desc) <= 40)
);

-- current_version_id 에 FK 를 걸지 않는다.
-- story → story_version → story 로 순환하므로 양쪽에 걸면 시드와 버전 발행이 DEFERRABLE
-- 트랜잭션에 묶인다. 참조 방향의 진실은 story_version.story_id 쪽이고, current_version_id 는
-- "지금 어느 버전을 보여줄 것인가"라는 포인터다 (R2.1 — 다르면 Resume 에서 version_changed).
-- 정합성은 CatalogSeedTests 가 확인한다.

-- ── 작품 버전 ────────────────────────────────────────────────
-- 세션은 이 id 에 고정된다 (R2.1, I-4).
CREATE TABLE story_version (
    id            UUID        NOT NULL,
    story_id      UUID        NOT NULL,
    version_no    INTEGER     NOT NULL,
    world_prompt  TEXT        NOT NULL,

    -- §2.3 — choice_policy(min:1, max:4, preferred:3).
    choice_policy JSONB       NOT NULL,

    -- R4.1 — GameState 화이트리스트. 미정의 키는 AI 가 반환해도 무시한다.
    -- R4.2 — 수치 필드는 min / max / maxDeltaPerTurn 을 갖는다 (기본 델타 상한 ±5).
    state_schema       JSONB  NOT NULL,

    -- §13-9 — R4.4 는 "UGC 작성자가 state_schema 를 자유 정의하게 하지 않는다. 플랫폼 템플릿 중
    -- 선택하게 한다"를 요구한다. 자유 정의를 허용하면 clamp 규칙과 disabled 판정을 일반화할 수 없다.
    -- 정정본이 §2.3(이 컬럼이 없다)을 이긴다.
    state_template_key TEXT   NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT story_version_pkey PRIMARY KEY (id),
    CONSTRAINT story_version_story_fkey FOREIGN KEY (story_id) REFERENCES story (id),
    CONSTRAINT story_version_no_key UNIQUE (story_id, version_no),
    CONSTRAINT story_version_no_check CHECK (version_no >= 1),
    CONSTRAINT story_version_state_template_key_check
        CHECK (state_template_key IN ('affinity', 'flag', 'numeric'))
);

-- ── 캐릭터 ───────────────────────────────────────────────────
-- CHARACTER 레이어는 display_order 대로 프롬프트에 들어간다 (§4.4).
CREATE TABLE character (
    id                    UUID        NOT NULL,
    story_version_id      UUID        NOT NULL,
    story_id              UUID        NOT NULL,
    name                  TEXT        NOT NULL,
    role                  TEXT,
    portrait_url          TEXT,
    one_line              TEXT,
    persona_prompt        TEXT        NOT NULL,
    display_order         INTEGER     NOT NULL,
    is_visible_in_detail  BOOLEAN     NOT NULL,

    CONSTRAINT character_pkey PRIMARY KEY (id),
    CONSTRAINT character_version_fkey FOREIGN KEY (story_version_id) REFERENCES story_version (id),
    CONSTRAINT character_display_order_key UNIQUE (story_version_id, display_order),
    CONSTRAINT character_display_order_check CHECK (display_order >= 1)
);

-- ── 챕터 ─────────────────────────────────────────────────────
-- R7.1 — 챕터는 서버가 entry_condition 으로 판정한다. AI 응답에서 추정하지 않는다.
CREATE TABLE chapter_def (
    id               UUID        NOT NULL,
    story_version_id UUID        NOT NULL,
    story_id         UUID        NOT NULL,
    chapter_no       INTEGER     NOT NULL,
    title            TEXT        NOT NULL,

    -- R7.4 — GameState 참조식. 1장은 진입 조건이 없다. 조건 DSL 평가는 S-6 이며 여기서는 저장만 한다.
    entry_condition  JSONB,
    summary_seed     TEXT,
    min_turns        INTEGER     NOT NULL,
    max_turns        INTEGER     NOT NULL,

    CONSTRAINT chapter_def_pkey PRIMARY KEY (id),
    CONSTRAINT chapter_def_version_fkey FOREIGN KEY (story_version_id) REFERENCES story_version (id),
    CONSTRAINT chapter_def_no_key UNIQUE (story_version_id, chapter_no),
    CONSTRAINT chapter_def_no_check CHECK (chapter_no >= 1),

    -- R7.2 의 평가 순서(min_turns 충족 → 조건 → max_turns 강제 전환)는 max < min 이면 성립하지 않는다.
    CONSTRAINT chapter_def_turns_check CHECK (min_turns >= 1 AND max_turns >= min_turns)
);

-- ── 엔딩 ─────────────────────────────────────────────────────
-- R7.6 — ending_no 순으로 condition 을 평가하고 최초 매칭에서 종료를 선언한다.
-- R7.9 — endingSuggested 가 와도 조건이 매칭되지 않으면 무시한다. AI 임의 종료 불가.
CREATE TABLE ending_def (
    id               UUID        NOT NULL,
    story_version_id UUID        NOT NULL,
    story_id         UUID        NOT NULL,
    ending_no        INTEGER     NOT NULL,
    label            TEXT        NOT NULL,
    epilogue_text    TEXT        NOT NULL,
    condition        JSONB,
    visual_url       TEXT,
    is_secret        BOOLEAN     NOT NULL DEFAULT FALSE,
    is_default       BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT ending_def_pkey PRIMARY KEY (id),
    CONSTRAINT ending_def_version_fkey FOREIGN KEY (story_version_id) REFERENCES story_version (id),
    CONSTRAINT ending_def_no_key UNIQUE (story_version_id, ending_no),
    CONSTRAINT ending_def_no_check CHECK (ending_no >= 1),

    -- catalog-authoring.md — 기본 엔딩은 총계에서 빠지는 엔딩이 될 수 없다.
    -- 폴백이 감춰지면 R7.11 의 totalEndings 표기가 어긋난다.
    CONSTRAINT ending_def_default_not_secret_check CHECK (NOT (is_default AND is_secret))
);

-- R2.2 — ending_def 중 **정확히 1개**가 is_default = true 여야 한다.
-- 0개면 R7.7 의 폴백이 없어 세션이 끝나지 못하고, 2개면 어느 쪽으로 끝나는지가 행 순서에 달린다.
-- "최소 1개"는 DB 제약으로 표현할 수 없다(행 부재를 CHECK 로 볼 수 없다) — 작성 도구(B-49)와
-- 시드가 책임진다. 여기서 막는 것은 "2개 이상"이다.
CREATE UNIQUE INDEX ending_def_one_default_per_version
    ON ending_def (story_version_id)
    WHERE is_default;
