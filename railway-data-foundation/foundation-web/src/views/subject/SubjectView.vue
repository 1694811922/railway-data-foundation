<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, toRaw, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { subjectApi } from '../../api/subject'
import { subjectDemoApi } from '../../mock/subjectData'
import { subjectTypes, subjectStatuses, subjectPayload, assertSubject, filterSubjects } from '../../utils/subject'
import { fetchDashboardList } from '../../utils/dashboard'
import { displayTime } from '../../utils/time'

const demo=import.meta.env.VITE_USE_MOCK==='true', api=demo?subjectDemoApi:subjectApi
const rows=ref([]), loading=ref(false), error=ref(''), notice=ref(''), busy=ref(false)
const filters=reactive({keyword:'',type:'',status:''}), page=ref(1), size=ref(10)
const filtered=computed(()=>filterSubjects(rows.value,filters)), paged=computed(()=>filtered.value.slice((page.value-1)*size.value,page.value*size.value))
watch(()=>[filters.keyword,filters.type,filters.status,size.value],()=>{page.value=1})
watch(()=>filtered.value.length,()=>{page.value=Math.min(page.value,Math.max(1,Math.ceil(filtered.value.length/size.value)))})
const visible=ref(false), editing=ref(null), form=reactive({}), formError=ref('')
let active=true, readEpoch=0, operationEpoch=0, dialogEpoch=0
async function load() {
  const epoch=++readEpoch;loading.value=true;error.value='';rows.value=[];notice.value=''
  try {
    const result=await fetchDashboardList(params=>api.list(params),'subject_id')
    result.rows.forEach(row=>assertSubject(row,demo))
    if (!active || epoch!==readEpoch) return
    rows.value=result.rows;notice.value=result.complete?'':result.notice
  } catch { if(active && epoch===readEpoch) error.value='共享主体读取失败，请确认新版后端已启动后重试。没有使用演示数据替代。' }
  finally { if(active && epoch===readEpoch) loading.value=false }
}
function open(row=null) {
  if (busy.value || loading.value || error.value) return
  ++dialogEpoch;editing.value=row?structuredClone(toRaw(row)):null;formError.value=''
  Object.assign(form,{subject_code:'',subject_name:'',subject_type:'PROVIDER',owner_department:'',description:'',version:undefined},row||{})
  visible.value=true
}
function close() { if (!busy.value) {visible.value=false;++dialogEpoch} }
function failure(error) {
  return error?.response?.data?.message || (demo ? error.message : error?.response ? error.message || '操作失败，请刷新检查' : '请求结果未确认，请刷新列表检查，勿重复提交')
}
async function save() {
  if (busy.value || !visible.value) return
  let body;const current=editing.value?structuredClone(toRaw(editing.value)):null
  try { body=subjectPayload(form,Boolean(current));if(current)assertSubject(current,demo) }
  catch(e){formError.value=e.message;return}
  const epoch=++operationEpoch, dialog=dialogEpoch;busy.value=true;formError.value=''
  try {
    const result=current?await api.update(current.subject_id,body):await api.create(body)
    assertSubject(result,demo,current?{subject_id:current.subject_id,subject_code:current.subject_code,subject_type:current.subject_type,version:current.version+1}:{subject_code:body.subject_code,subject_type:body.subject_type,version:1,status:'ENABLED'})
    if (!active || epoch!==operationEpoch || dialog!==dialogEpoch) return
    ElMessage.success(demo?'演示主体已保存':'共享主体已保存');visible.value=false;++dialogEpoch;await load()
  } catch(e) {if(active && epoch===operationEpoch && dialog===dialogEpoch)formError.value=failure(e)}
  finally {if(active && epoch===operationEpoch)busy.value=false}
}
async function toggle(row) {
  if (busy.value || loading.value) return
  const current=structuredClone(toRaw(row)),target=current.status==='ENABLED'?'DISABLED':'ENABLED'
  const epoch=++operationEpoch;busy.value=true
  try {
    assertSubject(current,demo)
    await ElMessageBox.confirm(target==='DISABLED'?'将停用该业务主体，其参与的新的REST共享请求将被拒绝。':'将启用该业务主体登记。实际访问还需有效调用凭据和启用的共享关系。',target==='DISABLED'?'停用主体':'启用主体',{confirmButtonText:'确认',cancelButtonText:'取消',type:'warning'})
    if (!active || epoch!==operationEpoch) return
    const result=await api.status(current.subject_id,{status:target,version:current.version})
    assertSubject(result,demo,{subject_id:current.subject_id,subject_code:current.subject_code,subject_type:current.subject_type,status:target,version:current.version+1})
    if (!active || epoch!==operationEpoch) return
    ElMessage.success('主体状态已更新');await load()
  } catch(e) {if(e!=='cancel' && e!=='close' && active && epoch===operationEpoch)ElMessage.error(failure(e))}
  finally {if(active && epoch===operationEpoch)busy.value=false}
}
async function removeSubject(row){
  if(busy.value||loading.value)return
  if(row.status!=='DISABLED')return ElMessage.warning('请先停用共享主体，再执行删除')
  const epoch=++operationEpoch;busy.value=true
  try{
    await ElMessageBox.confirm(`确认删除共享主体“${row.subject_name}”？`,'删除共享主体',{confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'})
    if(!active||epoch!==operationEpoch)return
    await api.delete(row.subject_id)
    if(!active||epoch!==operationEpoch)return
    ElMessage.success('共享主体已删除');await load()
  }catch(e){if(e!=='cancel'&&e!=='close'&&active&&epoch===operationEpoch)ElMessage.error(failure(e))}
  finally{if(active&&epoch===operationEpoch)busy.value=false}
}
onMounted(load)
onBeforeUnmount(()=>{active=false;++readEpoch;++operationEpoch;++dialogEpoch})
</script>
<template>
  <div class="stack">
    <div class="page-heading"><div><h1>共享主体管理</h1></div><div><el-button :disabled="busy || loading" @click="load">刷新列表</el-button><el-button type="primary" :disabled="busy || loading || !!error" @click="open()">新增主体</el-button></div></div>

    <div v-if="demo" data-testid="subject-demo"><el-tag type="warning">演示数据</el-tag></div>
    <div class="panel">
      <div class="panel-title">主体目录 </div>
      <div class="subject-filters"><el-input v-model="filters.keyword" placeholder="搜索主体名称、编码、编号或责任单位" clearable aria-label="搜索主体" /><el-select v-model="filters.type" placeholder="全部主体角色" clearable aria-label="筛选角色"><el-option v-for="(label,value) in subjectTypes" :key="value" :label="label" :value="value" /></el-select><el-select v-model="filters.status" placeholder="全部登记状态" clearable aria-label="筛选状态"><el-option v-for="(label,value) in subjectStatuses" :key="value" :label="label" :value="value" /></el-select></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon data-testid="subject-error" />
      <el-alert v-if="notice" :title="notice" type="warning" :closable="false" show-icon />
      <el-table :data="paged" v-loading="loading" data-testid="subject-table" empty-text="暂无主体，请登记提供方或需求方">
        <el-table-column prop="subject_name" label="主体名称" min-width="190" show-overflow-tooltip /><el-table-column prop="subject_code" label="业务编码" min-width="170" show-overflow-tooltip />
        <el-table-column label="主体角色" width="100"><template #default="{row}"><el-tag :type="row.subject_type==='PROVIDER'?'primary':'success'">{{ subjectTypes[row.subject_type] }}</el-tag></template></el-table-column>
        <el-table-column prop="owner_department" label="责任单位" min-width="170" show-overflow-tooltip /><el-table-column label="登记状态" width="110"><template #default="{row}"><el-tag :type="row.status==='ENABLED'?'success':'info'">{{ subjectStatuses[row.status] }}</el-tag></template></el-table-column>
        <el-table-column prop="version" label="版本" width="65" /><el-table-column label="更新时间" min-width="170"><template #default="{row}">{{ displayTime(row.updated_at) }}</template></el-table-column>
        <el-table-column label="操作" width="180" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="busy || loading" @click="open(row)">编辑</el-button><el-button link :type="row.status==='ENABLED'?'warning':'success'" :disabled="busy || loading" @click="toggle(row)">{{ row.status==='ENABLED'?'停用':'启用' }}</el-button><el-button v-if="row.status==='DISABLED'" link type="danger" :disabled="busy||loading" @click="removeSubject(row)">删除</el-button></template></el-table-column>
        <el-table-column prop="subject_id" label="主体编号" min-width="305" show-overflow-tooltip />
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="filtered.length" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" />
    </div>
    <el-dialog :model-value="visible" :title="editing?'编辑共享主体':'新增共享主体'" width="660px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close" @update:model-value="value=>{if(!value)close()}">
      <el-form label-position="top" :disabled="busy" @submit.prevent="save">
        <el-alert v-if="formError" :title="formError" type="error" :closable="false" data-testid="subject-form-error" />
        <el-form-item v-if="editing" label="主体编号"><span class="id-text">{{ editing.subject_id }}</span></el-form-item>
        <el-form-item label="业务编码" required><el-input v-model="form.subject_code" :disabled="!!editing" maxlength="64" placeholder="如 GUANGZHOU_PORT" /></el-form-item>
        <el-form-item label="主体名称" required><el-input v-model="form.subject_name" maxlength="120" /></el-form-item>
        <el-form-item label="主体角色" required><el-select v-model="form.subject_type" :disabled="!!editing"><el-option v-for="(label,value) in subjectTypes" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="责任单位" required><el-input v-model="form.owner_department" maxlength="120" /></el-form-item>
        <el-form-item label="业务说明"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="busy" @click="close">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存主体</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.subject-filters { display:flex; gap:12px; margin:18px 0; }
.subject-filters .el-input { flex:1; }
.subject-filters .el-select { width:170px; }
.el-pagination { margin-top:20px; justify-content:flex-end; }
.el-form-item .dim { font-size:12px; line-height:1.6; margin-top:5px; }
@media(max-width:900px) { .subject-filters { flex-wrap:wrap; } .subject-filters .el-input { flex-basis:100%; } }
</style>
