-- V2 — 세션 · 턴 · GameState 스냅샷 (S-2 / B-09 축소, #39)
--
-- 슬라이스가 처음으로 상태를 저장하는 지점이다. S-5(GameState) · S-7(Chapter·Ending) ·
-- S-9(Turn 오케스트레이터)가 전부 이 세 테이블 위에 올라간다.
--
-- 여기서 중요한 것은 컬럼 목록이 아니라 **불변 규칙이 제약으로 표현되는가**다.
--   I-4  세션은 생성 시 story_version_id · provider_id · model_id 에 고정된다 → NOT NULL, 갱신 없음
--   I-5  스냅샷은 append-only → 턴당 살아 있는 행 1개, UPDATE 하지 않는다
--   I-6  turn_no 는 낙관적 잠금 키 → 세션과 턴 양쪽에 존재하고 (session_id, turn_no) 가 유일하다
--   I-1  choiceId 는 서버가 발급한다 → 선택지는 turn.choices 안에서만 존재한다
--
-- **스키마 간 FK 를 만들지 않는다 (§5.3).** story_id · story_version_id · ending_id 는 catalog 스키마,
-- player_ref 는 identity 가 발급한 값이다. 참조는 애플리케이션 레벨에서만 한다. 같은 play 스키마
-- 안(turn·game_state_snapshot → play_session)에서만 FK 를 건다.
--
-- **비-Identity 스토어는 user.id 를 저장하지 않는다 (§5.3, I-3).** player_ref(UUID) 만 저장한다.
--
-- 시각은 전부 UTC 다 (§9.1). timestamptz 로 두어 서버 타임존이 바뀌어도 값의 의미가 변하지 않게 한다.

-- ── 세션 ─────────────────────────────────────────────────────
CREATE TABLE play_session (
    id               UUID        NOT NULL,
    player_ref       UUID        NOT NULL,
    story_id         UUID        NOT NULL,
    story_version_id UUID        NOT NULL,
    provider_id      TEXT        NOT NULL,
    model_id         TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    turn_no          INTEGER     NOT NULL,
    chapter_no       INTEGER     NOT NULL,
    is_test_session  BOOLEAN     NOT NULL DEFAULT FALSE,
    ending_id        UUID,
    started_at       TIMESTAMPTZ NOT NULL,
    last_played_at   TIMESTAMPTZ NOT NULL,
    completed_at     TIMESTAMPTZ,
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT play_session_pkey PRIMARY KEY (id),

    -- §13-6 정정 — 상태값은 이 넷뿐이다. API 쿼리 파라미터의 `in_progress` 는 상태가 아니다.
    CONSTRAINT play_session_status_check
        CHECK (status IN ('active', 'completed', 'abandoned', 'expired')),

    -- 턴 1 이 세션 생성과 함께 만들어진다 (§4.2). 0 은 아직 어떤 턴도 없는 과도 상태다.
    CONSTRAINT play_session_turn_no_check CHECK (turn_no >= 0),
    CONSTRAINT play_session_chapter_no_check CHECK (chapter_no >= 1),

    -- 종료 시각은 종료된 세션에만 있다 (§4.6). 반대도 성립해야 한다 —
    -- completed 인데 completed_at 이 비어 있으면 통계·정리 배치가 조용히 어긋난다.
    CONSTRAINT play_session_completed_at_check
        CHECK ((status = 'completed') = (completed_at IS NOT NULL))
);

-- §13-9 — 작품당 active 세션 1개.
-- 애플리케이션이 먼저 확인하더라도(§4.2) 동시 요청 두 개는 그 확인을 함께 통과한다.
-- 마지막 방어선은 DB 여야 한다. restart=true 는 기존 active 를 abandoned 로 바꾼 뒤 새로 만든다.
CREATE UNIQUE INDEX play_session_one_active_per_story
    ON play_session (player_ref, story_id)
    WHERE status = 'active';

-- ── 턴 ───────────────────────────────────────────────────────
CREATE TABLE turn (
    id               UUID        NOT NULL,
    session_id       UUID        NOT NULL,
    turn_no          INTEGER     NOT NULL,
    chapter_no       INTEGER     NOT NULL,
    paragraphs       JSONB       NOT NULL,
    choices          JSONB       NOT NULL,
    chosen_choice_id TEXT,
    chosen_at        TIMESTAMPTZ,
    is_ending        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT turn_pkey PRIMARY KEY (id),
    CONSTRAINT turn_session_fkey FOREIGN KEY (session_id) REFERENCES play_session (id),

    -- I-6 — 한 세션에 같은 번호의 턴이 둘일 수 없다. 낙관적 잠금이 성립하는 근거다.
    CONSTRAINT turn_session_turn_no_key UNIQUE (session_id, turn_no),
    CONSTRAINT turn_turn_no_check CHECK (turn_no >= 1),

    -- §13-9 isPending — 마지막 턴이며 chosen_choice_id 가 비어 있으면 true 다.
    -- 선택 시각은 선택과 함께 기록된다(§4.3-3). 한쪽만 있는 상태를 만들지 않는다.
    CONSTRAINT turn_chosen_pair_check
        CHECK ((chosen_choice_id IS NULL) = (chosen_at IS NULL))
);

-- ── GameState 스냅샷 ─────────────────────────────────────────
-- I-5 — 턴마다 1행을 append 한다. **덮어쓰지 않는다.** 덮어쓰는 순간 롤백(R14.4)이 불가능해진다.
CREATE TABLE game_state_snapshot (
    id         UUID        NOT NULL,
    session_id UUID        NOT NULL,
    turn_no    INTEGER     NOT NULL,
    state      JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT game_state_snapshot_pkey PRIMARY KEY (id),
    CONSTRAINT game_state_snapshot_session_fkey FOREIGN KEY (session_id) REFERENCES play_session (id),
    CONSTRAINT game_state_snapshot_turn_no_check CHECK (turn_no >= 0)
);

-- §13-9 — 롤백은 soft delete 로 한다. 되돌린 스냅샷은 지우지 않고 deleted_at 을 채운다.
-- 유일성은 **살아 있는 행 기준**이다. 전체 기준으로 잠그면 재생성(B-42)이 같은 턴 번호로
-- 새 스냅샷을 남길 수 없게 되고, 결국 UPDATE 로 되돌아가 I-5 가 깨진다.
CREATE UNIQUE INDEX game_state_snapshot_live_turn_key
    ON game_state_snapshot (session_id, turn_no)
    WHERE deleted_at IS NULL;

-- story_summary 는 여기 없다. 슬라이스가 S-10 이후로 미뤘고 요약 파이프라인(B-34)은 슬라이스 밖이다
-- (ADR-0004). 필요해지는 시점에 V3 로 추가한다 — 이미 머지된 마이그레이션은 고치지 않는다.
