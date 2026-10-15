<script setup>
import {collectionPoints,outcomes,reasons,quantity,tagType} from '../utils/telemetry'
import {displayTime} from '../utils/time'
defineProps({event:Object})
const ids={event_id:'事件编号',trace_id:'追踪编号',collector_id:'采集节点',resource_id:'资源编号',capability_id:'能力编号',relationship_id:'关系编号',subject_id:'需求方编号',policy_id:'策略编号'}
</script>
<template><el-descriptions v-if="event" :column="1" :label-width="140" border data-testid="runtime-event-detail">
  <el-descriptions-item label="调用结果"><el-tag :type="tagType(event.outcome)">{{outcomes[event.outcome]}}</el-tag> · {{reasons[event.reason_code]}}</el-descriptions-item>
  <el-descriptions-item label="采集位置">{{collectionPoints[event.collection_point]}}</el-descriptions-item>
  <el-descriptions-item v-for="(label,key) in ids" :key="key" :label="label"><span class="id-text">{{event[key]||'未知'}}</span></el-descriptions-item>
  <el-descriptions-item label="执行时治理版本">关系 {{event.relationship_version??'未知'}} · 策略 {{event.policy_version??'未知'}}</el-descriptions-item>
  <el-descriptions-item label="开始时间">{{displayTime(event.started_at)}}</el-descriptions-item><el-descriptions-item label="完成时间">{{displayTime(event.completed_at)}}</el-descriptions-item>
  <el-descriptions-item label="底座接收时间">{{displayTime(event.received_at)}}</el-descriptions-item>
  <el-descriptions-item label="响应状态">{{event.http_status}}</el-descriptions-item><el-descriptions-item label="记录数">{{quantity(event.record_count)}}</el-descriptions-item><el-descriptions-item label="字节数（B）">{{quantity(event.byte_count)}}</el-descriptions-item><el-descriptions-item label="执行耗时（ms）">{{quantity(event.duration_ms)}}</el-descriptions-item>
  <template v-if="event.collection_point==='KAFKA_PRODUCER'"><el-descriptions-item label="消息主题">{{event.topic||'—'}}</el-descriptions-item><el-descriptions-item label="消息分区">{{event.topic_partition??'—'}}</el-descriptions-item><el-descriptions-item label="消息位点">{{event.topic_offset??'—'}}</el-descriptions-item></template>
</el-descriptions></template>
<style scoped>.id-text{overflow-wrap:anywhere}</style>
