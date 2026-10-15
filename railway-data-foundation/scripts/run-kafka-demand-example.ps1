param(
    [Parameter(Mandatory=$true)][string]$Brokers,
    [Parameter(Mandatory=$true)][string]$Topic,
    [Parameter(Mandatory=$true)][string]$Group,
    [Parameter(Mandatory=$true)][string]$Username,
    [ValidateSet('SASL_SSL','SASL_PLAINTEXT')][string]$Protocol='SASL_SSL',
    [ValidateSet('SCRAM-SHA-256','SCRAM-SHA-512','PLAIN')][string]$Mechanism='SCRAM-SHA-512',
    [ValidateRange(1,1000)][int]$MaxMessages=10,
    [ValidateRange(1,60)][int]$Seconds=15,
    [ValidateSet('latest','earliest')][string]$OffsetReset='latest',
    [string]$FoundationUrl,
    [string]$GrantId,
    [switch]$Offline
)
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
$env:JAVA_HOME=$env:LOCAL_JAVA_HOME
$settings=Join-Path $PSScriptRoot 'maven-settings.xml'
$cache=Join-Path (Split-Path -Parent $LocalProject) '.runtime/m2'
$taskKafkaExample=Join-Path $LocalProject 'examples/kafka-demand'
$taskKafkaClasspath=Join-Path $LocalRuntime 'kafka-demand-classpath.txt'
$taskKafkaBuild=@('-gs',$settings,'-s',$settings,"-Dmaven.repo.local=$cache",'-f',"$taskKafkaExample/pom.xml",'compile','org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath','-DincludeScope=runtime',"-Dmdep.outputFile=$taskKafkaClasspath",'-B','-ntp')
if ($Offline) { $taskKafkaBuild+='-o' }
& mvn.cmd @taskKafkaBuild
if ($LASTEXITCODE -ne 0) { throw 'Kafka example build failed.' }
$taskKafkaSecret=Read-Host '请输入独立Kafka账号密码（不回显，不是REST调用令牌）' -AsSecureString
$taskKafkaPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskKafkaSecret)
$taskKafkaProcess=[Diagnostics.Process]::new()
$taskKafkaStarted=$false
$taskReportSecret=$null
$taskReportPointer=[IntPtr]::Zero
try {
    if ($FoundationUrl -or $GrantId) {
        if (-not $FoundationUrl -or $GrantId -notmatch '^KSG_[a-f0-9]{32}$') { throw 'Reporting requires FoundationUrl and GrantId together.' }
        $taskReportSecret=Read-Host '请输入一次性消费摘要上报令牌（不是Kafka密码）' -AsSecureString
        $taskReportPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskReportSecret)
    }
    $taskKafkaCp="$taskKafkaExample/target/classes;"+[IO.File]::ReadAllText($taskKafkaClasspath).Trim()
    $taskKafkaProcess.StartInfo.FileName="$env:LOCAL_JAVA_HOME/bin/java.exe"
    $taskKafkaProcess.StartInfo.UseShellExecute=$false
    $taskKafkaProcess.StartInfo.CreateNoWindow=$true
    $taskKafkaProcess.StartInfo.Arguments="-Dfile.encoding=UTF-8 -cp `"$taskKafkaCp`" cn.railway.example.kafka.KafkaSubscriber"
    $taskKafkaEnvironment=$taskKafkaProcess.StartInfo.EnvironmentVariables
    $taskKafkaEnvironment.Clear()
    foreach ($key in @('SystemRoot','PATH','PATHEXT','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA')) {
        $value=[Environment]::GetEnvironmentVariable($key,'Process')
        if ($value) { $taskKafkaEnvironment[$key]=$value }
    }
    $taskKafkaEnvironment['KAFKA_DEMAND_BROKERS']=$Brokers; $taskKafkaEnvironment['KAFKA_DEMAND_TOPIC']=$Topic; $taskKafkaEnvironment['KAFKA_DEMAND_GROUP']=$Group
    $taskKafkaEnvironment['KAFKA_DEMAND_USERNAME']=$Username; $taskKafkaEnvironment['KAFKA_DEMAND_PROTOCOL']=$Protocol; $taskKafkaEnvironment['KAFKA_DEMAND_MECHANISM']=$Mechanism
    $taskKafkaEnvironment['KAFKA_DEMAND_MAX_MESSAGES']=$MaxMessages.ToString(); $taskKafkaEnvironment['KAFKA_DEMAND_SECONDS']=$Seconds.ToString()
    $taskKafkaEnvironment['KAFKA_DEMAND_OFFSET_RESET']=$OffsetReset
    if ($taskReportSecret) {
        $taskKafkaEnvironment['KAFKA_DEMAND_FOUNDATION_URL']=$FoundationUrl; $taskKafkaEnvironment['KAFKA_DEMAND_GRANT_ID']=$GrantId
        $taskKafkaEnvironment['KAFKA_DEMAND_REPORT_TOKEN']=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskReportPointer)
    }
    $taskKafkaEnvironment['KAFKA_DEMAND_PASSWORD']=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskKafkaPointer)
    $taskKafkaStarted=$taskKafkaProcess.Start()
    $taskKafkaProcess.WaitForExit()
    if ($taskKafkaProcess.ExitCode -ne 0) { throw 'Kafka subscription or consumption report was not confirmed; inspect the fixed status messages.' }
} finally {
    if ($taskKafkaStarted -and -not $taskKafkaProcess.HasExited) { $taskKafkaProcess.Kill(); $taskKafkaProcess.WaitForExit() }
    $taskKafkaProcess.StartInfo.EnvironmentVariables.Clear()
    $taskKafkaProcess.Dispose()
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskKafkaPointer)
    $taskKafkaSecret.Dispose()
    if ($taskReportSecret) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskReportPointer); $taskReportSecret.Dispose() }
}
