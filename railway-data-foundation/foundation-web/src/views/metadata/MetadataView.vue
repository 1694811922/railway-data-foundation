<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hub } from '../../utils/dataHub'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { displayTime } from '../../utils/time'
import { filterScanTasks, filterMetadataRows, scanSourceError, scanResultRows, scanTableError, scanResponseTask } from '../../utils/scanCatalog'
import { catalogPage, resourcePageSizes } from '../../utils/resourceCatalog'
const router=useRouter(), route=useRoute()
const tasks=ref([]), sources=ref([]), tables=ref([]), fields=ref([])
const selected=ref(null), selectedTable=ref(null), selectedSource=ref(''), demo=ref(false), tablesDemo=ref(false), drawer=ref(false), loading=ref(false), scanning=ref(false), resultsLoading=ref(false), deleting=ref('')
const fieldTask=ref(null)
const taskError=ref(''), sourceError=ref(''), selectionError=ref(''), tableError=ref(''), fieldError=ref(''), fieldsLoading=ref(false), notices=ref([])
const keyword=ref(''), sourceFilter=ref(''), statusFilter=ref(''), tableKeyword=ref(''), fieldKeyword=ref(''), page=ref(1), pageSize=ref(10)
const tablePage=ref(1), tablePageSize=ref(10), fieldPage=ref(1), fieldPageSize=ref(10)
let readVersion=0, selectionVersion=0, fieldVersion=0, active=true
const states=[{value:'PENDING',label:'待执行'},{value:'RUNNING',label:'执行中'},{value:'SUCCESS',label:'成功'},{value:'FAILED',label:'失败'}]
const visibleTasks=computed(()=>filterScanTasks(tasks.value,{keyword:keyword.value,source_id:sourceFilter.value,status:statusFilter.value}))
const taskPage=computed(()=>catalogPage(visibleTasks.value,page.value,pageSize.value))
const visibleTables=computed(()=>filterMetadataRows(tables.value,tableKeyword.value,['table_name','schema_name','table_comment']))
const visibleFields=computed(()=>filterMetadataRows(fields.value,fieldKeyword.value,['field_name','column_type','data_type','technical_comment']))
const pagedTables=computed(()=>catalogPage(visibleTables.value,tablePage.value,tablePageSize.value))
const pagedFields=computed(()=>catalogPage(visibleFields.value,fieldPage.value,fieldPageSize.value))
const canScan=computed(()=>!loading.value && !scanning.value && !sourceError.value && !scanSourceError(sources.value.find(s=>s.source_id===selectedSource.value),demo.value))
watch([keyword,sourceFilter,statusFilter,pageSize],()=>{page.value=1})
watch([tableKeyword,tablePageSize],()=>{tablePage.value=1})
watch([fieldKeyword,fieldPageSize],()=>{fieldPage.value=1})
function resetFilters(){keyword.value='';sourceFilter.value='';statusFilter.value='';page.value=1}
function clearSelection(){
  ++selectionVersion;++fieldVersion
  selected.value=null;tables.value=[];fields.value=[];drawer.value=false;selectedTable.value=null;fieldTask.value=null
  tableError.value='';fieldError.value='';resultsLoading.value=false;fieldsLoading.value=false;tablesDemo.value=false
  tableKeyword.value='';fieldKeyword.value=''
  tablePage.value=1;fieldPage.value=1
}
async function load(preferredId){
  if(!active)return
  const targetId=typeof preferredId==='string'?preferredId:selected.value?.task_id || (typeof route.query.task==='string'?route.query.task:'')
  const version=++readVersion
  clearSelection();selectionError.value='';loading.value=true
  try{
    const result=await hub.scanCatalog(route.query.task)
    if(version!==readVersion)return
    tasks.value=result.tasks.rows;sources.value=result.sources.rows;demo.value=result.demo
    taskError.value=result.tasks.error?result.tasks.notice:'';sourceError.value=result.sources.error?result.sources.notice:''
    notices.value=[['任务列表',result.tasks],['数据源选项',result.sources]].filter(([,data])=>!data.error&&!data.complete).map(([label,data])=>label+'：'+data.notice)
    if(!sources.value.some(s=>s.source_id===selectedSource.value))selectedSource.value=''
    if(taskError.value)return
    let task=targetId?tasks.value.find(t=>t.task_id===targetId):tasks.value[0]
    if(targetId&&!task){
      try{
        const direct=await hub.scanTask(targetId)
        if(version!==readVersion)return
        if(!direct.value || direct.value.task_id!==targetId || direct.demo!==demo.value)throw new Error('invalid task')
        task=direct.value;tasks.value=[task,...tasks.value]
        notices.value.push('指定任务不在本次列表范围内，已通过任务详情接口单独加载')
      }catch{if(version===readVersion)selectionError.value='指定扫描任务读取失败，请确认任务编号后刷新重试';return}
    }
    page.value=taskPage.value.page
    if(task)await select(task)
  }catch{
    if(version!==readVersion)return
    tasks.value=[];sources.value=[];demo.value=false;notices.value=[]
    taskError.value='扫描任务读取失败，请刷新重试';sourceError.value='数据源选项读取失败，请刷新重试'
  }finally{if(version===readVersion)loading.value=false}
}
async function select(task){
  if(!active || !task || (task._demo===true)!==demo.value)return
  clearSelection();selectionError.value='';selected.value=task
  const version=selectionVersion
  if(task.status!=='SUCCESS')return
  resultsLoading.value=true
  try{
    const result=await hub.tables(task.task_id)
    if(version===selectionVersion){tables.value=scanResultRows(result,task,demo.value);tablesDemo.value=result.demo}
  }catch{if(version===selectionVersion){tables.value=[];tableError.value='扫描结果加载失败或归属不一致，请重试'}}
  finally{if(version===selectionVersion)resultsLoading.value=false}
}
async function showFields(table){
  const task=selected.value
  if(!active || resultsLoading.value || tableError.value || scanTableError(table,task,tables.value,demo.value))return
  const version=++fieldVersion
  fields.value=[];fieldError.value='';fieldKeyword.value='';fieldPage.value=1;selectedTable.value=table;fieldTask.value=task;drawer.value=true;fieldsLoading.value=true
  try{
    const result=await hub.fieldsForTask(task.task_id,table.table_name)
    if(version!==fieldVersion||selected.value?.task_id!==task.task_id)return
    fields.value=scanResultRows(result,task,demo.value,table)
  }catch{if(version===fieldVersion){fields.value=[];fieldError.value='字段加载失败或归属不一致，请重试'}}
  finally{if(version===fieldVersion)fieldsLoading.value=false}
}
function closeFields(){++fieldVersion;fieldsLoading.value=false}
async function scan(){
  if(!active || scanning.value || loading.value)return
  const source=sources.value.find(s=>s.source_id===selectedSource.value), invalid=scanSourceError(source,demo.value)
  if(sourceError.value || invalid)return ElMessage.warning(sourceError.value || invalid)
  const version=selectionVersion, mode=demo.value
  scanning.value=true
  try{
    const result=await hub.startScan(source)
    if(!active || version!==selectionVersion)return
    const task=scanResponseTask(result,source,mode)
    if(task.status==='FAILED')ElMessage.error(task.failure_reason||'扫描失败')
    else if(task.status==='SUCCESS')ElMessage.success(result.demo?'演示扫描完成':'扫描完成')
    else ElMessage.info('扫描任务已提交，请刷新任务查看结果')
    await load(task.task_id)
  }catch(e){if(active && version===selectionVersion)ElMessage.error(e.code==='ECONNABORTED'||e.code==='ERR_NETWORK'?'扫描请求结果尚未确认，请刷新任务检查，不要重复发起扫描':e.response?.data?.message || (e.message?.startsWith('扫描响应')?e.message:'扫描请求失败，请检查后端服务'))}
  finally{scanning.value=false}
}
function create(table,task=selected.value){
  if(!active)return
  const invalid=resultsLoading.value||tableError.value||task?.task_id!==selected.value?.task_id || scanTableError(table,task,tables.value,demo.value)
  if(invalid){ElMessage.warning('请先选择当前任务中已读取的成功扫描结果');return}
  router.push({path:'/resources',query:{fromTable:table.table_name,schema:table.schema_name,source:task.source_id,sourceName:task.source_name,task:task.task_id}})
}
async function removeTask(task){
  if(loading.value||scanning.value||deleting.value||['PENDING','RUNNING'].includes(task.status))return
  try{
    await ElMessageBox.confirm(`确认删除扫描任务“${task.task_id}”？已用于创建资源的任务不能删除。`,'删除扫描任务',{confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'})
    deleting.value=task.task_id
    await hub.deleteTask(task.task_id,task._demo===true)
    if(!active)return
    ElMessage.success('扫描任务已删除');await load(selected.value?.task_id===task.task_id?'':undefined)
  }catch(e){if(e!=='cancel'&&e!=='close'&&active)ElMessage.error(e.response?.data?.message||e.message||'扫描任务删除失败')}
  finally{deleting.value=''}
}
onMounted(()=>load())
watch(()=>route.query.task,()=>{page.value=1;load(typeof route.query.task==='string'?route.query.task:'')})
onBeforeUnmount(()=>{active=false;++readVersion;clearSelection()})
</script>
<template><div class="stack">
  <div class="page-heading"><div><h1>元数据扫描</h1></div><div class="scan-actions"><el-select v-model="selectedSource" filterable placeholder="选择已启用的数据源" style="width:240px" :disabled="loading||scanning||!!sourceError"><el-option v-for="s in sources" :key="s.source_id" :value="s.source_id" :label="s.source_name" :disabled="s.status!=='ENABLED'" /></el-select><el-button type="primary" :loading="scanning" :disabled="!canScan" @click="scan">开始扫描</el-button><el-button :loading="loading" :disabled="scanning" @click="load()">刷新任务</el-button></div></div>
  <DemoNotice :show="demo||tablesDemo" />
  <el-alert v-if="taskError" :title="taskError" type="error" :closable="false" show-icon data-testid="scan-task-error" />
  <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" show-icon data-testid="scan-source-error" />
  <el-alert v-if="notices.length" :title="notices.join('；')" type="warning" :closable="false" show-icon />
  <div class="grid-2 scan-workspace">
    <div class="panel"><div class="panel-title">扫描任务 <span class="panel-sub" data-testid="scan-task-count">已读取 {{ tasks.length }} 项 · 匹配 {{ visibleTasks.length }} 项</span></div>
      <el-form label-position="top" class="task-filters">
        <el-form-item label="任务关键词" class="task-keyword"><el-input v-model="keyword" clearable maxlength="200" placeholder="任务编号、来源名称或失败原因" /></el-form-item>
        <el-form-item label="任务来源"><el-select v-model="sourceFilter" clearable filterable placeholder="全部来源"><el-option v-for="s in sources" :key="s.source_id" :value="s.source_id" :label="s.source_name" /></el-select></el-form-item>
        <el-form-item label="任务状态"><el-select v-model="statusFilter" clearable placeholder="全部状态"><el-option v-for="s in states" :key="s.value" :value="s.value" :label="s.label" /></el-select></el-form-item>
      </el-form><div class="task-tools"><span>空格分隔的关键词需同时匹配</span><el-button link type="primary" @click="resetFilters">重置筛选</el-button></div>
      <div v-if="!taskError" v-loading="loading" class="task-scroll">
      <div v-for="task in taskPage.rows" :key="task.task_id" :data-task-id="task.task_id" @click="select(task)" :class="['task-card',{active:selected?.task_id===task.task_id}]">
        <div style="display:flex;justify-content:space-between;gap:5px"><strong>{{ task.source_name || task.source_id }}</strong><StatusTag :value="task.status" /></div>
        <div class="id-text task-id">扫描任务编号：{{ task.task_id }}</div>

        <div class="task-card-actions"><el-button link type="primary" @click.stop="select(task)">查看扫描结果</el-button><el-button link type="danger" :loading="deleting===task.task_id" :disabled="!!deleting||['PENDING','RUNNING'].includes(task.status)" @click.stop="removeTask(task)">删除任务</el-button></div>
        <p v-if="task.status==='FAILED'" class="task-failure">{{ task.failure_reason || '扫描失败，请查看任务并检查连接权限' }}</p>
      </div><div v-if="!visibleTasks.length" class="empty-hint">{{ tasks.length?'没有符合条件的任务':'暂无扫描任务，请选择数据源开始扫描' }}</div>
      </div><el-pagination v-if="!taskError" class="task-pager" :current-page="taskPage.page" v-model:page-size="pageSize" :page-sizes="resourcePageSizes" :total="visibleTasks.length" layout="sizes,prev,pager,next" :pager-count="5" :disabled="loading" @current-change="page=$event" />
    </div>
    <div class="panel scan-results"><div class="panel-title">扫描结果 <span class="panel-sub">{{ selected?.source_name || selected?.source_id || '请选择任务' }}</span></div>
      <el-alert v-if="selectionError" :title="selectionError" type="error" :closable="false" show-icon data-testid="scan-selection-error" />
      <el-descriptions v-if="selected" :column="2" border class="selected-summary"><el-descriptions-item label="任务编号" :span="2"><span class="id-text">{{ selected.task_id }}</span></el-descriptions-item><el-descriptions-item label="任务状态"><StatusTag :value="selected.status" /></el-descriptions-item><el-descriptions-item label="发现表／字段">{{ selected.table_count ?? '—' }} / {{ selected.field_count ?? '—' }}</el-descriptions-item></el-descriptions>
      <el-alert v-if="selected?.status==='FAILED'" :title="selected.failure_reason || '扫描失败，请检查连接和账号权限'" type="error" :closable="false" style="margin-bottom:16px" />
      <el-alert v-if="selected && ['PENDING','RUNNING'].includes(selected.status)" title="任务尚未完成，请刷新任务查看最新状态" type="info" :closable="false" show-icon />
      <div v-if="selected?.status==='SUCCESS'" class="result-tools"><el-input v-model="tableKeyword" clearable placeholder="搜索表名、数据库模式或表说明" aria-label="扫描表关键词" /><el-button :loading="resultsLoading" @click="select(selected)">重新加载结果</el-button></div>
      <el-alert v-if="tableError" :title="tableError" type="error" :closable="false" show-icon data-testid="scan-table-error" />
      <p v-if="selected?.status==='SUCCESS'&&!tableError&&!resultsLoading" class="dim" data-testid="scan-table-count">已读取 {{ tables.length }} 张表 · 匹配 {{ visibleTables.length }} 张</p>
      <el-table v-if="!tableError" :data="pagedTables.rows" v-loading="resultsLoading" empty-text="暂无符合条件的扫描结果" data-testid="scan-table-list">
        <el-table-column prop="table_name" label="表名" min-width="150" /><el-table-column prop="table_comment" label="表说明" min-width="155" /><el-table-column prop="field_count" label="字段数量" width="95" /><el-table-column prop="estimated_rows" label="估算记录数" width="105" />
        <el-table-column label="扫描状态" width="100"><template #default><StatusTag :value="selected?.status || 'SUCCESS'" /></template></el-table-column>
        <el-table-column label="操作" min-width="160"><template #default="{row}"><el-button link type="primary" :disabled="resultsLoading||selected?.status!=='SUCCESS'" @click="showFields(row)">查看字段</el-button><el-button link type="primary" :disabled="resultsLoading||selected?.status!=='SUCCESS'" @click="create(row)">创建资源</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-if="selected?.status==='SUCCESS'&&!tableError" class="task-pager" data-testid="scan-table-pager" :current-page="pagedTables.page" v-model:page-size="tablePageSize" :page-sizes="resourcePageSizes" :total="visibleTables.length" layout="sizes,prev,pager,next" :pager-count="5" :disabled="resultsLoading" @current-change="tablePage=$event" />

    </div>
  </div>
  <el-drawer v-model="drawer" :title="`${selectedTable?.table_name || ''} · 字段详情`" :size="1080" style="max-width:95vw" @close="closeFields">

    <p v-if="!fieldError&&!fieldsLoading" class="dim" data-testid="scan-field-count">已读取 {{ fields.length }} 个字段 · 匹配 {{ visibleFields.length }} 个</p>
    <div class="result-tools"><el-input v-model="fieldKeyword" clearable placeholder="搜索字段名、类型或注释" aria-label="扫描字段关键词" /><el-button :loading="fieldsLoading" @click="showFields(selectedTable)">重新加载字段</el-button></div>
    <el-alert v-if="fieldError" :title="fieldError" type="error" :closable="false" show-icon data-testid="scan-field-error" />
    <el-table v-if="!fieldError" :data="pagedFields.rows" v-loading="fieldsLoading" empty-text="暂无符合条件的字段" data-testid="scan-field-list"><el-table-column prop="field_name" label="字段名称" min-width="145" /><el-table-column label="字段类型" min-width="145"><template #default="{row}">{{ row.column_type || row.data_type }}</template></el-table-column><el-table-column label="是否主键" width="95"><template #default="{row}">{{ row.primary_key?'是':'否' }}</template></el-table-column><el-table-column label="是否允许为空" width="120"><template #default="{row}">{{ row.nullable?'是':'否' }}</template></el-table-column><el-table-column prop="default_value" label="默认值" width="95" /><el-table-column prop="technical_comment" label="字段说明" min-width="185" /><el-table-column label="字段顺序" width="100"><template #default="{row,$index}">{{ row.ordinal_position || fields.indexOf(row)+1 }}</template></el-table-column></el-table>
    <el-pagination v-if="!fieldError" class="task-pager" data-testid="scan-field-pager" :current-page="pagedFields.page" v-model:page-size="fieldPageSize" :page-sizes="resourcePageSizes" :total="visibleFields.length" layout="sizes,prev,pager,next" :pager-count="5" :disabled="fieldsLoading" @current-change="fieldPage=$event" />
    <template #footer><el-button type="primary" :disabled="fieldsLoading||!!fieldError||fieldTask?.status!=='SUCCESS'" @click="create(selectedTable,fieldTask)">从此表创建资源</el-button></template>
  </el-drawer>
</div></template>
<style scoped>
.scan-actions { display:flex;gap:8px;flex-wrap:wrap; }
.scan-workspace { grid-template-columns:minmax(380px,.85fr) minmax(0,1.5fr);align-items:start; }
.task-filters { display:grid;grid-template-columns:1fr 1fr;gap:12px; }
.task-keyword { grid-column:span 2; }
.task-filters .el-form-item { margin-bottom:0; }
.task-tools { display:flex;justify-content:space-between;align-items:center;margin:12px 0;font-size:12px;color:#718497; }
.task-scroll { max-height:620px;overflow:auto; }
.task-card { cursor:pointer;border:1px solid #e8eef2;border-radius:10px;padding:14px;margin-bottom:10px; }
.task-card.active { border-color:#79bcc7;background:#f1f9fa; }
.task-id { margin:7px 0;overflow-wrap:anywhere; }
.task-failure { font-size:12px;color:#b95151;line-height:1.6;margin:6px 0 0; }
.task-card-actions { display:flex;justify-content:space-between;align-items:center; }
.task-pager { margin-top:16px;flex-wrap:wrap;gap:6px; }
.selected-summary { margin-bottom:16px;overflow-wrap:anywhere; }
.result-tools { display:flex;gap:12px;margin:16px 0; }
.scan-results { min-width:0; }
@media(max-width:1100px) { .scan-workspace { grid-template-columns:1fr; } }
</style>
