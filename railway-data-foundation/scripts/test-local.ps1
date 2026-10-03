param([switch]$Offline)
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
Initialize-LocalCredentialKey
if (-not (Get-RecordedProcess 'mysql')) { throw 'Start local MySQL with start-local.ps1 first.' }
$env:JAVA_HOME=$env:LOCAL_JAVA_HOME
$env:DEBUG='false'
# Infrastructure bootstrap only; Flyway creates all test tables and fixtures.
Push-Location $LocalRuntime
try {
    & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -e "CREATE DATABASE IF NOT EXISTS foundation_test CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS port_source_test CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON foundation_test.* TO 'foundation_app'@'localhost'; GRANT ALL PRIVILEGES ON port_source_test.* TO 'source_app'@'localhost';"
    if ($LASTEXITCODE -ne 0) { throw 'Test database bootstrap failed.' }
    & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -e "CREATE DATABASE IF NOT EXISTS another_source_test CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON another_source_test.* TO 'source_app'@'localhost'; GRANT SELECT ON another_source_test.* TO 'provider_reader'@'localhost';"
    if ($LASTEXITCODE -ne 0) { throw 'Second Source test database bootstrap failed.' }
    & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -e "GRANT SELECT ON port_source_test.* TO 'provider_reader'@'localhost';"
    if ($LASTEXITCODE -ne 0) { throw 'Provider test account grant failed.' }
} finally { Pop-Location }
$env:RUN_MYSQL_TESTS='true'
$env:FOUNDATION_DB_URL=$env:FOUNDATION_DB_URL.Replace('/foundation_db?', '/foundation_test?')
$env:TEST_SOURCE_DB_URL=$env:SOURCE_MIGRATION_URL.Replace('/port_source_db?', '/port_source_test?')
if ($env:FOUNDATION_DB_URL -notmatch '/foundation_test\?' -or $env:TEST_SOURCE_DB_URL -notmatch '/port_source_test\?') { throw 'Refusing to test against non-test database names.' }
$env:TEST_SOURCE_DB_USER=$env:SOURCE_DB_USER
$env:TEST_SOURCE_DB_PASSWORD=$env:SOURCE_DB_PASSWORD
$env:TEST_PROVIDER_SOURCE_DB_USER=$env:PROVIDER_SOURCE_DB_USER
$env:TEST_PROVIDER_SOURCE_DB_PASSWORD=$env:PROVIDER_SOURCE_DB_PASSWORD
$env:TEST_SECOND_SOURCE_DB_URL=$env:TEST_SOURCE_DB_URL.Replace('/port_source_test?', '/another_source_test?')
if ($env:TEST_SECOND_SOURCE_DB_URL -notmatch '/another_source_test\?') { throw 'Refusing to use a non-test second Source database.' }
$settings=Join-Path $PSScriptRoot 'maven-settings.xml'
$cache=Join-Path (Split-Path -Parent $LocalProject) '.runtime/m2'
$mavenArgs=@('-gs',$settings,'-s',$settings,"-Dmaven.repo.local=$cache",'-f',"$LocalProject/pom.xml",'test','-B','-ntp')
if ($Offline) { $mavenArgs += '-o' }
& mvn.cmd @mavenArgs
if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
