#!/bin/bash
# ─────────────────────────────────────────────────────────────
# 4-스토어 스키마와 계정 (B-01, §5.3)
#
# 이 스크립트는 **볼륨이 비어 있을 때 한 번만** 실행된다. 시드 데이터는 여기가 아니라
# Flyway 다 (§2.5).
#
# 스키마 간 격리는 PostgreSQL 이 기본으로 준다 — CREATE SCHEMA 는 PUBLIC 에 아무 권한도 주지
# 않으므로 catalog_user 는 identity 스키마에 USAGE 가 애초에 없다. StoreSeparationTests 가
# 4계정 × 3타스키마 12조합에서 42501 로 거부되는 것을 확인한다.
#
# 아래 명시는 그 위에 남는 것들이다 (#22).
# ─────────────────────────────────────────────────────────────
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE SCHEMA IF NOT EXISTS identity;
  CREATE SCHEMA IF NOT EXISTS catalog;
  CREATE SCHEMA IF NOT EXISTS play;
  CREATE SCHEMA IF NOT EXISTS promptlog;

  -- 이 넷은 세이프티·개인정보 경계를 지탱하는 계정이다. 기본값과 같더라도 명시한다 —
  -- 기본값에 기대는 보안 속성은 버전이 바뀌면 조용히 달라지고, 읽는 사람이 의도를 확인할
  -- 방법이 없다.
  CREATE ROLE identity_user  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${IDENTITY_DB_PASSWORD}';
  CREATE ROLE catalog_user   LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${CATALOG_DB_PASSWORD}';
  CREATE ROLE play_user      LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${PLAY_DB_PASSWORD}';
  CREATE ROLE promptlog_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${PROMPTLOG_DB_PASSWORD}';

  -- USAGE + CREATE 만 준다. **OWNER 는 슈퍼유저로 남긴다** — 그래야 각 계정이 자기 스키마를
  -- DROP 할 수 없다. Flyway 의 cleanDisabled(true) 와 같은 방향이다 (clean 은 스키마를 비운다).
  GRANT ALL ON SCHEMA identity  TO identity_user;
  GRANT ALL ON SCHEMA catalog   TO catalog_user;
  GRANT ALL ON SCHEMA play      TO play_user;
  GRANT ALL ON SCHEMA promptlog TO promptlog_user;

  -- PostgreSQL 15+ 는 public 스키마의 CREATE 를 PUBLIC 에서 회수했지만 **USAGE 는 남긴다.**
  -- currentSchema 없이 실행된 DDL 이 public 에 떨어지면 **네 계정이 전부 그것을 본다** —
  -- §5.3 이 세운 격리 밖의 공용 지대가 하나 생긴다.
  --
  -- 애플리케이션 쪽 방어는 DataSourceConfiguration#requireCurrentSchema 하나뿐이고, psql 로
  -- 직접 붙는 경로는 덮지 못한다. DB 쪽에도 한 겹 둔다.
  REVOKE ALL ON SCHEMA public FROM PUBLIC;

  -- 인스턴스/DB 분리로 승격할 때 필요해진다 (§5.3). 지금은 한 DB 에 네 계정이 모두 붙으므로
  -- 실효가 낮지만, 그때 급하게 넣는 것보다 형태를 지금 잡아 둔다.
  REVOKE CONNECT ON DATABASE "$POSTGRES_DB" FROM PUBLIC;
  GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO identity_user, catalog_user, play_user, promptlog_user;
EOSQL
