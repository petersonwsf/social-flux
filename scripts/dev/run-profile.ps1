# Roda o profile_service na sua máquina usando as MESMAS credenciais do .env dos containers
# (banco `profile` na mesma instância PostgreSQL, usuário/senha do authentication_service).
# Uso (na raiz):  powershell -ExecutionPolicy Bypass -File scripts/dev/run-profile.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
. "$PSScriptRoot\load-env.ps1"
Import-DotEnv "$root\.env"

$pgPort = Get-EnvOr 'POSTGRES_PORT' '5432'
$env:PROFILE_DB_URL = "jdbc:postgresql://localhost:$pgPort/" + (Get-EnvOr 'PROFILE_DB_NAME' 'profile')
# PROFILE_DB_USER/PROFILE_DB_PASSWORD, se ausentes, caem em IDENTITY_DB_USER/IDENTITY_DB_PASSWORD (do .env)
$env:AUTH_JWKS_URI = 'http://localhost:8081/.well-known/jwks.json'      # chave PÚBLICA do authentication_service
$env:RABBITMQ_HOST = 'localhost'; $env:RABBITMQ_PORT = (Get-EnvOr 'RABBITMQ_PORT' '5672')
# RABBITMQ_USER / RABBITMQ_PASSWORD já vêm do .env
# Avatares no bucket `avatars` do RustFS (API S3 no loopback); RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY já vêm do .env
$env:RUSTFS_ENDPOINT = "http://localhost:" + (Get-EnvOr 'RUSTFS_PORT' '9000')

Write-Host "profile_service -> $($env:PROFILE_DB_URL) como '$((Get-EnvOr 'PROFILE_DB_USER' (Get-EnvOr 'IDENTITY_DB_USER' 'profile')))'; JWKS: $($env:AUTH_JWKS_URI); broker: $($env:RABBITMQ_HOST):$($env:RABBITMQ_PORT)"
Set-Location "$root\profile_service"
# Instala as bibliotecas compartilhadas (libs/socialflux-events e libs/socialflux-media) no repositório Maven local.
mvn -q -f "$root\libs\socialflux-events\pom.xml" -DskipTests install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
mvn -q -f "$root\libs\socialflux-media\pom.xml" -DskipTests install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
mvn spring-boot:run
