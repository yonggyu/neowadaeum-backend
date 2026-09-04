-- 미리보기가 만든 작품·세션을 원고가 기억한다 (#332, §13-5, §13-68).
--
-- 미리보기는 임시 draft·private 작품을 발행하고 그 위에 테스트 세션을 만들지만, 지금까지
-- **어디에도 연결되지 않은 채** 파기를 기다렸다. 그래서 검수자가 "이 작품이 실제로 어떤 문장을
-- 내놓는가" 를 볼 길이 없었고, 프롬프트만 읽고 판정하게 된다.
--
-- story_id 를 재사용하지 않는다. 그 컬럼은 **제출된 작품** 하나를 가리키며 (R8.8 — 재제출은 같은
-- 작품에 버전을 얹는다), 미리보기 작품을 같은 자리에 넣으면 제출이 그것을 덮어쓰거나 재제출이
-- 미리보기 작품에 버전을 얹는다.
ALTER TABLE story_draft
    ADD COLUMN preview_story_id   UUID,
    ADD COLUMN preview_session_id UUID,
    ADD COLUMN previewed_at       TIMESTAMPTZ;

-- 마지막 것만 남는다. 여러 번 돌리면 이전 미리보기는 연결이 끊겨 보관 기간 뒤에 파기된다
-- (§13-37) — 검수자가 보는 것은 **작성자가 마지막으로 확인한 것**이다.
COMMENT ON COLUMN story_draft.preview_story_id IS
    '마지막 미리보기가 발행한 임시 작품 (#332). 제출된 작품인 story_id 와 다른 자리다';
COMMENT ON COLUMN story_draft.preview_session_id IS
    '그 작품 위에서 돈 테스트 세션 (#332). 검수 상세가 이 세션의 턴을 읽는다';
COMMENT ON COLUMN story_draft.previewed_at IS
    '마지막 미리보기 시각 (#332). 검수자가 **얼마나 오래된 미리보기인지** 알아야 한다';

-- 파기 배치가 "이 작품을 지금 지워도 되는가" 를 이 컬럼으로 되묻는다 (§13-68).
CREATE INDEX story_draft_preview_story_idx ON story_draft (preview_story_id)
    WHERE preview_story_id IS NOT NULL;

-- 세션은 play 스토어에 있다. FK 를 걸지 않는 것은 스키마 간 참조 금지(§5.3) 때문이며,
-- **없는 세션을 가리키는 값은 없는 것으로 읽는다** — 파기가 그것을 만든다.
