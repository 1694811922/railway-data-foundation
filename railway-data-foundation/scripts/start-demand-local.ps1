param([switch]$Build,[switch]$Offline)
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if (-not (Get-RecordedProcess 'mysql')) { throw 'Start project MySQL first.' }
function Invoke-ReceiverMysql([string]$Sql) {
    Push-Location $LocalRuntime
    try { $Sql | & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf --batch --skip-column-names; if ($LASTEXITCODE -ne 0) { throw 'Receiver SQL setup failed.' } }
    finally { Pop-Location }
}
$taskDemandJar=Join-Path $LocalProject 'demand-service/target/demand-service-0.1.0-SNAPSHOT.jar'
if (-not $env:DEMAND_CREDENTIAL_KEY) {
    $taskExisting=Invoke-ReceiverMysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='demand_receiver_db' AND table_name='receiving_task';"
    if ($taskExisting -eq '1') { throw 'Receiver schema exists without its private key. Restore the original key, do not regenerate it.' }
    $taskKeyBytes=New-Object byte[] 32
    $taskRng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRng.GetBytes($taskKeyBytes) } finally { $taskRng.Dispose() }
    $taskDemandKey=[Convert]::ToBase64String($taskKeyBytes)
    [IO.File]::AppendAllText($LocalConfig,"`nDEMAND_CREDENTIAL_KEY=$taskDemandKey`n",$Utf8NoBom)
}
if (-not $env:DEMAND_DB_URL) {
    $taskDemandPassword=[guid]::NewGuid().ToString('N')
    [IO.File]::AppendAllText($LocalConfig,"`nDEMAND_BIND=127.0.0.1`nDEMAND_PORT=18082`nDEMAND_DB_NAME=demand_receiver_db`nDEMAND_DB_URL=jdbc:mysql://127.0.0.1:$env:LOCAL_MYSQL_PORT/demand_receiver_db?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=UTC`nDEMAND_DB_USER=demand_receiver_app`nDEMAND_DB_PASSWORD=$taskDemandPassword`nDEMAND_FOUNDATION_URL=http://127.0.0.1:$env:FOUNDATION_PORT`n",$Utf8NoBom)
}
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if ($env:DEMAND_DB_USER -ne 'demand_receiver_app' -or $env:DEMAND_DB_PASSWORD -notmatch '^[a-f0-9]{32}$' -or $env:DEMAND_DB_NAME -ne 'demand_receiver_db' -or $env:DEMAND_DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:[0-9]+/demand_receiver_db\?') { throw 'Local receiver bootstrap is restricted to its isolated database/account. Configure remote installations separately.' }
$taskBootstrap="CREATE DATABASE IF NOT EXISTS demand_receiver_db CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'demand_receiver_app'@'localhost' IDENTIFIED BY '$env:DEMAND_DB_PASSWORD'; GRANT ALL PRIVILEGES ON demand_receiver_db.* TO 'demand_receiver_app'@'localhost';"
Invoke-ReceiverMysql $taskBootstrap
$env:JAVA_HOME=$env:LOCAL_JAVA_HOME
if ($Build -or -not (Test-Path -LiteralPath $taskDemandJar)) {
    $taskMaven=@('-gs',"$PSScriptRoot/maven-settings.xml",'-s',"$PSScriptRoot/maven-settings.xml","-Dmaven.repo.local=$(Split-Path -Parent $LocalProject)/.runtime/m2",'-f',"$LocalProject/pom.xml",'-pl','demand-service','-am','package','-B','-ntp')
    if ($Offline) { $taskMaven+='-o' }
    $taskPreviousTestFlag=$env:RUN_DEMAND_TESTS
    try {
        # Build must never opt integration fixtures into the configured business receiver database.
        $env:RUN_DEMAND_TESTS='false'
        & mvn.cmd @taskMaven
        if ($LASTEXITCODE -ne 0) { throw 'Receiver build failed.' }
    } finally { [Environment]::SetEnvironmentVariable('RUN_DEMAND_TESTS',$taskPreviousTestFlag,'Process') }
}
$taskProcess=Get-RecordedProcess 'demand'
if ($Build -and $taskProcess) { $taskProcess.Kill();$taskProcess.WaitForExit(10000)|Out-Null;$taskProcess=$null }
if (-not $taskProcess) {
    Assert-FreePort ([int]$env:DEMAND_PORT)
    # Explicit CreateProcess environment: Start-Process may reuse its original environment snapshot.
    $taskProcess=[Diagnostics.Process]::new()
    $taskProcess.StartInfo.FileName="$env:JAVA_HOME/bin/java.exe"
    $taskProcess.StartInfo.WorkingDirectory=$LocalProject
    $taskProcess.StartInfo.UseShellExecute=$false
    $taskProcess.StartInfo.CreateNoWindow=$true
    $taskProcess.StartInfo.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
    $taskProcess.StartInfo.RedirectStandardOutput=$true
    $taskProcess.StartInfo.RedirectStandardError=$true
    $taskProcess.StartInfo.Arguments="-Dfile.encoding=UTF-8 -jar `"$taskDemandJar`" --debug=false --spring.main.banner-mode=off --logging.level.root=INFO --logging.level.org.springframework.web=INFO --logging.level.org.springframework.jdbc=INFO"
    $taskProcess.StartInfo.EnvironmentVariables.Clear()
    foreach($taskEntry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) {
        if($taskEntry.Key -like 'DEMAND_*' -or $taskEntry.Key -in @('SystemRoot','WINDIR','Path','PATHEXT','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','JAVA_HOME','COMSPEC')) { $taskProcess.StartInfo.EnvironmentVariables[$taskEntry.Key]=$taskEntry.Value }
    }
    $taskProcess.StartInfo.EnvironmentVariables['DEBUG']='false'
    $taskProcess.StartInfo.EnvironmentVariables['DEMAND_LOG_FILE']=Join-Path $LocalRuntime 'demand.out.log'
    if (-not $taskProcess.Start()) { throw 'Receiver process creation failed.' }
    # Application logging is file-only; drain startup stderr/stdout while this launcher is alive.
    $taskOutputDrain=$taskProcess.StandardOutput.ReadToEndAsync()
    $taskErrorDrain=$taskProcess.StandardError.ReadToEndAsync()
    Save-LocalProcess 'demand' $taskProcess
}
for ($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++) {
    if ($taskProcess.HasExited) { throw 'Receiver exited. Inspect .runtime/local/demand logs.' }
    try { $taskHealth=Invoke-RestMethod "http://127.0.0.1:$env:DEMAND_PORT/api/v1/demand/health" -TimeoutSec 2;if($taskHealth.code -eq 0){Write-Host 'Demand receiver ready; its database and credentials are isolated.';return} } catch {}
    Start-Sleep -Milliseconds 500
}
throw 'Receiver startup timed out.'
