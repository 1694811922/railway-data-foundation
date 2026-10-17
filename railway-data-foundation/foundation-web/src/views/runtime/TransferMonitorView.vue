<script setup>
import {ref,reactive,watch,onMounted,onBeforeUnmount} from 'vue'
import {useRoute} from 'vue-router'
import {telemetryApi} from '../../api/telemetry'
import {session,explicitDemo} from '../../utils/sessionState'
import {receivingReadGate} from '../../utils/receiving'
import {transferQuery,transferMonitorPage,transferMethods,transferStages,durationScopes,summaryLabels,monitorRange} from '../../utils/transferMonitor'
import {reasons,quantity,assertEvent,traceEvents,collectorStatus} from '../../utils/telemetry'
import {transferReasons,logOutcomes,logColor} from '../../utils/transferLogs'
import {displayTime} from '../../utils/time'
import RuntimeEventDetails from '../../components/RuntimeEventDetails.vue'
import KafkaConsumptionPanel from '../../components/KafkaConsumptionPanel.vue'
import {subscriptionApi} from '../../api/subscription'
import {assertSubscriptionPage} from '../../utils/subscription'
const route=useRoute(),form=reactive({range:monitorRange(),method:'',stage:'',outcome:'',resourceId:'',taskId:'',traceId:''})
const rows=ref([]),total=ref(0),summary=ref(null),configured=ref(null),page=ref(1),size=ref(20),loading=ref(false),error=ref(''),auto=ref(false),live=ref(true),advanced=ref(false)
const gate=receivingReadGate(),detailGate=receivingReadGate(),extraGate=receivingReadGate(),collectorGate=receivingReadGate();let timer,applied
const drawer=ref(false),selected=ref(null),detail=ref(null),trace=ref(null),detailError=ref(''),detailLoading=ref(false)
const extras=ref(false),extraLoading=ref(false),extraError=ref(''),collector=ref(null),subscriptions=ref([]),subTotal=ref(0),keyword=ref(''),grant=ref('')
const why=r=>reasons[r.reason_code]||transferReasons[r.reason_code]||'未知技术事件'
function clear(){rows.value=[];total.value=0;summary.value=null;configured.value=null}
function query(resetLive=false){try{live.value=resetLive||live.value&&form.range?.length===2&&new Date(form.range[0]).toISOString()===applied?.from&&new Date(form.range[1]).toISOString()===applied?.until;applied=transferQuery(form);page.value=1;load()}catch(e){gate.invalidate();clear();loading.value=false;error.value=e.message}}
async function load(background=false){
 if(explicitDemo||!session.user||background&&(loading.value||document.hidden))return
 if(live.value){form.range=monitorRange();applied={...applied,from:form.range[0].toISOString(),until:form.range[1].toISOString()}}
 const token=gate.next(),epoch=session.epoch,params={...applied,limit:size.value,offset:(page.value-1)*size.value},current=()=>gate.current(token)&&epoch===session.epoch
 loading.value=true;error.value='';if(!background)clear()
 try{const data=transferMonitorPage(await telemetryApi.transfers(params),params);if(current()){rows.value=data.items;total.value=data.total;summary.value=data.summary;configured.value=data.receiver_configured}}
 catch(e){if(current()){clear();auto.value=false;error.value=e.response?.data?.message||e.message||'传输记录读取失败'}}finally{if(current())loading.value=false}
}
function reset(){Object.assign(form,{range:monitorRange(),method:'',stage:'',outcome:'',resourceId:'',taskId:'',traceId:''});query(true)}
async function show(row,withTrace=false){
 const token=detailGate.next(),epoch=session.epoch;drawer.value=true;selected.value=row;detail.value=null;trace.value=null;detailError.value='';detailLoading.value=true
 const current=()=>detailGate.current(token)&&epoch===session.epoch
 try{if(row.origin==='RUNTIME'){const value=withTrace?traceEvents(await telemetryApi.trace(row.trace_id),row.trace_id):assertEvent(await telemetryApi.event(row.event_id),{id:row.event_id});if(current()){if(withTrace)trace.value=value;else detail.value=value}}}
 catch{if(current())detailError.value='记录详情读取失败，请重试'}finally{if(current())detailLoading.value=false}
}
async function loadCollector(){
 if(explicitDemo)return;const token=collectorGate.next(),epoch=session.epoch
 try{const data=collectorStatus(await telemetryApi.collector());if(collectorGate.current(token)&&epoch===session.epoch)collector.value=data}catch{if(collectorGate.current(token)&&epoch===session.epoch)extraError.value='采集状态读取失败，可在详情重试'}
}
async function loadExtras(more=false){
 if(explicitDemo)return
 const token=extraGate.next(),epoch=session.epoch,offset=more?subscriptions.value.length:0;extraLoading.value=true;extraError.value='';collector.value=null;if(!more){subscriptions.value=[];subTotal.value=0;grant.value=''}
 try{const values=await Promise.allSettled([telemetryApi.collector(),subscriptionApi.list({keyword:keyword.value,limit:20,offset})]);if(!extraGate.current(token)||epoch!==session.epoch)return
  if(values[0].status==='fulfilled')collector.value=collectorStatus(values[0].value);else extraError.value='采集状态读取失败，可重试'
  if(values[1].status==='fulfilled'){const data=assertSubscriptionPage(values[1].value,{limit:20,offset});subscriptions.value=more?[...subscriptions.value,...data.items]:data.items;subTotal.value=data.total}else{grant.value='';subscriptions.value=[];extraError.value='订阅列表读取失败，可重试'}
 }catch(e){if(extraGate.current(token)){collector.value=null;subscriptions.value=[];grant.value='';extraError.value=e.message}}finally{if(extraGate.current(token))extraLoading.value=false}
}
watch(drawer,open=>{if(!open){detailGate.invalidate();selected.value=null;detail.value=null;trace.value=null;detailError.value=''}})
watch(extras,open=>{if(open)loadExtras();else{extraGate.invalidate();collector.value=null;subscriptions.value=[];grant.value=''}})
watch(auto,value=>{clearInterval(timer);if(value&&!explicitDemo)timer=setInterval(()=>load(true),5000)})
watch(()=>route.query,()=>{form.method=route.query.method||'';form.stage=route.query.stage||'';form.traceId=route.query.traceId||'';advanced.value=!!form.traceId;query(true);if(route.query.summary==='1')extras.value=true},{immediate:true})
onMounted(()=>{applied||query(true);loadCollector()})
onBeforeUnmount(()=>{clearInterval(timer);gate.dispose();detailGate.dispose();extraGate.dispose();collectorGate.dispose();clear();selected.value=null;detail.value=null;trace.value=null;subscriptions.value=[];collector.value=null})
</script>
<template><div class="stack">
 <div class="page-heading"><h1>传输监测</h1><el-button :disabled="explicitDemo" @click="extras=true">采集状态与订阅摘要</el-button></div>
 <el-alert v-if="explicitDemo" title="演示模式不读取真实传输记录" type="warning" :closable="false" />
 <div class="monitor-stats"><div v-for="(label,key) in summaryLabels" :key="key" class="stat-card"><div class="stat-label">{{label}}</div><div class="stat-number" :data-testid="'transfer-stat-'+key">{{quantity(summary?.[key])}}</div></div></div>
 <el-alert v-if="extraError||collector&&(!collector.enabled||collector.dropped_events||collector.quarantined_reports||collector.spool_failures)" title="日志采集状态需关注，可打开采集状态查看" type="warning" :closable="false" />
 <div class="panel"><el-form class="filters" @submit.prevent="query()">
  <el-date-picker v-model="form.range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" aria-label="传输时间范围" />
  <el-select v-model="form.method" placeholder="全部传输方式" aria-label="传输方式" clearable><el-option v-for="(label,key) in transferMethods" :key="key" :label="label" :value="key" /></el-select>
  <el-select v-model="form.stage" placeholder="全部处理阶段" aria-label="处理阶段" clearable><el-option v-for="(label,key) in transferStages" :key="key" :label="label" :value="key" /></el-select>
  <el-select v-model="form.outcome" placeholder="全部结果" aria-label="传输结果" clearable><el-option v-for="(label,key) in {...logOutcomes,DENIED:'拒绝'}" :key="key" :label="label" :value="key" /></el-select>
  <el-input v-model="form.resourceId" placeholder="完整资源编号（可选）" aria-label="传输资源编号" clearable />
  <el-button type="primary" native-type="submit" :disabled="explicitDemo">查询</el-button><el-button :disabled="explicitDemo" @click="reset">重置</el-button><el-button :disabled="explicitDemo" :loading="loading" @click="load()">刷新</el-button><el-switch v-model="auto" :disabled="explicitDemo" active-text="自动刷新" /><el-button link @click="advanced=!advanced">更多筛选</el-button>
  <template v-if="advanced"><el-input v-model="form.taskId" placeholder="完整接收任务编号（可选）" aria-label="接收任务编号" clearable /><el-input v-model="form.traceId" placeholder="追踪编号（可选）" aria-label="传输追踪编号" clearable /></template>
 </el-form>
 <div class="summary-note">统计按所选时间与资源汇总，其他筛选仅影响列表。数量来自已上报记录，未上报显示“—”。</div>
 <el-alert v-if="configured===false" title="接收监测未接入，接收情况未知" type="warning" :closable="false" />
 <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="transfer-monitor-error"><el-button link @click="load()">重试</el-button></el-alert>
 <template v-else><el-table :data="rows" v-loading="loading" row-key="entry_id" empty-text="所选范围暂无已上报传输记录" data-testid="transfer-monitor-table">
  <el-table-column label="时间" min-width="170"><template #default="{row}">{{displayTime(row.occurred_at)}}</template></el-table-column>
  <el-table-column label="数据资源" min-width="180" show-overflow-tooltip><template #default="{row}">{{row.resource_name||row.resource_id||'未关联资源'}}</template></el-table-column>
  <el-table-column label="传输方式" width="125"><template #default="{row}">{{transferMethods[row.method]}}</template></el-table-column>
  <el-table-column label="处理阶段" width="125"><template #default="{row}">{{transferStages[row.stage]}}</template></el-table-column>
  <el-table-column label="结果" width="90"><template #default="{row}"><el-tag :type="row.outcome==='DENIED'?'warning':logColor(row.outcome)">{{row.outcome==='DENIED'?'拒绝':logOutcomes[row.outcome]}}</el-tag></template></el-table-column>
  <el-table-column label="数据量（条）" width="120"><template #default="{row}">{{quantity(row.record_count)}}</template></el-table-column>
  <el-table-column label="阶段耗时（毫秒）" min-width="155"><template #default="{row}"><el-tooltip :content="durationScopes[row.duration_scope]||'该阶段未采集耗时'" placement="top"><span>{{quantity(row.duration_ms)}}</span></el-tooltip></template></el-table-column>
  <el-table-column label="操作" width="85" fixed="right"><template #default="{row}"><el-button link type="primary" @click="show(row)">详情</el-button></template></el-table-column>
 </el-table><el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50]" layout="total,sizes,prev,pager,next" :disabled="loading||explicitDemo" @change="load()" /></template>
 </div>
 <el-drawer v-model="drawer" title="传输记录详情" size="760px"><div v-if="selected" v-loading="detailLoading" class="stack">
  <el-alert v-if="detailError" :title="detailError" type="error" :closable="false"><el-button link @click="show(selected)">重试详情</el-button></el-alert>
  <el-descriptions :column="1" border><el-descriptions-item label="处理说明">{{why(selected)}}</el-descriptions-item><el-descriptions-item label="耗时范围">{{durationScopes[selected.duration_scope]||'该阶段未采集耗时'}} · {{quantity(selected.duration_ms)}} 毫秒</el-descriptions-item><el-descriptions-item label="数据来源">{{selected.origin==='RECEIVER'?'接收端自报':'提供方技术摘要'}}</el-descriptions-item>
   <el-descriptions-item v-for="(label,key) in {event_id:'事件编号',resource_id:'资源编号',collector_id:'采集节点',task_id:'接收任务编号',grant_id:'授权编号',batch_id:'关联批次',report_id:'摘要编号',topic:'消息主题',topic_partition:'消息分区',topic_offset:'消息位置',inserted_count:'新增落库条数',duplicate_count:'重读去重条数',byte_count:'载荷字节'}" :key="key" :label="label">{{selected[key]??'—'}}</el-descriptions-item><el-descriptions-item label="底座收到时间">{{displayTime(selected.received_at)}}</el-descriptions-item>
  </el-descriptions>
  <el-table v-if="selected.positions.length" :data="selected.positions"><el-table-column prop="partition" label="分区" /><el-table-column prop="first_offset" label="本批首位置" /><el-table-column prop="last_offset" label="本批末位置" /><el-table-column prop="next_offset" label="下一消费位置" /></el-table>
  <template v-if="selected.origin==='RUNTIME'"><el-button :loading="detailLoading" @click="show(selected,true)">查看已有追踪过程</el-button><RuntimeEventDetails v-if="detail" :event="detail" /><el-timeline v-if="trace"><el-timeline-item v-for="item in trace" :key="item.event_id" :timestamp="displayTime(item.completed_at)"><RuntimeEventDetails :event="item" /></el-timeline-item></el-timeline></template>
 </div></el-drawer>
 <el-drawer v-model="extras" title="采集状态与订阅摘要" size="850px"><div v-loading="extraLoading" class="stack"><el-alert v-if="extraError" :title="extraError" type="error" :closable="false"><el-button link @click="loadExtras()">重试</el-button></el-alert><el-descriptions v-if="collector" :column="2" border><el-descriptions-item label="自动采集">{{collector.enabled?'已开启':'已关闭'}}</el-descriptions-item><el-descriptions-item label="最近上报">{{displayTime(collector.last_success_at)}}</el-descriptions-item><el-descriptions-item v-for="(label,key) in {pending_reports:'待补报报告',quarantined_reports:'隔离报告',dropped_events:'丢弃事件',spool_failures:'存储失败'}" :key="key" :label="label">{{quantity(collector[key])}}</el-descriptions-item></el-descriptions>
  <el-divider>Kafka订阅摘要</el-divider><div class="filters"><el-input v-model="keyword" placeholder="授权名称或编号" aria-label="摘要授权搜索" /><el-button :disabled="explicitDemo" @click="loadExtras()">查询订阅</el-button><el-select v-model="grant" placeholder="选择订阅授权" aria-label="监测订阅授权" clearable><el-option v-for="s in subscriptions" :key="s.grant_id" :value="s.grant_id" :label="`${s.grant_name} · ${s.topic}`" /></el-select><el-button v-if="subscriptions.length<subTotal" @click="loadExtras(true)">加载更多</el-button></div><KafkaConsumptionPanel v-if="grant" :key="grant" :grant-id="grant" />
 </div></el-drawer>
</div></template>
<style scoped>.monitor-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px}.filters{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.filters :deep(.el-select){width:165px}.filters :deep(.el-input){width:240px}.summary-note{color:#687c8e;font-size:13px;margin:18px 0}.el-pagination{justify-content:flex-end;margin-top:18px}:deep(.el-descriptions__content){overflow-wrap:anywhere}@media(max-width:1000px){.monitor-stats{grid-template-columns:repeat(2,minmax(0,1fr))}}</style>
