-- V3 — 슬라이스 시드 1벌 (S-4 / B-45 축소, #48)
--
-- 작품 1편 / 챕터 3 / 엔딩 2 / 캐릭터 1. B-45(챕터 6 / 엔딩 5 / 캐릭터 3)의 축소판이다.
-- 엔진 검증에는 완결된 작품이 반드시 필요하다 (§13-14-h).
--
-- **story_version.id 는 S-3 데모 시나리오와 같은 값이다.**
-- src/main/resources/scenarios/demo-first-day.json 의 storyVersionRef 와 일치한다.
-- 어긋나면 S-9 에서 조용히 "시나리오에 없는 요청"이 되고, 원인이 UUID 라는 것을 찾는 데 시간이 든다.
--
-- 시각은 고정값이다. now() 를 쓰면 마이그레이션이 실행 시점마다 다른 데이터를 만든다.
-- 시드는 창작 텍스트이며 실데이터·시크릿이 아니다 (persistence.md).
-- 이미지 URL 은 비워 둔다 — 없는 자산의 경로를 지어내지 않는다.

-- ── 작품 ─────────────────────────────────────────────────────
-- 공식 작품이므로 author_type = 'official' 이고 author_ref 는 없다 (§2.3, R2.3).
INSERT INTO story (id, slug, title, cover_url, hero_url, short_desc, description, world_intro,
                   author_type, author_ref, visibility, review_status,
                   current_version_id, published_at, created_at)
VALUES (
    '11111111-1111-4111-8111-000000000001',
    'first-day',
    '너와 다음',
    NULL,
    NULL,
    '전학 첫날, 아직 아무 이름도 모르는 교실에서',
    '작은 사건이 관계를 천천히 움직이는 3장짜리 학원 이야기. 선택은 크지 않지만 쌓인다.',
    '평범한 인문계 고등학교의 봄. 당신은 학기 중간에 전학 왔다.',
    'official',
    NULL,
    'public',
    'approved',
    '11111111-1111-4111-8111-111111111111',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    TIMESTAMPTZ '2026-01-01 00:00:00+00'
);

-- ── 작품 버전 ────────────────────────────────────────────────
-- choice_policy 는 §2.3 의 값 그대로다.
-- state_schema 는 R4.1 화이트리스트이며 수치 필드는 R4.2 의 min / max / maxDeltaPerTurn 을 갖는다.
-- 델타 상한은 원문의 기본값 ±5 를 쓴다 — 이 작품만의 값을 임의로 정하지 않는다.
INSERT INTO story_version (id, story_id, version_no, world_prompt, choice_policy, state_schema, published_at)
VALUES (
    '11111111-1111-4111-8111-111111111111',
    '11111111-1111-4111-8111-000000000001',
    1,
    '평범한 인문계 고등학교의 봄. 주인공은 학기 중간에 전학 왔고, 아직 아무의 이름도 외우지 못했다. 사건은 크지 않고 관계가 천천히 움직인다.',
    '{ "min": 1, "max": 4, "preferred": 3 }'::JSONB,
    '{
       "affinity": {
         "yuna": { "min": 0, "max": 100, "maxDeltaPerTurn": 5 }
       },
       "flags": ["met_yuna", "shared_lunch"]
     }'::JSONB,
    TIMESTAMPTZ '2026-01-01 00:00:00+00'
);

-- ── 캐릭터 1 ─────────────────────────────────────────────────
INSERT INTO character (id, story_version_id, story_id, name, role, portrait_url, one_line,
                       persona_prompt, display_order, is_visible_in_detail)
VALUES (
    '11111111-1111-4111-8111-0000000000a1',
    '11111111-1111-4111-8111-111111111111',
    '11111111-1111-4111-8111-000000000001',
    '유나',
    '같은 반 학생',
    NULL,
    '먼저 말을 걸지는 않지만 눈은 자주 마주친다.',
    '창가 자리에 앉는 같은 반 학생. 말수가 적고 문장이 짧다. 호의를 크게 표현하지 않는다.',
    1,
    TRUE
);

-- ── 챕터 3 ───────────────────────────────────────────────────
-- 1장은 진입 조건이 없다. 2·3장은 GameState 기반 결정론 조건이다 (R7.1, R7.4, I-15).
-- 3장 조건은 R7.4 의 all / gte / has 조합 형태를 그대로 쓴다.
INSERT INTO chapter_def (id, story_version_id, story_id, chapter_no, title,
                         entry_condition, summary_seed, min_turns, max_turns)
VALUES
    ('11111111-1111-4111-8111-0000000000c1',
     '11111111-1111-4111-8111-111111111111',
     '11111111-1111-4111-8111-000000000001',
     1, '첫날', NULL,
     '전학 첫날 아침, 교실 문 앞에서 시작한다.', 1, 3),

    ('11111111-1111-4111-8111-0000000000c2',
     '11111111-1111-4111-8111-111111111111',
     '11111111-1111-4111-8111-000000000001',
     2, '점심시간',
     '{"gte": ["affinity.yuna", 3]}'::JSONB,
     '첫 대화 이후. 같이 앉을지 말지가 갈린다.', 1, 3),

    ('11111111-1111-4111-8111-0000000000c3',
     '11111111-1111-4111-8111-111111111111',
     '11111111-1111-4111-8111-000000000001',
     3, '하교길',
     '{"all": [{"gte": ["affinity.yuna", 8]}, {"has": ["flags", "met_yuna"]}]}'::JSONB,
     '교문까지 함께 걷거나, 먼저 나서거나.', 1, 3);

-- ── 엔딩 2 ───────────────────────────────────────────────────
-- R7.6 — ending_no 순으로 평가해 최초 매칭에서 종료한다. 그래서 조건부가 먼저다.
-- R7.7 — 어떤 조건도 매칭되지 않고 마지막 챕터 max_turns 에 도달하면 is_default 엔딩으로 끝난다.
INSERT INTO ending_def (id, story_version_id, story_id, ending_no, label, epilogue_text,
                        condition, visual_url, is_secret, is_default)
VALUES
    ('11111111-1111-4111-8111-0000000000e1',
     '11111111-1111-4111-8111-111111111111',
     '11111111-1111-4111-8111-000000000001',
     1, '첫 빛',
     '내일도, 라는 말이 첫날을 첫날로만 끝나지 않게 했다.',
     '{"all": [{"gte": ["affinity.yuna", 15]}, {"has": ["flags", "shared_lunch"]}]}'::JSONB,
     NULL, FALSE, FALSE),

    ('11111111-1111-4111-8111-0000000000e2',
     '11111111-1111-4111-8111-111111111111',
     '11111111-1111-4111-8111-000000000001',
     2, '조용한 하교',
     '이름을 세 개쯤 외웠고, 그중 하나는 유나였다. 첫날은 그렇게 끝난다.',
     NULL, NULL, FALSE, TRUE);
