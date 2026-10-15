param([ValidateRange(1,20)][int]$Messages=20,[string]$NodeExecutable='node.exe',[switch]$Offline)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/local-common.ps1"
$runs=Join-Path $LocalProject '.runtime'
$before=@(Get-ChildItem -LiteralPath $runs -Directory -Filter 'kafka-chain-*' | ForEach-Object { $_.Name })
# Real isolated Foundation/Provider and the native local Broker. No development identity or shared password.
& "$PSScriptRoot/test-kafka-foundation-live.ps1" -Offline:$Offline -NativeLocalBroker -NodeExecutable $NodeExecutable -Messages $Messages
$new=@(Get-ChildItem -LiteralPath $runs -Directory -Filter 'kafka-chain-*' | Where-Object { $_.Name -notin $before })
if($new.Count -ne 1) { throw 'Cannot unambiguously identify this run evidence; no success report generated.' }
$evidence=$new[0].FullName
$verified=Get-Content -LiteralPath (Join-Path $evidence 'verification.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$cleaned=Get-Content -LiteralPath (Join-Path $evidence 'cleanup.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if(!$verified.passed -or $verified.messages -ne $Messages -or !$verified.telemetry_matched -or !$verified.subscription_revoked -or !$verified.source_unchanged -or !$cleaned.metadata_cleaned -or !$cleaned.temporary_services_stopped) { throw 'Verification/cleanup incomplete; no success report generated.' }
$offsetRows=($verified.production_offsets | ForEach-Object { "| $($_.event_id) | $($_.partition) | $($_.offset) |" }) -join "`n"
$report=@"
# Kafka 模拟数据完整流程结果

本次发送 **$($verified.messages) 条**项目预置模拟事件，实际消费 **$($verified.messages) 条 / $($verified.bytes) 字节**，已核对成功。

模拟源库 → 外部提供服务 → 实际 Kafka → 授权的外部消费者

底座只登记资源、审批订阅、配置权限并接收技术摘要，不接收或保存业务消息。

| 环节 | 实际结果 |
|---|---|
| 数据来源 | port_source_db.container_event；项目 SQL 预置20条，非真实广州港业务数据 |
| 元数据登记 | 实际扫描、登记 Resource、验证并启用 Kafka 能力 |
| 消息生产 | 调用现有 Provider，收到 $($verified.messages) 个真实回执 |
| 未授权订阅 | Broker 拒绝读取 |
| 申请与审批 | 实际提交、管理员确认整主题、配置专属身份和精确权限 |
| 外部消费 | 检查事件编号唯一、位点、数量和载荷字节；未提交消费位点 |
| 消费摘要 | 外部CLI实际自报，真实需求方前端显示；不代表业务处理完成 |
| 权限撤回 | 已核对Broker身份和权限撤销，原连接后续读取被拒绝 |
| 其他权限 | 非授权主题、消费组、订阅身份写入均拒绝；REST授权回归通过 |
| 数据保护 | 原业务行完整摘要前后一致；资源版本/字段没有被Kafka操作修改 |
| 清理 | 独立账号、主体、资源、申请及临时服务已清理；主开发服务保留 |

## 生产回执

主题：$($verified.topic)

| 模拟事件编号 | 分区 | 位点 |
|---|---|---|
$offsetRows

## 实际前端截图

![能力验证与启用](kafka-enabled.png)
![实际生产审计](kafka-real-audit.png)
![需求方消费观察](consumption-real.png)
![订阅撤权结果](subscription-real-revoked.png)

## 范围

本次为真实传输的本机隔离演示，不是性能测试或稳定部署结论。演示登记已清理，当前开发5173不会保留这些测试申请。Windows禁用在线删除主题，消息和测试主题保留在私有Kafka运行目录，测试账号/权限已撤销；未上传业务消息或凭据到报告。成功报告仅在验证与清理均完成后生成。
"@
Write-LocalFile (Join-Path $evidence 'SIMULATION_REPORT.md') $report
Write-Host "Kafka simulation passed: $Messages messages, $($verified.bytes) bytes."
Write-Host "Report: $(Join-Path $evidence 'SIMULATION_REPORT.md')"
