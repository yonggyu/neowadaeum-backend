-- V8 — 파기된 완주 세션의 도달 집계 (§13-44, R2.7, R12.4, 이슈 #228)
--
-- **왜 이 표가 필요한가.** 도달률 집계는 매 회차 play_session 을 전량 재계산한다 (B-39) —
-- 누적 카운터가 아니라 살아 있는 행을 그때그때 센다. 그리고 탈퇴 파기는 그 회원의
-- play_session 을 지운다 (B-61). 둘이 만나면 **탈퇴 한 건이 그 사람이 완주했던 모든 작품의
-- 도달률을 줄인다** — 작품에 아무 변화가 없는데 숫자가 바뀌고, R2.8 의 표본 경계(50) 근처에서는
-- 도달률이 사라졌다 나타난다.
--
-- **§13-44 는 C 안을 채택했다** — 지우기 전에 집계에 반영하고 지운다. 원본 플레이 기록은
-- 파기하되, 그 기여분은 **개인과 다시 이을 수 없는 합계**로만 남긴다.
--
-- 그래서 이 표가 갖지 않는 것이 갖는 것보다 중요하다:
--   * player_ref 가 없다   — 누구의 플레이였는지 되물을 수 없다
--   * session_id 가 없다   — 어느 세션이었는지 되물을 수 없다
--   * 시각이 없다(집계 갱신 시각만 둔다) — 언제 플레이했는지로 사람을 좁힐 수 없다
--
-- 남는 것은 "이 작품의 이 엔딩에 몇 번 도달했다" 하나이며, 그것은 **작품에 대한 사실**이지
-- 회원에 대한 사실이 아니다. R12.4 가 세션·턴에 대해 "삭제 또는 익명화"를 허용하는 근거가
-- 여기에 있다 — 지우는 것은 원본이고, 남는 것은 익명 합계다.
--
-- **이 표는 append-add 다.** 같은 (story_id, ending_id) 로 다시 들어오면 더한다 — 파기는
-- 배치가 여러 회차에 걸쳐 수행하므로 덮어쓰면 앞 회차의 기여분이 사라진다.
--
-- 스키마 간 FK 를 만들지 않는다 (§5.3). story_id · ending_id 는 catalog 의 값이다.
-- 시각은 UTC 다 (§9.1).

CREATE TABLE purged_session_tally (
    story_id      UUID        NOT NULL,
    ending_id     UUID        NOT NULL,
    -- 파기된 완주 세션 중 이 엔딩에 도달한 수. 0 인 행은 만들지 않는다 — 셀 것이 없으면
    -- 행도 없다.
    reached_count BIGINT      NOT NULL,
    -- **집계를 마지막으로 더한 시각이다.** 플레이 시각이 아니다 — 그것을 남기면 파기한
    -- 기록의 시간축이 되살아난다.
    updated_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT purged_session_tally_pkey PRIMARY KEY (story_id, ending_id),
    CONSTRAINT purged_session_tally_reached_count_check CHECK (reached_count > 0)
);
