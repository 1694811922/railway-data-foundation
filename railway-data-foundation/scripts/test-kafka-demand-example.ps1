param([switch]$Offline)
. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
$env:JAVA_HOME=$env:LOCAL_JAVA_HOME
$settings=Join-Path $PSScriptRoot 'maven-settings.xml'
$cache=Join-Path (Split-Path -Parent $LocalProject) '.runtime/m2'
$taskKafkaArgs=@('-gs',$settings,'-s',$settings,"-Dmaven.repo.local=$cache",'-f',"$LocalProject/examples/kafka-demand/pom.xml",'test','-B','-ntp')
if ($Offline) { $taskKafkaArgs+='-o' }
& mvn.cmd @taskKafkaArgs
if ($LASTEXITCODE -ne 0) { throw 'Kafka Broker authorization verification failed.' }
