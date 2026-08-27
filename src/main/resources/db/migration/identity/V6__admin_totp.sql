-- V6 — 관리자 TOTP (B-40(2/2), R14.6, S-4)
--
-- **[결정 필요]** R14.6 은 "2FA 를 요구한다" 한 줄이며 방식을 규정하지 않는다.
-- TOTP(RFC 6238) 를 채택한 이유와 함께 정한 값은 docs/corrections.md §13-29 에 있다.
--
-- **회원당 한 벌이다.** user_id 가 곧 기본키다 — 관리자 한 사람에게 인증기가 둘 있으면
-- 하나를 잃어버렸을 때 무엇을 지워야 하는지가 모호해진다.
--
-- **스키마 간 FK 를 만들지 않는다 (§5.3).** user_id 는 같은 identity 스키마의 값이지만,
-- 여기서도 참조는 애플리케이션 레벨에서 한다 — 인스턴스 분리로 승격할 때 코드가 바뀌지
-- 않아야 한다는 이유는 스키마 안팎에서 같다.
CREATE TABLE admin_totp (
    user_id        UUID        NOT NULL,

    -- **평문으로 두지 않는다.** 이 값은 코드를 만드는 재료이므로, 읽히면 2FA 가 없는 것과
    -- 같아진다. DB 덤프 하나가 두 번째 인증 요소를 통째로 무력화하면 안 된다.
    secret_enc     TEXT        NOT NULL,

    -- 등록만 하고 확인하지 않은 상태를 구분한다. NULL 이면 **아직 문을 열지 못한다** —
    -- 인증기에 제대로 들어갔는지 확인되기 전의 비밀을 신뢰하면, 등록에 실패한 관리자가
    -- 스스로 잠기거나 반대로 확인되지 않은 비밀이 통과하게 된다.
    confirmed_at   TIMESTAMPTZ,

    -- 마지막으로 통과한 시간 스텝. **같은 코드가 두 번 통하지 않게 하는 값이다** —
    -- 코드는 30초 동안 유효하므로, 어깨너머로 본 여섯 자리가 그 창 안에서 재사용된다.
    last_used_step BIGINT,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT admin_totp_pkey PRIMARY KEY (user_id)
);
