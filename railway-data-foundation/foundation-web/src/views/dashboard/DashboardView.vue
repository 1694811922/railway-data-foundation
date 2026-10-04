<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { hub } from '../../utils/dataHub'
import { dashboardModules, dashboardMetrics, capabilityTypes, recentResources } from '../../utils/dashboard.js'
import { displayTime } from '../../utils/time'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'

const router = useRouter()
const data = ref({}), demo = ref(false), loading = ref(true), refreshedAt = ref(null)
let revision = 0
const counts = computed(()=>dashboardMetrics(data.value))
const issues = computed(()=>dashboardModules.filter(module=>data.value[module.key]?.error))
const incomplete = computed(()=>dashboardModules.filter(module=>data.value[module.key] && !data.value[module.key].error && !data.value[module.key].complete))
const incompleteText = computed(()=>incomplete.value.map(module=>module.label+'：'+data.value[module.key].notice).join('；'))
const recent = computed(()=>recentResources(data.value.resources?.rows || []))
const capabilityRows = computed(()=>data.value.capabilities?.rows || [])
const distribution = computed(()=>capabilityTypes.map(item=>({ ...item, count:capabilityRows.value.filter(row=>row.type===item.type).length })))
const steps = [
  { label:'数据源', note:'登记连接并测试', path:'/sources' },
  { label:'元数据扫描', note:'发现表结构与字段', path:'/scans' },
  { label:'数据资源', note:'补全业务信息并发布', path:'/resources' },
  { label:'接口能力', note:'登记、验证与启停', path:'/capabilities' },
]
async function load() {
  if (loading.value && refreshedAt.value) return
  const current = ++revision
  loading.value = true
  try {
    const result = await hub.dashboard()
    if (current!==revision) return
    data.value = result.data
    demo.value = result.demo
    refreshedAt.value = new Date()
  } catch {
    if (current!==revision) return
    data.value = Object.fromEntries(dashboardModules.map(({key,label})=>[key,{ rows:[], complete:false, error:true, notice:label+'读取失败，请重试' }]))
    refreshedAt.value = new Date()
  } finally { if (current===revision) loading.value=false }
}
onMounted(load)
onBeforeUnmount(()=>revision++)
</script>

