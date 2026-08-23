-- V3 — play 스키마를 요구사항 §2.5 와 일치시킴 (#50)
--
-- S-2(#39)는 요구사항 원문을 참조하지 못한 채 작성됐다. 그 커밋이 인용한 근거는 §13-6 · §13-9 ·
-- I-4/I-5/I-6 뿐이고 §2.5 는 없다 — 원문이 레포 밖에 있었기 때문이다(S-4 와 같은 원인).
-- 결과는 단순 누락이 아니라 **불변 규칙을 추적할 자리가 없는 상태**였다.
--
-- V2 는 이미 머지됐으므로 고치지 않는다. 여기서 보완한다 (체크섬).
-- 사용자 데이터가 아직 없으므로 개명이 가능한 마지막 시점이다.

-- ── play_session — 이름을 원문에 맞춘다 ─────────────────────
-- 이름이 어긋나면 원문을 읽는 사람과 코드를 읽는 사람이 서로 다른 것을 보게 된다.
ALTER TABLE play_session RENAME COLUMN started_at     TO created_at;
ALTER TABLE play_session RENAME COLUMN last_played_at TO updated_at;
ALTER TABLE play_session RENAME COLUMN ending_id      TO current_ending_id;

-- ── play_session — 누락 컬럼 ────────────────────────────────
-- status 는 'expired' 를 허용하는데 만료 시각이 없었다. 상태값만 있고 판정 근거가 없는 상태다 (§4.7).
ALTER TABLE play_session ADD COLUMN expires_at         TIMESTAMPTZ;

-- Resume 화면이 "어디까지 왔는지"를 보여줄 때 쓴다 (§4.7).
ALTER TABLE play_session ADD COLUMN last_scene_summary TEXT;
ALTER TABLE play_session ADD COLUMN last_choice_text   TEXT;

-- ── turn — I-2 추적 근거 ────────────────────────────────────
-- R9.3 은 "모든 판정은 turn.safety_verdict 에 기록한다"를 명시한다. 기록할 자리가 없으면 어떤 턴이
-- 수정본인지 사후에 알 수 없고, S-8(L2 판정기)은 결과를 버리게 된다.
--
-- **DEFAULT 를 두지 않는다.** 'pass' 를 기본값으로 두면 판정을 거치지 않은 INSERT 가 조용히
-- "통과"로 기록된다 — 세이프티 필드에서 fail-open 은 가장 나쁜 기본값이다.
-- 기존 행이 없으므로 UPDATE 는 no-op 이고, 이후 INSERT 는 값을 반드시 명시해야 한다.
ALTER TABLE turn ADD COLUMN safety_verdict TEXT;
UPDATE turn SET safety_verdict = 'pass' WHERE safety_verdict IS NULL;
ALTER TABLE turn ALTER COLUMN safety_verdict SET NOT NULL;
ALTER TABLE turn ADD CONSTRAINT turn_safety_verdict_check
    CHECK (safety_verdict IN ('pass', 'revised', 'blocked'));

-- ── turn — I-17 · I-18 감사 근거 ────────────────────────────
-- R14.2 — 자유입력 턴은 표시한다. I-18 은 "사용자 소유 세션에 자유입력 금지, is_test_session 에서만"
-- 인데, 턴 단위 표시가 없으면 위반이 없었다는 것을 사후에 증명할 수 없다.
--
-- **"자유입력은 테스트 세션에서만" 은 CHECK 로 표현할 수 없다** — 다른 행(play_session)을 봐야 한다.
-- 트리거나 (session_id, is_test_session) 복합 FK 로는 가능하지만 §2.5 에 없는 컬럼을 turn 에
-- 비정규화해야 한다. 턴 쓰기 경로가 생기는 S-9 시점에 판단한다. 지금은 애플리케이션이 책임진다.
ALTER TABLE turn ADD COLUMN is_admin_free_input BOOLEAN NOT NULL DEFAULT FALSE;

-- ── turn — 롤백 (R14.4) ─────────────────────────────────────
-- game_state_snapshot 에만 deleted_at 이 있어 롤백이 반쪽이었다. R14.4 는 "스냅샷과 요약을 함께
-- 되돌린다"를 요구하는데 턴은 되돌릴 수단이 없었다 — 내부 비일관성이다.
ALTER TABLE turn ADD COLUMN deleted_at TIMESTAMPTZ;

-- 유일성을 **살아 있는 행 기준**으로 좁힌다. game_state_snapshot 과 같은 이유다 —
-- 전체 기준으로 잠그면 재생성(B-42)이 같은 턴 번호로 새 행을 남길 수 없어 결국 UPDATE 로
-- 되돌아가고 I-5 가 깨진다. I-6 의 낙관적 잠금은 살아 있는 행 사이에서 그대로 성립한다.
ALTER TABLE turn DROP CONSTRAINT turn_session_turn_no_key;
CREATE UNIQUE INDEX turn_live_session_turn_no_key
    ON turn (session_id, turn_no)
    WHERE deleted_at IS NULL;

-- ── turn — 응답 재구성 (§4.7 Resume · History) ──────────────
-- ending_id 는 catalog 의 ending_def 를 애플리케이션 레벨로 참조한다. FK 를 걸지 않는다 (§5.3).
ALTER TABLE turn ADD COLUMN ending_id       UUID;
ALTER TABLE turn ADD COLUMN chapter_changed BOOLEAN NOT NULL DEFAULT FALSE;

-- 표시 전용 (§5.2 출력 스키마). 값이 없을 수 있다.
ALTER TABLE turn ADD COLUMN speaker_name    TEXT;
ALTER TABLE turn ADD COLUMN scene_image_url TEXT;
