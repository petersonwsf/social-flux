# Carrega o .env da raiz (KEY=VALUE, ignora comentários/linhas vazias) como variáveis de ambiente do processo.
function Import-DotEnv([string]$Path) {
    if (-not (Test-Path $Path)) { throw "Arquivo .env não encontrado em $Path (copie de .env.example)" }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $i = $line.IndexOf('=')
        if ($i -lt 1) { return }
        $key = $line.Substring(0, $i).Trim()
        $value = $line.Substring($i + 1).Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }
}

function Get-EnvOr([string]$Name, [string]$Default) {
    $v = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrEmpty($v)) { return $Default } else { return $v }
}
