. "$PSScriptRoot/kafka-local-common.ps1"
$process=Get-RecordedProcess 'kafka'
if (-not $process) { Write-Host 'Recorded local Kafka is stopped; data preserved.'; return }
Write-LocalFile $KafkaStopFile 'shutdown'
if (-not $process.WaitForExit(30000)) { throw 'Kafka graceful shutdown timed out; refusing to kill it or remove its data.' }
Write-Host 'Kafka stopped gracefully; local data and identity files retained. Restart verification is a separate check.'
