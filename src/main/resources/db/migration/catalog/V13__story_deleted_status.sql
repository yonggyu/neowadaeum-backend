-- ─────────────────────────────────────────────────────────────
-- 작성자가 게시된 작품을 지운다 (이슈 #290-3, docs/corrections.md §13-58)
--
-- 지운다는 말을 행 삭제로 옮길 수 없다. 작품에는 **플레이한 사람들의** 기록이
-- 매달려 있다 — play_session · turn · game_state_snapshot 이 story_id 와
-- story_version_id 를 들고 있고, ending_stat 은 (story_id, ending_no) 로 집계된다.
-- 스키마 간 FK 가 없으므로(§5.3) DB 가 막아 주지도 않는다: 행을 지우면 그 기록들이
-- 조용히 어디도 가리키지 않는 값이 된다. §13-44 가 탈퇴 파기에 대해 같은 이유로
-- 도달률을 되돌리지 않기로 한 것과 같은 판단이다.
--
-- ★ 그래서 상태로 내린다. 'deleted' 는 review_status 의 값이다 — visibility 가 아니다.
--   visibility 는 작성자가 PATCH 로 언제든 되돌리는 자유로운 다이얼이고(private ·
--   unlisted · public 은 넓이의 눈금이다), review_status 는 §13-9 가 전이표를 가진
--   상태 머신으로 정의한 자리다. 삭제는 되돌아오는 길이 없어야 하므로 가드가 있는
--   쪽에 두어야 한다. visibility 에 두면 PATCH /visibility 한 번이 삭제를 되돌린다.
--
-- ★ 여기서 나가는 전이는 없다. 열거값을 늘릴 뿐 CHECK 로 단방향을 강제하지는 못한다
--   — CHECK 는 한 행의 현재 값만 보고 이전 값을 모른다. 단방향은 애플리케이션이
--   지킨다(StoryPublisher 의 applyReview · suspend 가 'deleted' 행을 건드리지 않고,
--   statusOf · ownerStatusOf 가 그 행을 보지 못한다). 트리거로 옮기지 않는 이유는
--   상태 전이 규칙이 두 곳에 생기는 쪽이 더 위험하기 때문이다.
--
-- 기존 행 점검(작업 시점) — 'deleted' 는 새 값이므로 위반 행이 있을 수 없다.
-- 값 목록을 넓히기만 하는 변경이라 되돌리기(contract)도 기존 행을 보지 않는다.
ALTER TABLE story
    DROP CONSTRAINT story_review_status_check;

ALTER TABLE story
    ADD CONSTRAINT story_review_status_check
    CHECK (review_status IN ('draft', 'pending', 'auto_rejected', 'in_review',
                             'approved', 'rejected', 'suspended', 'deleted'));
