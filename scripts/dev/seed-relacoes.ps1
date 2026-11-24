<#
.SYNOPSIS
    Cria contas de teste (mocks) e relações de seguir com a SUA conta, só para desenvolvimento local.

.DESCRIPTION
    - N contas "mock_seguidor_NN" que seguem você  -> enche "Seguidores" no seu perfil.
    - N contas "mock_seguido_NN" que você segue    -> enche "Seguidos" no seu perfil.
    Tudo passa pela API pública do gateway (cadastro + PUT /api/relacoes/seguindo/{id}), então os eventos
    (profile.*, follow.*) são gerados normalmente e os outros serviços conhecem as contas.
    A senha das contas de teste é aleatória e descartada (ninguém faz login nelas). A sua senha é lida do
    prompt, nunca vai para argumentos nem para o disco.

    Pré-requisitos: infraestrutura no ar (docker compose --profile host up -d), auth, profile, graph
    (e, para os outros serviços conhecerem as contas, post e notification) rodando, e o gateway em :8080.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/dev/seed-relacoes.ps1 -Email voce@exemplo.com
#>
param(
    [Parameter(Mandatory = $true)][string]$Email,
    [int]$Quantidade = 30,
    [string]$Gateway = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$cabecalhos = @{ Origin = $Gateway }

function Invoke-Api {
    param([string]$Metodo, [string]$Caminho, $Corpo, $Sessao)
    $args2 = @{
        Uri = "$Gateway$Caminho"; Method = $Metodo; Headers = $cabecalhos
        WebSession = $Sessao; UseBasicParsing = $true; ContentType = 'application/json; charset=utf-8'
    }
    if ($null -ne $Corpo) { $args2.Body = [Text.Encoding]::UTF8.GetBytes(($Corpo | ConvertTo-Json -Compress)) }
    $resp = Invoke-WebRequest @args2
    if ($resp.Content) { return $resp.Content | ConvertFrom-Json }
}

function New-Sessao { New-Object Microsoft.PowerShell.Commands.WebRequestSession }

function New-SenhaAleatoria {
    $bytes = New-Object byte[] 18
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return 'Aa1!' + [Convert]::ToBase64String($bytes).Replace('/', 'x').Replace('+', 'y')
}

function Register-Mock {
    param([string]$Prefixo, [string]$Rotulo, [int]$Indice)
    $nn = '{0:D2}' -f $Indice
    $senha = New-SenhaAleatoria
    $sessao = New-Sessao
    $resp = Invoke-Api POST '/auth/register' @{
        email = "${Prefixo}_$nn@exemplo.test"; senha = $senha; username = "${Prefixo}_$nn"
        nome = "$Rotulo Mock $nn"; telefone = '11999990000'; sexo = $(if ($Indice % 2) { 'M' } else { 'F' })
        data_nascimento = '1995-05-20'
    } $sessao
    return [pscustomobject]@{ Id = $resp.account.accountId; Sessao = $sessao; Username = "${Prefixo}_$nn" }
}

function Add-Relacao {
    # O graph aprende o perfil novo por evento (profile.created.v1): tenta de novo enquanto ele não chegou.
    param($Sessao, [string]$IdAlvo)
    for ($t = 1; $t -le 8; $t++) {
        try { Invoke-Api PUT "/api/relacoes/seguindo/$IdAlvo" $null $Sessao | Out-Null; return $true }
        catch { Start-Sleep -Seconds 2 }
    }
    return $false
}

# 1) Login na sua conta
$ponteiro = [Runtime.InteropServices.Marshal]::SecureStringToBSTR((Read-Host -AsSecureString "Senha de $Email"))
try { $minhaSenha = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ponteiro) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ponteiro) }
$minhaSessao = New-Sessao
Invoke-Api POST '/auth/login' @{ email = $Email; senha = $minhaSenha } $minhaSessao | Out-Null
$minhaSenha = $null
$eu = Invoke-Api GET '/api/usuarios/me' $null $minhaSessao
Write-Host "Logado como @$($eu.username)"

# 2) Seguidores: contas novas que seguem você
$falhas = 0
for ($i = 1; $i -le $Quantidade; $i++) {
    try {
        $mock = Register-Mock 'mock_seguidor' 'Seguidor' $i
        if (Add-Relacao $mock.Sessao $eu.id) { Write-Host "seguidor $i/$Quantidade ok ($($mock.Username))" }
        else { $falhas++; Write-Warning "seguidor ${i}: perfil ainda não chegou ao social_graph_service" }
    } catch { $falhas++; Write-Warning "seguidor ${i}: $($_.Exception.Message)" }
}

# 3) Seguidos: contas novas que você segue
for ($i = 1; $i -le $Quantidade; $i++) {
    try {
        $mock = Register-Mock 'mock_seguido' 'Seguido' $i
        if (Add-Relacao $minhaSessao $mock.Id) { Write-Host "seguido $i/$Quantidade ok ($($mock.Username))" }
        else { $falhas++; Write-Warning "seguido ${i}: perfil ainda não chegou ao social_graph_service" }
    } catch { $falhas++; Write-Warning "seguido ${i}: $($_.Exception.Message)" }
}

Write-Host "Concluído com $falhas falha(s). Recarregue o seu perfil."
