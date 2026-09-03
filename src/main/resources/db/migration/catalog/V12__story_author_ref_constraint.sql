-- ─────────────────────────────────────────────────────────────
-- story 의 공식/작성자 참조 제약 (이슈 #269, R13.1)
--
-- V2 는 author_type 의 값 CHECK 만 걸었다 — "공식 작품이면 author_ref 는 NULL" 은
-- 어디에도 강제되지 않았다. 그래서 작성자 표시명을 붙이는 두 경로가 이 사실을 다르게
-- 다뤘다: 목록(cards)은 조인 조건에 author_type = 'user' 를 넣어 막았지만, 상세는
-- author_ref 가 있으면 authorType 을 보지 않고 그대로 읽었다. 공식 작품 행에 author_ref 가
-- 채워지는 순간 상세에만 작성자 닉네임이 실리는 경로가 생긴다.
--
-- ★ 방어를 조회가 아니라 데이터에 둔다. 양방향으로 묶으면 "작성자 없는 UGC" 자리도
--   함께 막힌다 — user 인데 author_ref 가 없는 행은 상세든 목록이든 표시할 이름이 없다.
--
-- 기존 행 점검(작업 시점) — 시드(V3)의 공식 작품은 author_ref 가 NULL 이고, 애플리케이션의
-- 유일한 쓰기 경로(StoryPublisher.publishNew/publishRevision)는 항상 author_type = 'user' 와
-- 인증된 세션의 player_ref 를 함께 쓴다. WithdrawnAuthorContentHandler 도 탈퇴 시 author_ref 를
-- 지우지 않는다. 위반 행은 없었다 — 아래 제약은 정리 없이 바로 걸 수 있다.
ALTER TABLE story
    ADD CONSTRAINT story_author_type_ref_check
    CHECK ((author_type = 'official') = (author_ref IS NULL));
