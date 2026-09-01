-- V5 — 회원 역할 (B-40, R14.6, S-4)
--
-- **[결정 필요]** §2.2 는 역할 컬럼을 규정하지 않는다. 그러나 S-4 는 Admin API 가
-- `role=admin` 을 요구한다고 못박으므로, **관리자를 표시할 자리가 어딘가 필요하다.**
-- 별도 표(admin_user)와 컬럼 중 컬럼을 택한다 — 역할은 회원의 속성이고, 표를 나누면
-- "회원인데 admin 표에 없는" 과 "admin 표에 있는데 회원이 아닌" 두 상태가 생긴다.
--
-- 기본값은 user 다. **admin 은 마이그레이션으로 만들지 않는다** — 관리자 계정을 시드로
-- 넣으면 그 계정이 모든 배포에 존재하게 되고, 그것은 공개 레포에 적힌 백도어다 (S-11).
-- 승격은 운영에서 직접 한다.
ALTER TABLE "user" ADD COLUMN role TEXT NOT NULL DEFAULT 'user';

-- 목록에 없는 값이 조용히 들어오는 것을 막는다. 역할은 권한의 근거다.
ALTER TABLE "user" ADD CONSTRAINT user_role_check CHECK (role IN ('user', 'admin'));

-- 관리자는 소수다. 부분 인덱스로 그 소수만 색인한다 — 전수 조회가 필요해지는 화면(B-41)이 있다.
CREATE INDEX user_admin_idx ON "user" (id) WHERE role = 'admin';
