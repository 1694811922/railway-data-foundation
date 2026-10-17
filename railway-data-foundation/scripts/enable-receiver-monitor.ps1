# Explicit local configuration upgrade; never rotate existing passwords or encryption keys.
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
$taskText=[IO.File]::ReadAllText($LocalConfig,$Utf8NoBom)
$taskLeft=$env:FOUNDATION_RECEIVER_TELEMETRY_TOKEN
$taskRight=$env:DEMAND_TELEMETRY_TOKEN
if ($taskLeft -or $taskRight) {
    if ($taskLeft -cne $taskRight -or $taskLeft -cnotmatch '^[A-Za-z0-9_-]{43,128}$' -or $env:RECEIVER_TELEMETRY_NODE_ID -cne $env:DEMAND_TELEMETRY_NODE_ID -or $env:RECEIVER_TELEMETRY_NODE_ID -cnotmatch '^[A-Za-z0-9_-]{1,64}$') { throw 'Existing monitoring configuration is incomplete; restore it instead of rotating keys.' }
    Write-Host 'Existing receiver monitoring configuration preserved.'
    return
}
New-Item -ItemType Directory -Force -Path $LocalRuntime | Out-Null
$taskBackup=Join-Path $LocalRuntime 'env-before-receiver-monitor.private'
$taskTemp=Join-Path $LocalRuntime 'env-receiver-monitor.private'
$taskReplaceBackup=Join-Path $LocalRuntime 'env-receiver-monitor-replace-backup.private'
if (Test-Path -LiteralPath $taskBackup) {
    if ([IO.File]::ReadAllText($taskBackup,$Utf8NoBom) -cne $taskText -or -not (Test-Path -LiteralPath $taskTemp)) { throw 'An earlier configuration backup exists; inspect it before retrying.' }
    $taskStaged=[IO.File]::ReadAllText($taskTemp,$Utf8NoBom)
    if (-not $taskStaged.StartsWith($taskText,[StringComparison]::Ordinal)) { throw 'Staged upgrade does not preserve the original configuration.' }
    $taskEntries=@{}
    foreach ($taskLine in $taskStaged.Substring($taskText.Length).Trim().Split("`n")) {
        if ($taskLine.Trim() -notmatch '^([A-Z_]+)=([A-Za-z0-9_-]+)$') { throw 'Staged monitoring configuration is invalid.' }
        $taskEntries[$Matches[1]]=$Matches[2]
    }
    if ($taskEntries.Count -ne 4 -or $taskEntries['FOUNDATION_RECEIVER_TELEMETRY_TOKEN'] -cne $taskEntries['DEMAND_TELEMETRY_TOKEN'] -or $taskEntries['DEMAND_TELEMETRY_TOKEN'] -cnotmatch '^[A-Za-z0-9_-]{43,128}$' -or $taskEntries['RECEIVER_TELEMETRY_NODE_ID'] -cne $taskEntries['DEMAND_TELEMETRY_NODE_ID'] -or $taskEntries['RECEIVER_TELEMETRY_NODE_ID'] -cnotmatch '^[A-Za-z0-9_-]{1,64}$') { throw 'Staged monitoring configuration is incomplete.' }
    [IO.File]::Replace($taskTemp,$LocalConfig,$taskReplaceBackup)
    Write-Host 'Staged monitoring configuration safely resumed; original credentials preserved.'
    return
}
[IO.File]::WriteAllText($taskBackup,$taskText,$Utf8NoBom)
$taskBytes=New-Object byte[] 32
$taskRng=[Security.Cryptography.RandomNumberGenerator]::Create()
try {$taskRng.GetBytes($taskBytes)} finally {$taskRng.Dispose()}
$taskSecret=[Convert]::ToBase64String($taskBytes).TrimEnd('=').Replace('+','-').Replace('/','_')
$taskNode='receiver-'+[guid]::NewGuid().ToString('N').Substring(0,16)
$taskNew=$taskText+"`nFOUNDATION_RECEIVER_TELEMETRY_TOKEN=$taskSecret`nRECEIVER_TELEMETRY_NODE_ID=$taskNode`nDEMAND_TELEMETRY_TOKEN=$taskSecret`nDEMAND_TELEMETRY_NODE_ID=$taskNode`n"
[IO.File]::WriteAllText($taskTemp,$taskNew,$Utf8NoBom)
[IO.File]::Replace($taskTemp,$LocalConfig,$taskReplaceBackup)
Write-Host 'Receiver monitoring configuration added without displaying secrets. Restart Foundation first, then Demand.'
