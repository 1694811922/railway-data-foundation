param([switch]$Offline)
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if (-not $env:DEMAND_DB_URL) { throw 'Initialize the isolated receiver with start-demand-local.ps1 first.' }
if ($env:DEMAND_DB_USER -ne 'demand_receiver_app') { throw 'Test requires the local isolated receiver account.' }
Push-Location $LocalRuntime
try { & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -e "CREATE DATABASE IF NOT EXISTS demand_receiver_test CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON demand_receiver_test.* TO 'demand_receiver_app'@'localhost';";if($LASTEXITCODE -ne 0){throw 'Isolated receiver test database setup failed.'} } finally { Pop-Location }
$env:DEMAND_DB_URL=$env:DEMAND_DB_URL.Replace('/demand_receiver_db?','/demand_receiver_test?')
if ($env:DEMAND_DB_URL -notmatch '/demand_receiver_test\?') { throw 'Refusing to use a non-test receiver database.' }
$env:DEMAND_DB_NAME='demand_receiver_test'
$env:RUN_DEMAND_TESTS='true'
$env:JAVA_HOME=$env:LOCAL_JAVA_HOME
$taskArgs=@('-gs',"$PSScriptRoot/maven-settings.xml",'-s',"$PSScriptRoot/maven-settings.xml","-Dmaven.repo.local=$(Split-Path -Parent $LocalProject)/.runtime/m2",'-f',"$LocalProject/pom.xml",'-pl','demand-service','-am','test','-B','-ntp')
if($Offline){$taskArgs+='-o'}
& mvn.cmd @taskArgs
if($LASTEXITCODE -ne 0){throw 'Receiver tests failed.'}
