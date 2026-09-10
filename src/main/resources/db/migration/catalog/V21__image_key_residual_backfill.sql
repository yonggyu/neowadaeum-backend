-- V21 — 개명의 **2 단계**가 읽기를 좁히기 전에, 옛 컬럼에만 있는 값을 마저 옮겨 적는다
-- (#486, §13-85). 1 단계는 V20 이다.
--
-- ── 왜 V20 의 백필로 끝나지 않는가 ───────────────────────────────────────────
--
-- V20 은 **자기가 도는 순간에 있던 행**을 옮겨 적었다. 그런데 마이그레이션은 새 이미지가
-- 뜨면서 돌고 (docs/deployment.md §2) 그동안 **구 버전이 아직 트래픽을 받는다** — 그 창에서
-- 구 버전이 만든 행은 옛 컬럼만 채워져 있다. V20 의 UPDATE 는 이미 지나간 뒤다.
--
-- 지금까지 그 행들이 화면에서 사라지지 않은 이유는 **읽기가 COALESCE 로 옛 컬럼을 함께
-- 봤기** 때문이다. 이 배포가 그 COALESCE 를 걷으므로, 걷기 전에 옮겨 적지 않으면 **그 행의
-- 커버가 다시 발행될 때까지 조용히 사라진다.** 백필이 좁히기의 전제라는 것이 이 파일이
-- 2 단계에 함께 서는 이유다.
--
-- 한 번 더 돌아도 결과가 같다 — 조건이 "새 컬럼이 비어 있는 행"이므로 이미 옮긴 값을 덮지
-- 않는다. 그것이 이 백필을 안전하게 만드는 유일한 성질이다.
--
-- ── 여기서 옛 컬럼을 지우지 않는다 ───────────────────────────────────────────
--
-- 3 단계(cover_url · portrait_url 삭제)는 **이 배포가 끝난 다음 배포**다. 합치면 겹쳐 도는
-- 동안 구 버전의 모든 쓰기가 42703 으로 실패하고, 되돌릴 자리도 없어진다.
--
-- promptlog/V4 가 이름을 한 번에 바꾼 선례는 여기 적용되지 않는다 — 그쪽은 값이 전부 NULL
-- 이었고 쓰는 코드가 없었다 (V20 의 주석에 조건 셋이 적혀 있다).

UPDATE story
SET cover_image_key = cover_url
WHERE cover_image_key IS NULL AND cover_url IS NOT NULL;

UPDATE story_version
SET cover_image_key = cover_url
WHERE cover_image_key IS NULL AND cover_url IS NOT NULL;

UPDATE character
SET portrait_image_key = portrait_url
WHERE portrait_image_key IS NULL AND portrait_url IS NOT NULL;
