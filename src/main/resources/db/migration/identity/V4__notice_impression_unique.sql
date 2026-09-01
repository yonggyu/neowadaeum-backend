-- V4 — 고지 노출 이력은 판본당 한 행이다 (B-14, R11.3)
--
-- 이력이 답해야 하는 질문은 **"이 회원이 이 판본을 봤는가"** 다. "몇 번 봤는가"가 아니다.
-- B-07 이 그 전제로 existsByUserIdAndNoticeVersion 을 뒀고, 여기서 DB 가 그것을 강제한다.
--
-- 애플리케이션이 먼저 확인하더라도 동시 요청 두 개는 그 확인을 나란히 통과한다 —
-- 마지막 방어선은 이 인덱스다. 위반은 예외로 드러나고, 기록기는 그것을 삼켜 로그로 남긴다
-- (고지 이력 하나 때문에 플레이가 멈추지 않는다).
--
-- surface 는 유일성에 들어가지 않는다. 같은 판본을 여러 화면에서 보여 줘도 사실은 하나다 —
-- 남는 값은 **처음 보여 준 화면**이다.
CREATE UNIQUE INDEX ai_notice_impression_user_version_key
    ON ai_notice_impression (user_id, notice_version);
