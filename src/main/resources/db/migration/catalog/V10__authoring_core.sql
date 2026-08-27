-- V10 — Authoring (B-10, §2.4)
--
-- **catalog 스키마에 있지만 소유는 authoring 이다** (ADR-0002). 스키마를 나누지 않는 이유는
-- 작품(story·story_version)과 원고(story_draft)가 **같은 트랜잭션에서 움직이는 순간**이
-- 있기 때문이다 — 승인이 곧 버전 발행이다 (B-56).
--
-- **스키마 간 FK 를 만들지 않는다 (§5.3).** author_ref · reporter_ref · reviewer_ref 는
-- identity 의 값이고 session_id 는 play 의 값이다. 참조는 애플리케이션 레벨에서만 한다.
--
-- **비-Identity 스토어는 user.id 를 저장하지 않는다.** 전부 player_ref(UUID) 다.
--
-- 시각은 전부 UTC 다 (§9.1).

-- ── 작성 중인 원고 (R2.4, R8.3) ──────────────────────────────
-- payload 는 **검수 대상 원문**이다. 그래서 보관 주기와 접근 통제가 세션 데이터와 다르며
-- (R12.4), 읽는 것 자체가 감사 대상이다 (S-5, access_audit_log 의 story_draft).
CREATE TABLE story_draft (
    id             UUID        NOT NULL,
    author_ref     UUID        NOT NULL,

    -- 아직 발행되지 않은 원고는 작품이 없다. 재편집은 기존 작품을 가리킨다.
    story_id       UUID,

    -- 5단계 작성 (R8.3). 서버가 단계를 강제한다 — blocked 상태에서 다음 단계로 가지 못한다.
    step           INTEGER     NOT NULL DEFAULT 1,

    payload        JSONB       NOT NULL DEFAULT '{}'::JSONB,

    -- L0 precheck 결과 (B-50). blocked 면 다음 단계가 막힌다.
    safety_state   TEXT        NOT NULL DEFAULT 'clean',
    safety_findings JSONB      NOT NULL DEFAULT '[]'::JSONB,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT story_draft_pkey PRIMARY KEY (id),
    CONSTRAINT story_draft_step_check CHECK (step BETWEEN 1 AND 5),
    CONSTRAINT story_draft_safety_state_check
        CHECK (safety_state IN ('clean', 'warned', 'blocked'))
);

-- 작성자의 원고 목록. 최근 것부터 본다.
CREATE INDEX story_draft_author_idx ON story_draft (author_ref, updated_at DESC);

-- ── 검수 이력 (R8.6, R8.7) ───────────────────────────────────
-- **append-only 로 다룬다.** 자동 검수와 인간 검수는 같은 작품에 여러 번 일어나며
-- (unlisted→public 재검수, 블록리스트 갱신 후 재스캔), 마지막 판정만 남기면
-- **왜 그렇게 됐는지**를 잃는다.
CREATE TABLE story_review (
    id           UUID        NOT NULL,
    story_id     UUID        NOT NULL,
    stage        TEXT        NOT NULL,
    verdict      TEXT        NOT NULL,

    -- 반려 사유는 **카테고리만** 노출한다 (R8.7). 문구를 담으면 그것이 우회의 단서가 된다.
    reasons      JSONB       NOT NULL DEFAULT '[]'::JSONB,

    -- 자동 검수에는 사람이 없다.
    reviewer_ref UUID,
    note         TEXT,
    reviewed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT story_review_pkey PRIMARY KEY (id),
    CONSTRAINT story_review_stage_check CHECK (stage IN ('auto', 'human')),
    CONSTRAINT story_review_verdict_check CHECK (verdict IN ('pass', 'reject', 'hold')),
    -- 인간 검수에는 검수자가 있어야 한다. 누가 승인했는지 모르는 승인은 감사에 쓸모가 없다.
    CONSTRAINT story_review_human_has_reviewer_check
        CHECK (stage <> 'human' OR reviewer_ref IS NOT NULL)
);

