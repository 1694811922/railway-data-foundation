param([switch]$Offline,[string]$NodeExecutable='node.exe',[switch]$NativeLocalBroker,[ValidateRange(1,20)][int]$Messages=1)
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
Initialize-LocalCredentialKey
if (-not (Get-RecordedProcess 'mysql')) { throw 'Start local MySQL first.' }
$env:JAVA_HOME=$env:LOCAL_JAVA_HOME
$env:KAFKA_LIVE_PROJECT=$LocalProject
$env:RUN_KAFKA_FOUNDATION_LIVE='true'
$env:KAFKA_LIVE_MESSAGES="$Messages"
if (-not $env:DEMAND_DB_URL) { throw 'Initialize the receiver with start-demand-local.ps1 before the complete-chain verification.' }
$env:KAFKA_LIVE_DEMAND_DB_URL=$env:DEMAND_DB_URL.Replace('/demand_receiver_db?','/demand_receiver_live_test?')
if ($env:KAFKA_LIVE_DEMAND_DB_URL -notmatch '/demand_receiver_live_test\?') { throw 'Refusing non-test receiver database.' }
if ($NativeLocalBroker) {
    & "$PSScriptRoot/start-local-kafka.ps1" -Offline:$Offline
    $env:KAFKA_LIVE_NATIVE_IDENTITY=Join-Path $LocalRuntime 'kafka/identity.properties'
} else { Remove-Item Env:KAFKA_LIVE_NATIVE_IDENTITY -ErrorAction SilentlyContinue }
$env:KAFKA_LIVE_DB_URL=$env:FOUNDATION_DB_URL.Replace('/foundation_db?', '/foundation_kafka_live_test?')
if ($env:KAFKA_LIVE_DB_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:[0-9]+/foundation_kafka_live_test\?') { throw 'Only the isolated local Kafka test schema is allowed.' }
$taskNodeCommand=Get-Command $NodeExecutable -ErrorAction SilentlyContinue
if (-not $taskNodeCommand) { throw 'Node.js is required; specify -NodeExecutable with its absolute path.' }
$env:KAFKA_LIVE_NODE=$taskNodeCommand.Source
Push-Location $LocalRuntime
try {
    & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -e "CREATE DATABASE IF NOT EXISTS foundation_kafka_live_test CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON foundation_kafka_live_test.* TO 'foundation_app'@'localhost';"
    if ($LASTEXITCODE -ne 0) { throw 'Isolated test schema bootstrap failed.' }
    & "$env:LOCAL_MYSQL_HOME/bin/mysql.exe" --defaults-extra-file=client.cnf -e "CREATE DATABASE IF NOT EXISTS demand_receiver_live_test CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON demand_receiver_live_test.* TO 'demand_receiver_app'@'localhost';"
    if ($LASTEXITCODE -ne 0) { throw 'Isolated receiver schema bootstrap failed.' }
} finally { Pop-Location }
$settings=Join-Path $PSScriptRoot 'maven-settings.xml'
$cache=Join-Path (Split-Path -Parent $LocalProject) '.runtime/m2'
$taskMavenArgs=@('-gs',$settings,'-s',$settings,"-Dmaven.repo.local=$cache",'-B','-ntp')
if ($Offline) { $taskMavenArgs+='-o' }
# Compile and use separate runtime classpaths; never replace jars used by development services.
$taskBuildArgs=$taskMavenArgs+@('-f',"$LocalProject/pom.xml",'compile')
& mvn.cmd @taskBuildArgs
if ($LASTEXITCODE -ne 0) { throw 'Backend compilation failed.' }
foreach ($taskModule in @('foundation-service','provider-service','demand-service')) {
    $taskClasspathArgs=$taskMavenArgs+@('-f',"$LocalProject/$taskModule/pom.xml",'org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath','-DincludeScope=runtime',"-Dmdep.outputFile=$LocalProject/.runtime/$taskModule-kafka-live-classpath.txt")
    & mvn.cmd @taskClasspathArgs
    if ($LASTEXITCODE -ne 0) { throw "Runtime dependency preparation failed: $taskModule" }
}
$taskLiveArgs=$taskMavenArgs+@('-f',"$LocalProject/examples/kafka-demand/pom.xml",'test','-Dtest=KafkaFoundationLiveTest')
& mvn.cmd @taskLiveArgs
if ($LASTEXITCODE -ne 0) { throw 'Kafka full-chain verification failed. Inspect the isolated .runtime/kafka-chain-* evidence directory.' }
