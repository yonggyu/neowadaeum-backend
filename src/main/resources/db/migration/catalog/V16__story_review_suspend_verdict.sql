-- 사람이 손으로 내리는 정지 (§13-64, R8.9, 이슈 #316)
--
-- 자동 정지는 신고 임계가 하지만 (R8.9), **임계에 닿지 않은 것을 사람이 보고 내려야 할 때**가
-- 있다. 그 판정이 검수 이력에 남지 않으면 작품은 내려가 있는데 **왜 내려갔는지 아무도 모르는**
-- 상태가 된다 — story_review 는 append-only 로 바로 그것을 담는 자리다.
--
-- CHECK 만 넓힌다. 컬럼도 표도 늘리지 않는다 — 이미 있는 어휘에 값 하나가 더해질 뿐이다.
ALTER TABLE story_review DROP CONSTRAINT story_review_verdict_check;

ALTER TABLE story_review
    ADD CONSTRAINT story_review_verdict_check
        CHECK (verdict IN ('pass', 'reject', 'hold', 'suspend'));
