#!/usr/bin/env bash
# Roda o notification_service na sua máquina usando as MESMAS credenciais do .env dos containers
# (banco `notification` na mesma instância PostgreSQL, usuário/senha do authentication_service).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$root/.env" ] || { echo ".env não encontrado (copie de .env.example)"; exit 1; }
set -a; . "$root/.env"; set +a

export NOTIFICATION_DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/${NOTIFICATION_DB_NAME:-notification}"
# NOTIFICATION_DB_USER/NOTIFICATION_DB_PASSWORD, se ausentes, caem em IDENTITY_DB_USER/IDENTITY_DB_PASSWORD (do .env)
export AUTH_JWKS_URI=http://localhost:8081/.well-known/jwks.json          # chave PÚBLICA do authentication_service
export RABBITMQ_HOST=localhost RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"

echo "notification_service -> $NOTIFICATION_DB_URL como '${NOTIFICATION_DB_USER:-$IDENTITY_DB_USER}'; JWKS: $AUTH_JWKS_URI; broker: $RABBITMQ_HOST:$RABBITMQ_PORT"
cd "$root/notification_service"
# Instala a biblioteca compartilhada (libs/socialflux-events) no repositório Maven local.
mvn -q -f "$root/libs/socialflux-events/pom.xml" -DskipTests install
exec mvn spring-boot:run
