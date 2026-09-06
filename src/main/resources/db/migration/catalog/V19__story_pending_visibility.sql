-- ─────────────────────────────────────────────────────────────
-- #391 — 통과가 열 가시성을 작품이 들고 간다 (docs/corrections.md §13-83)
--
-- 지금까지 in_review 로 오는 길은 하나였다 — public 을 원한 제출과 그 승격이다. 그래서
-- 통과가 여는 값은 물어볼 필요가 없었고(언제나 public), visibility 컬럼은 **반려됐을 때
-- 돌아갈 자리**만 담으면 됐다 (#245 · #249, §13-42 · §13-48 · §13-50).
--
-- 이미지가 있는 원고가 사람을 기다리기 시작하면 그 전제가 깨진다. unlisted 를 원한 작성자도
-- 큐를 지나야 하고, 그때 통과가 여는 값은 public 이 아니다. **요청한 자리와 돌아갈 자리는
-- 서로 다른 사실이며 한 컬럼이 둘을 담을 수 없다** — 담게 하면 반려가 승격을 되돌리지
-- 못하거나(돌아갈 자리를 잃는다) 통과가 작성자가 고르지 않은 넓이로 작품을 연다 (I-8).
--
-- ★ NULL 은 결손이 아니라 **요청이 없었다**는 사실이다. 사람을 기다리게 한 것이 작성자가
--   아닐 때 — 신고 누적 정지(R8.9)와 샘플링(R8.11) — 요청한 자리는 존재하지 않는다. 그 둘은
--   가시성을 건드리지 않으므로 통과는 있던 자리로 돌려놓으면 된다 (§13-42).
--
-- ★ 판정이 이 값을 지운다. 한 회차가 끝났는데 남아 있으면 다음 회차 — 정지 뒤의 통과 — 가
--   지난 회차의 요청으로 작품을 연다. StoryPublisher.applyReview 가 그 자리다.
ALTER TABLE story
    ADD COLUMN pending_visibility TEXT;

-- 지금 큐에 있는 작품은 정의상 public 을 원한 것들이다 (그 길이 하나뿐이었다). 그 사실을
-- 여기서 적어 두면 **읽는 쪽에 규칙이 하나만 남는다** — 안 적으면 "NULL 이면 옛 규칙"이라는
-- 두 번째 규칙이 읽는 자리에 영원히 남고, 그 규칙은 새로 만들어지는 행에는 거짓이다.
UPDATE story SET pending_visibility = 'public' WHERE review_status = 'in_review';

ALTER TABLE story
    ADD CONSTRAINT story_pending_visibility_check
    CHECK (pending_visibility IS NULL
           OR pending_visibility IN ('private', 'unlisted', 'public'));
