param([switch]$Offline,[ValidateRange(1,2)][int]$Rounds=2)
. "$PSScriptRoot/kafka-local-common.ps1"
& "$PSScriptRoot/start-local-kafka.ps1" -Offline:$Offline
$overlay=Join-Path $KafkaRuntime 'backend.env'
$identityBefore=(Get-FileHash -LiteralPath $KafkaSecrets -Algorithm SHA256).Hash
$overlayBefore=(Get-FileHash -LiteralPath $overlay -Algorithm SHA256).Hash
$marker=Join-Path $KafkaRuntime 'restart-check.properties'
if (Test-Path -LiteralPath $marker) { throw 'A previous restart checkpoint exists; preserve it and diagnose or clean that exact checkpoint before another run.' }
try {
    Invoke-KafkaLocal @('checkpoint','write',$KafkaSecrets,"$KafkaRuntime/data/meta.properties",$marker)
    Invoke-KafkaLocal @('checkpoint','inspect',$KafkaSecrets,"$KafkaRuntime/data/meta.properties",$marker)
    Invoke-KafkaLocal @('checkpoint','reject-invalid',$KafkaSecrets,"$KafkaRuntime/data/meta.properties",$marker)
    Invoke-KafkaLocal @('checkpoint','read-first',$KafkaSecrets,"$KafkaRuntime/data/meta.properties",$marker)
    for ($round=1;$round -le $Rounds;$round++) {
        & "$PSScriptRoot/stop-local-kafka.ps1"
        & "$PSScriptRoot/start-local-kafka.ps1" -Offline:$Offline
        Invoke-KafkaLocal @('checkpoint','inspect',$KafkaSecrets,"$KafkaRuntime/data/meta.properties",$marker)
        Invoke-KafkaLocal @('checkpoint','read',$KafkaSecrets,"$KafkaRuntime/data/meta.properties",$marker)
        Invoke-KafkaLocal @('checkpoint','reject-invalid',$KafkaSecrets,"$KafkaRuntime/data/meta.properties",$marker)
        Write-Host "Kafka restart round $round verified without reissuing credentials."
    }
    # A copy of cluster metadata exercises the missing-overlay guard without
    # moving production configuration or modifying any real account/permission.
    $guardData=Join-Path $KafkaRuntime 'readiness-guard/data'
    New-Item -ItemType Directory -Force -Path $guardData | Out-Null
    $guardMeta=Join-Path $guardData 'meta.properties'
    Copy-Item -LiteralPath "$KafkaRuntime/data/meta.properties" -Destination $guardMeta
    $guardLog=Join-Path $KafkaRuntime 'readiness-guard/result.log'
    # Windows PowerShell turns native stderr into an error record. This probe
    # deliberately fails; capture it before restoring normal fail-fast handling.
    $guardPreference=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        & "$env:LOCAL_JAVA_HOME/bin/java.exe" -Xmx192m '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' "-Dlogback.configurationFile=$PSScriptRoot/java/kafka-logback.xml" -cp "$KafkaClasses;$KafkaHome/libs/*" LocalKafka setup $KafkaSecrets $guardMeta *> $guardLog
        $guardExitCode=$LASTEXITCODE
    } finally { $ErrorActionPreference=$guardPreference }
    if ($guardExitCode -ne 1 -or (Get-Content -LiteralPath $guardLog -Raw -Encoding UTF8) -notmatch 'MissingRuntimeOverlayException' -or (Test-Path -LiteralPath (Join-Path $KafkaRuntime 'readiness-guard/backend.env'))) { throw 'Missing-overlay guard did not fail closed.' }
    if ((Get-FileHash -LiteralPath $KafkaSecrets -Algorithm SHA256).Hash -ne $identityBefore -or (Get-FileHash -LiteralPath $overlay -Algorithm SHA256).Hash -ne $overlayBefore) { throw 'Restart verification unexpectedly rewrote private configuration.' }
    Write-Host 'Missing-overlay guard rejected startup; original identity and backend configuration unchanged.'
} finally {
    if (Test-Path -LiteralPath $marker) { Invoke-KafkaLocal @('checkpoint','cleanup',$KafkaSecrets,"$KafkaRuntime/data/meta.properties",$marker) }
}
Write-Host 'Native local Kafka restart and persistence verification passed. Broker remains running.'
