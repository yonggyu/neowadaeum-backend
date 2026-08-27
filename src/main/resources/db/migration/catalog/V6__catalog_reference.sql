-- V6 — catalog 분류 테이블 (B-08 1/2): genre · story_genre (§2.7)
--
-- V2 는 작품과 그 버전을 세웠고 V3 · V5 가 시드를 넣었다. 남은 넷 중 둘을 여기서 더한다.
-- 작성자 표시명(author_profile)과 도달률(ending_stat)은 V7 (B-08 2/2) 다.
--
-- **스키마 간 FK 를 만들지 않는다** (§5.3, R2.9). 여기 두 표의 FK 는 전부 catalog 안이다.
--
-- 시각은 전부 UTC 다.

-- ── 장르 (§2.7) ──────────────────────────────────────────────
-- key 가 API 표기이고 label 은 화면 문구다. 문구를 코드에 하드코딩하지 않기 위해 둘을 나눈다.
--
-- 컬럼 이름은 원문 그대로 key 다. PostgreSQL 에서 비예약어라 인용부호가 필요 없다.
-- 다만 JPQL 에서 key 는 예약어이므로 엔티티 필드 이름은 genreKey 로 둔다 — 컬럼과 필드가
-- 다른 유일한 자리이며, 그 매핑은 @Column 한 줄에 있다.
CREATE TABLE genre (
    id            UUID    NOT NULL,
    key           TEXT    NOT NULL,
    label         TEXT    NOT NULL,
    display_order INTEGER NOT NULL,

    CONSTRAINT genre_pkey PRIMARY KEY (id),
    -- key 가 유일하지 않으면 API 필터가 두 장르를 같은 이름으로 가리킨다.
    CONSTRAINT genre_key_key UNIQUE (key),
    CONSTRAINT genre_display_order_key UNIQUE (display_order),
    CONSTRAINT genre_display_order_check CHECK (display_order >= 1)
);

-- ── 작품 ↔ 장르 (§2.7) ───────────────────────────────────────
-- 한 작품이 여러 장르를 갖는다. 두 컬럼이 함께 PK 이므로 같은 짝이 두 번 들어가지 않는다.
CREATE TABLE story_genre (
    story_id UUID NOT NULL,
    genre_id UUID NOT NULL,

    CONSTRAINT story_genre_pkey PRIMARY KEY (story_id, genre_id),
    CONSTRAINT story_genre_story_fkey FOREIGN KEY (story_id) REFERENCES story (id),
    CONSTRAINT story_genre_genre_fkey FOREIGN KEY (genre_id) REFERENCES genre (id)
);

-- 장르로 작품을 찾는 방향 (B-15 Library 의 필터). PK 는 반대 방향만 커버한다.
CREATE INDEX story_genre_genre_idx ON story_genre (genre_id);
