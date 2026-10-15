param([ValidateRange(30,2000)][int]$Count=120,[ValidateRange(64,65536)][int]$PayloadBytes=512,[ValidateRange(1,1000)][int]$Rate=60,[ValidateRange(100,10000)][int]$PauseMs=1500)
. "$PSScriptRoot/kafka-local-common.ps1"
Initialize-KafkaJava
if (-not (Get-RecordedProcess 'kafka')) { throw 'Start the persistent local Kafka first.' }
$evidence=Join-Path $LocalProject ('.runtime/interface-lab/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
& $KafkaJava '-Xmx256m' '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' "-Dlogback.configurationFile=$PSScriptRoot/java/kafka-logback.xml" -cp "$KafkaClasses;$KafkaHome/libs/*" InterfaceLab $KafkaSecrets "$KafkaRuntime/data/meta.properties" $evidence $Count $PayloadBytes $Rate $PauseMs
if ($LASTEXITCODE -ne 0) { throw 'Interface experiment failed; incomplete results must not be used as successful evidence.' }
Write-Host "Transport experiment (not platform stress acceptance): $evidence/results.csv"
