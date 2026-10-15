. "$PSScriptRoot/local-common.ps1"
$KafkaVersion='3.9.1'
$KafkaHome=Join-Path $LocalProject ".runtime/tools/kafka_2.13-$KafkaVersion"
$KafkaRuntime=Join-Path $LocalRuntime 'kafka'
$KafkaSecrets=Join-Path $KafkaRuntime 'identity.properties'
$KafkaServerConfig=Join-Path $KafkaRuntime 'server.properties'
$KafkaClasses=Join-Path $KafkaRuntime 'classes'
$KafkaStopFile=Join-Path $KafkaRuntime 'stop.request'

function Initialize-KafkaJava {
    . "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
    $script:KafkaJava=Join-Path $env:LOCAL_JAVA_HOME 'bin/java.exe'
    if (-not (Test-Path -LiteralPath "$KafkaHome/libs/kafka-clients-$KafkaVersion.jar")) { throw 'Install Kafka with install-local-kafka.ps1 first.' }
    New-Item -ItemType Directory -Force -Path $KafkaClasses | Out-Null
    & "$env:LOCAL_JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp "$KafkaHome/libs/*" -d $KafkaClasses "$PSScriptRoot/java/LocalKafka.java" "$PSScriptRoot/java/InterfaceLab.java"
    if ($LASTEXITCODE -ne 0) { throw 'Local Kafka tools compilation failed.' }
}
function Invoke-KafkaLocal([string[]]$Arguments) {
    $taskJava=Join-Path $env:LOCAL_JAVA_HOME 'bin/java.exe'
    Push-Location $KafkaRuntime
    try {
    & $taskJava '-Xmx192m' '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' "-Dlogback.configurationFile=$PSScriptRoot/java/kafka-logback.xml" -cp "$KafkaClasses;$KafkaHome/libs/*" LocalKafka @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Kafka operation failed; inspect the fixed error and private local logs.' }
    } finally { Pop-Location }
}
function New-KafkaSecret {
    $bytes=New-Object byte[] 32
    $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant() } finally { $rng.Dispose(); [Array]::Clear($bytes,0,$bytes.Length) }
}
function Protect-KafkaRuntime {
    # Private secrets/configuration are restricted to this Windows user and SYSTEM.
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $KafkaRuntime '/inheritance:r' '/grant:r' "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '/q' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot restrict the private Kafka runtime directory.' }
}
