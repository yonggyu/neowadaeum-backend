-- ────────────────────────────────────────────────────────────
-- B-34 (#118) — 요약 저장소.
--
-- V2 가 이 테이블을 비워 두며 남긴 주석의 조건이 성립했다: 요약 파이프라인이
-- 시작된다. 컬럼은 요구사항 §2.5 의 정의를 그대로 따른다.
--
-- R2.6 — append-only 다. game_state_snapshot 과 같은 이유이며(I-5), 덮어쓰면
-- 롤백(R14.4)이 "스냅샷과 요약을 함께 되돌린다"를 지킬 수 없다.
--
-- (session_id, upto_turn_no) 에 유일성을 걸지 않는다. R4.5 의 재압축은
-- **같은 upto_turn_no 에 대해 더 짧은 요약을 새로 남기는 일**이며, 잠그면
-- 그것이 UPDATE 로 돌아가고 append-only 가 깨진다. 현재 요약은 "살아 있는 행 중
-- 가장 최근"이며 그 순서는 (upto_turn_no, created_at) 이다.
-- ────────────────────────────────────────────────────────────

CREATE TABLE story_summary (
    id             UUID        NOT NULL,
    session_id     UUID        NOT NULL,
    upto_turn_no   INTEGER     NOT NULL,
    summary_text   TEXT        NOT NULL,
    token_estimate INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    deleted_at     TIMESTAMPTZ,

    CONSTRAINT story_summary_pkey PRIMARY KEY (id),
    CONSTRAINT story_summary_session_fkey FOREIGN KEY (session_id) REFERENCES play_session (id),

    -- 0 턴까지의 요약은 요약할 것이 없다는 뜻이라 행을 만들 이유가 없다.
    CONSTRAINT story_summary_upto_turn_no_check CHECK (upto_turn_no >= 1),
    CONSTRAINT story_summary_token_estimate_check CHECK (token_estimate >= 0),
    CONSTRAINT story_summary_text_not_blank_check CHECK (length(btrim(summary_text)) > 0)
);

-- 프롬프트 조립이 매 턴 읽는 경로다 — "이 세션의 살아 있는 최신 요약".
CREATE INDEX story_summary_session_live_idx
    ON story_summary (session_id, upto_turn_no DESC, created_at DESC)
    WHERE deleted_at IS NULL;
