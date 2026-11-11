# Roda o backend legado (Javalin) na sua máquina em modo gateway, com as credenciais do .env dos containers.
# Uso (na raiz):  powershell -ExecutionPolicy Bypass -File scripts/dev/run-legacy.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
. "$PSScriptRoot\load-env.ps1"
Import-DotEnv "$root\.env"

$mysqlPort = Get-EnvOr 'MYSQL_PORT' '3306'
$env:AUTH_MODE = 'gateway'
$env:PORTA_SERVIDOR = '8000'
$env:SQLDB_URL = "jdbc:mysql://localhost:$mysqlPort/" + (Get-EnvOr 'LEGACY_DB_NAME' 'social_flux')
$env:SQLDB_USERNAME = Get-EnvOr 'LEGACY_DB_USER' 'social'
$env:SQLDB_PASSWORD = Get-EnvOr 'LEGACY_DB_PASSWORD' ''

Write-Host "backend legado -> $($env:SQLDB_URL) como '$($env:SQLDB_USERNAME)' (AUTH_MODE=gateway, porta 8000)"
Set-Location "$root\BackEnd"
mvn compile exec:java
