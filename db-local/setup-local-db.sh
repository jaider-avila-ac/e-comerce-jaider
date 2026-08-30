#!/bin/bash
# Restaura un dump de calzacaribe_db (bajado del VPS) en el Postgres LOCAL de
# docker-compose.local-db.yaml. Pensado para el trabajo de
# PLAN_MEJORAS_API_ECOMMERCE_MULTITENANT.md (rama feature/multitenant-plan) —
# la idea es tener una copia real de los datos pero desconectada del VPS,
# para poder hacer ALTER/CREATE libremente sin tocar producción.
#
# Uso: bash backups/setup-local-db.sh <archivo.dump>   (ruta relativa a backups/)
#
# Requiere: docker compose -f docker-compose.local-db.yaml up -d (ya corriendo).

set -e
cd "$(dirname "$0")/.."

DUMP_FILE="${1:?Uso: setup-local-db.sh <archivo.dump dentro de backups/>}"
APP_PW=$(grep LOCAL_DB_APP_PASSWORD .env.local-db | cut -d= -f2)

echo "== Recreando calzacaribe_db limpia =="
docker exec ecommerce-postgres-local psql -U postgres -d postgres \
  -c "DROP DATABASE IF EXISTS calzacaribe_db;" \
  -c "CREATE DATABASE calzacaribe_db OWNER postgres;"

echo "== Extensiones requeridas por el dump =="
docker exec ecommerce-postgres-local psql -U postgres -d calzacaribe_db \
  -c "CREATE EXTENSION IF NOT EXISTS hstore; CREATE EXTENSION IF NOT EXISTS pgcrypto;"

echo "== Restaurando ${DUMP_FILE} (como postgres, respeta OWNER TO calzacaribe_usr del dump) =="
# IMPORTANTE: no usar --no-owner/--role aquí — calzacaribe_usr no tiene CREATE en el
# schema public (igual que en el VPS), así que restaurar "como si fuera" calzacaribe_usr
# falla con "permission denied for schema public". Restaurar como postgres deja cada
# objeto con el owner que ya traía el dump (calzacaribe_usr en casi todo).
MSYS_NO_PATHCONV=1 docker exec ecommerce-postgres-local pg_restore -U postgres -d calzacaribe_db \
  "/backups/${DUMP_FILE}"

echo "== Password real del rol de aplicación (placeholder del init queda sin uso) =="
docker exec ecommerce-postgres-local psql -U postgres -d calzacaribe_db \
  -c "ALTER ROLE calzacaribe_usr WITH PASSWORD '${APP_PW}';"

echo "== Listo. Verificando RLS (debe dar 0 filas sin tenant, N filas con tenant=1) =="
docker exec -e PGPASSWORD="${APP_PW}" ecommerce-postgres-local psql -U calzacaribe_usr -d calzacaribe_db \
  -c "SELECT count(*) AS sin_tenant FROM productos;"
docker exec -e PGPASSWORD="${APP_PW}" ecommerce-postgres-local psql -U calzacaribe_usr -d calzacaribe_db \
  -c "SELECT set_config('app.current_tnd_id','1',false); SELECT count(*) AS con_tenant_1 FROM productos;"
