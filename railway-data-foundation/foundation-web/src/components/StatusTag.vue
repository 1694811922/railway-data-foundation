<script setup>
defineProps({ value: { type: String, default: 'UNKNOWN' } })
const color = { OFFLINE:'warning', PUBLISHED: 'success', ENABLED: 'success', SUCCESS: 'success', UP: 'success', CONNECTED: 'success', VERIFIED: 'primary', RUNNING: 'primary', DRAFT: 'info', PENDING: 'info', UNKNOWN: 'info', UNTESTED: 'info', DISABLED: 'warning', DEGRADED: 'warning', FAILED: 'danger', DOWN: 'danger', RETIRED: 'info' }
const label = { OFFLINE:'已下架', PUBLISHED:'已发布', ENABLED:'已启用', SUCCESS:'成功', UP:'正常', CONNECTED:'已连接', VERIFIED:'已验证', RUNNING:'扫描中', DRAFT:'草稿', PENDING:'等待处理', UNKNOWN:'未知', UNTESTED:'未检测', DISABLED:'已停用', DEGRADED:'降级', FAILED:'失败', DOWN:'异常', RETIRED:'已退役', DISCOVERED:'已发现', REST_API:'REST 接口', KAFKA_TOPIC:'Kafka 消息主题', CDC_STREAM:'CDC 变化流', READONLY_QUERY:'只读查询' }
</script>
<template><el-tag :type="color[value] || 'info'" effect="light" size="small">{{ label[value] || value || '—' }}</el-tag></template>
