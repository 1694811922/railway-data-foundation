. "$PSScriptRoot/kafka-local-common.ps1"
Initialize-KafkaJava
if (-not (Test-Path -LiteralPath $KafkaServerConfig)) { throw 'Configure the local experimental Kafka first.' }
& "$env:LOCAL_JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp "$KafkaClasses;$KafkaHome/libs/*" -d $KafkaClasses "$PSScriptRoot/java/LocalKafkaInitCheck.java"
if ($LASTEXITCODE -ne 0) { throw 'Fresh initialization checks compilation failed.' }
Push-Location $KafkaRuntime
try {
    $resultName='bootstrap-result-'+([Guid]::NewGuid().ToString('N'))+'.properties'
    & "$env:LOCAL_JAVA_HOME/bin/java.exe" -Xmx384m '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' "-Dlogback.configurationFile=$PSScriptRoot/java/kafka-logback.xml" -cp "$KafkaClasses;$KafkaHome/libs/*" LocalKafkaInitCheck $resultName
    if ($LASTEXITCODE -ne 0) { throw 'Fresh native initialization verification failed; private fixture retained.' }
    if (-not (Test-Path -LiteralPath $resultName)) { throw 'Bootstrap process ended without completing its checks.' }
    $evidence=Get-Content -LiteralPath $resultName -Raw
    foreach ($check in @('fresh.initialization','wrong.password.rejected','missing.acl.rejected','missing.overlay.rejected','test.broker.stopped')) {
        if ($evidence -notmatch ('(?m)^'+[regex]::Escape($check)+'=true\r?$')) { throw "Bootstrap completion evidence missing: $check" }
    }
} finally { Pop-Location }
Write-Host 'Native bootstrap and fail-closed readiness checks passed; isolated test broker stopped.'
