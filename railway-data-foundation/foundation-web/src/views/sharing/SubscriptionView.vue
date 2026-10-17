<script setup>
import KafkaConsumptionPanel from '../../components/KafkaConsumptionPanel.vue'
import ProviderReviewTag from '../../components/ProviderReviewTag.vue'
import { session } from '../../utils/sessionState'
import { allowedActions } from '../../utils/workspace'
const isDemand=()=>session.user?.role==='DEMAND'
const canApply=()=>session.user?.role!=='PROVIDER'
import {computed,onBeforeUnmount,reactive,ref,toRaw,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {useRoute,useRouter} from 'vue-router'
import {offerReceivingDraft} from '../../utils/receiving'
import {subscriptionApi} from '../../api/subscription'
import {subjectApi} from '../../api/subject'
import {resourceApi} from '../../api/resource'
import {capabilityApi} from '../../api/capability'
import {fetchDashboardList} from '../../utils/dashboard'
import {displayTime} from '../../utils/time'
import {assertSubscription,assertSubscriptionAction,assertSubscriptionPage,assertSubscriptionDelivery,subscriptionExecutionLabels,subscriptionExecutionActions,subscriptionExecutionActionsFor,subscriptionActions,subscriptionActionsFor,subscriptionPayload,subscriptionReadGate,subscriptionStatuses} from '../../utils/subscription'
import {subscriptionProviders,subscriptionDemands,subscriptionResources,subscriptionCapabilities,subscriptionSelectionError,subscriptionEntry} from '../../utils/subscriptionCatalog'

const roleActions=row=>allowedActions(session.user?.role,subscriptionActionsFor(row))
const roleExecutions=row=>allowedActions(session.user?.role,subscriptionExecutionActionsFor(row),'execution')
const pageTitle=computed(()=>({ADMIN:'Kafka 订阅授权',PROVIDER:'Kafka 订阅记录',DEMAND:'我的 Kafka 订阅'})[session.user?.role]||'Kafka 订阅')
const mock=import.meta.env.VITE_USE_MOCK==='true'
const route=useRoute()
const router=useRouter()
function configureReceiving(){if(!isDemand()||mock||!delivery.value)return;offerReceivingDraft(delivery.value,session.epoch);const grant=delivery.value.grant.grant_id;deliveryVisible.value=false;delivery.value=null;router.push({path:'/receiving',query:{grant}})}
const rows=ref([]),total=ref(0),page=ref(1),size=ref(20),keyword=ref(''),status=ref(''),loading=ref(false),error=ref(''),busy=ref(false)
const subjects=ref([]),resources=ref([]),capabilities=ref([]),referenceError=ref(''),referenceLoading=ref(false)
const visible=ref(false),editing=ref(null),form=reactive({}),formError=ref(''),conflict=ref(false)
const actionVisible=ref(false),actionRow=ref(null),actionName=ref(''),reason=ref(''),acknowledged=ref(false),actionError=ref('')
const drawer=ref(false),detail=ref(null),events=ref([]),detailLoading=ref(false),detailError=ref(''),detailId=ref('')
const delivery=ref(null),deliveryVisible=ref(false)
const entryError=ref('')
const actionLabels={...subscriptionActions,...subscriptionExecutionActions}
const listGate=subscriptionReadGate(),referenceGate=subscriptionReadGate(),detailGate=subscriptionReadGate(),writeGate=subscriptionReadGate(),entryGate=subscriptionReadGate()
const catalog=()=>({subjects:subjects.value,resources:resources.value,capabilities:capabilities.value})
const providers=computed(()=>subscriptionProviders(subjects.value))
const demands=computed(()=>subscriptionDemands(subjects.value,session.user))
const published=computed(()=>subscriptionResources(resources.value,form.provider_subject_id,session.user?.role))
const available=computed(()=>published.value.some(row=>row.resource_id===form.resource_id)?subscriptionCapabilities(capabilities.value,form.resource_id):[])
const chosenTopic=computed(()=>capabilities.value.find(row=>row.capability_id===form.capability_id)?.topic||'—')
const safe=e=>e?.response?.data?.message||(!e?.response&&['ECONNABORTED','ERR_NETWORK'].includes(e?.code)?'结果未确认，请刷新检查，不要重复提交':e?.message||'请求失败，请重试')
async function load(){
  if(mock)return
  const token=listGate.begin();loading.value=true;error.value='';rows.value=[]
  const params={limit:size.value,offset:(page.value-1)*size.value,keyword:keyword.value.trim(),status:status.value}
  try{const data=assertSubscriptionPage(await subscriptionApi.list(params),params);if(listGate.current(token)){rows.value=data.items;total.value=data.total}}
  catch(e){if(listGate.current(token)){error.value=safe(e);total.value=0}}finally{if(listGate.current(token))loading.value=false}
}
async function references(){
  if(mock)return
  const token=referenceGate.begin();referenceLoading.value=true;referenceError.value='';subjects.value=[];resources.value=[];capabilities.value=[]
  try{
    const result=await Promise.all([[subjectApi.list,'subject_id'],[resourceApi.list,'resource_id'],[capabilityApi.list,'capability_id']].map(([fetch,id])=>fetchDashboardList(fetch,id)))
    if(result.some(data=>!data.complete||data.rows.some(row=>row._demo)))throw new Error('登记选项未完整读取，请整理目录后重试')
    if(referenceGate.current(token))[subjects.value,resources.value,capabilities.value]=result.map(data=>data.rows)
  }catch(e){if(referenceGate.current(token))referenceError.value=safe(e)}finally{if(referenceGate.current(token))referenceLoading.value=false}
}
function search(){if(page.value===1)load();else page.value=1}
watch(page,load);watch(size,search);watch(status,search)
async function refresh(){await Promise.all([load(),references()])}
function open(row=null){
  if(!canApply())return
  if(mock||busy.value||referenceLoading.value||referenceError.value)return
  editing.value=row?structuredClone(toRaw(row)):null;formError.value='';entryError.value='';conflict.value=false
  Object.assign(form,{grant_name:'',provider_subject_id:'',demand_subject_id:isDemand()?session.user.subject_id:'',resource_id:'',capability_id:'',purpose:'',reason:'',whole_topic_acknowledged:false,version:undefined,valid_range:[new Date(Date.now()-60000),new Date(Date.now()+30*86400000)]},row?{...row,reason:'',valid_range:[new Date(row.valid_from),new Date(row.valid_until)]}:{})
  visible.value=true
}
function providerChanged(){form.resource_id='';form.capability_id='';form.whole_topic_acknowledged=false;formError.value=''}
function resourceChanged(id){
  form.capability_id='';form.whole_topic_acknowledged=false;formError.value=''
  if(isDemand())form.provider_subject_id=resources.value.find(row=>row.resource_id===id)?.provider_subject_id||''
}
function capabilityChanged(){form.whole_topic_acknowledged=false;formError.value=''}
async function save(){
  if(mock||busy.value||conflict.value)return
  const current=editing.value,token=writeGate.begin();let body
  try{
    body=subscriptionPayload(form,!!current)
    const invalid=subscriptionSelectionError(form,catalog(),session.user)
    if(invalid)throw new Error(invalid)
  }catch(e){formError.value=e.message;return}
  busy.value=true;formError.value=''
  try{
    const result=current?await subscriptionApi.update(current.grant_id,body):await subscriptionApi.create(body)
    assertSubscription(result,{...(current?{grant_id:current.grant_id}:{}),status:'DRAFT',version:current?current.version+1:1,provider_subject_id:form.provider_subject_id,demand_subject_id:form.demand_subject_id,resource_id:form.resource_id,capability_id:form.capability_id,topic:chosenTopic.value})
    if(writeGate.current(token)){visible.value=false;ElMessage.success('订阅申请已保存');await load()}
  }catch(e){if(writeGate.current(token)){formError.value=safe(e);conflict.value=e?.response?.status===409}}finally{if(writeGate.current(token))busy.value=false}
}
function openAction(row,action){if(mock||busy.value||![...roleActions(row),...roleExecutions(row)].includes(action))return;delivery.value=null;deliveryVisible.value=false;actionRow.value=structuredClone(toRaw(row));actionName.value=action;reason.value='';acknowledged.value=false;actionError.value='';conflict.value=false;actionVisible.value=true}
async function act(){
  if(mock||busy.value||conflict.value)return
  if(!reason.value.trim()||reason.value.trim().length>1000){actionError.value='请填写操作原因，最多1000字';return}
  if(['confirm','provider-confirm'].includes(actionName.value)&&!acknowledged.value){actionError.value='请明确核对并确认完整消息及历史范围';return}
  const current=actionRow.value,action=actionName.value,token=writeGate.begin();busy.value=true;actionError.value=''
  try{
    if(action==='credential') {
      const response=assertSubscriptionDelivery(await subscriptionApi.claim(current.grant_id,{version:current.version,reason:reason.value.trim()}),current)
      if(writeGate.current(token)){delivery.value=response;deliveryVisible.value=true;actionVisible.value=false;closeDetail();await load()}
    }else if(subscriptionExecutionActions[action]) {
      const result=assertSubscription(await subscriptionApi.execute(current.grant_id,action,{version:current.version,reason:reason.value.trim()}),{grant_id:current.grant_id})
      if(result.version<=current.version||result.topic!==current.topic||result.consumer_group!==current.consumer_group)throw new Error('执行响应与当前授权不一致，请刷新确认')
      if(writeGate.current(token)){actionVisible.value=false;closeDetail();if(result.execution_state==='FAILED')ElMessage.error(result.execution_reason||'权限状态未确认，请重试撤权');else ElMessage.success(subscriptionExecutionLabels[result.execution_state]);await load()}
    }else {
      const body={version:current.version,reason:reason.value.trim(),...(action.startsWith('provider-')?{content_acknowledged:acknowledged.value}:{whole_topic_acknowledged:acknowledged.value})}
      assertSubscriptionAction(await subscriptionApi.action(current.grant_id,action,body),current,action)
      if(writeGate.current(token)){actionVisible.value=false;closeDetail();ElMessage.success('订阅审批状态已更新');await load()}
    }
  }catch(e){if(writeGate.current(token)){actionError.value=safe(e);conflict.value=e?.response?.status===409}}finally{if(writeGate.current(token))busy.value=false}
}
function closeDetail(){drawer.value=false;detailGate.invalidate();detail.value=null;events.value=[];detailLoading.value=false}
async function showDetail(id){
  if(mock)return
  const token=detailGate.begin();detailId.value=id;drawer.value=true;detail.value=null;events.value=[];detailError.value='';detailLoading.value=true
  try{
    const [row,history]=await Promise.all([subscriptionApi.get(id),subscriptionApi.events(id)]);assertSubscription(row,{grant_id:id})
    if(!Array.isArray(history)||history.length!==row.version||history.some((event,index)=>event._demo||event.grant_id!==id||event.version!==index+1)||history.at(-1)?.to_status!==row.status)throw new Error('审批历史已变化，请重新读取')
    if(detailGate.current(token)&&drawer.value){detail.value=row;events.value=history}
  }catch(e){if(detailGate.current(token))detailError.value=safe(e)}finally{if(detailGate.current(token))detailLoading.value=false}
}
async function initializeEntry(){
  const token=entryGate.begin(),query={resource:route.query.resource,capability:route.query.capability}
  writeGate.invalidate();busy.value=false;visible.value=false;editing.value=null;actionVisible.value=false
  closeDetail();deliveryVisible.value=false;delivery.value=null;entryError.value=''
  keyword.value=typeof route.query.keyword==='string'?route.query.keyword:''
  await refresh()
  if(!entryGate.current(token)||mock||!isDemand()||query.resource===undefined&&query.capability===undefined)return
  if(referenceError.value)return
  try{const selection=subscriptionEntry(query,catalog(),session.user);open();if(visible.value)Object.assign(form,selection)}
  catch(e){entryError.value=e.message}
}
watch(()=>[route.query.resource,route.query.capability],initializeEntry,{immediate:true})
onBeforeUnmount(()=>{delivery.value=null;[listGate,referenceGate,detailGate,writeGate,entryGate].forEach(gate=>gate.dispose())})
</script>
<template>
  <div class="stack">
    <div class="page-heading"><h1>{{pageTitle}}</h1><div><el-button :disabled="busy||mock" @click="refresh">刷新</el-button><el-button v-if="canApply()" type="primary" :disabled="busy||mock||referenceLoading||!!referenceError||loading||!!error" @click="open()">新增订阅申请</el-button></div></div>
    <el-alert v-if="mock" title="订阅审批仅支持真实接口，请关闭演示模式后使用。" type="warning" :closable="false" />
    <el-alert v-if="referenceError" :title="referenceError" type="error" :closable="false" data-testid="subscription-reference-error"><el-button :disabled="busy||referenceLoading" @click="initializeEntry">重试登记选项</el-button></el-alert>
    <el-alert v-if="entryError&&!referenceError" :title="entryError" type="error" :closable="false" data-testid="subscription-entry-error"><el-button :disabled="busy||referenceLoading" @click="initializeEntry">重试申请入口</el-button></el-alert>
    <div class="panel">
      <div class="filters"><el-input v-model="keyword" aria-label="搜索订阅授权" placeholder="搜索授权、主体、资源或主题" clearable @keyup.enter="search" /><el-select v-model="status" aria-label="审批状态" placeholder="全部审批状态" clearable><el-option v-for="(label,value) in subscriptionStatuses" :key="value" :label="label" :value="value" /></el-select><el-button :disabled="busy||mock" @click="search">查询</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="subscription-error"><el-button @click="load">重试</el-button></el-alert>
      <el-alert v-if="referenceError" :title="'申请选项读取失败：'+referenceError" type="error" :closable="false"><el-button @click="references">重试选项</el-button></el-alert>
      <el-table :data="rows" v-loading="loading" data-testid="subscription-table" empty-text="暂无订阅申请">
        <el-table-column prop="grant_name" label="授权名称" min-width="170" show-overflow-tooltip /><el-table-column prop="provider_name" label="提供方" min-width="120" show-overflow-tooltip /><el-table-column prop="demand_name" label="需求方" min-width="120" show-overflow-tooltip /><el-table-column prop="topic" label="消息主题" min-width="180" show-overflow-tooltip />
        <el-table-column label="共享范围" width="100"><template #default>整主题</template></el-table-column><el-table-column label="审批状态" width="125"><template #default="{row}"><el-tag :type="row.status==='APPROVED'?'success':row.status==='REVOKED'?'info':'warning'">{{subscriptionStatuses[row.status]}}</el-tag></template></el-table-column>
        <el-table-column label="提供方确认" width="140"><template #default="{row}"><ProviderReviewTag :row="row" /></template></el-table-column>
        <el-table-column label="权限执行" width="135"><template #default="{row}"><el-tag :type="row.execution_state==='FAILED'?'danger':row.broker_access_confirmed?'success':'info'">{{row.execution_state==='ACTIVE'&&!row.broker_access_confirmed?'配置待核对':subscriptionExecutionLabels[row.execution_state]}}</el-tag></template></el-table-column><el-table-column prop="version" label="版本" width="65" />
        <el-table-column label="操作" fixed="right" min-width="250"><template #default="{row}"><el-button link type="primary" :disabled="busy" @click="showDetail(row.grant_id)">查看</el-button><el-button v-if="canApply() && row.status==='DRAFT'" link type="primary" :disabled="busy||!!referenceError" @click="open(row)">编辑</el-button><el-dropdown v-if="roleActions(row).length" :disabled="busy" @command="action=>openAction(row,action)"><el-button link type="primary">{{session.user?.role==='PROVIDER'?'内容确认':'审批操作'}}</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in roleActions(row)" :key="action" :command="action">{{subscriptionActions[action]}}</el-dropdown-item></el-dropdown-menu></template></el-dropdown><el-dropdown v-if="roleExecutions(row).length" :disabled="busy" @command="action=>openAction(row,action)"><el-button link type="primary">订阅操作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in roleExecutions(row)" :key="action" :command="action">{{subscriptionExecutionActions[action]}}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
        <el-table-column v-if="isDemand()" label="数据接收" width="150"><template #default="{row}"><el-button link type="primary" :disabled="mock||busy" @click="router.push({path:'/receiving',query:{grant:row.grant_id}})">数据接收</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" :disabled="busy||mock" />
    </div>
    <el-dialog :model-value="visible" :title="editing?'编辑订阅申请':'新增订阅申请'" width="800px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="value=>{if(!busy)visible=value}">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="授权名称" required><el-input v-model="form.grant_name" aria-label="授权名称" maxlength="120" /></el-form-item>
        <div class="form-grid"><el-form-item label="提供方" required><el-select v-model="form.provider_subject_id" aria-label="提供方" :disabled="!!editing" filterable @change="providerChanged"><el-option v-for="row in providers" :key="row.subject_id" :label="row.subject_name" :value="row.subject_id" /></el-select></el-form-item><el-form-item label="需求方" required><el-select v-model="form.demand_subject_id" aria-label="需求方" :disabled="!!editing||isDemand()" filterable><el-option v-for="row in demands" :key="row.subject_id" :label="row.subject_name" :value="row.subject_id" /></el-select></el-form-item>
        <el-form-item label="数据资源" required><el-select v-model="form.resource_id" aria-label="数据资源" :disabled="!!editing||!form.provider_subject_id" filterable @change="resourceChanged"><el-option v-for="row in published" :key="row.resource_id" :label="row.resource_name+(row.provider_subject_id?'':'（未归属）')" :value="row.resource_id" /></el-select></el-form-item><el-form-item label="Kafka 能力" required><el-select v-model="form.capability_id" aria-label="Kafka 能力" :disabled="!!editing||!form.resource_id" filterable @change="capabilityChanged"><el-option v-for="row in available" :key="row.capability_id" :label="row.capability_name" :value="row.capability_id" /></el-select></el-form-item></div>
        <el-descriptions :column="2" border><el-descriptions-item label="精确主题">{{chosenTopic}}</el-descriptions-item><el-descriptions-item label="共享范围">完整消息及可访问历史</el-descriptions-item></el-descriptions>
        <el-form-item label="计划授权有效期" required><el-date-picker v-model="form.valid_range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item>
        <el-form-item label="使用目的" required><el-input v-model="form.purpose" aria-label="使用目的" type="textarea" maxlength="1000" /></el-form-item>
        <el-form-item label="操作原因" required><el-input v-model="form.reason" aria-label="操作原因" maxlength="1000" /></el-form-item>
        <el-checkbox v-if="!editing" v-model="form.whole_topic_acknowledged">我理解并接受整主题的完整消息及历史，不继承 REST 字段或数据时间限制</el-checkbox>
      </el-form>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" data-testid="subscription-form-error" />
      <template #footer><el-button :disabled="busy" @click="visible=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="conflict" @click="save">保存申请</el-button></template>
    </el-dialog>
    <el-dialog :model-value="actionVisible" :title="actionLabels[actionName]" width="620px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="value=>{if(!busy)actionVisible=value}">
      <el-descriptions :column="1" border><el-descriptions-item label="授权名称">{{actionRow?.grant_name}}</el-descriptions-item><el-descriptions-item label="消息主题">{{actionRow?.topic}}</el-descriptions-item><el-descriptions-item label="共享范围">完整消息及可访问历史</el-descriptions-item></el-descriptions>
      <el-form-item label="操作原因"><el-input v-model="reason" aria-label="审批操作原因" type="textarea" maxlength="1000" /></el-form-item>
      <template v-if="actionName.startsWith('provider-')"><el-descriptions :column="1" border><el-descriptions-item label="需求方">{{actionRow?.demand_name}}</el-descriptions-item><el-descriptions-item label="数据资源">{{actionRow?.resource_name}}</el-descriptions-item><el-descriptions-item label="接口能力">{{actionRow?.capability_name}}</el-descriptions-item><el-descriptions-item label="使用目的">{{actionRow?.purpose}}</el-descriptions-item><el-descriptions-item label="授权有效期">{{displayTime(actionRow?.valid_from)}} 至 {{displayTime(actionRow?.valid_until)}}</el-descriptions-item></el-descriptions></template>
      <el-checkbox v-if="['confirm','provider-confirm'].includes(actionName)" v-model="acknowledged" aria-label="确认整主题共享内容">{{actionName==='provider-confirm'?'我已核对用途、有效期和整主题完整消息及历史范围':'明确同意完整消息及历史范围，审批通过不表示 Broker 权限已生效'}}</el-checkbox>
      <el-alert v-if="subscriptionExecutionActions[actionName]" :title="actionName==='credential'?'密码仅本次显示，不会再次返回。响应丢失时请停止订阅后重新配置身份。':'权限操作需消息服务确认；失败时状态未知。自动撤权有执行延迟，控制服务停机期间静态权限仍可能保留。'" type="warning" :closable="false" />
      <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" data-testid="subscription-action-error" />
      <template #footer><el-button :disabled="busy" @click="actionVisible=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="conflict" @click="act">确认操作</el-button></template>
    </el-dialog>
    <el-dialog v-model="deliveryVisible" title="一次性订阅凭据" width="680px" :close-on-click-modal="false" @closed="delivery=null">
      <template v-if="delivery"><el-alert title="密码和摘要令牌仅本次显示，关闭后清除；两者用途不同，请分别妥善保存。" type="warning" :closable="false" /><el-descriptions :column="1" border><el-descriptions-item label="连接地址">{{delivery.bootstrap_servers}}</el-descriptions-item><el-descriptions-item label="安全协议">{{delivery.security_protocol}}</el-descriptions-item><el-descriptions-item label="认证机制">{{delivery.sasl_mechanism}}</el-descriptions-item><el-descriptions-item label="用户名">{{delivery.username}}</el-descriptions-item><el-descriptions-item label="密码"><span data-testid="subscription-secret">{{delivery.password}}</span></el-descriptions-item><el-descriptions-item label="消费摘要上报令牌"><span data-testid="consumption-secret">{{delivery.consumption_token}}</span></el-descriptions-item><el-descriptions-item label="消息主题">{{delivery.topic}}</el-descriptions-item><el-descriptions-item label="消费组">{{delivery.consumer_group}}</el-descriptions-item></el-descriptions></template><template #footer><el-button v-if="isDemand()" type="primary" @click="configureReceiving">配置接收任务</el-button><el-button @click="deliveryVisible=false;delivery=null">我已记录，关闭</el-button></template>
    </el-dialog>
    <el-drawer :model-value="drawer" title="订阅授权详情" size="760px" @update:model-value="value=>{if(!value)closeDetail()}">
      <div v-loading="detailLoading" class="stack"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false"><el-button @click="showDetail(detailId)">重试详情</el-button></el-alert>
        <template v-if="detail"><el-descriptions :column="2" border><el-descriptions-item label="授权名称">{{detail.grant_name}}</el-descriptions-item><el-descriptions-item label="审批状态">{{subscriptionStatuses[detail.status]}}</el-descriptions-item><el-descriptions-item label="授权编号" :span="2">{{detail.grant_id}}</el-descriptions-item><el-descriptions-item label="提供方">{{detail.provider_name}}</el-descriptions-item><el-descriptions-item label="需求方">{{detail.demand_name}}</el-descriptions-item><el-descriptions-item label="数据资源">{{detail.resource_name}}</el-descriptions-item><el-descriptions-item label="接口能力">{{detail.capability_name}}</el-descriptions-item><el-descriptions-item label="消息主题" :span="2">{{detail.topic}}</el-descriptions-item><el-descriptions-item label="预留消费组" :span="2">{{detail.consumer_group}}</el-descriptions-item><el-descriptions-item label="共享范围" :span="2">整主题：完整消息及可访问历史</el-descriptions-item><el-descriptions-item label="计划开始">{{displayTime(detail.valid_from)}}</el-descriptions-item><el-descriptions-item label="计划结束">{{displayTime(detail.valid_until)}}</el-descriptions-item><el-descriptions-item label="权限执行">{{subscriptionExecutionLabels[detail.execution_state]}}</el-descriptions-item><el-descriptions-item label="订阅用户名">{{detail.broker_username||'—'}}</el-descriptions-item><el-descriptions-item label="最近核对">{{displayTime(detail.checked_at)}}</el-descriptions-item><el-descriptions-item label="版本">{{detail.version}}</el-descriptions-item><el-descriptions-item label="使用目的" :span="2">{{detail.purpose}}</el-descriptions-item></el-descriptions>
        <el-alert v-if="detail.execution_reason" :title="detail.execution_reason" type="error" :closable="false" />
        <el-descriptions :column="2" border><el-descriptions-item label="提供方确认"><ProviderReviewTag :row="detail" /></el-descriptions-item><el-descriptions-item label="提供方确认时间">{{displayTime(detail.provider_confirmed_at)}}</el-descriptions-item></el-descriptions><KafkaConsumptionPanel :key="detail.grant_id" :grant-id="detail.grant_id" /><h3>操作历史</h3><el-table :data="events"><el-table-column label="操作" width="135"><template #default="{row}">{{({CREATE:'登记',UPDATE:'编辑',SUBMIT:'提交确认',CONFIRM:'确认整主题',RETURN:'退回',REVOKE:'撤回申请',ACCESS_REQUEST:'申请配置',ACCESS_GRANTED:'权限已配置',STOP_REQUEST:'申请停止',ACCESS_REVOKED:'已核对撤权',EXECUTION_FAILED:'执行未确认',RETRY_REQUEST:'重试执行',CREDENTIAL_ISSUED:'领取凭据',PROVIDER_CONFIRM:'提供方确认',PROVIDER_RETURN:'提供方退回',RESOURCE_UNPUBLISH:'资源下架停止共享',RESOURCE_RETIRE:'资源退役终止共享'})[row.action]||row.action}}</template></el-table-column><el-table-column label="审批状态" width="125"><template #default="{row}">{{subscriptionStatuses[row.to_status]}}</template></el-table-column><el-table-column label="权限执行" width="125"><template #default="{row}">{{subscriptionExecutionLabels[row.execution_state]||'未配置权限'}}</template></el-table-column><el-table-column prop="operator_name" label="操作者" width="120" /><el-table-column prop="reason" label="操作原因" min-width="150" show-overflow-tooltip /><el-table-column label="时间" min-width="160"><template #default="{row}">{{displayTime(row.occurred_at)}}</template></el-table-column><el-table-column prop="version" label="版本" width="60" /></el-table></template>
      </div>
      <template #footer><el-button @click="closeDetail">关闭详情</el-button></template>
    </el-drawer>
  </div>
</template>
<style scoped>
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.el-select{width:100%}.el-descriptions{margin-bottom:18px}.el-checkbox{white-space:normal;height:auto;line-height:1.6}:deep(.el-checkbox__label){white-space:normal}
.filters{display:flex;gap:12px;align-items:center;margin-bottom:18px}.filters .el-input{max-width:420px}.filters .el-select{width:180px}:deep(.el-descriptions__label){min-width:100px;width:100px}:deep(.el-descriptions__content){overflow-wrap:anywhere}
</style>
