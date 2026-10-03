<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { hub } from '../../utils/dataHub'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
const router = useRouter()
const sources = ref([]), tasks = ref([]), resources = ref([]), capabilities = ref([])
const demo = ref({ sources:false, tasks:false, resources:false, capabilities:false })
const loading = ref(true)
const types = ['REST_API','KAFKA_TOPIC','CDC_STREAM','READONLY_QUERY']
const typeLabels = ['REST API','Kafka Topic','CDC Stream','Readonly Query']
const counts = computed(() => [
  { label:'已接入数据源', value:sources.value.length, note:'外部数据源登记' },
  { label:'已发布数据资源', value:resources.value.filter(x => x.status === 'PUBLISHED').length, note:'已纳入资源目录' },
  { label:'已注册接口能力', value:capabilities.value.length, note:'多协议能力纳管' },
  { label:'当前可用能力', value:capabilities.value.filter(x => x.status === 'ENABLED' && x.health_status === 'UP').length, note:'启用且健康' },
  { label:'今日扫描任务', value:tasks.value.filter(x => String(x.started_at || '').startsWith(new Date().toISOString().slice(0,10))).length, note:'当天启动' },
  { label:'异常能力', value:capabilities.value.filter(x => ['DOWN','DEGRADED'].includes(x.health_status)).length, note:'需关注健康状态' },
])
const distribution = computed(() => types.map((type,i) => ({ type, label:typeLabels[i], count:capabilities.value.filter(c=>c.type===type).length })))
const anyDemo = computed(() => Object.values(demo.value).some(Boolean))
async function load() {
  loading.value=true
  const results = await Promise.allSettled([hub.sources(),hub.tasks(),hub.resources(),hub.capabilities()])
  for (const [i,key] of ['sources','tasks','resources','capabilities'].entries()) if (results[i].status==='fulfilled') {
    const data=results[i].value; ({sources,tasks,resources,capabilities})[key].value=data.rows || []; demo.value[key]=data.demo
  }
  loading.value=false
}
onMounted(load)
</script>
<template>
  <div class="stack" v-loading="loading">
    <div class="page-heading"><div><div class="eyebrow">OVERVIEW / 平台总览</div><h1>首页概览</h1><p>统一观察数据接入、资源登记与能力状态</p></div><el-button @click="load">刷新数据</el-button></div>
    <DemoNotice :show="anyDemo" text="扫描、资源和能力统计包含前端演示数据；数据源优先从真实接口获取。各模块以页面标记为准。" />
    <div class="stat-grid"><div v-for="item in counts" :key="item.label" class="stat-card"><div class="stat-label">{{ item.label }}</div><div class="stat-number">{{ item.value }}</div><div class="stat-note">{{ item.note }}</div></div></div>
    <div class="panel"><div class="panel-title">资源接入流程 <span class="panel-sub">从源系统到可访问能力</span></div><div class="flow"><div v-for="(step,index) in ['数据源','元数据扫描','数据资源','接口能力']" :key="step" style="display:contents"><div class="flow-node">{{ step }}</div><span v-if="index<3" class="flow-arrow">→</span></div></div></div>
    <div class="grid-2">
      <div class="panel"><div class="panel-title">最近接入资源 <el-button text type="primary" @click="router.push('/resources')">查看目录 →</el-button></div><el-table :data="resources.slice(0,5)" style="width:100%" empty-text="暂无资源"><el-table-column prop="resource_name" label="资源名称" min-width="190" /><el-table-column label="状态" width="90"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column><el-table-column label="操作" width="72"><template #default="{row}"><el-button link type="primary" @click="router.push(`/resources/${row.resource_id}`)">详情</el-button></template></el-table-column></el-table></div>
      <div class="panel"><div class="panel-title">接口能力类型分布 <span class="panel-sub">{{ capabilities.length }} 项能力</span></div><div v-for="item in distribution" :key="item.type" style="display:flex;align-items:center;gap:12px;margin:18px 0"><span style="width:115px;font-size:13px">{{ item.label }}</span><el-progress :percentage="capabilities.length ? Math.round(item.count/capabilities.length*100) : 0" :show-text="false" style="flex:1" /><strong style="width:20px;text-align:right">{{ item.count }}</strong></div></div>
    </div>
    <div class="panel" style="background:#eef6f8;border-color:#d7e9ed"><div class="panel-title">系统架构说明</div><p style="color:#436176;line-height:1.8;margin:0">业务数据不进入统一数据底座，底座负责资源注册、能力纳管和共享治理。数据保留在源系统，由提供方与需求方按授权直接共享。</p></div>
  </div>
</template>
