-- V7 — catalog 표시명·집계 테이블 (B-08 2/2): author_profile · ending_stat (§2.6 · §13-7)
--
-- V6 가 분류를 세웠다. 남은 둘을 여기서 더한다 — 둘 다 **작품 자체가 아니라 작품을 둘러싼 것**이다.
--
-- 규칙이 제약으로 표현되는 자리:
--   I-3    author_profile 은 player_ref 를 PK 로 갖는다. user.id 를 담지 않는다 (§13-7)
--   §2.6   ending_stat 의 집계 키는 (story_id, ending_no) 다. ending_id 가 아니다
--   I-20   도달률은 배치가 갱신한다 (B-39). 이 표에 실시간 계산 경로를 두지 않는다
--   R2.8   total_completed_count < 50 이면 API 가 null 을 반환한다 — 그 판정은 조회 쪽이며
--          여기서는 원자료만 센다
--
-- **스키마 간 FK 를 만들지 않는다** (§5.3, R2.9). player_ref 는 identity 의 값이고,
-- ending_stat 은 play 의 세션 결과로 갱신된다 — 참조는 애플리케이션 레벨에서만 한다.
--
-- 시각은 전부 UTC 다.

-- ── 작성자 표시명 (§13-7) ────────────────────────────────────
-- §13.3 은 authorDisplayName 을 반환하지만 catalog 는 player_ref 만 알고, 스토어 분리 원칙상
-- identity 를 조회할 수도 없다. 닉네임은 회원 식별정보가 아니라 **공개 표시명**이므로
-- catalog 가 보관한다. 설정 시 identity 가 catalog 파사드로 동기화한다.
--
-- **PK 가 player_ref 다** (I-3). user.id 를 담을 컬럼이 존재하지 않는다.
CREATE TABLE author_profile (
    player_ref   UUID        NOT NULL,
    display_name TEXT        NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT author_profile_pkey PRIMARY KEY (player_ref)
);

-- ── 엔딩 도달률 원자료 (§2.6) ────────────────────────────────
-- **집계 키가 (story_id, ending_no) 다.** ending_id 로 세면 버전을 발행할 때마다 행이 새로
-- 생겨 도달률이 0 부터 다시 시작한다 — 같은 엔딩인데 통계가 끊긴다 (§13-1 부수 영향).
--
-- **I-20 — 배치가 갱신한다** (B-39). 조회 경로가 이 값을 계산하지 않는다.
-- R2.8 의 "표본 50 미만이면 null" 판정도 여기가 아니라 조회 쪽이다 — 이 표는 센 값만 갖는다.
CREATE TABLE ending_stat (
    story_id              UUID        NOT NULL,
    ending_no             INTEGER     NOT NULL,
    reached_count         BIGINT      NOT NULL DEFAULT 0,
    -- 분모. 그 작품에서 엔딩에 도달한 세션 전체 수다.
    total_completed_count BIGINT      NOT NULL DEFAULT 0,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ending_stat_pkey PRIMARY KEY (story_id, ending_no),
    CONSTRAINT ending_stat_story_fkey FOREIGN KEY (story_id) REFERENCES story (id),
    CONSTRAINT ending_stat_ending_no_check CHECK (ending_no >= 1),
    CONSTRAINT ending_stat_reached_count_check CHECK (reached_count >= 0),
    CONSTRAINT ending_stat_total_check CHECK (total_completed_count >= reached_count)
);