<template>
  <div class="stack dashboard" v-loading="loading">
    <div class="page-heading">
      <div><div class="eyebrow">统一数据底座 / 平台总览</div><h1>首页概览</h1><p>查看数据接入、资源登记与能力状态，沿接入流程进入各模块</p></div>
      <el-button :loading="loading" @click="load">刷新数据</el-button>
    </div>
    <div class="dashboard-summary">
      <div class="summary-tags"><el-tag :type="demo ? 'warning' : 'success'">{{ demo ? '前端演示数据' : '真实接口统计' }}</el-tag><el-tag v-if="issues.length" type="danger">部分接口异常</el-tag><el-tag v-else-if="incomplete.length" type="warning">部分统计未完整</el-tag></div>
      <span>最近读取：{{ displayTime(refreshedAt) }} · 今日任务按北京时间统计</span>
    </div>
    <DemoNotice :show="demo" text="已显式开启前端演示模式。以下统计仅来自当前浏览器的演示记录，不代表真实系统数据。" />
    <el-alert v-if="issues.length" data-testid="dashboard-errors" :title="issues.map(module=>module.label).join('、')+'读取失败'" description="相关统计显示“—”，不使用演示记录或上一次结果替代。请启动或检查后端服务，然后刷新数据。" type="error" :closable="false" show-icon />
    <el-alert v-if="incomplete.length" data-testid="dashboard-incomplete" :title="incompleteText" description="“≥”表示已读取记录中的数量下限，不是系统总数。各列表分页读取，数据变动时请重新刷新。" type="warning" :closable="false" show-icon />
    <div class="stat-grid">
      <button v-for="item in counts" :key="item.key" class="stat-card dashboard-stat" :data-testid="'stat-'+item.key" @click="router.push(item.path)">
        <div class="stat-label">{{ item.label }}<span class="stat-link" aria-hidden="true">↗</span></div>
        <div class="stat-number" :class="{ unavailable:item.value===null }">{{ item.value===null ? '—' : (item.partial ? '≥ ' : '')+item.value }}</div>
        <div class="stat-note">{{ item.value===null ? (loading ? '正在读取…' : '数据未读取成功') : item.note }}</div>
      </button>
    </div>
    <div class="panel">
      <div class="panel-title">资源接入流程 <span class="panel-sub">点击步骤进入对应模块</span></div>
      <div class="flow"><template v-for="(step,index) in steps" :key="step.path"><button class="flow-node flow-step" @click="router.push(step.path)"><strong>{{ step.label }}</strong><span>{{ step.note }}</span></button><span v-if="index&lt;steps.length-1" class="flow-arrow" aria-hidden="true">→</span></template></div>
    </div>
    <div class="grid-2">
      <div class="panel">
        <div class="panel-title">最近登记资源 <el-button text type="primary" @click="router.push('/resources')">查看目录 →</el-button></div>
        <p class="panel-sub">按资源创建时间排序{{ data.resources && !data.resources.complete && !data.resources.error ? '，仅涵盖已读取记录' : '' }}</p>
        <el-alert v-if="data.resources?.error" title="资源列表读取失败，刷新后重试" type="error" :closable="false" show-icon />
        <el-table v-else :data="recent" style="width:100%" empty-text="暂无登记资源" data-testid="dashboard-resources">
          <el-table-column prop="resource_name" label="资源名称" min-width="150" show-overflow-tooltip />
          <el-table-column label="登记时间" min-width="145"><template #default="{row}">{{ displayTime(row.created_at) }}</template></el-table-column>
          <el-table-column label="状态" width="86"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
          <el-table-column label="操作" width="65"><template #default="{row}"><el-button link type="primary" @click="router.push('/resources/'+row.resource_id)">详情</el-button></template></el-table-column>
        </el-table>
      </div>
      <div class="panel" data-testid="dashboard-distribution">
        <div class="panel-title">接口能力类型分布 <span class="panel-sub">{{ data.capabilities?.error ? '读取失败' : capabilityRows.length+' 项'+(data.capabilities && !data.capabilities.complete ? '已读取能力' : '能力') }}</span></div>
        <el-alert v-if="data.capabilities?.error" title="能力列表读取失败，刷新后重试" type="error" :closable="false" show-icon />
        <template v-else><div v-for="item in distribution" :key="item.type" class="distribution-row"><span>{{ item.label }}</span><el-progress :percentage="capabilityRows.length ? Math.round(item.count/capabilityRows.length*100) : 0" :show-text="false" /><strong>{{ item.count }}</strong></div><p class="panel-sub">占比基于本次读取的能力记录；无记录时各类型为 0。</p></template>
      </div>
    </div>
    <div class="panel architecture-note"><div class="panel-title">系统架构与统计说明</div><p>业务数据保留在源系统，统一数据底座负责资源注册、能力纳管和共享治理。现有 REST 提供服务直接读取源系统，并检查底座中的能力状态；共享授权模块尚未实现。</p><p class="panel-sub">可用能力按登记状态、最近一次验证结果、资源发布状态及来源启用状态统计，未执行实时健康探测。页面只读取元数据，不查询业务明细；各列表分页统计，每类最多读取 5,000 条。</p></div>
  </div>
</template>

<style scoped>
.dashboard-summary { display:flex; align-items:center; justify-content:space-between; gap:12px; color:#718497; font-size:12px; flex-wrap:wrap; }
.summary-tags { display:flex; gap:8px; }
.dashboard-stat { text-align:left; cursor:pointer; color:inherit; transition:none; }
.dashboard-stat:hover, .dashboard-stat:focus-visible { border-color:#126c86; outline:2px solid #d7e9ed; }
.dashboard-stat .stat-label { display:flex; align-items:center; justify-content:space-between; font-size:14px; position:relative; z-index:1; }
.stat-link { color:#126c86; }
.dashboard-stat .stat-note { font-size:12px; line-height:1.5; }
.unavailable { color:#8596a7; }
.flow-step { cursor:pointer; display:flex; flex-direction:column; gap:7px; }
.flow-step:hover, .flow-step:focus-visible { border-color:#126c86; outline:2px solid #d7e9ed; }
.flow-step span { font-size:12px; font-weight:400; }
.distribution-row { display:flex; align-items:center; gap:12px; margin:20px 0; }
.distribution-row > span { width:125px; font-size:14px; }
.distribution-row .el-progress { flex:1; }
.distribution-row strong { min-width:24px; text-align:right; }
.architecture-note { background:#eef6f8; border-color:#d7e9ed; }
.architecture-note p { color:#436176; line-height:1.8; margin:8px 0 0; }
.architecture-note .panel-sub { color:#607387; }
</style>
