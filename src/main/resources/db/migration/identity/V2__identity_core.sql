-- V2 — identity 도메인 테이블 (1/2): 회원과 소셜 로그인 (B-07, §2.2)
--
-- V1 은 의도적으로 빈 베이스라인이었고 "도메인 테이블은 B-07 에서 추가한다"고 적어 뒀다.
-- 그 자리를 여기서 채우기 시작한다. 다음 작업(B-12 Google OAuth)이 로그인 결과를 남길 곳이다.
-- 동의 이력과 고지 노출 이력은 V3 (B-07 2/2) 다.
--
-- 컬럼 목록보다 중요한 것은 **어떤 규칙이 제약으로 표현되는가**다.
--   §2.1 / I-3  비-Identity 스토어는 user.id 를 담지 않는다 → player_ref 가 회원당 1개이며 UNIQUE 다
--   §2.2        나이를 캐시하지 않는다 → birth_date 원본만 두고 age 컬럼을 만들지 않는다 (생일 경과 처리)
--   §12         개인정보 최소화 → 이메일은 원문이 아니라 해시로만 남는다
--
-- **FK 는 identity 스키마 안에서만 건다** (§5.3, R2.9). 스키마 밖으로 나가는 순간
-- 이 스토어를 별도 인스턴스로 승격할 수 없게 된다.
--
-- 시각은 전부 UTC 다.

-- ── 회원 (§2.2) ──────────────────────────────────────────────
-- user 는 PostgreSQL 예약어다. 원문의 이름을 바꾸지 않고 인용부호로 감싼다 —
-- 엔티티도 같은 방식으로 인용하므로 hbm2ddl validate 가 일치를 확인한다.
--
-- status 3종은 §2.2 원문 그대로다. withdrawn 은 삭제가 아니라 상태다 —
-- 실제 파기·익명화는 B-61 의 배치가 수행한다 (R12.4).
CREATE TABLE "user" (
    id              UUID        NOT NULL,
    -- I-3 의 실질. 비-Identity 스토어가 회원을 가리키는 유일한 값이며 회원정보와 무관하다.
    -- UNIQUE 가 "회원당 1개"를 강제한다 (§2.1).
    player_ref      UUID        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'active',
    -- §2.2 — 나이만 캐시하지 않고 원본을 둔다. 생일이 지나면 나이가 바뀌기 때문이다.
    -- 가입 연령 게이트(B-13)가 이 값으로 만 15세를 판정한다.
    birth_date      DATE,
    -- 연령 확인을 마친 시각. 미확인 회원은 NULL 이다.
    age_verified_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT user_pkey PRIMARY KEY (id),
    CONSTRAINT user_player_ref_key UNIQUE (player_ref),
    CONSTRAINT user_status_check
        CHECK (status IN ('active', 'suspended', 'withdrawn'))
);

-- ── 소셜 로그인 (§2.2, §13-11) ───────────────────────────────
-- MVP 는 Google 하나다 (§13-11 채택안). provider 값을 CHECK 로 못박되 apple 을 함께 둔다 —
-- 원문이 두 값을 규정했고, 목록에 없는 값이 조용히 들어오는 것을 막는 것이 CHECK 의 목적이다.
--
-- **이메일 원문을 두지 않는다.** 같은 사람인지 비교하는 데는 해시로 충분하며,
-- AI 페이로드로 새어 나갈 원문 자체를 만들지 않는 것이 I-3 를 구조로 보장하는 방법이다.
-- 이메일 로그인을 도입한다면 그때 user 에 암호화 컬럼을 더한다 (§13-11).
CREATE TABLE oauth_identity (
    id         UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    provider   TEXT        NOT NULL,
    -- OAuth provider 가 발급한 계정 식별자(sub). provider 안에서만 유일하다.
    subject    TEXT        NOT NULL,
    email_hash TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT oauth_identity_pkey PRIMARY KEY (id),
    CONSTRAINT oauth_identity_user_fkey FOREIGN KEY (user_id) REFERENCES "user" (id),
    CONSTRAINT oauth_identity_provider_check CHECK (provider IN ('google', 'apple')),
    -- 같은 provider 의 같은 계정이 두 회원에 붙으면 로그인이 어느 쪽으로도 갈 수 있다.
    CONSTRAINT oauth_identity_subject_key UNIQUE (provider, subject)
);

CREATE INDEX oauth_identity_user_idx ON oauth_identity (user_id);
