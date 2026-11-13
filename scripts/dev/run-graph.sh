#!/usr/bin/env bash
# Roda o social_graph_service na sua máquina usando as MESMAS credenciais do .env dos containers
# (banco `social_graph` na mesma instância PostgreSQL, usuário/senha do authentication_service).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$root/.env" ] || { echo ".env não encontrado (copie de .env.example)"; exit 1; }
set -a; . "$root/.env"; set +a

export GRAPH_DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/${GRAPH_DB_NAME:-social_graph}"
# GRAPH_DB_USER/GRAPH_DB_PASSWORD, se ausentes, caem em IDENTITY_DB_USER/IDENTITY_DB_PASSWORD (do .env)
export AUTH_JWKS_URI=http://localhost:8081/.well-known/jwks.json          # chave PÚBLICA do authentication_service
export RABBITMQ_HOST=localhost RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
export GRAPH_LEGACY_ENABLED=true
export LEGACY_DB_URL="jdbc:mysql://localhost:${MYSQL_PORT:-3306}/${LEGACY_DB_NAME:-social_flux}"

echo "social_graph_service -> $GRAPH_DB_URL como '${GRAPH_DB_USER:-$IDENTITY_DB_USER}'; JWKS: $AUTH_JWKS_URI; broker: $RABBITMQ_HOST:$RABBITMQ_PORT"
cd "$root/social_graph_service"
# Instala a biblioteca compartilhada de eventos (libs/socialflux-events) no repositório Maven local.
mvn -q -f "$root/libs/socialflux-events/pom.xml" -DskipTests install
exec mvn spring-boot:run
