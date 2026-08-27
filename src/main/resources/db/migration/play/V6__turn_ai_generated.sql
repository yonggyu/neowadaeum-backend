-- V6 — 턴이 AI 생성물인지 기록한다 (B-14, R11.2)
--
-- §11 은 「인공지능 발전과 신뢰 기반 조성 등에 관한 기본법」제31조를 근거로 **결과물의 AI 생성
-- 사실 표시**를 의무로 적는다. 그리고 R11.2 는 응답의 isAiGenerated 가
-- **"상수 true 가 아니라 실제 생성 경로를 반영한다"** 고 못박는다.
--
-- 그래서 컬럼이다. 응답을 만들 때 계산하면 그 값은 **응답을 만드는 코드의 성질**이지 그 턴의
-- 사실이 아니다 — 기록(B-35 히스토리)이나 재생(R14.4 롤백)에서 같은 턴이 다른 값을 갖게 된다.
--
-- 기존 행은 전부 Provider 가 만든 본문이다 (§4.3-6 외의 경로가 없었다). 그래서 TRUE 로 채운다.
ALTER TABLE turn ADD COLUMN is_ai_generated BOOLEAN NOT NULL DEFAULT TRUE;

-- **기본값을 지운다.** 남겨 두면 새 경로가 값을 넣지 않아도 조용히 TRUE 가 되고, 그것이
-- R11.2 가 금지한 "상수 true" 다. 앞으로 턴을 만드는 쪽은 자기 경로의 사실을 명시해야 한다.
ALTER TABLE turn ALTER COLUMN is_ai_generated DROP DEFAULT;
