#!/bin/sh
# Runs once, when the Postgres volume is empty. For an existing volume, run infra/helm/cartogra/charts/postgres/files/bootstrap-roles.sql
# manually (see infra/helm/cartogra/README.md, "Database roles").
set -eu
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v owner_password="$CARTOGRA_OWNER_PASSWORD" \
  -v app_password="$CARTOGRA_APP_PASSWORD" \
  -f /bootstrap/bootstrap-roles.sql
