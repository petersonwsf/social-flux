#!/usr/bin/env bash
# Roda o backend legado (Javalin) na sua máquina em modo gateway, com as credenciais do .env dos containers.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$root/.env" ] || { echo ".env não encontrado (copie de .env.example)"; exit 1; }
set -a; . "$root/.env"; set +a

export AUTH_MODE=gateway PORTA_SERVIDOR=8000
export SQLDB_URL="jdbc:mysql://localhost:${MYSQL_PORT:-3306}/${LEGACY_DB_NAME:-social_flux}"
export SQLDB_USERNAME="${LEGACY_DB_USER:-social}" SQLDB_PASSWORD="${LEGACY_DB_PASSWORD:-}"

echo "backend legado -> $SQLDB_URL como '$SQLDB_USERNAME' (AUTH_MODE=gateway, porta 8000)"
cd "$root/BackEnd"
exec mvn compile exec:java
