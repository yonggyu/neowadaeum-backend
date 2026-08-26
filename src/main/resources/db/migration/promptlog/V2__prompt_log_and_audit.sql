-- V2 — ai_call_log · 감사 로그 · 서비스 설정 (B-11, §13-4)
--
-- 원문 보관처가 처음 생기는 지점이다. B-22 로 실 Provider 가 붙은 뒤 지금까지
-- **요청·응답 원문이 어디에도 남지 않았다** — 구조화 로그만 있고 원문은 없었다.
-- 그래서 "모델이 이상한 이야기를 썼다"를 사후에 확인할 방법이 없었다 (§13-20).
--
-- 여기서 중요한 것은 컬럼 목록이 아니라 **어떤 규칙이 제약으로 표현되는가**다.
--   I-3  ai_call_log 에 player_ref 를 담지 않는다 → 그 컬럼이 존재하지 않는다. session_id 로만 역추적한다
--   S-3  원문 보관처는 여기뿐이다 → 애플리케이션 로그에는 남기지 않는다
--   R2.10 이 테이블은 접근 통제 대상이다 → 원문을 읽는 경로가 access_audit_log 를 남긴다 (R12.3)
--
-- **스키마 간 FK 를 만들지 않는다 (§5.3).** session_id 는 play, draft_id 는 authoring,
-- admin_user_id 는 identity 의 값이다. 참조는 애플리케이션 레벨에서만 한다.
--
-- 시각은 전부 UTC 다 (§9.1).

-- ── AI 호출 원문 (R3.7 · R9.3 · R12.3) ───────────────────────
-- purpose 4종은 R3.6 의 용도별 모델 분리와 같은 축이다. 값을 CHECK 로 못박는다 —
-- 오타 하나가 통계에서 조용히 빠지는 것을 막는다.
CREATE TABLE ai_call_log (
    id             UUID        NOT NULL,
    session_id     UUID,
    draft_id       UUID,
    purpose        TEXT        NOT NULL,
    provider_id    TEXT        NOT NULL,
    model_id       TEXT        NOT NULL,
    -- R3.7 — fallback 발동 사실을 남긴다. 발동하지 않았으면 NULL 이다.
    fallback_from  TEXT,
    request_raw    TEXT        NOT NULL,
    response_raw   TEXT,
    input_tokens   INTEGER,
    output_tokens  INTEGER,
    latency_ms     INTEGER,
    cost_micro     BIGINT,
    -- R9.3 — 세이프티 판정 결과. 카테고리 배열이며 통과면 빈 배열이다.
    safety_flags   JSONB       NOT NULL DEFAULT '[]'::JSONB,
    -- R5.8 · R3.3 — 재요청은 같은 턴의 별도 호출이다. 1부터 센다.
    attempt_no     INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ai_call_log_pkey PRIMARY KEY (id),
    CONSTRAINT ai_call_log_purpose_check
        CHECK (purpose IN ('turn', 'summary', 'safety', 'outline')),
    CONSTRAINT ai_call_log_attempt_no_check CHECK (attempt_no >= 1)
);

-- 역추적은 세션 단위다 (I-3). 최신부터 읽으므로 created_at 은 내림차순으로 둔다.
CREATE INDEX ai_call_log_session_idx ON ai_call_log (session_id, created_at DESC);

-- R12.4 파기 배치(B-61)가 90일 경과분을 훑는다.
CREATE INDEX ai_call_log_created_at_idx ON ai_call_log (created_at);

-- ── 관리자 행위 감사 (R14.5, S-4) ────────────────────────────
CREATE TABLE admin_audit_log (
    id            UUID        NOT NULL,
    admin_user_id UUID        NOT NULL,
    action        TEXT        NOT NULL,
    target_type   TEXT        NOT NULL,
    target_id     UUID,
    payload       JSONB       NOT NULL DEFAULT '{}'::JSONB,
    -- IP 원문을 두지 않는다. 같은 접속자인지 비교하는 데는 해시로 충분하다 (§12 개인정보 최소화).
    ip_hash       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT admin_audit_log_pkey PRIMARY KEY (id)
);

CREATE INDEX admin_audit_log_admin_idx ON admin_audit_log (admin_user_id, created_at DESC);

-- ── 원문 열람 감사 (R12.3, S-5) ──────────────────────────────
-- ai_call_log 를 읽는 것 자체가 기록 대상이다. 원문이 여기에만 있기 때문이다.
CREATE TABLE access_audit_log (
    id            UUID        NOT NULL,
    admin_user_id UUID        NOT NULL,
    resource      TEXT        NOT NULL,
    resource_id   UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT access_audit_log_pkey PRIMARY KEY (id),
    CONSTRAINT access_audit_log_resource_check
        CHECK (resource IN ('ai_call_log', 'story_draft'))
);

CREATE INDEX access_audit_log_resource_idx ON access_audit_log (resource, resource_id);

-- ── 운영 중 바뀌는 설정 (R11.1) ──────────────────────────────
-- AI 사전 고지 문구(B-14)가 코드에 하드코딩되지 않게 하는 자리다.
CREATE TABLE service_config (
    config_key  TEXT        NOT NULL,
    config_value JSONB      NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT service_config_pkey PRIMARY KEY (config_key)
);
