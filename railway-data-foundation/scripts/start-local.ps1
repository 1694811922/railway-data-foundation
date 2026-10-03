param([switch]$Build, [switch]$Offline)
. "$PSScriptRoot/local-common.ps1"
New-Item -ItemType Directory -Force -Path $LocalRuntime | Out-Null

if (-not (Test-Path -LiteralPath $LocalConfig)) {
    $javaHome = $env:LOCAL_JAVA_HOME
    if (-not $javaHome) { $javaHome = $env:JAVA_HOME }
    if (-not $javaHome -or -not (Test-Path -LiteralPath "$javaHome/bin/javac.exe")) { throw 'Set LOCAL_JAVA_HOME to a JDK 17 directory.' }
    $mysqlHome = $env:LOCAL_MYSQL_HOME
    if (-not $mysqlHome -or -not (Test-Path -LiteralPath "$mysqlHome/bin/mysqld.exe")) { throw 'Set LOCAL_MYSQL_HOME to a MySQL 8 installation directory.' }
    $rootSecret = [guid]::NewGuid().ToString('N')
    $foundationSecret = [guid]::NewGuid().ToString('N')
    $sourceSecret = [guid]::NewGuid().ToString('N')
    Write-LocalFile $LocalConfig @"
LOCAL_JAVA_HOME=$javaHome
LOCAL_MYSQL_HOME=$mysqlHome
LOCAL_MYSQL_PORT=13306
LOCAL_MYSQL_ROOT_PASSWORD=$rootSecret
FOUNDATION_BIND=127.0.0.1
FOUNDATION_PORT=18080
FOUNDATION_DB_URL=jdbc:mysql://127.0.0.1:13306/foundation_db?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=Asia/Shanghai
FOUNDATION_DB_USER=foundation_app
FOUNDATION_DB_PASSWORD=$foundationSecret
SOURCE_MIGRATION_URL=jdbc:mysql://127.0.0.1:13306/port_source_db?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=Asia/Shanghai
SOURCE_DB_USER=source_app
SOURCE_DB_PASSWORD=$sourceSecret
SOURCE_DB_HOST=127.0.0.1
SOURCE_DB_PORT=13306
SOURCE_DB_DATABASE=port_source_db
SOURCE_DB_SSL_MODE=DISABLED
"@
}
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
Initialize-LocalCredentialKey
# Upgrade existing local configuration without replacing credentials or database files.
if (-not $env:SOURCE_DB_HOST) {
    $sourceUri = [Uri]($env:SOURCE_MIGRATION_URL.Substring(5))
    $extra = "`nSOURCE_DB_HOST=$($sourceUri.Host)`nSOURCE_DB_PORT=$($sourceUri.Port)`nSOURCE_DB_DATABASE=$($sourceUri.AbsolutePath.TrimStart('/'))`nSOURCE_DB_SSL_MODE=DISABLED`n"
    [IO.File]::AppendAllText($LocalConfig, $extra, $Utf8NoBom)
    . "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
}
# Existing local installations gain a dedicated read-only Provider account without changing prior credentials.
if (-not $env:PROVIDER_SOURCE_DB_PASSWORD) {
    $providerSecret = [guid]::NewGuid().ToString('N')
    $extra = "`nPROVIDER_BIND=127.0.0.1`nPROVIDER_PORT=18081`nPROVIDER_REST_BASE_URL=http://127.0.0.1:18081`nPROVIDER_SOURCE_DB_URL=$env:SOURCE_MIGRATION_URL`nPROVIDER_SOURCE_DB_USER=provider_reader`nPROVIDER_SOURCE_DB_PASSWORD=$providerSecret`n"
    [IO.File]::AppendAllText($LocalConfig, $extra, $Utf8NoBom)
    . "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
}
if (-not $env:PROVIDER_SOURCE_ENV_PREFIX) {
    [IO.File]::AppendAllText($LocalConfig, "`nPROVIDER_SOURCE_ENV_PREFIX=SOURCE_DB`n", $Utf8NoBom)
    . "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
}
# REST runtime control must follow the configured Foundation bind address and port.
if (-not $env:PROVIDER_FOUNDATION_BASE_URL) {
    [Environment]::SetEnvironmentVariable('PROVIDER_FOUNDATION_BASE_URL',"http://$($env:FOUNDATION_BIND):$($env:FOUNDATION_PORT)",'Process')
}
$env:JAVA_HOME = $env:LOCAL_JAVA_HOME
$env:DEBUG = 'false'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$mysqld = Join-Path $env:LOCAL_MYSQL_HOME 'bin/mysqld.exe'
$mysqladmin = Join-Path $env:LOCAL_MYSQL_HOME 'bin/mysqladmin.exe'
if ((Get-Content -LiteralPath "$env:JAVA_HOME/release" -Raw) -notmatch 'JAVA_VERSION="17[.\"]') { throw 'Local development requires JDK 17.' }

