<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { subscriptionApi } from '../api/subscription'
import { explicitDemo } from '../utils/sessionState'
import { subscriptionReadGate } from '../utils/subscription'
import { assertConsumptionPage } from '../utils/consumption'
import { displayTime } from '../utils/time'
const props = defineProps({ grantId: { type: String, required: true } })
const data = ref(null), page = ref(1), size = ref(10), total = ref(0), loading = ref(false), error = ref('')
const gate = subscriptionReadGate()
async function load() {
  if (explicitDemo) return
  const token = gate.begin(), id = props.grantId, limit = size.value, offset = (page.value - 1) * limit
  loading.value = true; error.value = ''; data.value = null
  try {
    const result = assertConsumptionPage(await subscriptionApi.consumption(id, { limit, offset }), id, limit, offset)
    if (gate.current(token)) { data.value = result; total.value = result.total }
  } catch (e) { if (gate.current(token)) { total.value = 0; error.value = e.response?.data?.message || e.message || '消费摘要读取失败' } }
  finally { if (gate.current(token)) loading.value = false }
}
watch(page, load)
watch(size, () => { if (page.value === 1) load(); else page.value = 1 })
watch(() => props.grantId, () => { gate.invalidate(); if (page.value === 1) load(); else page.value = 1 })
onMounted(load); onBeforeUnmount(() => { gate.dispose(); data.value = null })
</script>
<template>
  <section class="stack" data-testid="consumption-panel" v-loading="loading">
    <div class="panel-heading"><h3>消费观察</h3><el-button :disabled="explicitDemo" @click="load">刷新消费摘要</el-button></div>
    <el-alert v-if="explicitDemo" title="演示模式不读取真实消费摘要" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="consumption-error"><el-button link @click="load">重试消费摘要</el-button></el-alert>
    <template v-if="data">
      <el-alert title="本申请累计自报观察值，包含历史身份配置；重读会再次计数，不代表业务处理完成。" type="info" :closable="false" />
      <el-empty v-if="data.summary.state === 'NOT_REPORTED'" description="尚未收到消费摘要，消费数量未知" :image-size="65" />
      <template v-else>
        <el-descriptions :column="3" border data-testid="consumption-summary"><el-descriptions-item label="观察消息条次">{{data.summary.records}}</el-descriptions-item><el-descriptions-item label="载荷字节合计">{{data.summary.bytes}}</el-descriptions-item><el-descriptions-item label="接收报告数">{{data.summary.reports}}</el-descriptions-item></el-descriptions>
        <el-table :data="data.items" data-testid="consumption-table"><el-table-column type="expand"><template #default="{row}"><el-descriptions :column="1" border style="padding:16px 28px"><el-descriptions-item label="摘要编号">{{row.report_id}}</el-descriptions-item><el-descriptions-item label="授权版本">{{row.grant_version}}</el-descriptions-item><el-descriptions-item label="观察开始">{{displayTime(row.started_at)}}</el-descriptions-item><el-descriptions-item label="观察结束">{{displayTime(row.completed_at)}}</el-descriptions-item><el-descriptions-item label="底座收到时间">{{displayTime(row.received_at)}}</el-descriptions-item></el-descriptions></template></el-table-column><el-table-column label="观察结束" min-width="165"><template #default="{row}">{{displayTime(row.completed_at)}}</template></el-table-column><el-table-column prop="record_count" label="消息条次" width="90" /><el-table-column prop="byte_count" label="载荷字节" width="100" /><el-table-column label="接收时间" min-width="165"><template #default="{row}">{{displayTime(row.received_at)}}</template></el-table-column><el-table-column prop="broker_username" label="订阅身份" min-width="180" show-overflow-tooltip /></el-table>
      </template>
    </template>
    <el-pagination v-if="!error && total > 0" v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" />
  </section>
</template>
<style scoped>.panel-heading { display:flex; justify-content:space-between; align-items:center; }.panel-heading h3 { margin:0; }.el-pagination { justify-content:flex-end; }</style>
