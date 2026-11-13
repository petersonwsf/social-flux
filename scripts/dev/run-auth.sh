#!/usr/bin/env bash
# Roda o authentication_service na sua máquina usando as MESMAS credenciais do .env dos containers.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$root/.env" ] || { echo ".env não encontrado (copie de .env.example)"; exit 1; }
set -a; . "$root/.env"; set +a

export IDENTITY_DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/${IDENTITY_DB_NAME:-identity}"
export AUTH_COOKIE_SECURE=false AUTH_COOKIE_ACCESS_NAME=at AUTH_COOKIE_REFRESH_NAME=rt
export AUTH_PUBLIC_BASE_URL=http://localhost:5173
export AUTH_MAIL_ENABLED=true SMTP_HOST=localhost SMTP_PORT="${MAILPIT_SMTP_PORT:-1025}"
# O cadastro cria o perfil no profile_service (8082) e os eventos account.* vão para o RabbitMQ.
export PROFILE_SERVICE_URL=http://localhost:8082
export RABBITMQ_HOST=localhost RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
[ -n "${JWT_PRIVATE_KEY_B64:-}" ] || export AUTH_ALLOW_EPHEMERAL_KEY=true

echo "authentication_service -> $IDENTITY_DB_URL como '$IDENTITY_DB_USER'; perfil: $PROFILE_SERVICE_URL; broker: $RABBITMQ_HOST:$RABBITMQ_PORT"
cd "$root/authentication_service"
# Instala a biblioteca compartilhada de eventos (libs/socialflux-events) no repositório Maven local.
mvn -q -f "$root/libs/socialflux-events/pom.xml" -DskipTests install
exec mvn spring-boot:run
