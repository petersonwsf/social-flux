# Roda o authentication_service na sua máquina usando as MESMAS credenciais do .env dos containers.
# Uso (na raiz):  powershell -ExecutionPolicy Bypass -File scripts/dev/run-auth.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
. "$PSScriptRoot\load-env.ps1"
Import-DotEnv "$root\.env"

$pgPort    = Get-EnvOr 'POSTGRES_PORT' '5432'
$env:IDENTITY_DB_URL = "jdbc:postgresql://localhost:$pgPort/" + (Get-EnvOr 'IDENTITY_DB_NAME' 'identity')
# IDENTITY_DB_USER / IDENTITY_DB_PASSWORD já vêm do .env
$env:AUTH_COOKIE_SECURE = 'false'; $env:AUTH_COOKIE_ACCESS_NAME = 'at'; $env:AUTH_COOKIE_REFRESH_NAME = 'rt'
$env:AUTH_PUBLIC_BASE_URL = 'http://localhost:5173'
$env:AUTH_MAIL_ENABLED = 'true'; $env:SMTP_HOST = 'localhost'; $env:SMTP_PORT = (Get-EnvOr 'MAILPIT_SMTP_PORT' '1025')
# O cadastro cria o perfil no profile_service (porta 8082) e os eventos account.* vão para o RabbitMQ.
$env:PROFILE_SERVICE_URL = 'http://localhost:8082'
$env:RABBITMQ_HOST = 'localhost'; $env:RABBITMQ_PORT = (Get-EnvOr 'RABBITMQ_PORT' '5672')
# RABBITMQ_USER / RABBITMQ_PASSWORD já vêm do .env
if (-not $env:JWT_PRIVATE_KEY_B64) { $env:AUTH_ALLOW_EPHEMERAL_KEY = 'true' }

Write-Host "authentication_service -> $($env:IDENTITY_DB_URL) como '$($env:IDENTITY_DB_USER)'; perfil: $($env:PROFILE_SERVICE_URL); broker: $($env:RABBITMQ_HOST):$($env:RABBITMQ_PORT)"
Set-Location "$root\authentication_service"
mvn spring-boot:run
