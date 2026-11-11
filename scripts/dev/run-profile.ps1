# Roda o profile_service na sua máquina usando as MESMAS credenciais do .env dos containers
# (banco `profile` na mesma instância PostgreSQL, usuário/senha do authentication_service).
# Uso (na raiz):  powershell -ExecutionPolicy Bypass -File scripts/dev/run-profile.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
. "$PSScriptRoot\load-env.ps1"
Import-DotEnv "$root\.env"

$pgPort    = Get-EnvOr 'POSTGRES_PORT' '5432'
$mysqlPort = Get-EnvOr 'MYSQL_PORT' '3306'
$env:PROFILE_DB_URL = "jdbc:postgresql://localhost:$pgPort/" + (Get-EnvOr 'PROFILE_DB_NAME' 'profile')
# PROFILE_DB_USER/PROFILE_DB_PASSWORD, se ausentes, caem em IDENTITY_DB_USER/IDENTITY_DB_PASSWORD (do .env)
$env:AUTH_JWKS_URI = 'http://localhost:8081/.well-known/jwks.json'      # chave PÚBLICA do authentication_service
$env:RABBITMQ_HOST = 'localhost'; $env:RABBITMQ_PORT = (Get-EnvOr 'RABBITMQ_PORT' '5672')
# RABBITMQ_USER / RABBITMQ_PASSWORD já vêm do .env
# Projeção do perfil na tabela `usuario` do MySQL legado (JOINs do backend Javalin)
$env:PROFILE_LEGACY_ENABLED = 'true'
$env:LEGACY_DB_URL = "jdbc:mysql://localhost:$mysqlPort/" + (Get-EnvOr 'LEGACY_DB_NAME' 'social_flux')
# LEGACY_DB_USER / LEGACY_DB_PASSWORD já vêm do .env

Write-Host "profile_service -> $($env:PROFILE_DB_URL) como '$((Get-EnvOr 'PROFILE_DB_USER' (Get-EnvOr 'IDENTITY_DB_USER' 'profile')))'; JWKS: $($env:AUTH_JWKS_URI); broker: $($env:RABBITMQ_HOST):$($env:RABBITMQ_PORT)"
Set-Location "$root\profile_service"
mvn spring-boot:run
