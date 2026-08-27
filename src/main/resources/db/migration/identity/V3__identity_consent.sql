-- V3 — identity 도메인 테이블 (2/2): 동의와 고지 노출 (B-07, §2.2 · §2.7)
--
-- V2 가 회원과 소셜 로그인을 세웠다. 여기서 남은 둘을 더한다 — B-13(연령 게이트)과
-- B-14(AI 사전 고지)가 각각 쓸 표다.
--
-- 이 두 표의 공통 성질은 **append-only** 다. 동의도 노출도 시점의 사실이고,
-- 저장한 뒤 고칠 수 있게 만드는 순간 증빙이기를 그만둔다.
--
-- **§13-8 — 둘을 한 표에 넣지 않는다.** R11.3 은 고지 노출 이력을 consent_log 에
-- 기록하라고 하지만 노출은 동의가 아니라 표시 사실이다. 섞으면 동의 이력의 법적
-- 증빙력이 흐려진다.
--
-- FK 는 identity 스키마 안에서만 건다 (§5.3, R2.9). 시각은 전부 UTC 다.

-- ── 동의 이력 (§2.2, R10.2) ──────────────────────────────────
-- **append-only 다.** 동의는 시점의 사실이며 나중에 고치는 것은 증빙을 고치는 것이다.
-- 철회나 재동의는 UPDATE 가 아니라 새 행이다.
--
-- version 이 있는 이유 — 약관이 개정되면 같은 consent_type 을 다시 받아야 하고,
-- "어느 판본에 동의했는가"가 법적 증빙의 핵심이다.
CREATE TABLE consent_log (
    id           UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    consent_type TEXT        NOT NULL,
    version      TEXT        NOT NULL,
    agreed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- IP 원문을 두지 않는다. 같은 접속자인지 비교하는 데는 해시로 충분하다 (§12).
    ip_hash      TEXT,

    CONSTRAINT consent_log_pkey PRIMARY KEY (id),
    CONSTRAINT consent_log_user_fkey FOREIGN KEY (user_id) REFERENCES "user" (id),
    -- §13-8 — ai_notice 는 "AI 고지를 읽고 **동의**함"에만 쓴다. 노출 사실은 아래 표다.
    CONSTRAINT consent_log_type_check
        CHECK (consent_type IN ('tos', 'privacy', 'ai_notice', 'age'))
);

-- 최신 동의를 타입별로 찾는다 — "이 회원이 현재 판본에 동의했는가"가 가장 잦은 질문이다.
CREATE INDEX consent_log_user_type_idx ON consent_log (user_id, consent_type, agreed_at DESC);

-- ── AI 사전 고지 노출 이력 (§2.7, §13-8, R11.3) ──────────────
-- **consent_log 와 분리된 테이블이다.** R11.3 은 노출 이력을 consent_log 에 넣으라고 하지만,
-- 노출은 동의가 아니라 표시 사실이다 — 섞으면 동의 이력의 증빙력이 흐려진다 (§13-8).
--
-- 고지 문구는 코드가 아니라 service_config 에 있고 (R11.1, B-11 에서 만들었다),
-- notice_version 이 "그때 무엇을 보여 줬는가"를 그 설정과 이어 준다.
CREATE TABLE ai_notice_impression (
    id             UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    notice_version TEXT        NOT NULL,
    surface        TEXT        NOT NULL,
    shown_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ai_notice_impression_pkey PRIMARY KEY (id),
    CONSTRAINT ai_notice_impression_user_fkey FOREIGN KEY (user_id) REFERENCES "user" (id),
    -- §2.7 원문의 4종. 화면이 늘면 마이그레이션으로 값을 넓힌다 — 코드가 조용히 늘리지 못한다.
    CONSTRAINT ai_notice_impression_surface_check
        CHECK (surface IN ('landing', 'library', 'detail', 'play'))
);

CREATE INDEX ai_notice_impression_user_idx ON ai_notice_impression (user_id, shown_at DESC);
