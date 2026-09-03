-- V15 — 재검토 요청 (#290, §13-59, R8.9)
--
-- **자동으로 내려간 것을 사람이 다시 보는 길**이다. 신고 누적으로 suspended 가 된 작품은
-- 이미 인간 검수 큐에 올라 있지만 (R8.9, §13-41), **작성자 쪽에서 그 사실에 대고 말할 자리가
-- 없었다** — 화면은 "이의가 있으면 문의해 주세요"라고 적고 갈 곳이 없었다 (#290).
--
-- **story.review_status 를 건드리지 않는다.** 큐가 이미 담고 있으므로 상태를 바꿀 이유가
-- 없고, 바꾸면 작성자가 검수 결과를 움직이는 길이 된다 (I-8). 이 표가 더하는 것은
-- **작성자가 다투었다는 사실**과 검수자가 큐에서 그것을 알아보는 신호 하나다.
--
-- **story 에 컬럼을 더하지 않은 이유**는 §13-42 와 같다 — 상태가 두 곳에 생기면 어느 쪽이
-- 진실인지가 매번 문제가 된다. 여기는 **일어난 일**의 기록이고, 열려 있는지 아닌지는
-- story_review 의 인간 판정과 견주어 파생된다.
--
-- **스키마 간 FK 를 만들지 않는다** (§5.3). author_ref 는 identity 의 값이다.
-- 시각은 전부 UTC 다 (§9.1).
CREATE TABLE story_appeal (
    id         UUID        NOT NULL,
    story_id   UUID        NOT NULL,

    -- 작성자의 player_ref 다. **user.id 를 저장하지 않는다** (§5.3).
    author_ref UUID        NOT NULL,

    -- **검수자만 읽는다** (S-11). 다른 사용자에게 가는 경로도, AI 페이로드로 가는 경로도
    -- 없다 — 자유 문자열이 새로 열어 준 노출면이 없어야 이 자리가 안전하다.
    reason     TEXT        NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT story_appeal_pkey PRIMARY KEY (id),
    -- 사유 없는 이의는 검수자에게 아무것도 주지 않는다. 상한은 읽을 수 있는 길이다.
    CONSTRAINT story_appeal_reason_length_check
        CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500)
);

-- **정지 건마다 한 번**을 판정할 때 작품별 최신 요청을 본다 (§13-59).
CREATE INDEX story_appeal_story_idx ON story_appeal (story_id, created_at DESC);
