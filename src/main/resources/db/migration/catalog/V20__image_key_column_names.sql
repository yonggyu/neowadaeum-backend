-- V19 — 커버·초상 컬럼이 자기가 든 것을 이름으로 말하게 한다 (#396, §13-72 · §13-85).
--
-- cover_url · portrait_url 에 들어 있는 것은 **객체 키**다 (#315). 버킷이 비공개라 이 값만으로는
-- 아무도 이미지를 열지 못하며, 화면에 나가는 주소는 조회가 그때그때 서명해 만든다 (§13-79).
-- 계약은 #378 이 이미 정직하게 만들었고(coverImageKey · CoverImage · PortraitImage), 컬럼
-- 이름만 남아 있었다. **컬럼 이름을 믿고 코드를 쓰는 다음 사람**이 그 함정에 빠진다.
--
-- 이름은 코드의 관례를 따른다 — 계약과 catalog · authoring 의 DTO 가 이미 이 값을
-- coverImageKey · portraitImageKey 라고 부르므로, 컬럼은 그 이름의 snake_case 다. 옮겨 적을 때
-- 번역 단계가 없다는 것이 그 값을 다르게 부르지 않는 이유다.
--
-- ── 한 컬럼이 두 종류를 나르는 문제는 그대로 남는다 ─────────────────────────
--
-- 공식 시드 작품(V3 · V5)은 같은 자리에 **실제 주소**를 넣는다. 그래서 조회는 author_type 으로
-- 갈라 UGC 만 서명한다 (§13-79). 이 마이그레이션이 고치는 것은 **이름이 거짓말한다** 하나이며,
-- 그 판정은 여전히 필요하다. 시드를 객체 키로 옮기면 종류가 하나로 줄어 판정 자체가 없어지지만,
-- 그것은 시드 이관과 공식 이미지의 새 보관 위치가 따라오는 **별개의 결정**이다 (#396).
--
-- ── expand-contract ──────────────────────────────────────────────────────────
--
-- 무중단 배포에서는 구 버전과 신 버전이 겹쳐 돈다 (docs/deployment.md §2). 이름 변경은
-- **새 컬럼 추가 → 양쪽 쓰기 → 옛 컬럼 삭제**이며, 이 마이그레이션은 그 첫 단계다:
-- 새 컬럼을 NULL 허용으로 더하고 지금 값을 옮겨 적는다. 옛 컬럼은 **여기서 지우지 않는다** —
-- 지우면 겹쳐 도는 동안 구 버전의 모든 쓰기가 42703 으로 실패한다.
--
-- promptlog/V4 는 이름을 한 번에 바꾼 선례지만 **그 예외의 조건 셋이 여기서는 하나도 성립하지
-- 않는다**: 그쪽은 (1) 값이 전부 NULL 이라 백필이 없었고 (2) 어느 Provider 도 그 컬럼을 채우지
-- 않아 쓰는 코드가 없었으며 (3) 되돌릴 값이 없어 섞인 행이 존재할 수 없었다. 이 컬럼들은 시드가
-- 값을 갖고 있고, 발행·검수·조회 세 경로가 읽고 쓴다.

ALTER TABLE story
    ADD COLUMN cover_image_key TEXT;

ALTER TABLE story_version
    ADD COLUMN cover_image_key TEXT;

ALTER TABLE character
    ADD COLUMN portrait_image_key TEXT;

-- 지금 값을 옮겨 적는다. 이것을 빠뜨리면 **이미 있는 모든 커버가 사라진다** — 읽는 쪽이 새
-- 컬럼을 먼저 보기 때문이다. IF EXISTS 를 쓰지 않는 이유와 같다: 전제가 틀렸다면 드러나야 한다.
UPDATE story SET cover_image_key = cover_url WHERE cover_url IS NOT NULL;
UPDATE story_version SET cover_image_key = cover_url WHERE cover_url IS NOT NULL;
UPDATE character SET portrait_image_key = portrait_url WHERE portrait_url IS NOT NULL;

COMMENT ON COLUMN story.cover_image_key IS
    '커버 이미지. UGC 는 객체 키이고 공식 작품은 실제 주소다 — 한 컬럼이 두 종류를 나르며, '
        '그것은 이 개명이 고치지 않은 채 남긴 문제다 (#396, §13-85). 갈라내는 것은 author_type 이고 '
        '값의 모양이 아니다. 승인된 UGC 만 조회가 서명한다 (§13-79).';

COMMENT ON COLUMN story_version.cover_image_key IS
    '그 버전이 심사받은 커버 (#358). story.cover_image_key 와 같은 규칙이며 같은 문제를 남긴다.';

COMMENT ON COLUMN character.portrait_image_key IS
    '인물 초상. story.cover_image_key 와 같은 규칙이며 같은 문제를 남긴다 (§13-79, §13-85).';
