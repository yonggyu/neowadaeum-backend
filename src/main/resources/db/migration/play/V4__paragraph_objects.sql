-- ────────────────────────────────────────────────────────────
-- #84 — turn.paragraphs 를 문자열 배열에서 문단 객체 배열로 옮긴다.
--
-- 이전 형식은 ["본문"] 이었다. 통 문자열 하나를 List.of(...) 로 감싼 것이며,
-- R5.1 이 금지한 형태다 — 배열이라는 껍데기만 있고 문단은 하나뿐이다.
-- 새 형식은 [{"type": ..., "speakerName": ..., "text": ...}] 다 (§5.2).
--
-- 운영 데이터는 없다. 그러나 dev 플레이 콘솔(S-10)이 이미 있어 로컬 개발 DB 에는
-- 옛 형식 행이 실재한다. 그것을 남겨 두면 조회 시점에 text 가 null 로 읽히고,
-- 증상은 "본문이 안 보인다"로 나타나 원인을 찾는 데 시간이 든다.
--
-- 종류는 NARRATION 으로 둔다. 옛 형식에는 종류도 화자도 없었으므로 복원할 수 없고,
-- 나레이션이 화자가 없는 쪽이다 (R5.2).
-- ────────────────────────────────────────────────────────────

UPDATE turn
SET paragraphs = (
        SELECT jsonb_agg(jsonb_build_object('type', 'NARRATION', 'speakerName', NULL, 'text', element)
                         ORDER BY ordinality)
        FROM jsonb_array_elements_text(paragraphs) WITH ORDINALITY AS t(element, ordinality)
    )
WHERE jsonb_typeof(paragraphs) = 'array'
  AND jsonb_array_length(paragraphs) > 0
  AND jsonb_typeof(paragraphs -> 0) = 'string';
