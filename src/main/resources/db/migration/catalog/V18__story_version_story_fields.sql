-- #358 — 재발행이 작품 수준 필드를 갱신하지 않았다 (§13-74).
--
-- 버전에 묶인 것(세계관 프롬프트·챕터·엔딩·인물·상태 스키마)은 재발행마다 새로 들어갔지만
-- 작품에 묶인 것(제목·소개·커버·장르)은 첫 발행의 값에 멈춰 있었다. 그래서 제목을 고쳐
-- 재제출하면 카탈로그는 옛 값을 계속 보여 주고, **검수자가 승인한 것과 독자가 보는 것이
-- 달랐다.**
--
-- 고칠 자리가 `publishRevision` 이 아닌 이유: 그것은 검수 통과 **전에** 불린다 (R8.8 —
-- 승인이 현재 버전을 만든다). 거기서 작품 행을 바꾸면 **검수 중인 값이 라이브러리에 먼저
-- 뜬다** — 버전을 나눈 이유가 그것이다. 갱신은 `markCurrent` 가 한다.
--
-- 그러려면 `markCurrent(storyId, versionId)` 가 **그 버전이 심사받은 작품 수준 값**을 알아야
-- 한다. 원고에서 다시 읽을 수는 없다 — 제출 뒤에도 작성자는 원고를 계속 고칠 수 있으므로,
-- 승인 시점에 원고를 읽으면 **검수자가 보지 않은 값이 게시된다** (I-8).
--
-- 그래서 버전이 그것을 함께 들고 간다. 버전은 **무엇이 심사받았는가**의 기록이다.

ALTER TABLE story_version
    -- NULL 을 허용한다. 공식 작품 시드(V3)와 UGC 발행 경로 밖에서 만들어진 버전 행은 이
    -- 스냅샷을 갖지 않으며, 그것은 결손이 아니라 **그 버전이 작품 수준 값을 정한 적이
    -- 없다**는 사실이다. markCurrent 가 제목에 COALESCE 를 쓰는 근거가 이것이다.
    ADD COLUMN title       TEXT,
    ADD COLUMN short_desc  TEXT,
    ADD COLUMN world_intro TEXT,

    -- 컬럼 이름이 url 이지만 들어가는 것은 **객체 키**다 (#315, §13-72). 버킷이 비공개이므로
    -- 이 값만으로는 이미지가 보이지 않는다. story.cover_url 과 같은 이름을 쓰는 것은 옮겨
    -- 적을 때 헷갈리지 않기 위해서다.
    ADD COLUMN cover_url   TEXT;

-- §2.3 — short_desc(≤40자). story 쪽에 같은 CHECK 가 있고, 없으면 **복사하는 순간** 그쪽
-- 제약을 위반한다. 여기서 막으면 위반은 발행 시점에 드러난다.
ALTER TABLE story_version
    ADD CONSTRAINT story_version_short_desc_length_check
        CHECK (short_desc IS NULL OR char_length(short_desc) <= 40);

-- 장르도 버전이 든다. story_genre 와 같은 모양이며, 승인 시점에 그쪽으로 옮겨진다.
CREATE TABLE story_version_genre (
    story_version_id UUID NOT NULL,
    genre_id         UUID NOT NULL,

    CONSTRAINT story_version_genre_pkey PRIMARY KEY (story_version_id, genre_id),
    CONSTRAINT story_version_genre_version_fkey
        FOREIGN KEY (story_version_id) REFERENCES story_version (id),
    CONSTRAINT story_version_genre_genre_fkey FOREIGN KEY (genre_id) REFERENCES genre (id)
);

-- 이미 있는 버전 행을 작품 행에서 채운다. 채우지 않으면 **다음 승인이 제목을 비우거나
-- 장르를 지운다** — markCurrent 가 버전을 정본으로 읽기 때문이다.
--
-- 옛 버전에 대해서는 근사다: 그 버전이 심사받았을 때의 제목이 아니라 **지금 작품이 들고
-- 있는 제목**이다. 그때의 값은 어디에도 남아 있지 않으므로 복원할 수 없고, 근사가 아닌
-- 유일한 대안은 비워 두는 것인데 그쪽은 승인이 값을 지우게 만든다.
UPDATE story_version v
SET title       = s.title,
    short_desc  = s.short_desc,
    world_intro = s.world_intro,
    cover_url   = s.cover_url
FROM story s
WHERE s.id = v.story_id;

INSERT INTO story_version_genre (story_version_id, genre_id)
SELECT v.id, g.genre_id
FROM story_version v
         JOIN story_genre g ON g.story_id = v.story_id
ON CONFLICT DO NOTHING;
