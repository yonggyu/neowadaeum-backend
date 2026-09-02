-- ─────────────────────────────────────────────────────────────
-- 공개 표시명의 제약 (이슈 #287, §13-7)
--
-- display_name 은 TEXT NOT NULL 뿐이었다. 길이도 문자도 정해져 있지 않아, 값이 실제로
-- 들어오기 시작하면 카드 한 줄을 통째로 미는 이름이나 눈에 보이지 않는 문자가 섞인
-- 이름을 막을 자리가 없었다.
--
-- ★ 여기는 **마지막 방어선**이다. 규칙의 정본은 도메인(DisplayNames)이며 정규화(NFC)와
--   예약값 판정은 거기서 한다. DB 가 하는 일은 **어떤 경로로 들어와도 깨지지 않을 최소**를
--   지키는 것이다 — 애플리케이션을 우회하는 쓰기가 생겨도 아래 셋은 성립해야 한다.
--
-- ★ 예약값("탈퇴한 사용자")을 여기서 막지 않는다. 파기 배치가 그 값을 **실제로 써야 하므로**
--   제약을 걸면 배치가 막힌다. 사칭을 막는 자리는 사용자 입력이 들어오는 도메인이다.
--   그 값은 한글과 공백뿐이라 아래 허용목록을 통과한다.
-- ─────────────────────────────────────────────────────────────

-- 길이는 코드포인트 기준이다. char_length() 가 그 단위로 센다 — octet_length() 로 세면
-- 한글이 영문의 세 배로 계산돼 같은 규칙이 언어마다 다르게 적용된다.
ALTER TABLE author_profile
    ADD CONSTRAINT author_profile_display_name_length
    CHECK (char_length(display_name) BETWEEN 2 AND 12);

-- 허용목록이다. 금지목록은 새 문자가 생길 때마다 뒤따라가야 하고, 뒤처진 그 순간이 빈틈이 된다.
-- 이 목록은 제어 문자 · 폭 없는 문자 · 양방향 제어를 **자동으로** 막는다 — 목록에 없기 때문이다.
-- 그 문자들은 눈에 보이지 않으면서 같아 보이는 다른 이름을 만들거나 표시 순서를 뒤집는다.
--   가-힣      완성형 한글
--   ㄱ-ㅎㅏ-ㅣ  호환 자모 (조합 상태로 들어오는 입력기가 있다)
--   ᄀ-ᇿ       조합용 자모
-- '-' 는 문자 범위로 읽히지 않도록 목록 끝에 둔다.
ALTER TABLE author_profile
    ADD CONSTRAINT author_profile_display_name_charset
    CHECK (display_name ~ '^[가-힣ㄱ-ㅎㅏ-ㅣᄀ-ᇿa-zA-Z0-9_ -]+$');

-- 양끝 공백을 허용하지 않는다. 허용하면 " 연우" 와 "연우" 가 서로 다른 행이면서 같게 보인다.
ALTER TABLE author_profile
    ADD CONSTRAINT author_profile_display_name_trimmed
    CHECK (display_name = btrim(display_name));

COMMENT ON COLUMN author_profile.display_name IS
    '공개 표시명. @ 는 값에 포함되지 않는다 — 화면이 붙인다 (#287). 규칙의 정본은 DisplayNames.';
