. "$PSScriptRoot/kafka-local-common.ps1"
Initialize-KafkaJava
foreach ($file in @('kafka-local-common.ps1','install-local-kafka.ps1','start-local-kafka.ps1','stop-local-kafka.ps1','test-local-kafka.ps1','test-local-kafka-bootstrap.ps1','run-interface-lab.ps1','run-kafka-simulation.ps1','start-local.ps1','test-kafka-foundation-live.ps1')) {
    $tokens=$null;$errors=$null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors) | Out-Null
    if ($errors.Count -gt 0) { throw "PowerShell syntax validation failed: $file" }
}
& "$env:LOCAL_JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp "$KafkaClasses;$KafkaHome/libs/*" -d $KafkaClasses "$PSScriptRoot/java/InterfaceLabCheck.java"
if ($LASTEXITCODE -ne 0) { throw 'Experiment checks compilation failed.' }
& "$env:LOCAL_JAVA_HOME/bin/java.exe" -Xmx128m -cp "$KafkaClasses;$KafkaHome/libs/*" InterfaceLabCheck
if ($LASTEXITCODE -ne 0) { throw 'Experiment calculations validation failed.' }
$invalidEvidence=Join-Path $KafkaRuntime 'invalid-range-check'
$taskPreviousPreference=$ErrorActionPreference
try {
    # Windows PowerShell 5 treats expected native stderr as an error record.
    $ErrorActionPreference='Continue'
    & "$env:LOCAL_JAVA_HOME/bin/java.exe" -Xmx128m -cp "$KafkaClasses;$KafkaHome/libs/*" InterfaceLab $KafkaSecrets "$KafkaRuntime/data/meta.properties" $invalidEvidence 0 512 60 1000 2>$null
    $taskBoundaryExit=$LASTEXITCODE
} finally { $ErrorActionPreference=$taskPreviousPreference }
if ($taskBoundaryExit -eq 0 -or (Test-Path -LiteralPath $invalidEvidence)) { throw 'Invalid experiment bounds were accepted.' }
Write-Host 'PowerShell parser and native experiment bounds checks passed.'
