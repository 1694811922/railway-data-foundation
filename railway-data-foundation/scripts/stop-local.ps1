. "$PSScriptRoot/local-common.ps1"
$demandProcess = Get-RecordedProcess 'demand'
if ($demandProcess) { $demandProcess.Kill(); $demandProcess.WaitForExit(10000) | Out-Null }
$frontendProcess = Get-RecordedProcess 'frontend'
if ($frontendProcess) { $frontendProcess.Kill(); $frontendProcess.WaitForExit(10000) | Out-Null }
$appProcess = Get-RecordedProcess 'foundation'
if ($appProcess) { $appProcess.Kill(); $appProcess.WaitForExit(10000) | Out-Null }
$providerProcess = Get-RecordedProcess 'provider'
if ($providerProcess) { $providerProcess.Kill(); $providerProcess.WaitForExit(10000) | Out-Null }
$mysqlProcess = Get-RecordedProcess 'mysql'
if ($mysqlProcess) {
    . "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
    & "$env:LOCAL_MYSQL_HOME/bin/mysqladmin.exe" "--defaults-extra-file=$LocalRuntime/client.cnf" shutdown
    if ($LASTEXITCODE -ne 0) { throw 'MySQL shutdown failed; process was not force-killed.' }
}
Write-Host 'Local services stopped. Databases and credentials preserved.'
