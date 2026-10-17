<script setup>
import { session } from '../../utils/sessionState'
import { allowedActions } from '../../utils/workspace'
const isDemand=()=>session.user?.role==='DEMAND'
const canManage=()=>session.user?.role!=='DEMAND'
const canApply=()=>session.user?.role!=='PROVIDER'
import {computed,onBeforeUnmount,onMounted,reactive,ref,toRaw,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {credentialApi} from '../../api/access'
import {subjectApi} from '../../api/subject'
import {fetchDashboardList} from '../../utils/dashboard'
import {assertSubject} from '../../utils/subject'
import {assertCredential,assertIssued,credentialEvents,credentialPayload} from '../../utils/access'
import {displayTime} from '../../utils/time'
const mock=import.meta.env.VITE_USE_MOCK==='true'
const rows=ref([]),subjects=ref([]),loading=ref(false),busy=ref(false),error=ref(''),subjectError=ref(''),notice=ref(''),keyword=ref(''),page=ref(1),size=ref(10)
const visible=ref(false),issued=ref(null),formError=ref(''),form=reactive({subject_id:'',credential_name:'',expires_at:null,reason:''})
const revokeDialog=ref(false),current=ref(null),reason=ref(''),revokeError=ref('')
const drawer=ref(false),events=ref([]),detail=ref(null),detailError=ref(''),detailLoading=ref(false)
let active=true,readEpoch=0,writeEpoch=0,detailEpoch=0
const demands=computed(()=>subjects.value.filter(row=>row.subject_type==='DEMAND'&&row.status==='ENABLED'))
const filtered=computed(()=>rows.value.filter(row=>[row.credential_id,row.credential_name,row.subject_name].some(value=>String(value||'').toLowerCase().includes(keyword.value.trim().toLowerCase())))),paged=computed(()=>filtered.value.slice((page.value-1)*size.value,page.value*size.value))
watch(()=>[keyword.value,size.value],()=>{page.value=1});watch(()=>filtered.value.length,()=>{page.value=Math.min(page.value,Math.max(1,Math.ceil(filtered.value.length/size.value)))})
const safe=e=>e?.response?.data?.message||(!e?.response&&e?.code?'操作结果未确认，请刷新检查；无法读取令牌时撤销该凭据并重新签发':e?.message||'操作失败')
async function load(){
  if(mock)return
  const epoch=++readEpoch;loading.value=true;rows.value=[];subjects.value=[];error.value='';subjectError.value='';notice.value=''
  const results=await Promise.allSettled([fetchDashboardList(credentialApi.list,'credential_id').then(data=>{data.rows.forEach(row=>assertCredential(row));return data}),fetchDashboardList(subjectApi.list,'subject_id').then(data=>{data.rows.forEach(row=>assertSubject(row,false));return data})])
  if(!active||epoch!==readEpoch)return
  results.forEach((result,i)=>{if(result.status==='rejected'){if(i===0)error.value='调用凭据读取失败，请检查新版后端并重试';else subjectError.value='需求方读取失败，暂不能签发';return};[rows,subjects][i].value=result.value.rows;if(!result.value.complete)notice.value=result.value.notice||'列表读取范围有限'})
  loading.value=false
}
function open(){if(busy.value||loading.value||error.value||subjectError.value||mock)return;Object.assign(form,{subject_id:isDemand()?session.user.subject_id:'',credential_name:'',expires_at:new Date(Date.now()+30*86400000),reason:''});issued.value=null;formError.value='';visible.value=true}
function close(){if(busy.value)return;visible.value=false;issued.value=null;form.reason=''}
async function issue(){
  if(busy.value||issued.value||!visible.value)return
  let body,id=form.subject_id;try{body=credentialPayload(form);if(!demands.value.some(row=>row.subject_id===id))throw new Error('需求方不可用，请刷新')}catch(e){formError.value=e.message;return}
  const epoch=++writeEpoch;busy.value=true;formError.value=''
  try{const result=await credentialApi.issue(id,body);assertIssued(result,id,body);if(!active||epoch!==writeEpoch)return;issued.value=result;ElMessage.success('调用凭据已签发，请保存本次令牌');await load()}
  catch(e){if(active&&epoch===writeEpoch)formError.value=safe(e)}finally{if(active&&epoch===writeEpoch)busy.value=false}
}
function openRevoke(row){if(busy.value||row.status!=='ENABLED'||mock)return;current.value=structuredClone(toRaw(row));reason.value='';revokeError.value='';revokeDialog.value=true}
async function revoke(){
  if(busy.value||!revokeDialog.value)return
  const text=reason.value.trim();if(!text||text.length>1000){revokeError.value='请填写撤销原因，最多1000字';return}
  const row=structuredClone(toRaw(current.value)),epoch=++writeEpoch;busy.value=true;revokeError.value=''
  try{const result=await credentialApi.revoke(row.credential_id,{version:row.version,reason:text});assertCredential(result,{credential_id:row.credential_id,subject_id:row.subject_id,status:'REVOKED',version:row.version+1});if(!active||epoch!==writeEpoch)return;revokeDialog.value=false;drawer.value=false;++detailEpoch;ElMessage.success('凭据已撤销，不能恢复');await load()}
  catch(e){if(active&&epoch===writeEpoch)revokeError.value=safe(e)}finally{if(active&&epoch===writeEpoch)busy.value=false}
}
async function show(row){
  const id=row.credential_id,epoch=++detailEpoch;drawer.value=true;detail.value=null;events.value=[];detailError.value='';detailLoading.value=true
  try{const [value,history]=await Promise.all([credentialApi.get(id),credentialApi.events(id)]);assertCredential(value,{credential_id:id});credentialEvents(history,value);if(active&&epoch===detailEpoch&&drawer.value){detail.value=value;events.value=history}}
  catch(e){if(active&&epoch===detailEpoch)detailError.value=safe(e)}finally{if(active&&epoch===detailEpoch)detailLoading.value=false}
}
onMounted(load);onBeforeUnmount(()=>{active=false;++readEpoch;++writeEpoch;++detailEpoch;issued.value=null})
</script>
<template>
  <div class="stack">
    <div class="page-heading"><div><h1>{{isDemand()?'我的REST调用凭据':'REST调用凭据'}}</h1></div><div><el-button :disabled="loading||busy||mock" @click="load">刷新凭据</el-button><el-button type="primary" :disabled="loading||busy||mock||!!error||!!subjectError" @click="open">签发调用凭据</el-button></div></div>

    <el-alert v-if="mock" title="调用凭据仅支持真实后端，不生成模拟令牌。请关闭演示模式。" type="warning" :closable="false" />
    <div class="panel"><div class="panel-title">凭据目录 </div><el-input v-model="keyword" aria-label="搜索调用凭据" placeholder="搜索名称、需求方或凭据编号" clearable class="search" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="credential-error" /><el-alert v-if="subjectError" :title="subjectError" type="error" :closable="false" /><el-alert v-if="notice" :title="notice" type="warning" :closable="false" />
      <el-table :data="paged" v-loading="loading" data-testid="credential-table" empty-text="暂无调用凭据，请先登记并启用需求方"><el-table-column prop="credential_name" label="凭据名称" min-width="180" show-overflow-tooltip /><el-table-column prop="subject_name" label="需求方" min-width="180" show-overflow-tooltip /><el-table-column prop="credential_id" label="凭据编号" min-width="200" show-overflow-tooltip /><el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='REVOKED'?'info':new Date(row.expires_at)<=new Date()?'warning':'success'">{{row.status==='REVOKED'?'已撤销':new Date(row.expires_at)<=new Date()?'已到期':'可用'}}</el-tag></template></el-table-column><el-table-column label="到期时间" min-width="190"><template #default="{row}">{{displayTime(row.expires_at)}}</template></el-table-column><el-table-column prop="version" label="版本" width="70" /><el-table-column label="操作" width="150" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="busy" @click="show(row)">查看</el-button><el-button link type="danger" :disabled="busy||row.status!=='ENABLED'" @click="openRevoke(row)">撤销</el-button></template></el-table-column></el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="filtered.length" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" />
    </div>
    <el-dialog :model-value="visible" title="签发调用凭据" width="650px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close" @update:model-value="value=>{if(!value)close()}">
      <template v-if="issued"><el-alert title="令牌仅显示一次，请立即保存。" type="warning" :closable="false" /><el-descriptions :column="1"><el-descriptions-item label="凭据编号">{{issued.credential.credential_id}}</el-descriptions-item></el-descriptions><el-input :model-value="issued.token" type="password" show-password readonly aria-label="本次调用令牌" data-testid="issued-token" /></template>
      <el-form v-else label-position="top" :disabled="busy"><el-alert v-if="formError" :title="formError" type="error" :closable="false" data-testid="credential-form-error" /><el-form-item label="需求方" required><el-select v-model="form.subject_id" :disabled="isDemand()" filterable placeholder="选择已启用需求方"><el-option v-for="row in demands" :key="row.subject_id" :label="row.subject_name" :value="row.subject_id" /></el-select></el-form-item><el-form-item label="凭据名称" required><el-input v-model="form.credential_name" maxlength="120" /></el-form-item><el-form-item label="到期时间" required><el-date-picker v-model="form.expires_at" type="datetime" /></el-form-item><el-form-item label="签发原因" required><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="1000" /></el-form-item></el-form>
      <template #footer><el-button :disabled="busy" @click="close">{{issued?'已保存，关闭':'取消'}}</el-button><el-button v-if="!issued" type="primary" :loading="busy" @click="issue">确认签发</el-button></template>
    </el-dialog>
    <el-dialog v-model="revokeDialog" title="撤销调用凭据" width="550px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy"><p>撤销 {{current?.credential_name}} 后不可恢复，后续请求将被拒绝。</p><el-alert v-if="revokeError" :title="revokeError" type="error" :closable="false" /><el-input v-model="reason" aria-label="撤销凭据原因" type="textarea" :rows="3" :disabled="busy" maxlength="1000" /><template #footer><el-button :disabled="busy" @click="revokeDialog=false">取消</el-button><el-button type="danger" :loading="busy" @click="revoke">确认撤销</el-button></template></el-dialog>
    <el-drawer v-model="drawer" title="调用凭据详情" size="700px" @update:model-value="value=>{if(!value)++detailEpoch}"><div v-loading="detailLoading"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" /><template v-if="detail"><el-descriptions :column="1" :label-width="130" border><el-descriptions-item label="凭据名称">{{detail.credential_name}}</el-descriptions-item><el-descriptions-item label="需求方">{{detail.subject_name}}</el-descriptions-item><el-descriptions-item label="凭据编号">{{detail.credential_id}}</el-descriptions-item><el-descriptions-item label="到期时间">{{displayTime(detail.expires_at)}}</el-descriptions-item></el-descriptions><el-table :data="events"><el-table-column label="操作" width="90"><template #default="{row}">{{row.action==='ISSUE'?'签发':'撤销'}}</template></el-table-column><el-table-column prop="reason" label="原因" min-width="170" /><el-table-column prop="operator_name" label="操作者" width="120" /><el-table-column label="时间" min-width="190"><template #default="{row}">{{displayTime(row.occurred_at)}}</template></el-table-column></el-table></template></div></el-drawer>
  </div>
</template>
<style scoped>.search{margin:16px 0}.el-pagination{justify-content:flex-end;margin-top:20px}.el-form .el-select,.el-date-editor{width:100%}.el-alert{margin-bottom:15px}.dim{line-height:1.8}</style>