if ($Build -or -not (Test-Path -LiteralPath $LocalJar) -or -not (Test-Path -LiteralPath $LocalProviderJar)) {
    $settings = Join-Path $PSScriptRoot 'maven-settings.xml'
    $cache = Join-Path (Split-Path -Parent $LocalProject) '.runtime/m2'
    $mavenArgs = @('-gs',$settings,'-s',$settings,"-Dmaven.repo.local=$cache",'-f',"$LocalProject/pom.xml",'package','-B','-ntp')
    if ($Offline) { $mavenArgs += '-o' }
    & mvn.cmd @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}

$mysqlProcess = Get-RecordedProcess 'mysql'
if (-not $mysqlProcess) {
    Assert-FreePort ([int]$env:LOCAL_MYSQL_PORT)
    $dataPath = Join-Path $LocalRuntime 'data'
    if (-not (Test-Path -LiteralPath $dataPath)) {
        & $mysqld --no-defaults --initialize-insecure "--basedir=$env:LOCAL_MYSQL_HOME" "--datadir=$dataPath" --console
        if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed. Inspect the local data directory before retrying.' }
    }
    # Generated secrets are SQL-safe hex strings. Do not interpolate arbitrary credentials into SQL.
    foreach ($secret in @($env:LOCAL_MYSQL_ROOT_PASSWORD,$env:FOUNDATION_DB_PASSWORD,$env:SOURCE_DB_PASSWORD)) {
        if ($secret -notmatch '^[a-f0-9]{32}$') { throw 'Local bootstrap requires generated 32-character hex passwords.' }
    }
    if ($env:FOUNDATION_DB_USER -ne 'foundation_app' -or $env:SOURCE_DB_USER -ne 'source_app') { throw 'Local bootstrap account names must remain foundation_app and source_app.' }
    $bootstrap = @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$env:LOCAL_MYSQL_ROOT_PASSWORD';
CREATE DATABASE IF NOT EXISTS foundation_db CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS port_source_db CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'foundation_app'@'localhost' IDENTIFIED BY '$env:FOUNDATION_DB_PASSWORD';
CREATE USER IF NOT EXISTS 'source_app'@'localhost' IDENTIFIED BY '$env:SOURCE_DB_PASSWORD';
GRANT ALL PRIVILEGES ON foundation_db.* TO 'foundation_app'@'localhost';
GRANT ALL PRIVILEGES ON port_source_db.* TO 'source_app'@'localhost';
"@
    Write-LocalFile (Join-Path $LocalRuntime 'bootstrap.sql') $bootstrap
    Write-LocalFile (Join-Path $LocalRuntime 'client.cnf') "[client]`nuser=root`npassword=$env:LOCAL_MYSQL_ROOT_PASSWORD`nhost=127.0.0.1`nport=$env:LOCAL_MYSQL_PORT`n"
    $mysqlArgs = @('--no-defaults',"--basedir=`"$env:LOCAL_MYSQL_HOME`"",'--datadir=./data','--bind-address=127.0.0.1',"--port=$env:LOCAL_MYSQL_PORT",'--mysqlx=OFF','--init-file=../bootstrap.sql','--console')
    $mysqlProcess = Start-Process -FilePath $mysqld -WorkingDirectory $LocalRuntime -ArgumentList $mysqlArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$LocalRuntime/mysql.out.log" -RedirectStandardError "$LocalRuntime/mysql.err.log"
    Save-LocalProcess 'mysql' $mysqlProcess
}

$ready = $false
for ($attempt=0; $attempt -lt 60; $attempt++) {
    if ($mysqlProcess.HasExited) { throw 'MySQL exited. See .runtime/local/mysql.err.log.' }
    $ErrorActionPreference = 'Continue'
    & $mysqladmin "--defaults-extra-file=$LocalRuntime/client.cnf" --connect-timeout=1 ping 2>$null | Out-Null
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -eq 0) { $ready=$true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw 'MySQL startup timed out. See .runtime/local/mysql.err.log.' }
$env:SOURCE_MIGRATION_PATH = Join-Path $LocalProject 'sql/source'
& $java '-Dloader.main=cn.railway.foundation.tools.MigrateSource' -cp $LocalJar org.springframework.boot.loader.launch.PropertiesLauncher
if ($LASTEXITCODE -ne 0) { throw 'Source Flyway migration failed.' }

if ($env:PROVIDER_SOURCE_DB_USER -ne 'provider_reader' -or $env:PROVIDER_SOURCE_DB_PASSWORD -notmatch '^[a-f0-9]{32}$') {
    throw 'Local Provider requires the generated read-only account.'
}
$providerGrant = "CREATE USER IF NOT EXISTS 'provider_reader'@'localhost' IDENTIFIED BY '$env:PROVIDER_SOURCE_DB_PASSWORD'; ALTER USER 'provider_reader'@'localhost' IDENTIFIED BY '$env:PROVIDER_SOURCE_DB_PASSWORD'; GRANT SELECT ON port_source_db.* TO 'provider_reader'@'localhost';"
Push-Location $LocalRuntime
try {
    $providerGrant | & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf
    if ($LASTEXITCODE -ne 0) { throw 'Read-only Provider account setup failed.' }
} finally { Pop-Location }

$providerProcess = Get-RecordedProcess 'provider'
if (-not $providerProcess) {
    Assert-FreePort ([int]$env:PROVIDER_PORT)
    $providerProcess = Start-Process -FilePath $java -WorkingDirectory $LocalProject -ArgumentList @('-jar',"`"$LocalProviderJar`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput "$LocalRuntime/provider.out.log" -RedirectStandardError "$LocalRuntime/provider.err.log"
    Save-LocalProcess 'provider' $providerProcess
}
$providerUrl = "http://$($env:PROVIDER_BIND):$($env:PROVIDER_PORT)"
$providerReady = $false
for ($attempt=0; $attempt -lt 60; $attempt++) {
    if ($providerProcess.HasExited) { throw 'Provider exited. See .runtime/local/provider.err.log.' }
    try {
        $providerHealth = Invoke-RestMethod "$providerUrl/api/v1/provider/health" -TimeoutSec 2
        if ($providerHealth.code -eq 0) { $providerReady=$true; break }
    } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $providerReady) { throw 'Provider startup timed out. Inspect .runtime/local logs.' }

$appProcess = Get-RecordedProcess 'foundation'
if (-not $appProcess) {
    Assert-FreePort ([int]$env:FOUNDATION_PORT)
    $appProcess = Start-Process -FilePath $java -WorkingDirectory $LocalProject -ArgumentList @('-jar',"`"$LocalJar`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput "$LocalRuntime/foundation.out.log" -RedirectStandardError "$LocalRuntime/foundation.err.log"
    Save-LocalProcess 'foundation' $appProcess
}
$baseUrl = "http://$($env:FOUNDATION_BIND):$($env:FOUNDATION_PORT)"
for ($attempt=0; $attempt -lt 60; $attempt++) {
    if ($appProcess.HasExited) { throw 'Foundation exited. See .runtime/local/foundation.out.log.' }
    try {
        $health = Invoke-RestMethod "$baseUrl/api/v1/health" -TimeoutSec 2
        if ($health.code -eq 0 -and $health.data.service -eq 'foundation-service') {
            Write-Host "Backend ready: $baseUrl/swagger-ui.html; Provider health: $providerUrl/api/v1/provider/health"
            Write-Host 'REST queries require an enabled capability and the X-Capability-Id request header.'
            exit 0
        }
    } catch { }
    Start-Sleep -Milliseconds 500
}
throw 'Foundation startup timed out. Inspect .runtime/local logs.'
