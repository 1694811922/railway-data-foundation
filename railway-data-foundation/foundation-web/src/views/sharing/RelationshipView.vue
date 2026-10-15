<script setup>
import { session } from '../../utils/sessionState'
import { allowedActions } from '../../utils/workspace'
const isDemand=()=>session.user?.role==='DEMAND'
const canApply=()=>session.user?.role!=='PROVIDER'
import {computed,onBeforeUnmount,onMounted,reactive,ref,toRaw,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {sharingApi} from '../../api/sharing'
import {subjectApi} from '../../api/subject'
import {resourceApi} from '../../api/resource'
import {capabilityApi} from '../../api/capability'
import {fetchDashboardList} from '../../utils/dashboard'
import {assertSubject} from '../../utils/subject'
import {actionsFor,assertActionResult,assertRelationship,filterRelationships,relationshipActions,relationshipStatuses,sharingPayload,timeFields,validity} from '../../utils/sharing'
import {displayTime} from '../../utils/time'
import PolicyDetails from '../../components/PolicyDetails.vue'
import ProviderReviewTag from '../../components/ProviderReviewTag.vue'
const roleActions=row=>allowedActions(session.user?.role,actionsFor(row))
const mock=import.meta.env.VITE_USE_MOCK==='true'
const rows=ref([]),subjects=ref([]),resources=ref([]),capabilities=ref([]),fields=ref([])
const loading=ref(false),busy=ref(false),error=ref(''),referenceError=ref(''),notice=ref(''),fieldError=ref(''),fieldLoading=ref(false)
const filters=reactive({keyword:'',status:''}),page=ref(1),size=ref(10)
const filtered=computed(()=>filterRelationships(rows.value,filters)),paged=computed(()=>filtered.value.slice((page.value-1)*size.value,page.value*size.value))
watch(()=>[filters.keyword,filters.status,size.value],()=>{page.value=1})
watch(()=>filtered.value.length,()=>{page.value=Math.min(page.value,Math.max(1,Math.ceil(filtered.value.length/size.value)))})
const providers=computed(()=>subjects.value.filter(row=>row.subject_type==='PROVIDER'&&row.status==='ENABLED'))
const demands=computed(()=>subjects.value.filter(row=>row.subject_type==='DEMAND'&&row.status==='ENABLED'))
const availableResources=computed(()=>resources.value.filter(row=>row.status==='PUBLISHED'))
const availableCapabilities=computed(()=>capabilities.value.filter(row=>row.resource_id===form.resource_id&&row.status!=='RETIRED'))
const dates=computed(()=>timeFields(fields.value))
const visible=ref(false),editing=ref(null),form=reactive({}),formError=ref('')
const actionDialog=ref(false),actionRow=ref(null),actionName=ref(''),actionReason=ref(''),actionError=ref(''),contentAcknowledged=ref(false),actionConflict=ref(false)
const drawer=ref(false),detail=ref(null),history=ref([]),detailError=ref(''),detailLoading=ref(false)
let active=true,readEpoch=0,fieldEpoch=0,detailEpoch=0,writeEpoch=0
const safe=e=>e?.response?.data?.message||(!e?.response&&['ECONNABORTED','ERR_NETWORK'].includes(e?.code)?'操作结果未确认，请刷新后检查，不要重复提交':e?.message||'操作失败，请刷新检查')
async function load(){
  if(mock)return
  const epoch=++readEpoch;loading.value=true;error.value='';referenceError.value='';rows.value=[];subjects.value=[];resources.value=[];capabilities.value=[];notice.value=''
  const calls=[['relationships',sharingApi.list,'relationship_id'],['subjects',subjectApi.list,'subject_id'],['resources',resourceApi.list,'resource_id'],['capabilities',capabilityApi.list,'capability_id']]
  const result=await Promise.allSettled(calls.map(async([key,fetch,id])=>{
    const data=await fetchDashboardList(fetch,id)
    if(key==='relationships')data.rows.forEach(row=>assertRelationship(row))
    else if(key==='subjects')data.rows.forEach(row=>assertSubject(row,false))
    else if(data.rows.some(row=>row._demo))throw new Error('真实列表混入演示数据')
    return data
  }))
  if(!active||epoch!==readEpoch)return
  for(let i=0;i<result.length;i++){
    if(result[i].status==='rejected'){if(i===0)error.value='共享关系读取失败，请确认新版后端已启动后重试';else referenceError.value='主体、资源或能力选项读取失败，请刷新后操作';continue}
    const data=result[i].value
    ;[rows,subjects,resources,capabilities][i].value=data.rows
    if(!data.complete)notice.value='部分列表读取范围有限，请先检索确认目标记录；'+data.notice
  }
  loading.value=false
}
async function loadFields(id){
  const epoch=++fieldEpoch;fields.value=[];fieldError.value='';fieldLoading.value=true
  try{
    const data=await resourceApi.fields(id)
    if(!Array.isArray(data)||data.some(row=>row._demo||!row.field_name)||new Set(data.map(row=>row.field_name)).size!==data.length)throw new Error('字段响应格式不正确')
    if(active&&epoch===fieldEpoch&&visible.value&&form.resource_id===id)fields.value=data
  }catch(e){if(active&&epoch===fieldEpoch)fieldError.value='资源字段读取失败，请重试：'+safe(e)}
  finally{if(active&&epoch===fieldEpoch)fieldLoading.value=false}
}
function open(row=null){
  if(!canApply())return
  if(busy.value||loading.value||error.value||referenceError.value||mock)return
  const current=row?structuredClone(toRaw(row)):null
  editing.value=current;formError.value='';fieldError.value='';fields.value=[]
  const now=Date.now()
  Object.assign(form,{relationship_name:'',provider_subject_id:'',demand_subject_id:isDemand()?session.user.subject_id:'',resource_id:'',capability_id:'',purpose:'',allowed_fields:[],time_field:'',reason:'',version:undefined,valid_range:[new Date(now-60000),new Date(now+30*86400000)],data_range:[new Date(now-30*86400000),new Date(now)]},current?{
    relationship_name:current.relationship_name,provider_subject_id:current.provider_subject_id,demand_subject_id:current.demand_subject_id,resource_id:current.resource_id,capability_id:current.capability_id,purpose:current.purpose,allowed_fields:[...current.policy.allowed_fields],time_field:current.policy.time_field,version:current.version,valid_range:[new Date(current.valid_from),new Date(current.valid_until)],data_range:[new Date(current.policy.data_from),new Date(current.policy.data_until)]}: {})
  visible.value=true;if(form.resource_id)loadFields(form.resource_id)
}
function changedResource(id){if(isDemand())form.provider_subject_id=resources.value.find(r=>r.resource_id===id)?.provider_subject_id||'';form.capability_id='';form.allowed_fields=[];form.time_field='';if(id)loadFields(id);else{++fieldEpoch;fields.value=[];fieldLoading.value=false}}
function close(){if(!busy.value){visible.value=false;++fieldEpoch;fieldLoading.value=false}}
async function save(){
  if(busy.value||!visible.value||fieldLoading.value||fieldError.value||referenceError.value)return
  let body;const current=editing.value?structuredClone(toRaw(editing.value)):null
  try{
    body=sharingPayload(form,{editing:!!current,fields:fields.value})
    if(!providers.value.some(row=>row.subject_id===form.provider_subject_id)||!demands.value.some(row=>row.subject_id===form.demand_subject_id)||!availableResources.value.some(row=>row.resource_id===form.resource_id)||!availableCapabilities.value.some(row=>row.capability_id===form.capability_id))throw new Error('主体、资源或能力已不可用，请刷新并确认')
  }catch(e){formError.value=e.message;return}
  const epoch=++writeEpoch;busy.value=true;formError.value=''
  try{
    const result=current?await sharingApi.update(current.relationship_id,body):await sharingApi.create(body)
    assertRelationship(result,{...(current?{relationship_id:current.relationship_id}:{}),provider_subject_id:form.provider_subject_id,demand_subject_id:form.demand_subject_id,resource_id:form.resource_id,capability_id:form.capability_id,status:'DRAFT',version:current?current.version+1:1})
    if(!active||epoch!==writeEpoch)return
    visible.value=false;++fieldEpoch;ElMessage.success('共享关系与访问策略已保存');await load()
  }catch(e){if(active&&epoch===writeEpoch)formError.value=safe(e)}finally{if(active&&epoch===writeEpoch)busy.value=false}
}
function openAction(row,action){if(busy.value||loading.value||mock||!roleActions(row).includes(action))return;actionRow.value=structuredClone(toRaw(row));actionName.value=action;actionReason.value='';actionError.value='';contentAcknowledged.value=false;actionConflict.value=false;actionDialog.value=true}
async function act(){
  if(busy.value||!actionDialog.value||actionConflict.value)return
  if(actionName.value==='provider-confirm'&&!contentAcknowledged.value){actionError.value='请先核对并确认共享内容';return}
  const reason=actionReason.value.trim();if(!reason||reason.length>1000){actionError.value='请填写操作原因，最多1000字';return}
  const current=structuredClone(toRaw(actionRow.value)),action=actionName.value,epoch=++writeEpoch;busy.value=true;actionError.value=''
  try{
    const result=await sharingApi.action(current.relationship_id,action,{version:current.version,reason,...(action.startsWith('provider-')?{content_acknowledged:contentAcknowledged.value}:{})})
    assertActionResult(result,current,action)
    if(!active||epoch!==writeEpoch)return
    actionDialog.value=false;drawer.value=false;++detailEpoch;ElMessage.success('共享关系状态已更新');await load()
  }catch(e){if(active&&epoch===writeEpoch){actionError.value=safe(e);actionConflict.value=e?.response?.status===409}}finally{if(active&&epoch===writeEpoch)busy.value=false}
}
async function showDetail(row){
  const id=row.relationship_id,epoch=++detailEpoch;drawer.value=true;detail.value=null;history.value=[];detailError.value='';detailLoading.value=true
  try{
    const [current,events]=await Promise.all([sharingApi.get(id),sharingApi.events(id)]);assertRelationship(current,{relationship_id:id})
    if(!Array.isArray(events)||events.some(event=>event.relationship_id!==id||event._demo))throw new Error('状态历史归属不正确')
    if(active&&epoch===detailEpoch&&drawer.value){detail.value=current;history.value=events}
  }catch(e){if(active&&epoch===detailEpoch)detailError.value=safe(e)}finally{if(active&&epoch===detailEpoch)detailLoading.value=false}
}
onMounted(async()=>{await load();const q=new URLSearchParams(location.search);if(isDemand()&&q.get('resource')){open();form.resource_id=q.get('resource');changedResource(form.resource_id);form.capability_id=q.get('capability')||''}});onBeforeUnmount(()=>{active=false;++readEpoch;++fieldEpoch;++detailEpoch;++writeEpoch})
</script>
<template>
  <div class="stack">
    <div class="page-heading"><div><h1>共享关系管理</h1></div><div><el-button :disabled="busy||loading||mock" @click="load">刷新列表</el-button><el-button v-if="canApply()" type="primary" :disabled="busy||loading||mock||!!error||!!referenceError" @click="open()">新增共享关系</el-button></div></div>

    <el-alert v-if="mock" title="共享关系当前接入真实后端。请设置VITE_USE_MOCK=false并重启前端后使用。" type="warning" :closable="false" />
    <div class="panel"><div class="panel-title">确认流程</div><div class="workflow"><span>需求方提交</span><b>→</b><span>提供方确认</span><b>→</b><span>管理员审批</span><b>→</b><span>启用登记</span></div></div>
    <div class="panel"><div class="panel-title">共享关系目录 </div>
      <div class="filters"><el-input v-model="filters.keyword" aria-label="搜索共享关系" placeholder="搜索关系、主体、资源或能力" clearable /><el-select v-model="filters.status" aria-label="筛选关系状态" placeholder="全部状态" clearable><el-option v-for="(label,value) in relationshipStatuses" :key="value" :label="label" :value="value" /></el-select></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="relationship-error" /><el-alert v-if="referenceError" :title="referenceError" type="error" :closable="false" /><el-alert v-if="notice" :title="notice" type="warning" :closable="false" />
      <el-table :data="paged" v-loading="loading" data-testid="relationship-table" empty-text="暂无共享关系，请先登记双方主体、发布资源并登记能力">
        <el-table-column prop="relationship_name" label="关系名称" min-width="170" show-overflow-tooltip /><el-table-column prop="provider_name" label="提供方" min-width="140" show-overflow-tooltip /><el-table-column prop="demand_name" label="需求方" min-width="140" show-overflow-tooltip /><el-table-column prop="resource_name" label="数据资源" min-width="180" show-overflow-tooltip /><el-table-column prop="capability_name" label="接口能力" min-width="160" show-overflow-tooltip />
        <el-table-column label="提供方确认" width="140"><template #default="{row}"><ProviderReviewTag :row="row" /></template></el-table-column><el-table-column label="状态" width="115"><template #default="{row}"><el-tag :type="row.status==='ACTIVE'?'success':row.status==='REVOKED'?'info':'warning'">{{relationshipStatuses[row.status]}}</el-tag></template></el-table-column><el-table-column label="授权有效期" min-width="130"><template #default="{row}">{{validity(row)}}</template></el-table-column><el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="操作" min-width="240" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="busy" @click="showDetail(row)">查看</el-button><el-button v-if="canApply() && row.status==='DRAFT'" link type="primary" :disabled="busy||!!referenceError" @click="open(row)">编辑</el-button><el-dropdown v-if="roleActions(row).length" :disabled="busy||!roleActions(row).length" @command="action=>openAction(row,action)"><el-button link type="primary" :disabled="busy||!roleActions(row).length">{{session.user?.role==='PROVIDER'?'内容确认':'状态操作'}}</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in roleActions(row)" :key="action" :command="action">{{relationshipActions[action]}}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
      </el-table><el-pagination v-model:current-page="page" v-model:page-size="size" :total="filtered.length" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" />
    </div>
    <el-dialog :model-value="visible" :title="editing?'编辑共享关系':'新增共享关系'" width="860px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close" @update:model-value="v=>{if(!v)close()}">
      <el-form label-position="top" :disabled="busy"><el-alert v-if="formError" :title="formError" type="error" :closable="false" data-testid="relationship-form-error" />
        <el-form-item label="关系名称" required><el-input v-model="form.relationship_name" maxlength="120" /></el-form-item>
        <div class="grid-2"><el-form-item label="提供方" required><el-select v-model="form.provider_subject_id" :disabled="!!editing||isDemand()" filterable placeholder="选择已启用的提供方"><el-option v-for="row in providers" :key="row.subject_id" :label="row.subject_name" :value="row.subject_id" /></el-select></el-form-item><el-form-item label="需求方" required><el-select v-model="form.demand_subject_id" :disabled="!!editing||isDemand()" filterable placeholder="选择已启用的需求方"><el-option v-for="row in demands" :key="row.subject_id" :label="row.subject_name" :value="row.subject_id" /></el-select></el-form-item></div>
        <div class="grid-2"><el-form-item label="数据资源" required><el-select v-model="form.resource_id" :disabled="!!editing" filterable placeholder="选择已发布资源" @change="changedResource"><el-option v-for="row in availableResources" :key="row.resource_id" :label="row.resource_name" :value="row.resource_id" /></el-select></el-form-item><el-form-item label="接口能力" required><el-select v-model="form.capability_id" :disabled="!!editing||!form.resource_id" placeholder="选择该资源下的能力"><el-option v-for="row in availableCapabilities" :key="row.capability_id" :label="row.capability_name" :value="row.capability_id" /></el-select></el-form-item></div>
        <el-form-item label="使用目的" required><el-input v-model="form.purpose" type="textarea" :rows="2" maxlength="1000" /></el-form-item>
        <el-form-item label="授权有效期" required><el-date-picker v-model="form.valid_range" type="datetimerange" start-placeholder="授权生效时间" end-placeholder="授权到期时间" /></el-form-item>
        <el-divider content-position="left">访问策略</el-divider><el-alert v-if="fieldError" :title="fieldError" type="error" :closable="false" /><el-button v-if="fieldError" :disabled="busy" @click="loadFields(form.resource_id)">重试读取字段</el-button>
        <div v-loading="fieldLoading"><el-form-item label="允许返回字段" required><el-checkbox-group v-model="form.allowed_fields" class="field-options"><el-checkbox v-for="field in fields" :key="field.field_name" :value="field.field_name">{{field.business_name||field.field_name}}（{{field.field_name}}）</el-checkbox></el-checkbox-group><p v-if="!fields.length" class="dim">请先选择资源；字段读取成功后逐项选择允许范围。</p></el-form-item>
          <el-form-item label="时间条件字段" required><el-select v-model="form.time_field" placeholder="选择人工标记的时间字段"><el-option v-for="field in dates" :key="field.field_name" :label="field.business_name||field.field_name" :value="field.field_name" /></el-select><p v-if="form.resource_id&&!fieldLoading&&!dates.length" class="dim">该资源尚无时间字段，请先在资源详情中标记日期或时间类型字段。</p></el-form-item>
          <el-form-item label="数据时间范围" required><el-date-picker v-model="form.data_range" type="datetimerange" start-placeholder="允许数据起点" end-placeholder="允许数据终点" /></el-form-item>
        </div><el-form-item label="操作原因" required><el-input v-model="form.reason" maxlength="1000" placeholder="说明登记或调整原因，将记录到关系历史" /></el-form-item>
      </el-form><template #footer><el-button :disabled="busy" @click="close">取消</el-button><el-button type="primary" :loading="busy" :disabled="fieldLoading||!!fieldError" @click="save">保存共享关系</el-button></template>
    </el-dialog>
    <el-dialog v-model="actionDialog" :title="relationshipActions[actionName]" width="550px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
      <p>{{actionRow?.relationship_name}} · 当前版本 {{actionRow?.version}}</p>
      <template v-if="actionName.startsWith('provider-')"><el-descriptions :column="1" border><el-descriptions-item label="需求方">{{actionRow?.demand_name}}</el-descriptions-item><el-descriptions-item label="资源 / 能力">{{actionRow?.resource_name}} / {{actionRow?.capability_name}}</el-descriptions-item><el-descriptions-item label="使用目的">{{actionRow?.purpose}}</el-descriptions-item><el-descriptions-item label="授权有效期">{{displayTime(actionRow?.valid_from)}} 至 {{displayTime(actionRow?.valid_until)}}</el-descriptions-item></el-descriptions><PolicyDetails :policy="actionRow.policy" /><el-checkbox v-if="actionName==='provider-confirm'" v-model="contentAcknowledged" aria-label="提供方确认共享内容" :disabled="busy">我已核对用途、有效期、允许字段和数据时间范围</el-checkbox></template>
      <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" data-testid="relationship-action-error" /><el-input v-model="actionReason" aria-label="状态操作原因" type="textarea" :rows="3" maxlength="1000" placeholder="填写原因，确认后写入操作历史" :disabled="busy" /><template #footer><el-button :disabled="busy" @click="actionDialog=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="actionConflict" @click="act">确认操作</el-button></template>
    </el-dialog>
    <el-drawer v-model="drawer" title="共享关系详情" size="780px" @update:model-value="value=>{if(!value)++detailEpoch}">
      <div v-loading="detailLoading" class="stack"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" /><template v-if="detail"><el-descriptions :column="1" :label-width="130" border><el-descriptions-item label="关系名称">{{detail.relationship_name}}</el-descriptions-item><el-descriptions-item label="关系编号">{{detail.relationship_id}}</el-descriptions-item><el-descriptions-item label="双方主体">{{detail.provider_name}} → {{detail.demand_name}}</el-descriptions-item><el-descriptions-item label="资源 / 能力">{{detail.resource_name}} / {{detail.capability_name}}</el-descriptions-item><el-descriptions-item label="使用目的">{{detail.purpose}}</el-descriptions-item><el-descriptions-item label="状态与版本">{{relationshipStatuses[detail.status]}} · 版本 {{detail.version}}</el-descriptions-item><el-descriptions-item label="授权有效期">{{displayTime(detail.valid_from)}} 至 {{displayTime(detail.valid_until)}}（{{validity(detail)}}）</el-descriptions-item></el-descriptions><el-descriptions :column="2" border><el-descriptions-item label="提供方确认"><ProviderReviewTag :row="detail" /></el-descriptions-item><el-descriptions-item label="提供方确认时间">{{displayTime(detail.provider_confirmed_at)}}</el-descriptions-item></el-descriptions><h3>访问策略</h3><PolicyDetails :policy="detail.policy" /><h3>操作历史</h3><el-table :data="history" data-testid="relationship-history"><el-table-column label="操作" width="115"><template #default="{row}">{{relationshipActions[row.action.toLowerCase()]||{CREATE:'登记',UPDATE:'编辑',PROVIDER_CONFIRM:'提供方确认',PROVIDER_RETURN:'提供方退回'}[row.action]}}</template></el-table-column><el-table-column prop="operator_name" label="操作者" width="120" /><el-table-column prop="reason" label="原因" min-width="180" /><el-table-column prop="version" label="版本" width="70" /><el-table-column label="时间" min-width="180"><template #default="{row}">{{displayTime(row.occurred_at)}}</template></el-table-column></el-table></template></div>
    </el-drawer>
  </div>
</template>
<style scoped>
.filters,.workflow{display:flex;gap:12px;margin:18px 0;align-items:center}.filters .el-input{flex:1}.filters .el-select{width:180px}.workflow{flex-wrap:wrap}.workflow span{background:#edf7f9;padding:10px 18px;border-radius:8px;color:#176b80}.workflow b{color:#8ba0af}.field-options{display:flex;flex-wrap:wrap;gap:5px 18px}.field-options .el-checkbox{margin-right:0}.el-pagination{margin-top:20px;justify-content:flex-end}.el-form .el-select,.el-date-editor{width:100%}.el-input,.el-alert{margin-bottom:6px}.dim{font-size:13px;line-height:1.7}
</style>
