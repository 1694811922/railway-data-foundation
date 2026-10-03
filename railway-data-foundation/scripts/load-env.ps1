param([string]$Path = (Join-Path $PSScriptRoot '..\.env'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Path)) { throw 'Copy .env.example to .env and configure it first.' }
foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $Path) {
    $entry = $line.Trim()
    if (-not $entry -or $entry.StartsWith('#')) { continue }
    if ($entry -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { throw 'Invalid .env entry.' }
    $name = $Matches[1]
    $value = $Matches[2]
    if ($value.StartsWith('CHANGE_ME')) { throw "Configure placeholder: $name" }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
