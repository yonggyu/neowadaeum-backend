#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE SCHEMA IF NOT EXISTS identity;
  CREATE SCHEMA IF NOT EXISTS catalog;
  CREATE SCHEMA IF NOT EXISTS play;
  CREATE SCHEMA IF NOT EXISTS promptlog;

  CREATE ROLE identity_user  LOGIN PASSWORD '${IDENTITY_DB_PASSWORD}';
  CREATE ROLE catalog_user   LOGIN PASSWORD '${CATALOG_DB_PASSWORD}';
  CREATE ROLE play_user      LOGIN PASSWORD '${PLAY_DB_PASSWORD}';
  CREATE ROLE promptlog_user LOGIN PASSWORD '${PROMPTLOG_DB_PASSWORD}';

  GRANT ALL ON SCHEMA identity  TO identity_user;
  GRANT ALL ON SCHEMA catalog   TO catalog_user;
  GRANT ALL ON SCHEMA play      TO play_user;
  GRANT ALL ON SCHEMA promptlog TO promptlog_user;
EOSQL
