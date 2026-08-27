-- V8 — 운영 중 바뀌는 설정 (B-14, §13-4 · §2.7)
--
-- **이 표는 promptlog 에 있었다.** B-11 이 거기 만들었고, docs/corrections.md §13-4 는
-- `service_config (catalog 스키마)` 라고 적는다. 정정본이 상위 문서를 이긴다.
--
-- 위치를 고치는 이유는 문서 일치만이 아니다.
--   · promptlog 는 원문 보관처이자 접근 통제 대상이다 (R2.10). 고지 문구는 공개 값이다
--   · 그 스토어의 엔티티를 소유하는 모듈은 ai 이고, 문구를 읽어야 하는 identity · play 는
--     ai 를 참조할 수 없다 (ADR-0006, 모듈 경계) — 지금 위치로는 읽을 방법이 없다
--
-- 표는 비어 있으므로 옮길 데이터가 없다. promptlog 쪽은 V3 가 지운다.
--
-- 컬럼 이름은 B-11 이 쓴 것을 그대로 가져온다. key · value 는 SQL 예약어와 겹치기 쉽고,
-- 이미 그 이름으로 만들어진 것을 여기서 다시 바꾸면 이유 없는 표류가 생긴다.
CREATE TABLE service_config (
    config_key   TEXT        NOT NULL,
    config_value JSONB       NOT NULL,
    -- R11.1 — 배포 없이 갱신한다. 누가 언제 바꿨는지는 admin_audit_log 가 따로 남긴다 (R14.5).
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT service_config_pkey PRIMARY KEY (config_key)
);
