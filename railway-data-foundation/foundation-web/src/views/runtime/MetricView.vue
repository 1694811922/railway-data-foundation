<script setup>
import { session } from '../../utils/sessionState'
import {ref,reactive,computed,onMounted,onBeforeUnmount} from 'vue'
import {telemetryApi} from '../../api/telemetry'
import {filterForm,runtimeQuery,metricWindows,metricSummary,collectorStatus,windowStates,quantity,tagType} from '../../utils/telemetry'
import {displayTime} from '../../utils/time'
import RuntimeFilters from '../../components/RuntimeFilters.vue'
const mock=import.meta.env.VITE_USE_MOCK==='true',form=reactive(filterForm(true)),windows=ref([]),collector=ref(null),error=ref(''),collectorError=ref(''),loading=ref(false),collectorLoading=ref(false)
let active=true,epoch=0,collectorEpoch=0,applied=runtimeQuery(form,true)
const point=ref(applied.collectionPoint),cards=computed(()=>metricSummary(windows.value,point.value)),trafficLabel=computed(()=>point.value==='REST_PROVIDER'?'调用次数':'成功消息数')
async function load(){if(mock)return;const current=++epoch,params={...applied};loading.value=true;error.value='';windows.value=[];point.value=params.collectionPoint
  try{const data=metricWindows(await telemetryApi.windows(params),params);if(active&&current===epoch)windows.value=data}
  catch{if(active&&current===epoch)error.value='共享计量读取失败，请重新查询'}finally{if(active&&current===epoch)loading.value=false}}
async function loadCollector(){if(mock||session.user?.role!=='ADMIN')return;const current=++collectorEpoch;collector.value=null;collectorError.value='';collectorLoading.value=true
  try{const data=collectorStatus(await telemetryApi.collector());if(active&&current===collectorEpoch)collector.value=data}
  catch{if(active&&current===collectorEpoch)collectorError.value='采集状态读取失败'}finally{if(active&&current===collectorEpoch)collectorLoading.value=false}}
function query(){try{applied=runtimeQuery(form,true);load()}catch(e){++epoch;loading.value=false;windows.value=[];error.value=e.message}}
function reset(){Object.assign(form,filterForm(true));query()}
onMounted(()=>{load();loadCollector()});onBeforeUnmount(()=>{active=false;++epoch;++collectorEpoch})
</script>
<template><div class="stack">
  <div class="page-heading"><h1>共享计量</h1><el-button :disabled="mock" :loading="loading" @click="load">刷新计量</el-button></div>
  <el-alert v-if="mock" title="演示模式下不读取共享计量" type="warning" :closable="false" />
  <div class="panel"><RuntimeFilters :form="form" metrics :busy="loading" @query="query" @reset="reset" /><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon data-testid="metric-error" /></div>
  <div class="stat-grid"><div v-for="card in cards" :key="card.id" class="stat-card" :data-testid="'metric-'+card.id" :title="card.partial?'部分数据，数值为已采集下限':card.value===null?'尚无已确认计量':'已接收事件汇总'"><div class="stat-label">{{card.label}}</div><div class="stat-number">{{card.partial?'≥ ':''}}{{quantity(card.value)}}</div></div></div>
  <div class="panel" v-loading="loading"><div class="panel-title">分钟窗口 <el-tag v-if="windows.length" type="info">{{windows.length}} 个窗口</el-tag></div>
    <el-table :data="windows" empty-text="暂无计量数据" data-testid="metric-table">
      <el-table-column label="窗口开始" min-width="175" fixed="left"><template #default="{row}">{{displayTime(row.window_start)}}</template></el-table-column>
      <el-table-column label="上报状态" width="105"><template #default="{row}"><el-tag :type="tagType(row.state)">{{windowStates[row.state]}}</el-tag></template></el-table-column>
      <el-table-column :label="trafficLabel" width="110"><template #default="{row}">{{quantity(point==='REST_PROVIDER'?row.calls:row.messages)}}</template></el-table-column>
      <el-table-column v-for="(label,key) in {successes:'成功',denied:'拒绝',errors:'异常',records:'记录数',bytes:'字节数（B）'}" :key="key" :label="label" :min-width="key==='bytes'?135:90"><template #default="{row}">{{quantity(row[key])}}</template></el-table-column>
      <el-table-column label="平均耗时（ms）" width="145"><template #default="{row}">{{row.average_duration_ms===null?'—':row.average_duration_ms.toFixed(1)}}</template></el-table-column><el-table-column :label="point==='REST_PROVIDER'?'频率（次/s）':'频率（条/s）'" width="125"><template #default="{row}">{{row.frequency_per_second===null?'—':row.frequency_per_second.toFixed(3)}}</template></el-table-column>
      <el-table-column prop="late_reports" label="节点延迟报告" width="130" /><el-table-column prop="maximum_report_delay_ms" label="节点最大延迟（ms）" width="175" />
    </el-table>
  </div>
  <div v-if="session.user?.role==='ADMIN'" class="panel" v-loading="collectorLoading"><div class="panel-title">采集状态 <el-button link type="primary" :disabled="mock" @click="loadCollector">刷新采集状态</el-button></div>
    <el-alert v-if="collectorError" :title="collectorError" type="error" :closable="false" data-testid="collector-error" />
    <el-descriptions v-else-if="collector" :column="3" border data-testid="collector-status"><el-descriptions-item label="自动采集"><el-tag :type="collector.enabled?'success':'info'">{{collector.enabled?'已开启':'已关闭'}}</el-tag></el-descriptions-item><el-descriptions-item label="存储初始化">{{collector.storage_ready?'已就绪':'未就绪'}}</el-descriptions-item><el-descriptions-item label="最近上报">{{displayTime(collector.last_success_at)}}</el-descriptions-item>
      <el-descriptions-item v-for="(label,key) in {queued_events:'排队事件',pending_reports:'待补报报告',quarantined_reports:'隔离报告',dropped_events:'丢弃事件',spool_failures:'存储失败',delivery_failures:'上报失败'}" :key="key" :label="label">{{quantity(collector[key])}}</el-descriptions-item></el-descriptions>
  </div>
</div></template>
