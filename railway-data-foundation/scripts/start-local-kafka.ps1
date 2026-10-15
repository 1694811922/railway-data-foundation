param([ValidateRange(1024,65535)][int]$Port=19092,[ValidateRange(1024,65535)][int]$ControllerPort=19093,[switch]$Offline)
. "$PSScriptRoot/kafka-local-common.ps1"
& "$PSScriptRoot/install-local-kafka.ps1" -Offline:$Offline
New-Item -ItemType Directory -Force -Path $KafkaRuntime | Out-Null
Protect-KafkaRuntime
Initialize-KafkaJava
$process=Get-RecordedProcess 'kafka'
if (Test-Path -LiteralPath $KafkaSecrets) {
    $values=@{}
    foreach ($line in Get-Content -LiteralPath $KafkaSecrets) { if ($line -match '^([^=]+)=(.*)$') { $values[$Matches[1]]=$Matches[2] } }
    if ($values['port'] -ne "$Port" -or $values['controller.port'] -ne "$ControllerPort") { throw 'Persisted Kafka ports differ; preserve the existing identity and use its ports.' }
} else {
    if (Test-Path -LiteralPath $KafkaServerConfig) { throw 'Kafka configuration without identity found; do not regenerate credentials over existing data.' }
    $values=@{ 'port'="$Port";'controller.port'="$ControllerPort";'topic'='port.container.event.v1';'broker.username'='local_broker';'admin.username'='local_subscription_admin';'probe.username'='local_metadata_probe';'producer.username'='local_event_producer';'controller.username'='local_controller' }
    foreach ($role in @('broker','admin','probe','producer','controller')) { $values["$role.password"]=New-KafkaSecret }
    Write-LocalFile $KafkaSecrets (($values.Keys | Sort-Object | ForEach-Object { "$_=$($values[$_])" }) -join "`n")
}
$meta=Join-Path $KafkaRuntime 'data/meta.properties'
if ($process) {
    Invoke-KafkaLocal @('setup',$KafkaSecrets,$meta)
    Invoke-KafkaLocal @('retention-check',$KafkaSecrets,$meta)
    Write-Host "Kafka already ready on 127.0.0.1:$Port"
    return
}
Assert-FreePort $Port
Assert-FreePort $ControllerPort
$data=(Join-Path $KafkaRuntime 'data').Replace('\','/')
Write-LocalFile $KafkaServerConfig @"
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:$ControllerPort
listeners=BROKER://127.0.0.1:$Port,CONTROLLER://127.0.0.1:$ControllerPort
advertised.listeners=BROKER://127.0.0.1:$Port
listener.security.protocol.map=BROKER:SASL_PLAINTEXT,CONTROLLER:SASL_PLAINTEXT
controller.listener.names=CONTROLLER
inter.broker.listener.name=BROKER
sasl.enabled.mechanisms=SCRAM-SHA-512,PLAIN
sasl.mechanism.inter.broker.protocol=PLAIN
sasl.mechanism.controller.protocol=PLAIN
listener.name.broker.sasl.enabled.mechanisms=SCRAM-SHA-512,PLAIN
listener.name.controller.sasl.enabled.mechanisms=PLAIN
listener.name.broker.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="$($values['broker.username'])" password="$($values['broker.password'])";
listener.name.broker.plain.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="$($values['broker.username'])" password="$($values['broker.password'])" user_$($values['broker.username'])="$($values['broker.password'])" user_$($values['admin.username'])="$($values['admin.password'])";
listener.name.controller.plain.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="$($values['controller.username'])" password="$($values['controller.password'])" user_$($values['controller.username'])="$($values['controller.password'])";
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
allow.everyone.if.no.acl.found=false
super.users=User:$($values['broker.username']);User:$($values['controller.username']);User:$($values['admin.username'])
log.dirs=data
auto.create.topics.enable=false
delete.topic.enable=false
num.partitions=3
default.replication.factor=1
offsets.topic.replication.factor=1
offsets.topic.num.partitions=1
offsets.topic.segment.bytes=1048576
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
log.segment.bytes=16777216
log.index.size.max.bytes=65536
# Local Windows experiment: retention-driven index renames can fail while mapped.
# Preserve messages instead of retrying destructive expiry; monitor disk use.
log.retention.ms=-1
log.retention.bytes=-1
group.initial.rebalance.delay.ms=0
num.network.threads=2
num.io.threads=2
"@
$firstInitialization=-not (Test-Path -LiteralPath $meta)
if ($firstInitialization) {
    if (Test-Path -LiteralPath "$KafkaRuntime/data") { throw 'Unformatted Kafka data directory exists; refusing to format or erase it.' }
    # Formatting runs inside Java so bootstrap credentials never appear in process arguments.
    Invoke-KafkaLocal @('format',$KafkaSecrets,$KafkaServerConfig)
}
if (-not $firstInitialization -and -not (Test-Path -LiteralPath (Join-Path $KafkaRuntime 'backend.env'))) { throw 'Existing Kafka installation overlay missing; preserve data and credentials, do not initialize or reformat it again.' }
if (Test-Path -LiteralPath $KafkaStopFile) { Remove-Item -LiteralPath $KafkaStopFile }
$argfile=Join-Path $KafkaRuntime 'server.args'
Write-LocalFile $argfile ((@('-Xms64m','-Xmx384m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=2','-Dlogback.configurationFile=../../../scripts/java/kafka-logback.xml','-cp',"classes;../../tools/kafka_2.13-$KafkaVersion/libs/*",'LocalKafka','serve','server.properties','stop.request')) -join "`n")
$process=Start-Process -FilePath $KafkaJava -ArgumentList @("`"@$argfile`"") -WorkingDirectory $KafkaRuntime -WindowStyle Hidden -PassThru -RedirectStandardOutput "$KafkaRuntime/server.out.log" -RedirectStandardError "$KafkaRuntime/server.err.log"
Save-LocalProcess 'kafka' $process
$ready=$false
for ($attempt=0;$attempt -lt 30;$attempt++) {
    if ($process.HasExited) { throw 'Kafka exited; inspect the private Kafka runtime logs.' }
    # Do not start authenticated probes before the listener exists. This is only
    # a port check; the Java probe still requires authentication and exact state.
    $socket=New-Object Net.Sockets.TcpClient
    try { $socket.Connect('127.0.0.1',$Port); $listening=$true } catch { $listening=$false } finally { $socket.Dispose() }
    if (-not $listening) { Start-Sleep -Milliseconds 500; continue }
    $operation=if ($firstInitialization) { 'initialize' } else { 'setup' }
    try {
        Invoke-KafkaLocal @($operation,$KafkaSecrets,$meta)
        Invoke-KafkaLocal @('retention-check',$KafkaSecrets,$meta)
        $ready=$true
        break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $ready) { throw 'Kafka readiness timeout; data and identity were preserved.' }
Write-Host "Local Kafka experiment ready: 127.0.0.1:$Port; topic $($values['topic'])."
