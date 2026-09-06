-- #377 (§13-78) — 승인 전 이미지 열람을 원고 열람과 다른 자원으로 남긴다.
--
-- 검수 상세를 한 번 열면 이미지 요청이 여러 건 따라온다. 같은 'story_draft' 로 남기면
-- **원고 열람 한 줄이 이미지 수만큼 부풀고**, "누가 이 원고를 열었는가"가 그 안에 묻힌다.
-- 자원 종류가 하나 느는 대신 **어떤 이미지를 봤는가**가 남는다 (R8.5 — 이미지도 판정 대상).
--
-- resource_id 는 객체 키의 마지막 마디(UUID)다. 이미지는 자기 행을 갖지 않는다.
ALTER TABLE access_audit_log DROP CONSTRAINT access_audit_log_resource_check;

ALTER TABLE access_audit_log ADD CONSTRAINT access_audit_log_resource_check
    CHECK (resource IN ('ai_call_log', 'story_draft', 'draft_image'));