-- 검수 큐는 작품별 최신 판정을 본다 (B-55).
CREATE INDEX story_review_story_idx ON story_review (story_id, reviewed_at DESC);

-- ── 신고 (R8.9, L3) ──────────────────────────────────────────
CREATE TABLE content_report (
    id           UUID        NOT NULL,
    reporter_ref UUID        NOT NULL,
    target_type  TEXT        NOT NULL,
    target_id    UUID        NOT NULL,

    -- 턴 신고는 어느 플레이에서 나왔는지가 있어야 재현된다. 작품 신고에는 없다.
    session_id   UUID,
    turn_no      INTEGER,

    reason       TEXT        NOT NULL,
    detail       TEXT,
    status       TEXT        NOT NULL DEFAULT 'open',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT content_report_pkey PRIMARY KEY (id),
    CONSTRAINT content_report_target_type_check CHECK (target_type IN ('story', 'turn')),
    CONSTRAINT content_report_reason_check
        CHECK (reason IN ('inappropriate', 'ip_violation', 'real_person', 'other')),
    CONSTRAINT content_report_status_check
        CHECK (status IN ('open', 'reviewing', 'actioned', 'dismissed')),
    -- **같은 사람이 같은 대상을 두 번 신고해도 한 건이다** (R8.9). 누적 3건이 자동 정지의
    -- 근거이므로, 중복이 세어지면 한 사람이 혼자 작품을 내릴 수 있다.
    CONSTRAINT content_report_unique_reporter UNIQUE (reporter_ref, target_type, target_id)
);

-- 신고 큐는 열린 것부터 본다 (B-57).
CREATE INDEX content_report_status_idx ON content_report (status, created_at DESC);

-- 누적 신고 수를 대상별로 센다.
CREATE INDEX content_report_target_idx ON content_report (target_type, target_id);

-- ── 블록리스트 (R2.5, R9.2, R9.4) ────────────────────────────
-- **normalized_value 로만 대조한다.** 원문을 그대로 비교하면 공백을 끼워 넣거나 글자를
-- 비슷하게 생긴 숫자로 바꾼 표기에 뚫린다 — 정규화기는 B-31 이다.
--
-- **실제 항목을 여기에 넣지 않는다** (S-11). 이 레포는 공개이며, 마이그레이션에 적힌
-- 블록리스트는 그대로 우회 목록이 된다. 운영에서 넣는다.
CREATE TABLE blocklist_entry (
    id               UUID        NOT NULL,
    kind             TEXT        NOT NULL,

    -- 사람이 읽는 값. 관리 화면이 보여 준다.
    value            TEXT        NOT NULL,

    -- 대조에 쓰는 값. 조회는 **항상 이쪽끼리** 비교한다 (R2.5).
    normalized_value TEXT        NOT NULL,

    severity         TEXT        NOT NULL DEFAULT 'block',

    -- 어디서 왔는가 — 운영 등록 · 신고 처리 · 외부 목록. 사후에 근거를 되짚는 데 쓴다.
    source           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT blocklist_entry_pkey PRIMARY KEY (id),
    CONSTRAINT blocklist_entry_kind_check
        CHECK (kind IN ('ip_title', 'character', 'real_person', 'phrase')),
    CONSTRAINT blocklist_entry_severity_check CHECK (severity IN ('block', 'warn')),
    -- 같은 정규화 값이 둘이면 대조가 두 번 일어나고, 지울 때 하나만 지워진다.
    CONSTRAINT blocklist_entry_unique_normalized UNIQUE (normalized_value)
);

-- 판정은 매 턴 일어난다. 전체를 읽어 캐시하므로(ADR-0002) 인덱스는 갱신 감지용이다.
CREATE INDEX blocklist_entry_updated_at_idx ON blocklist_entry (updated_at DESC);
