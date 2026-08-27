-- V7 — 미리보기 세션의 턴 상한 (B-53, R8.13)
--
-- R8.13 은 미리보기를 "3턴 후 자동 종료" 한다고 적는다. 그 3 을 코드 상수로 두지 않는 이유는
-- **is_test_session 인 세션이 미리보기만이 아니기 때문**이다 — 관리자 디버그 세션(B-43)도
-- 같은 플래그를 쓰며, 그쪽을 3턴에 끊으면 디버그가 되지 않는다.
--
-- **비어 있으면 상한이 없다.** 지금까지의 모든 세션이 그 상태이며, 그것이 정상이다.
ALTER TABLE play_session ADD COLUMN turn_limit INTEGER;

-- 0 이나 음수는 "시작하자마자 끝난 세션"이며, 그런 세션을 만들 이유가 없다.
ALTER TABLE play_session ADD CONSTRAINT play_session_turn_limit_check
    CHECK (turn_limit IS NULL OR turn_limit >= 1);
