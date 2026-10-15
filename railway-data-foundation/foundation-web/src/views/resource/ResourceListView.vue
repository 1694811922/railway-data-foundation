<script setup>
import { session } from '../../utils/sessionState'
import { allowedActions } from '../../utils/workspace'
const isDemand=()=>session.user?.role==='DEMAND'
const canManage=()=>session.user?.role!=='DEMAND'
const canApply=()=>session.user?.role!=='PROVIDER'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hub } from '../../utils/dataHub'
import { resourcePayload, validateResource, resourceError, isResourceConflict } from '../../utils/resourceForm'
import { isDemoResource } from '../../mock/demoData'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { catalogQuery, catalogRouteQuery, filterResources, catalogPage, resourcePageSizes } from '../../utils/resourceCatalog'
import { existingRegisteredResource, registrationCreateScope, registrationEditScope, verifyRegistrationOrigin } from '../../utils/resourceRegistration'
const route = useRoute(), router = useRouter()
const rows = ref([]), demo = ref(false), dialog = ref(false), editing = ref('')
const saving = ref(false), publishing = ref(''), deleting = ref(''), loading = ref(false), formDemo = ref(false)
const loadError=ref(''), complete=ref(true), notice=ref('')
const conflict=ref('')
const initial=catalogQuery(route.query), search=reactive({...initial.filters}), applied=ref(initial.filters)
const page=ref(initial.page), pageSize=ref(initial.pageSize)
let loadVersion=0, hasReal=false, originVersion=0, contextVersion=0, active=true, editScope=null
const origin=ref(null),originLoading=ref(false),originError=ref('')
const busy=computed(()=>saving.value || !!publishing.value || !!deleting.value)
const duplicate=computed(()=>editing.value ? null : existingRegisteredResource(rows.value,origin.value,formDemo.value))
const canSave=computed(()=>dialog.value && !conflict.value && !loading.value && !loadError.value && !busy.value && !originLoading.value && (editing.value || (origin.value && !originError.value && !duplicate.value)))
const currentContext=version=>active && version===contextVersion
const filtered=computed(()=>filterResources(rows.value,applied.value))
const paged=computed(()=>catalogPage(filtered.value,page.value,pageSize.value))
const sourceOptions=computed(()=>Array.from(new Map(rows.value.map(row=>[row.source_id,{id:row.source_id,name:row.source_name || row.source_id}])).values()))
const hasFilters=computed(()=>Object.values(applied.value).some(Boolean))
async function syncQuery() {
  if(!active)return
  const next={...route.query,...catalogRouteQuery(applied.value,paged.value.page,pageSize.value)}
  await router.replace({path:route.path,query:next})
}
function query() { applied.value={...search}; page.value=1; syncQuery() }
function reset() { Object.assign(search,catalogQuery().filters); query() }
function turnPage(value) { page.value=value; syncQuery() }
function resize(value) { pageSize.value=value; page.value=1; syncQuery() }
function viewResource(row) {
  if(!active)return
  router.push({path:'/resources/'+row.resource_id,query:catalogRouteQuery(applied.value,paged.value.page,pageSize.value)})
}
watch(()=>route.query,query=>{
  const state=catalogQuery(query)
  Object.assign(search,state.filters); applied.value=state.filters; page.value=state.page; pageSize.value=state.pageSize
})
const blank = () => ({ resource_name:'', source_id:'', source_name:'', scan_task_id:null, schema_name:'', physical_table:'', resource_type:'TABLE', business_description:'', owner_department:'', security_level:'内部', update_mode:'实时' })
const form = reactive(blank())
async function load(allowPending=false) {
  if(loading.value || (busy.value && allowPending!==true)) return
  const version=++loadVersion
  loading.value = true
  loadError.value=''
  try {
    const result=await hub.resourceCatalog({allowDemo:!hasReal})
    if(!active || version!==loadVersion) return
    rows.value=result.rows; demo.value=result.demo; complete.value=result.complete; notice.value=result.notice
    if(!result.demo) hasReal=true
    page.value=paged.value.page
    await syncQuery()
  } catch {
    if(!active || version!==loadVersion) return
    rows.value=[]; demo.value=false; loadError.value='资源目录读取失败，请检查后端服务后刷新重试。未展示旧结果或模拟资源。'
    complete.value=false; notice.value=''
  } finally { if(active && version===loadVersion) loading.value = false }
}
function open(row) {
  if(busy.value)return
  if(row){
    if(loading.value || loadError.value)return
    const invalid=registrationEditScope(row,row,rows.value,isDemoResource(row.resource_id))
    if(invalid)return ElMessage.warning(invalid)
  }
  ++originVersion;origin.value=null;originError.value='';originLoading.value=false
  conflict.value=''
  Object.assign(form, blank(), row || {})
  editing.value = row?.resource_id || ''
  editScope=row?{...row}:null
  formDemo.value = row ? isDemoResource(row.resource_id) : false
  dialog.value = true
  if(!row)verifyOrigin()
}
async function verifyOrigin() {
  if(saving.value || editing.value || !dialog.value)return
  const version=++originVersion,context=contextVersion,query={...route.query}
  originLoading.value=true;originError.value='';origin.value=null
  try{
    const result=await verifyRegistrationOrigin(query,{task:hub.scanTask,tables:hub.tables})
    if(!currentContext(context) || version!==originVersion || !dialog.value)return
    origin.value=result;formDemo.value=result.demo
    Object.assign(form,{source_id:result.source_id,source_name:result.source_name,scan_task_id:result.task_id,schema_name:result.schema_name,physical_table:result.physical_table})
    if(!form.resource_name.trim())form.resource_name=`${result.physical_table} 数据资源`
  }catch(error){if(currentContext(context) && version===originVersion)originError.value=error.response?'扫描来源读取失败，请检查服务后重试':error.message || '扫描来源核对失败，请重新选择表'}
  finally{if(currentContext(context) && version===originVersion)originLoading.value=false}
}
async function save() {
  if(!canSave.value)return
  const scope=editing.value ? registrationEditScope(editScope,form,rows.value,formDemo.value) : registrationCreateScope(origin.value,form,formDemo.value)
  if(scope)return ElMessage.warning(scope)
  const invalid = validateResource(form)
  if (invalid) return ElMessage.warning(invalid)
  saving.value = true
  const context=contextVersion,id=editing.value || undefined,body=resourcePayload(form,id ? editScope.version : undefined)
  try {
    const result = await hub.saveResource(body,id)
    if(!currentContext(context))return
    dialog.value = false
    ElMessage.success(result.demo ? '演示资源已保存' : '资源已保存')
    viewResource(result.value)
  } catch (error) { if(currentContext(context)) {
    if(isResourceConflict(error))conflict.value=resourceError(error,'保存资源')
    ElMessage.error(resourceError(error, '保存资源'))
  } }
  finally { saving.value = false }
}
async function publish(row) {
  if(busy.value || loading.value || loadError.value)return
  const scope=registrationEditScope(row,row,rows.value,isDemoResource(row.resource_id))
  if(scope || row.status!=='DRAFT')return ElMessage.warning(scope || '仅草稿资源可发布')
  const invalid = validateResource(row)
  if (invalid) return ElMessage.warning(invalid)
  publishing.value = row.resource_id
  const context=contextVersion
  try {
    const result = await hub.publishResource(row.resource_id)
    if(!currentContext(context))return
    ElMessage.success(`已发布 ${result.value.resource_id}${result.demo ? '（演示数据）' : ''}`)
    await load(true)
  } catch (error) { if(currentContext(context))ElMessage.error(resourceError(error, '发布资源')) }
  finally { publishing.value = '' }
}
async function removeResource(row){
  if(busy.value||loading.value||loadError.value)return
  if(row.status!=='DRAFT')return ElMessage.warning('仅草稿资源可以删除')
  try{
    await ElMessageBox.confirm(`确认删除草稿资源“${row.resource_name}”？资源字段元数据将一并删除。`,'删除资源',{confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'})
    deleting.value=row.resource_id
    await hub.deleteResource(row.resource_id)
    if(!active)return
    ElMessage.success('草稿资源已删除');await load(true)
  }catch(error){if(error!=='cancel'&&error!=='close'&&active)ElMessage.error(resourceError(error,'删除资源'))}
  finally{deleting.value=''}
}
function moreAction(row,command){if(command==='edit')open(row);else if(command==='delete')removeResource(row)}
function refreshAfterConflict(){if(busy.value)return;dialog.value=false;conflict.value='';load()}
watch(() => JSON.stringify([route.query.fromTable,route.query.source,route.query.schema,route.query.task]), () => {
  ++contextVersion;++originVersion;dialog.value=false;origin.value=null;originError.value='';originLoading.value=false;editScope=null
  if(route.query.fromTable&&canManage())open()
}, { immediate:true })
onMounted(load)
onBeforeUnmount(()=>{active=false;++loadVersion;++originVersion;++contextVersion;editScope=null})
</script>

<template>
  <div class="stack">
    <div class="page-heading">
      <div><h1>数据资源目录</h1></div>
      <div class="actions"><el-button :loading="loading" :disabled="busy" @click="load">刷新目录</el-button><el-button v-if="canManage() && route.query.fromTable && !dialog" :disabled="busy" @click="open()">继续登记所选表</el-button><el-button v-if="canManage()" type="primary" :disabled="busy" @click="router.push('/scans')">＋ 从扫描结果创建资源</el-button></div>
    </div>
    <DemoNotice :show="demo" />
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon data-testid="catalog-error" />
    <el-alert v-else-if="!complete" :title="notice" type="warning" :closable="false" show-icon data-testid="catalog-incomplete" />
    <div class="panel">
      <div class="panel-title">资源目录 <span class="panel-sub" data-testid="catalog-count">{{ loadError ? '读取失败' : '已读取 '+rows.length+' 项 · 匹配 '+filtered.length+' 项' }}</span></div>
      <el-form :model="search" label-position="top" class="catalog-filters" @submit.prevent="query">
        <el-form-item label="关键词" class="keyword"><el-input v-model="search.keyword" maxlength="200" clearable placeholder="搜索资源名称、编号、物理表、来源或责任单位" @keyup.enter="query" /></el-form-item>
        <el-form-item label="来源系统"><el-select v-model="search.source_id" clearable filterable placeholder="全部来源"><el-option v-for="source in sourceOptions" :key="source.id" :value="source.id" :label="source.name" /></el-select></el-form-item>
        <el-form-item label="资源状态"><el-select v-model="search.status" clearable placeholder="全部状态"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /></el-select></el-form-item>
        <el-form-item label="安全等级"><el-select v-model="search.security_level" clearable placeholder="全部等级"><el-option v-for="level in ['公开','内部','敏感','机密']" :key="level" :value="level" :label="level" /></el-select></el-form-item>
        <div class="filter-actions"><el-button type="primary" :disabled="loading || !!loadError" @click="query">查询</el-button><el-button :disabled="loading" @click="reset">重置</el-button></div>
      </el-form>

      <el-table v-if="!loadError" :data="paged.rows" v-loading="loading" :empty-text="hasFilters ? '没有符合条件的资源，请调整筛选条件' : '暂无资源，请先执行元数据扫描'" data-testid="catalog-table">
        <el-table-column prop="resource_id" label="资源编号" min-width="170"><template #default="{row}"><span class="id-text">{{ row.resource_id }}</span></template></el-table-column>
        <el-table-column prop="resource_name" label="资源名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="source_name" label="来源系统" min-width="140" />
        <el-table-column prop="physical_table" label="物理表" min-width="130" />
        <el-table-column prop="security_level" label="安全等级" width="95" />
        <el-table-column prop="update_mode" label="更新方式" width="95" />
        <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
        <el-table-column prop="version" label="版本" width="65" />
        <el-table-column label="操作" width="185" fixed="right"><template #default="{row}"><div class="table-actions">
          <el-button link type="primary" @click="viewResource(row)">查看</el-button>
          <el-button v-if="canManage() && row.status==='DRAFT'" link type="primary" :loading="publishing===row.resource_id" :disabled="busy || loading" @click="publish(row)">发布</el-button>
          <el-dropdown v-if="canManage()" :disabled="busy||loading" @command="moreAction(row,$event)"><el-button link type="primary">更多操作⌄</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="edit">编辑资源</el-dropdown-item><el-dropdown-item command="delete" :disabled="row.status!=='DRAFT'" divided>删除草稿</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div></template></el-table-column>
      </el-table>
      <div v-if="!loadError" class="catalog-pagination"><el-pagination :current-page="paged.page" :page-size="pageSize" :page-sizes="resourcePageSizes" :total="filtered.length" layout="sizes, prev, pager, next, jumper" :disabled="loading" @current-change="turnPage" @size-change="resize" /></div>
    </div>
    <el-dialog v-model="dialog" :title="editing ? '编辑数据资源' : '从扫描结果创建资源'" width="720px" :show-close="!saving" :close-on-press-escape="!saving" :close-on-click-modal="false">
      <template v-if="!editing">
        <el-alert v-if="originLoading" title="正在核对扫描任务与来源表，请稍候…" type="info" :closable="false" show-icon data-testid="registration-loading" />
        <div v-else-if="originError" class="registration-error"><el-alert :title="originError" type="error" :closable="false" show-icon data-testid="registration-origin-error" /><el-button :disabled="busy" @click="verifyOrigin">重新核对扫描来源</el-button></div>
        <div v-else-if="origin" class="registration-origin" data-testid="registration-origin"><p>任务编号：<span class="id-text">{{ origin.task_id }}</span></p><p>表说明：{{ origin.table_comment || '暂无' }} · 字段数：{{ origin.field_count ?? '—' }} · 估算记录数：{{ origin.estimated_rows ?? '—' }}</p></div>
        <div v-if="duplicate" class="registration-duplicate" data-testid="registration-duplicate"><el-alert :title="`该表已登记为资源：${duplicate.resource_name}`" type="warning" :closable="false" show-icon /><el-button type="primary" @click="viewResource(duplicate)">查看已有资源</el-button></div>
      </template>
      <DemoNotice :show="formDemo && !!(editing || origin)" />
      <div v-if="conflict" class="registration-error" data-testid="resource-edit-conflict"><el-alert :title="conflict" type="warning" :closable="false" show-icon /><el-button :disabled="busy" @click="refreshAfterConflict">取消并刷新目录</el-button></div>
      <el-alert v-if="loadError" title="资源目录读取失败，请关闭弹窗并刷新目录后再保存。" type="error" :closable="false" />

      <el-form label-position="top" :model="form" :disabled="saving || (!editing && (originLoading || !!originError || !origin || !!duplicate))">
        <div class="grid-2">
          <el-form-item label="来源系统"><el-input :model-value="form.source_name || form.source_id" readonly /></el-form-item>
          <el-form-item label="数据库模式"><el-input v-model="form.schema_name" readonly /></el-form-item>
          <el-form-item label="物理表" required><el-input v-model="form.physical_table" readonly /></el-form-item>
          <el-form-item label="资源名称" required><el-input v-model="form.resource_name" maxlength="120" placeholder="例如：集装箱作业事件数据集" /></el-form-item>
          <el-form-item label="责任单位" required><el-input v-model="form.owner_department" maxlength="120" placeholder="负责维护资源的单位或部门" /></el-form-item>
          <el-form-item label="安全等级"><el-select v-model="form.security_level" style="width:100%"><el-option v-for="v in ['公开','内部','敏感','机密']" :key="v" :label="v" :value="v" /></el-select></el-form-item>
          <el-form-item label="更新方式"><el-select v-model="form.update_mode" style="width:100%"><el-option v-for="v in ['实时','每日','按需']" :key="v" :label="v" :value="v" /></el-select></el-form-item>
          <el-form-item label="资源类型"><el-input model-value="数据库表" readonly /></el-form-item>
        </div>
        <el-form-item label="业务描述" required><el-input v-model="form.business_description" type="textarea" :rows="3" maxlength="2000" placeholder="说明数据的业务含义、用途和覆盖范围" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">保存资源</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.actions { display:flex; gap:8px; }
.registration-origin,.registration-error,.registration-duplicate { margin-bottom:16px; }
.registration-origin p { font-size:12px; line-height:1.7; color:#64748b; overflow-wrap:anywhere; }
.registration-error .el-button,.registration-duplicate .el-button { margin-top:10px; }
.catalog-filters { display:grid; grid-template-columns:minmax(240px,2fr) repeat(3,minmax(135px,1fr)) auto; gap:12px; align-items:end; margin-bottom:20px; }
.catalog-filters .el-form-item { margin-bottom:0; }
.filter-actions { display:flex; gap:8px; padding-bottom:1px; }
.filter-actions .el-button + .el-button { margin-left:0; }
.catalog-pagination { display:flex; justify-content:flex-end; gap:12px; align-items:center; flex-wrap:wrap; margin-top:20px; color:#718497; font-size:12px; }
@media(max-width:1400px) { .catalog-filters { grid-template-columns:repeat(3,minmax(0,1fr)); } .keyword { grid-column:span 2; } }
@media(max-width:760px) { .catalog-filters { grid-template-columns:1fr; } .keyword { grid-column:auto; } .actions { flex-wrap:wrap; } }
</style>
