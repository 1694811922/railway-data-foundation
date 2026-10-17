<script setup>
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {sharingApi} from '../../api/sharing'
import {resourceApi} from '../../api/resource'
import {callProvider} from '../../api/access'
import {fetchDashboardList} from '../../utils/dashboard'
import {assertRelationship,relationshipStatuses,validity} from '../../utils/sharing'
import {detailRows} from '../../utils/resourceDetail'
import {callerToken,invocationParams,providerRows} from '../../utils/access'
import PolicyDetails from '../../components/PolicyDetails.vue'
const route=useRoute(),mock=import.meta.env.VITE_USE_MOCK==='true'
const props=defineProps({relationshipId:String,embedded:Boolean})
const relationships=ref([]),selection=ref(''),relationship=ref(null),fields=ref([])
const loading=ref(false),scopeLoading=ref(false),busy=ref(false),error=ref(''),scopeError=ref(''),notice=ref('')
const token=ref(''),result=ref(null),rows=ref([]),columns=ref([])
const form=reactive({fields:[],range:null,limit:10,containerNo:'',trainNo:'',eventType:''})
let active=true,listEpoch=0,scopeEpoch=0,callEpoch=0
const labels=computed(()=>new Map(fields.value.map(row=>[row.field_name,row.business_name||row.field_name])))
function clearResult(){result.value=null;rows.value=[];columns.value=[];++callEpoch;busy.value=false}
async function load(){
  if(mock||busy.value)return
  const epoch=++listEpoch;++scopeEpoch;clearResult();loading.value=true;scopeLoading.value=false;relationships.value=[];relationship.value=null;fields.value=[];error.value='';scopeError.value='';notice.value=''
  if(props.relationshipId){loading.value=false;token.value='';if(selection.value===props.relationshipId)await loadScope(props.relationshipId);else selection.value=props.relationshipId;return}
  try{
    const data=await fetchDashboardList(sharingApi.list,'relationship_id');data.rows.forEach(row=>assertRelationship(row))
    if(!active||epoch!==listEpoch)return
    relationships.value=data.rows;notice.value=data.complete?'':data.notice||'关系目录读取范围有限'
    const id=selection.value||String(route.query.relationshipId||'')
    if(id&&data.rows.some(row=>row.relationship_id===id)){if(selection.value===id)await loadScope(id);else selection.value=id}
    else if(id){selection.value='';token.value='';scopeError.value='所选关系不在已读取目录中，请重新选择'}
  }catch(e){if(active&&epoch===listEpoch)error.value='共享关系读取失败，请检查后端并重试'}
  finally{if(active&&epoch===listEpoch)loading.value=false}
}
async function loadScope(id){
  const epoch=++scopeEpoch;clearResult();relationship.value=null;fields.value=[];scopeError.value='';scopeLoading.value=!!id
  Object.assign(form,{fields:[],range:null,limit:10,containerNo:'',trainNo:'',eventType:''})
  if(!id)return
  try{
    const current=await sharingApi.get(id);assertRelationship(current,{relationship_id:id})
    const metadata=detailRows({rows:await resourceApi.fields(current.resource_id),demo:false},current.resource_id,'field_name',false)
    if(metadata.some(row=>row._demo)||current.policy.allowed_fields.some(name=>!metadata.some(row=>row.field_name===name)))throw new Error('策略字段不属于当前资源')
    if(active&&epoch===scopeEpoch&&selection.value===id){relationship.value=current;fields.value=metadata}
  }catch(e){if(active&&epoch===scopeEpoch)scopeError.value='关系或资源字段核对失败，请刷新关系后重试'}
  finally{if(active&&epoch===scopeEpoch)scopeLoading.value=false}
}
watch(selection,id=>{token.value='';loadScope(id)})
watch([token,form],()=>clearResult(),{deep:true})
watch(()=>props.relationshipId,()=>{token.value='';load()})
watch(()=>route.query.relationshipId,id=>{if(!props.embedded&&!busy.value&&typeof id==='string')selection.value=id})
async function invoke(){
  if(busy.value||loading.value||scopeLoading.value||!relationship.value||scopeError.value||mock)return
  clearResult()
  let params,secret
  try{params=invocationParams(form);secret=callerToken(token.value)}catch(e){result.value={kind:'warning',message:e.message};return}
  const current=relationship.value,epoch=++callEpoch,scope=scopeEpoch
  const expected=form.fields.length?[...form.fields]:[...current.policy.allowed_fields]
  busy.value=true
  try{
    const response=await callProvider(current,secret,params)
    const data=providerRows(response.data,expected,params.limit)
    if(!active||epoch!==callEpoch||scope!==scopeEpoch||selection.value!==current.relationship_id)return
    rows.value=data;columns.value=expected;result.value={kind:'success',status:response.status,message:`授权查询成功，返回 ${data.length} 条记录`}
  }catch(e){
    if(!active||epoch!==callEpoch||scope!==scopeEpoch)return
    const status=e?.response?.status
    // Use only the provider's safe API message, never Axios request/config (contains the token).
    const message=typeof e?.response?.data?.message==='string'?e.response.data.message:
      status?'提供服务未返回有效授权结果':e?.response===undefined&&e?.code?'提供服务不可达或请求超时，请检查后重试':e.message||'共享查询失败'
    result.value={kind:'error',status,message};rows.value=[];columns.value=[]
  }finally{secret='';if(active&&epoch===callEpoch)busy.value=false}
}
onMounted(load)
onBeforeUnmount(()=>{active=false;++listEpoch;++scopeEpoch;++callEpoch;token.value='';rows.value=[]})
</script>
<template>
  <div class="stack">
    <div class="page-heading"><div><h1 v-if="!embedded">共享调用验证</h1><strong v-else>当前授权调用测试</strong></div><el-button :disabled="loading||scopeLoading||busy||mock" @click="load">刷新授权</el-button></div>

    <el-alert v-if="mock" title="共享调用验证仅支持真实后端，演示模式不会发送查询或生成授权结果。" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="access-list-error" />
    <el-alert v-if="notice" :title="notice" type="warning" :closable="false" />
    <div class="access-grid">
      <div class="panel" v-loading="loading||scopeLoading">
        <div class="panel-title">共享请求</div>
        <el-form label-position="top" :disabled="busy||loading||scopeLoading||mock">
          <el-form-item v-if="!embedded" label="共享关系" required><el-select v-model="selection" filterable placeholder="选择要验证的共享关系" aria-label="选择共享关系"><el-option v-for="row in relationships" :key="row.relationship_id" :value="row.relationship_id" :label="`${row.relationship_name} · ${relationshipStatuses[row.status]}`" /></el-select></el-form-item>
          <el-alert v-if="scopeError" :title="scopeError" type="error" :closable="false" data-testid="access-scope-error" />
          <el-form-item label="需求方调用令牌" required><el-input v-model="token" type="password" show-password autocomplete="off" placeholder="填写签发时获取的调用令牌" aria-label="需求方调用令牌" /></el-form-item>
          <el-form-item label="请求字段"><el-select v-model="form.fields" multiple filterable placeholder="不选择时按策略允许字段返回" aria-label="请求字段"><el-option v-for="field in fields" :key="field.field_name" :value="field.field_name" :label="`${field.business_name||field.field_name}（${field.field_name}）`" /></el-select></el-form-item>
          <el-form-item label="查询数据时间范围"><el-date-picker v-model="form.range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item>
          <div class="filter-pair"><el-form-item label="集装箱号"><el-input v-model="form.containerNo" maxlength="20" aria-label="查询集装箱号" /></el-form-item><el-form-item label="车次"><el-input v-model="form.trainNo" maxlength="30" aria-label="查询车次" /></el-form-item></div>
          <div class="filter-pair"><el-form-item label="事件类型"><el-select v-model="form.eventType" clearable><el-option label="进场" value="GATE_IN" /><el-option label="出场" value="GATE_OUT" /><el-option label="装载" value="LOAD" /><el-option label="卸载" value="UNLOAD" /></el-select></el-form-item><el-form-item label="最多返回条数"><el-input-number v-model="form.limit" :min="1" :max="100" :precision="0" /></el-form-item></div>

          <el-button type="primary" :loading="busy" :disabled="!relationship||!!scopeError||mock||loading||scopeLoading" @click="invoke">发送共享查询</el-button><el-button :disabled="busy" @click="token=''">清除令牌</el-button>
        </el-form>
      </div>
      <div class="stack">
        <div class="panel"><div class="panel-title">当前共享条件</div><el-empty v-if="!relationship" description="选择关系后核对资源与访问策略" /><template v-else><el-descriptions :column="1" :label-width="130" border><el-descriptions-item label="需求方">{{relationship.demand_name}}</el-descriptions-item><el-descriptions-item label="资源 / 能力">{{relationship.resource_name}} / {{relationship.capability_name}}</el-descriptions-item><el-descriptions-item label="关系状态">{{relationshipStatuses[relationship.status]}} · {{validity(relationship)}}</el-descriptions-item><el-descriptions-item label="关系编号">{{relationship.relationship_id}}</el-descriptions-item></el-descriptions><h3>访问策略</h3><PolicyDetails :policy="relationship.policy" /></template></div>
        <div class="panel"><div class="panel-title">调用结果</div><el-alert v-if="result" :type="result.kind" :title="`${result.status?`HTTP ${result.status} · `:''}${result.message}`" :closable="false" data-testid="access-result" /><p v-else class="dim">尚未发送查询</p><el-table v-if="result?.kind==='success'" :data="rows" empty-text="授权通过，时间范围内暂无匹配记录" data-testid="access-result-table"><el-table-column v-for="name in columns" :key="name" :prop="name" :label="labels.get(name)||name" min-width="150" show-overflow-tooltip /></el-table></div>
      </div>
    </div>
  </div>
</template>
<style scoped>
.access-grid{display:grid;grid-template-columns:minmax(380px,0.85fr) minmax(0,1.15fr);gap:20px;align-items:start}.filter-pair{display:grid;grid-template-columns:1fr 1fr;gap:16px}.hint{font-size:13px;line-height:1.7;color:#6c8393;margin:6px 0 0}.el-select,.el-date-editor{width:100%}.el-alert{margin:12px 0}.el-table{margin-top:18px}
@media(max-width:1100px){.access-grid{grid-template-columns:1fr}}
</style>
