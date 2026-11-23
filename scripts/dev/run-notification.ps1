# Roda o notification_service na sua máquina usando as MESMAS credenciais do .env dos containers
# (banco `notification` na mesma instância PostgreSQL, usuário/senha do authentication_service).
# Uso (na raiz):  powershell -ExecutionPolicy Bypass -File scripts/dev/run-notification.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
. "$PSScriptRoot\load-env.ps1"
Import-DotEnv "$root\.env"

$pgPort = Get-EnvOr 'POSTGRES_PORT' '5432'
$env:NOTIFICATION_DB_URL = "jdbc:postgresql://localhost:$pgPort/" + (Get-EnvOr 'NOTIFICATION_DB_NAME' 'notification')
# NOTIFICATION_DB_USER/NOTIFICATION_DB_PASSWORD, se ausentes, caem em IDENTITY_DB_USER/IDENTITY_DB_PASSWORD (do .env)
$env:AUTH_JWKS_URI = 'http://localhost:8081/.well-known/jwks.json'      # chave PÚBLICA do authentication_service
$env:RABBITMQ_HOST = 'localhost'; $env:RABBITMQ_PORT = (Get-EnvOr 'RABBITMQ_PORT' '5672')
# RABBITMQ_USER / RABBITMQ_PASSWORD já vêm do .env

Write-Host "notification_service -> $($env:NOTIFICATION_DB_URL) como '$((Get-EnvOr 'NOTIFICATION_DB_USER' (Get-EnvOr 'IDENTITY_DB_USER' 'notification')))'; JWKS: $($env:AUTH_JWKS_URI); broker: $($env:RABBITMQ_HOST):$($env:RABBITMQ_PORT)"
Set-Location "$root\notification_service"
# Instala a biblioteca compartilhada (libs/socialflux-events) no repositório Maven local.
mvn -q -f "$root\libs\socialflux-events\pom.xml" -DskipTests install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
mvn spring-boot:run
