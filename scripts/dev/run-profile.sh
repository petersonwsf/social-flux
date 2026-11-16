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
# Avatares no bucket `avatars` do RustFS (API S3 no loopback); RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY já vêm do .env
export RUSTFS_ENDPOINT="http://localhost:${RUSTFS_PORT:-9000}"

echo "profile_service -> $PROFILE_DB_URL como '${PROFILE_DB_USER:-$IDENTITY_DB_USER}'; JWKS: $AUTH_JWKS_URI; broker: $RABBITMQ_HOST:$RABBITMQ_PORT"
cd "$root/profile_service"
# Instala as bibliotecas compartilhadas (libs/socialflux-events e libs/socialflux-media) no repositório Maven local.
mvn -q -f "$root/libs/socialflux-events/pom.xml" -DskipTests install
mvn -q -f "$root/libs/socialflux-media/pom.xml" -DskipTests install
exec mvn spring-boot:run
