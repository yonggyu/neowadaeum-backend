-- ── 탈퇴 회원 파기 (R12.4, R12.5, B-61) ──────────────────────
--
-- **탈퇴는 상태이고 파기는 사건이다.** `withdrawn` 은 탈퇴 신청이 끝났다는 뜻이고,
-- 그것만으로는 아무것도 지워지지 않는다 (§2.2 의 주석이 이미 그렇게 적혀 있다).
-- 여기서 더하는 두 컬럼이 그 사건을 기록할 자리다.
--
-- **`user` 행 자체를 지우지 않는다.** 동의 이력은 법정 기간 동안 남아야 하고 (R12.4),
-- `consent_log` 는 `user_id` 를 FK 로 갖는다 — 행을 지우면 그 증빙이 함께 사라진다.
-- 지워야 하는 것은 회원 자체가 아니라 **회원과 기록을 잇는 고리**다 (R12.5).
--
-- **`player_ref` 를 NULL 로 만드는 것이 그 고리를 끊는 일이다.** 비-Identity 스토어는
-- `player_ref` 밖에 모르므로 (§2.1, I-3), 매핑이 사라지면 그쪽에 남은 값은 누구도
-- 가리키지 않는 UUID 가 된다. UNIQUE 는 NULL 을 여럿 허용하므로 제약을 손대지 않는다.
ALTER TABLE "user" ALTER COLUMN player_ref DROP NOT NULL;

-- 파기한 시각. **"지웠다"의 근거는 값이 비었다는 사실이 아니라 언제 지웠는가다** —
-- 비어 있는 것만으로는 아직 발급되지 않은 것과 구분되지 않는다.
ALTER TABLE "user" ADD COLUMN purged_at TIMESTAMPTZ;

-- 파기 대상은 "탈퇴했고 아직 파기되지 않은 회원"이다. 배치가 매 회차 이 조건을 묻는다.
CREATE INDEX user_pending_purge_idx ON "user" (status) WHERE purged_at IS NULL;

-- **파기된 회원에게 매핑이 남아 있으면 안 된다.** 배치가 한쪽만 쓰고 멈추면 그 회원은
-- 지워진 것으로 세어지면서 여전히 기록에 닿는다 — 그 상태를 DB 가 거절한다.
ALTER TABLE "user" ADD CONSTRAINT user_purged_has_no_player_ref_check
    CHECK (purged_at IS NULL OR player_ref IS NULL);
