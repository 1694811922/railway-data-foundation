$ErrorActionPreference = 'Stop'
$LocalProject = Split-Path -Parent $PSScriptRoot
$LocalRuntime = Join-Path $LocalProject '.runtime/local'
$LocalConfig = Join-Path $LocalProject '.env.local'
$LocalJar = Join-Path $LocalProject 'foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar'
$LocalProviderJar = Join-Path $LocalProject 'provider-service/target/provider-service-0.1.0-SNAPSHOT.jar'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-LocalFile([string]$Path, [string]$Content) {
    [IO.File]::WriteAllText($Path, $Content, $Utf8NoBom)
}

function Initialize-LocalCredentialKey {
    if (-not $env:FOUNDATION_CREDENTIAL_KEY) {
        $keyBytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($keyBytes) } finally { $rng.Dispose() }
        $keyValue = [Convert]::ToBase64String($keyBytes)
        [IO.File]::AppendAllText($LocalConfig, "`nFOUNDATION_CREDENTIAL_KEY=$keyValue`n", $Utf8NoBom)
        [Environment]::SetEnvironmentVariable('FOUNDATION_CREDENTIAL_KEY', $keyValue, 'Process')
    }
    try {
        if ([Convert]::FromBase64String($env:FOUNDATION_CREDENTIAL_KEY).Length -ne 32) { throw 'Invalid key length' }
    } catch { throw 'FOUNDATION_CREDENTIAL_KEY must be a Base64-encoded 32-byte key. Preserve the existing key when restarting.' }
}

function Initialize-ProviderControlToken {
    if (-not $env:PROVIDER_CONTROL_TOKEN) {
        $tokenBytes=New-Object byte[] 32
        $tokenRng=[Security.Cryptography.RandomNumberGenerator]::Create()
        try { $tokenRng.GetBytes($tokenBytes) } finally { $tokenRng.Dispose() }
        $controlValue=[Convert]::ToBase64String($tokenBytes).TrimEnd('=').Replace('+','-').Replace('/','_')
        [IO.File]::AppendAllText($LocalConfig,"`nPROVIDER_CONTROL_TOKEN=$controlValue`n",$Utf8NoBom)
        [Environment]::SetEnvironmentVariable('PROVIDER_CONTROL_TOKEN',$controlValue,'Process')
    }
    if ($env:PROVIDER_CONTROL_TOKEN -cnotmatch '^[A-Za-z0-9_-]{32,128}$') { throw 'PROVIDER_CONTROL_TOKEN must be a strong random service token; preserve it across restarts.' }
}

function Get-RecordedProcess([string]$Name) {
    $file = Join-Path $LocalRuntime "$Name.json"
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    $record = Get-Content -Encoding UTF8 -LiteralPath $file -Raw | ConvertFrom-Json
    $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    if ($process.ProcessName -ne $record.name -or $process.StartTime.ToUniversalTime().Ticks.ToString() -ne $record.ticks) {
        throw "Process identity changed for $Name; refusing to manage it."
    }
    return $process
}

function Save-LocalProcess([string]$Name, $Process) {
    $record = @{pid=$Process.Id; name=$Process.ProcessName; ticks=$Process.StartTime.ToUniversalTime().Ticks.ToString()}
    Write-LocalFile (Join-Path $LocalRuntime "$Name.json") ($record | ConvertTo-Json)
}

function Assert-FreePort([int]$Port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start() } catch { throw "Port $Port is occupied; stop the other service or configure a different port." }
    finally { $listener.Stop() }
}
