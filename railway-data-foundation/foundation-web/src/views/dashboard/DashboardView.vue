<script setup>
import { session } from '../../utils/sessionState'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { hub } from '../../utils/dataHub'
import { dashboardModules, dashboardMetrics, capabilityTypes, recentResources } from '../../utils/dashboard.js'
import { dashboardWorkGroups, dashboardWork, filterDashboardWork } from '../../utils/dashboardWork.js'
import { catalogPage, resourcePageSizes } from '../../utils/resourceCatalog.js'
import { displayTime } from '../../utils/time'
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
const workKey=ref('resources'), workKeyword=ref(''), workPage=ref(1), workPageSize=ref(10)
const workGroups=computed(()=>dashboardWorkGroups.map(group=>dashboardWork(data.value,group.key,demo.value)))
const currentWork=computed(()=>workGroups.value.find(group=>group.key===workKey.value))
const matchingWork=computed(()=>filterDashboardWork(currentWork.value.rows,workKeyword.value))
const pagedWork=computed(()=>catalogPage(matchingWork.value,workPage.value,workPageSize.value))
watch([workKey,workKeyword,workPageSize],()=>{workPage.value=1})
function openWork(row){
  if(loading.value || currentWork.value.state!=='ready')return
  const current=currentWork.value.rows.find(item=>item.id===row.id)
  if(current?.route)router.push(current.route)
}
const steps = [
  { label:'数据源', path:'/sources' },
  { label:'元数据扫描', path:'/scans' },
  { label:'数据资源', path:'/resources' },
  { label:'接口能力', path:'/capabilities' },
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
      <div><h1>{{session.user?.role==='PROVIDER'?'数据提供方工作台':'首页概览'}}</h1></div>
      <el-button :loading="loading" @click="load">刷新数据</el-button>
    </div>
    <div class="dashboard-summary">
      <div class="summary-tags"><el-tag :type="demo ? 'warning' : 'success'">{{ demo ? '前端演示数据' : '真实接口统计' }}</el-tag><el-tag v-if="issues.length" type="danger">部分接口异常</el-tag><el-tag v-else-if="incomplete.length" type="warning">部分统计未完整</el-tag></div>
      <span>更新：{{ displayTime(refreshedAt) }}</span>
    </div>
    <el-alert v-if="issues.length" data-testid="dashboard-errors" :title="issues.map(module=>module.label).join('、')+'读取失败'" type="error" :closable="false" show-icon />
    <el-alert v-if="incomplete.length" data-testid="dashboard-incomplete" :title="incompleteText" type="warning" :closable="false" show-icon />
    <div class="stat-grid">
      <button v-for="item in counts" :key="item.key" class="stat-card dashboard-stat" :title="item.note" :data-testid="'stat-'+item.key" @click="router.push(item.path)">
        <div class="stat-label">{{ item.label }}<span class="stat-link" aria-hidden="true">↗</span></div>
        <div class="stat-number" :class="{ unavailable:item.value===null }">{{ item.value===null ? '—' : (item.partial ? '≥ ' : '')+item.value }}</div>

      </button>
    </div>
    <div class="panel">
      <div class="panel-title">资源接入流程 </div>
      <div class="flow"><template v-for="(step,index) in steps" :key="step.path"><button class="flow-node flow-step" @click="router.push(step.path)"><strong>{{ step.label }}</strong></button><span v-if="index&lt;steps.length-1" class="flow-arrow" aria-hidden="true">→</span></template></div>
    </div>
    <div class="panel" data-testid="dashboard-work">
      <div class="panel-title">待处理事项 </div>
      <el-tabs v-model="workKey">
        <el-tab-pane v-for="group in workGroups" :key="group.key" :name="group.key" :label="`${group.label}（${group.count===null?'—':(group.partial?'≥ ':'')+group.count}）`" />
      </el-tabs>

      <el-alert v-if="currentWork.state==='error'" data-testid="dashboard-work-error" :title="currentWork.notice" type="error" show-icon :closable="false" />
      <el-alert v-else-if="currentWork.state==='loading'" title="正在读取相关元数据" type="info" :closable="false" />
      <template v-else>
        <el-alert v-if="currentWork.partial" data-testid="dashboard-work-partial" :title="currentWork.notice" type="warning" show-icon :closable="false" />
        <div class="work-tools"><el-input v-model="workKeyword" clearable maxlength="200" placeholder="搜索名称、编号、关联来源或处理提示" aria-label="待处理事项关键词" /><span class="panel-sub" data-testid="dashboard-work-count">匹配 {{ matchingWork.length }} 项</span></div>
        <el-table :data="pagedWork.rows" :max-height="420" :empty-text="currentWork.rows.length?'没有符合条件的事项':currentWork.empty" data-testid="dashboard-work-list">
          <el-table-column label="名称与编号" min-width="220"><template #default="{row}"><strong>{{ row.name }}</strong><div class="id-text work-id">{{ row.id }}</div></template></el-table-column>
          <el-table-column prop="related" label="关联来源／资源" min-width="160" show-overflow-tooltip />
          <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
          <el-table-column label="处理提示" min-width="230"><template #default="{row}"><span class="work-reason">{{ row.reason }}</span></template></el-table-column>
          <el-table-column label="最近记录时间" min-width="155"><template #default="{row}">{{ displayTime(row.time) }}</template></el-table-column>
          <el-table-column label="操作" width="140"><template #default="{row}"><el-button link type="primary" :disabled="loading||!row.route" @click="openWork(row)">{{ row.action }}</el-button></template></el-table-column>
        </el-table>
        <el-pagination class="work-pager" data-testid="dashboard-work-pager" :current-page="pagedWork.page" v-model:page-size="workPageSize" :page-sizes="resourcePageSizes" :total="matchingWork.length" layout="sizes,prev,pager,next" :pager-count="5" :disabled="loading" @current-change="workPage=$event" />
      </template>
    </div>
    <div class="grid-2">
      <div class="panel">
        <div class="panel-title">最近登记资源 <el-button text type="primary" @click="router.push('/resources')">查看目录 →</el-button></div>

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
        <template v-else><div v-for="item in distribution" :key="item.type" class="distribution-row"><span>{{ item.label }}</span><el-progress :percentage="capabilityRows.length ? Math.round(item.count/capabilityRows.length*100) : 0" :show-text="false" /><strong>{{ item.count }}</strong></div></template>
      </div>
    </div>

  </div>
</template>

<style scoped>
.dashboard-summary { display:flex; align-items:center; justify-content:space-between; gap:12px; color:#718497; font-size:12px; flex-wrap:wrap; }
.summary-tags { display:flex; gap:8px; }
.dashboard-stat { text-align:left; cursor:pointer; color:inherit; transition:none; }
.dashboard-stat:hover, .dashboard-stat:focus-visible { border-color:#126c86; outline:2px solid #d7e9ed; }
.dashboard-stat .stat-label { display:flex; align-items:center; justify-content:space-between; font-size:14px; position:relative; z-index:1; }
.stat-link { color:#126c86; }
.unavailable { color:#8596a7; }
.flow-step { cursor:pointer; display:flex; flex-direction:column; gap:7px; }
.flow-step:hover, .flow-step:focus-visible { border-color:#126c86; outline:2px solid #d7e9ed; }
.work-tools { display:flex;align-items:center;gap:16px;margin:16px 0; }
.work-tools .el-input { max-width:540px; }
.work-id { font-size:12px;margin-top:6px;overflow-wrap:anywhere; }
.work-reason { line-height:1.6;overflow-wrap:anywhere; }
.work-pager { margin-top:16px;flex-wrap:wrap;gap:6px; }
.distribution-row { display:flex; align-items:center; gap:12px; margin:20px 0; }
.distribution-row > span { width:125px; font-size:14px; }
.distribution-row .el-progress { flex:1; }
.distribution-row strong { min-width:24px; text-align:right; }
</style>
