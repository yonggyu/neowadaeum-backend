-- V2 — catalog 슬라이스 최소 테이블 (S-4 / B-08 축소, #48)
--
-- 슬라이스가 읽는 것만 만든다. B-08 은 이 마이그레이션으로 닫히지 않는다 —
-- genre · story_genre · ending_stat · service_config · author_profile · content_report ·
-- blocklist_entry 는 전부 범위 밖이고, 복귀 조건은 docs/tasks.md 의 제외표에 있다.
--
-- **컬럼의 출처.** backend-requirements.md §2.3(테이블 정의)은 이 레포에 없다. 없는 문서를 근거로
-- 컬럼을 지어내지 않는다. 여기 있는 것은 전부 레포 안의 문서에서 나왔다 —
-- 용어 사전 §3.1 · §4.2 · §4.4 · §4.5 · §4.6 · corrections §13-1 · §13-3 ·
-- .claude/rules/catalog-authoring.md. **그 밖의 컬럼은 넣지 않았다.**
--
-- **§13-1 이 이 마이그레이션의 중심이다.** chapter_def · ending_def · character 는
-- story_version_id 를 갖는다. story_id 로 묶으면 작성자가 캐릭터 성격이나 엔딩 조건을 고칠 때
-- 진행 중인 모든 세션이 즉시 영향을 받고, 버전 고정이 world_prompt 하나에만 걸린다.
-- story_id 는 조회 편의용 비정규화 컬럼일 뿐이며 참조 무결성의 근거가 아니다.
--
-- 스키마 간 FK 를 만들지 않는다 (§5.3). catalog 안에서만 FK 를 건다.
-- 비-Identity 스토어이므로 user.id 를 저장하지 않는다 (I-3).
-- 시각은 전부 UTC 다 (§9.1).

-- ── 작품 ─────────────────────────────────────────────────────
-- I-19 — age_rating 컬럼을 만들지 않는다. 단일 등급이므로 상수 "15세 이용가" 를 응답한다.
--        컬럼이 생기는 순간 작품마다 다른 값이 들어갈 수 있게 되고, 그때부터는 등급이 데이터가 된다.
CREATE TABLE story (
    id                 UUID        NOT NULL,
    kind               TEXT        NOT NULL,
    title              TEXT        NOT NULL,
    synopsis           TEXT,
    current_version_id UUID,
    review_status      TEXT        NOT NULL,
    visibility         TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT story_pkey PRIMARY KEY (id),
    CONSTRAINT story_kind_check CHECK (kind IN ('official', 'user')),

    -- catalog-authoring.md 의 전이표. auto_rejected 는 내부 기록용이고 사용자에게는 rejected 로 보인다.
    CONSTRAINT story_review_status_check
        CHECK (review_status IN ('draft', 'pending', 'auto_rejected', 'in_review',
                                 'approved', 'rejected', 'suspended')),
    CONSTRAINT story_visibility_check CHECK (visibility IN ('private', 'unlisted', 'public'))
);

-- current_version_id 에 FK 를 걸지 않는다.
-- story → story_version → story 로 순환하므로 FK 를 양쪽에 걸면 시드와 버전 발행이
-- DEFERRABLE 트랜잭션에 묶인다. 참조 방향의 진실은 story_version.story_id 쪽이고,
-- current_version_id 는 "지금 어느 버전을 보여줄 것인가"라는 포인터다 (§4.2, §4.7).
-- 정합성은 CatalogSeedTests 가 확인한다.

-- ── 작품 버전 ────────────────────────────────────────────────
-- 작품의 불변 스냅샷. 세션은 이 id 에 고정된다 (I-4, R8.8).
CREATE TABLE story_version (
    id           UUID        NOT NULL,
    story_id     UUID        NOT NULL,
    version_no   INTEGER     NOT NULL,
    world_prompt TEXT        NOT NULL,

    -- §13-3 — 상태 키 화이트리스트. 조건 평가기(S-6)와 GameState 병합(S-5)이 이걸로
    -- 미정의 키를 걸러낸다. AI 가 제안한 키를 그대로 병합하지 않기 위한 근거다.
    state_schema JSONB       NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT story_version_pkey PRIMARY KEY (id),
    CONSTRAINT story_version_story_fkey FOREIGN KEY (story_id) REFERENCES story (id),
    CONSTRAINT story_version_no_key UNIQUE (story_id, version_no),
    CONSTRAINT story_version_no_check CHECK (version_no >= 1)
);

-- ── 챕터 ─────────────────────────────────────────────────────
-- 사전 정의된 막. 전환 판정은 서버가 GameState 로 한다 (I-10, §4.5).
-- AI 의 chapterAdvanceSuggested 는 로그로만 남는다 (R7.1).
CREATE TABLE chapter_def (
    id               UUID        NOT NULL,
    story_version_id UUID        NOT NULL,
    story_id         UUID        NOT NULL,
    chapter_no       INTEGER     NOT NULL,
    title            TEXT        NOT NULL,

    -- 1장은 진입 조건이 없다. 조건 DSL 은 S-6 이며 여기서는 저장만 한다.
    entry_condition  JSONB,
    min_turns        INTEGER     NOT NULL,
    max_turns        INTEGER     NOT NULL,

    CONSTRAINT chapter_def_pkey PRIMARY KEY (id),
    CONSTRAINT chapter_def_version_fkey FOREIGN KEY (story_version_id) REFERENCES story_version (id),
    CONSTRAINT chapter_def_no_key UNIQUE (story_version_id, chapter_no),
    CONSTRAINT chapter_def_no_check CHECK (chapter_no >= 1),

    -- §4.5 — min_turns 충족 후 조건 평가, 불만족이면 max_turns 에서 강제 전환.
    -- max < min 이면 그 순서가 성립하지 않는다.
    CONSTRAINT chapter_def_turns_check CHECK (min_turns >= 1 AND max_turns >= min_turns)
);

-- ── 엔딩 ─────────────────────────────────────────────────────
-- ending_no 오름차순으로 순회해 최초 매칭에서 종료한다 (§4.6).
-- endingSuggested 가 와도 조건이 매칭되지 않으면 무시한다 (I-10, R7.9).
CREATE TABLE ending_def (
    id               UUID        NOT NULL,
    story_version_id UUID        NOT NULL,
    story_id         UUID        NOT NULL,
    ending_no        INTEGER     NOT NULL,
    label            TEXT        NOT NULL,
    condition        JSONB,
    epilogue         TEXT        NOT NULL,
    is_default       BOOLEAN     NOT NULL DEFAULT FALSE,
    is_secret        BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT ending_def_pkey PRIMARY KEY (id),
    CONSTRAINT ending_def_version_fkey FOREIGN KEY (story_version_id) REFERENCES story_version (id),
    CONSTRAINT ending_def_no_key UNIQUE (story_version_id, ending_no),
    CONSTRAINT ending_def_no_check CHECK (ending_no >= 1),

    -- 기본 엔딩은 총계에서 빠지는 엔딩이 될 수 없다. 폴백이 감춰지면 도달률 표기가 어긋난다 (R7.11).
    CONSTRAINT ending_def_default_not_secret_check CHECK (NOT (is_default AND is_secret)),

    -- 기본 엔딩은 "어떤 조건에도 걸리지 않을 때"의 폴백이다 (§3.1). 조건을 가지면 폴백이 아니고,
    -- 조건 없는 일반 엔딩은 ending_no 순회에서 항상 최초 매칭이 되어 뒤를 전부 가린다.
    CONSTRAINT ending_def_default_has_no_condition_check CHECK (is_default = (condition IS NULL))
);

-- 기본 엔딩은 작품 버전당 정확히 1개 (§3.1, catalog-authoring.md).
-- 0개면 조건 미매칭 세션이 끝나지 못하고, 2개면 어느 쪽으로 끝나는지가 행 순서에 달린다.
CREATE UNIQUE INDEX ending_def_one_default_per_version
    ON ending_def (story_version_id)
    WHERE is_default;

-- ── 캐릭터 ───────────────────────────────────────────────────
-- CHARACTER 레이어는 표시 순서대로 프롬프트에 들어간다 (§4.4).
CREATE TABLE character (
    id               UUID        NOT NULL,
    story_version_id UUID        NOT NULL,
    story_id         UUID        NOT NULL,
    name             TEXT        NOT NULL,
    persona_prompt   TEXT        NOT NULL,
    display_order    INTEGER     NOT NULL,

    CONSTRAINT character_pkey PRIMARY KEY (id),
    CONSTRAINT character_version_fkey FOREIGN KEY (story_version_id) REFERENCES story_version (id),
    CONSTRAINT character_display_order_key UNIQUE (story_version_id, display_order),
    CONSTRAINT character_display_order_check CHECK (display_order >= 1)
);
