param([switch]$Offline)
. "$PSScriptRoot/kafka-local-common.ps1"
if (Test-Path -LiteralPath "$KafkaHome/installation.json") {
    $manifest=Get-Content -LiteralPath "$KafkaHome/installation.json" -Raw | ConvertFrom-Json
    if ($manifest.version -ne $KafkaVersion) { throw 'Kafka runtime version mismatch.' }
    foreach ($jar in $manifest.jars) {
        if ($jar.name -notmatch '^[A-Za-z0-9_.-]+\.jar$' -or (Get-FileHash -LiteralPath "$KafkaHome/libs/$($jar.name)" -Algorithm SHA512).Hash -ne $jar.sha512) { throw 'Kafka runtime integrity check failed.' }
    }
    Write-Host "Kafka $KafkaVersion runtime manifest verified."; return
}
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
$env:JAVA_HOME=$env:LOCAL_JAVA_HOME
$cache=Join-Path (Split-Path -Parent $LocalProject) '.runtime/m2'
$settings=Join-Path $PSScriptRoot 'maven-settings.xml'
$classpath=Join-Path $LocalRuntime 'kafka-native-classpath.txt'
New-Item -ItemType Directory -Force -Path $LocalRuntime | Out-Null
$args=@('-gs',$settings,'-s',$settings,"-Dmaven.repo.local=$cache",'-f',"$LocalProject/examples/local-kafka/pom.xml",'-B','-ntp','org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath','-DincludeScope=runtime',"-Dmdep.outputFile=$classpath")
if ($Offline) { $args+='-o' }
& mvn.cmd @args
if ($LASTEXITCODE -ne 0) { throw 'Apache Kafka native runtime dependency preparation failed.' }
# Apache server runtime artifacts, resolved by Maven; no spring-kafka-test or EmbeddedKafka deployment.
$libs=Join-Path $KafkaHome 'libs'
New-Item -ItemType Directory -Force -Path $libs | Out-Null
$manifest=@()
foreach ($jar in (Get-Content -LiteralPath $classpath -Raw).Trim().Split(';')) {
    $resolved=(Resolve-Path -LiteralPath $jar).Path
    if ($resolved -notmatch '\.jar$' -or $resolved -match 'spring-kafka-test|testkit|test-common|junit|mockito') { throw 'Unexpected test dependency in native Kafka runtime.' }
    Copy-Item -LiteralPath $resolved -Destination $libs
    $manifest+=@{name=[IO.Path]::GetFileName($resolved);sha512=(Get-FileHash -LiteralPath $resolved -Algorithm SHA512).Hash}
}
if (-not (Test-Path -LiteralPath "$libs/kafka-clients-$KafkaVersion.jar") -or -not (Test-Path -LiteralPath "$libs/kafka_2.13-$KafkaVersion.jar")) { throw 'Pinned Kafka native server artifacts missing.' }
Write-LocalFile "$KafkaHome/installation.json" (@{version=$KafkaVersion;source='Apache Kafka Maven runtime artifacts';jars=$manifest} | ConvertTo-Json -Depth 4)
Write-Host "Native Apache Kafka $KafkaVersion prepared from Maven artifacts; local SHA-512 manifest recorded."
