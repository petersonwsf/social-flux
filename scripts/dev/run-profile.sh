#!/usr/bin/env bash
# Roda o profile_service na sua máquina usando as MESMAS credenciais do .env dos containers
# (banco `profile` na mesma instância PostgreSQL, usuário/senha do authentication_service).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$root/.env" ] || { echo ".env não encontrado (copie de .env.example)"; exit 1; }
set -a; . "$root/.env"; set +a

export PROFILE_DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/${PROFILE_DB_NAME:-profile}"
# PROFILE_DB_USER/PROFILE_DB_PASSWORD, se ausentes, caem em IDENTITY_DB_USER/IDENTITY_DB_PASSWORD (do .env)
export AUTH_JWKS_URI=http://localhost:8081/.well-known/jwks.json          # chave PÚBLICA do authentication_service
export RABBITMQ_HOST=localhost RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
export PROFILE_LEGACY_ENABLED=true
export LEGACY_DB_URL="jdbc:mysql://localhost:${MYSQL_PORT:-3306}/${LEGACY_DB_NAME:-social_flux}"

echo "profile_service -> $PROFILE_DB_URL como '${PROFILE_DB_USER:-$IDENTITY_DB_USER}'; JWKS: $AUTH_JWKS_URI; broker: $RABBITMQ_HOST:$RABBITMQ_PORT"
cd "$root/profile_service"
# Instala a biblioteca compartilhada de eventos (libs/socialflux-events) no repositório Maven local.
mvn -q -f "$root/libs/socialflux-events/pom.xml" -DskipTests install
exec mvn spring-boot:run
