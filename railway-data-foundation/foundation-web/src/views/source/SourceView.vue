<script setup>
import { session, explicitDemo } from '../../utils/sessionState'
import { subjectApi } from '../../api/subject'
const owners=ref([])
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hub } from '../../utils/dataHub'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { displayTime } from '../../utils/time'
import { filterSources, validateSource, sourceSaveScope } from '../../utils/sourceCatalog'
import { catalogPage, resourcePageSizes } from '../../utils/resourceCatalog'
import { isDemoSource } from '../../mock/demoData'
import SourceOverviewDrawer from '../../components/SourceOverviewDrawer.vue'
import { sourceOverviewInfo } from '../../utils/sourceOverview'
const router=useRouter()
const rows=ref([]), demo=ref(false), loading=ref(false), dialog=ref(false), saving=ref(false), editingId=ref('')
const overviewOpen=ref(false), overviewSource=ref(null)
const checked=reactive({}), busy=reactive({})
const listError=ref(''), notice=ref(''), formDemo=ref(false), keyword=ref(''), typeFilter=ref(''), statusFilter=ref(''), page=ref(1), pageSize=ref(10)
let readVersion=0,active=true
const blank=()=>({provider_subject_id:null,source_name:'',source_type:'MYSQL',description:'',host:'',port:3306,database_name:'',username:'',password:'',ssl_mode:'PREFERRED',status:'ENABLED',connection_mode:'DYNAMIC'})
const form=reactive(blank())
const types={MYSQL:'MySQL数据库',ORACLE:'Oracle数据库',POSTGRESQL:'PostgreSQL数据库'}
const visible=computed(()=>filterSources(rows.value,{keyword:keyword.value,source_type:typeFilter.value,status:statusFilter.value}))
const paged=computed(()=>catalogPage(visible.value,page.value,pageSize.value))
const hasBusy=computed(()=>Object.values(busy).some(Boolean))
const canManage=computed(()=>!loading.value&&!saving.value&&!hasBusy.value&&!listError.value)
const formScopeError=computed(()=>sourceSaveScope(editingId.value,rows.value,formDemo.value))
watch([keyword,typeFilter,statusFilter,pageSize],()=>{page.value=1})
function resetFilters(){keyword.value='';typeFilter.value='';statusFilter.value='';page.value=1}
function clearChecks(){Object.keys(checked).forEach(key=>delete checked[key])}
function operationError(error,fallback,uncertain=false){
  if(uncertain&&(error.code==='ECONNABORTED'||error.code==='ERR_NETWORK'))return '操作结果尚未确认，请刷新列表或任务检查实际状态，不要重复提交'
  return error.response?.data?.message||fallback
}
async function load(){
  const version=++readVersion
  loading.value=true;clearChecks()
  try{
    const result=await hub.sourceCatalog()
    if(version!==readVersion)return
    rows.value=result.rows;demo.value=result.demo;listError.value=result.error?result.notice:'';notice.value=!result.error&&!result.complete?result.notice:''
    if(overviewSource.value){
      const current=rows.value.find(row=>row.source_id===overviewSource.value.source_id)
      if(current)overviewSource.value=sourceOverviewInfo(current)
      else {overviewOpen.value=false;overviewSource.value=null}
    }
    page.value=paged.value.page
  }catch{
    if(version!==readVersion)return
    rows.value=[];demo.value=false;notice.value='';listError.value='数据源列表读取失败，请刷新重试'
    overviewOpen.value=false;overviewSource.value=null
  }finally{if(version===readVersion)loading.value=false}
}
function open(row){
  if(!canManage.value)return
  Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,blank(),row||{},{password:''})
  editingId.value=row?.source_id||'';formDemo.value=row?isDemoSource(row.source_id):demo.value;dialog.value=true
}
function openOverview(row){
  if(!active || !canManage.value || sourceSaveScope(row.source_id,rows.value,demo.value))return
  overviewSource.value=sourceOverviewInfo(row);overviewOpen.value=true
}
function closeForm(){if(!saving.value)form.password=''}
async function save(andTest=false){
  if(saving.value||loading.value||hasBusy.value||listError.value)return
  const invalid=formScopeError.value||validateSource(form,editingId.value)
  if(invalid)return ElMessage.warning(invalid)
  saving.value=true
  try{
  const payload={provider_subject_id:form.provider_subject_id||null,source_name:form.source_name.trim(),source_type:form.source_type,description:form.description,
    host:form.host.trim(),port:Number(form.port),database_name:form.database_name.trim(),username:form.username.trim(),ssl_mode:form.ssl_mode,status:form.status}
  if(form.password)payload.password=form.password
  const result=await hub.saveSource(payload,editingId.value||undefined,formDemo.value)
  if(!active)return
  form.password='';dialog.value=false;await load();if(editingId.value)delete checked[editingId.value]
  ElMessage.success(result.demo?'演示配置已保存到当前浏览器，密码未保存':'数据源配置已保存')
  // Saved successfully; a later connection error must not imply the save failed.
  if(andTest)await test(rowForTest(result),true)
  }catch(e){if(active)ElMessage.error(operationError(e,'数据源保存请求失败，请检查后端服务后重试',true))}
  finally{saving.value=false}
}
function rowForTest(result){return {...result.value,_demo:result.demo}}
async function test(row,afterSave=false){
  if(busy[row.source_id]||loading.value||(!afterSave&&!canManage.value)||row.status!=='ENABLED')return
  busy[row.source_id]='test';delete checked[row.source_id]
  const version=readVersion
  try{
    const result=await hub.testSource(row)
    if(version!==readVersion)return
    checked[row.source_id]={...result.value,tested_at:new Date().toISOString()}
    if(result.value.connected)ElMessage.success(result.demo?'演示连接成功':'连接成功')
    else ElMessage.error(result.value.reason||'连接失败')
  }catch(e){
    if(version!==readVersion)return
    const reason=operationError(e,'连接测试请求失败，请检查后端服务')
    checked[row.source_id]={error:true,reason,tested_at:new Date().toISOString()};ElMessage.error(reason)
  }finally{delete busy[row.source_id]}
}
async function scan(row){
  if(!canManage.value||row.status!=='ENABLED')return
  busy[row.source_id]='scan'
  try{
    const result=await hub.startScan(row)
    if(!active)return
    if(result.value.status==='FAILED')ElMessage.error(result.value.failure_reason||'扫描失败')
    else ElMessage.success(result.demo?'演示扫描完成':'扫描完成')
    router.push({path:'/scans',query:{task:result.value.task_id}})
  }catch(e){if(active)ElMessage.error(operationError(e,'扫描请求失败，请检查后端服务后重试',true))}
  finally{delete busy[row.source_id]}
}
async function changeStatus(row){
  if(!canManage.value)return
  busy[row.source_id]='status'
  try{
    const status=row.status==='DISABLED'?'ENABLED':'DISABLED'
    await hub.sourceStatus(row.source_id,status,isDemoSource(row.source_id))
    if(!active)return
    await load()
    ElMessage.success(status==='ENABLED'?'数据源已启用':'数据源已停用')
  }catch(e){if(active)ElMessage.error(operationError(e,'启停请求失败，请检查后端服务',true))}
  finally{delete busy[row.source_id]}
}
async function removeSource(row){
  if(!canManage.value)return
  if(row.status!=='DISABLED')return ElMessage.warning('请先停用数据源，再执行删除')
  try{
    await ElMessageBox.confirm(`确认删除数据源“${row.source_name}”？仅无扫描历史的数据源可删除。`,'删除数据源',{confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'})
    busy[row.source_id]='delete'
    await hub.deleteSource(row.source_id,isDemoSource(row.source_id))
    if(!active)return
    ElMessage.success('数据源已删除');await load()
  }catch(e){if(e!=='cancel'&&e!=='close'&&active)ElMessage.error(operationError(e,'数据源删除失败，请检查是否存在扫描记录',true))}
  finally{delete busy[row.source_id]}
}
function moreAction(row,command){
  if(command==='edit')open(row)
  else if(command==='status')changeStatus(row)
  else if(command==='delete')removeSource(row)
}
onMounted(()=>{load();if(session.user?.role==='ADMIN'&&!explicitDemo)subjectApi.list({limit:200,offset:0}).then(data=>owners.value=data.filter(row=>row.subject_type==='PROVIDER'&&row.status==='ENABLED')).catch(()=>{})})
onBeforeUnmount(()=>{active=false;++readVersion;form.password=''})
</script>
<template>
  <div class="stack">
    <div class="page-heading"><div><h1>数据源管理</h1></div><div class="source-actions"><el-button :loading="loading" :disabled="saving||hasBusy" @click="load">刷新列表</el-button><el-button type="primary" :disabled="!canManage" @click="open()">＋ 新增数据源</el-button></div></div>
    <DemoNotice :show="demo" />
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon data-testid="source-list-error" />
    <el-alert v-if="notice" :title="notice" type="warning" :closable="false" show-icon />
    <div class="panel"><div class="panel-title">接入数据源 <span class="panel-sub" data-testid="source-count">{{ listError?'读取失败':'已读取 '+rows.length+' 项 · 匹配 '+visible.length+' 项' }}</span></div>
      <el-form label-position="top" class="source-filters"><el-form-item label="数据源关键词"><el-input v-model="keyword" clearable maxlength="200" placeholder="名称、编号、地址、数据库或备注" /></el-form-item><el-form-item label="类型筛选"><el-select v-model="typeFilter" clearable placeholder="全部类型"><el-option v-for="(label,value) in types" :key="value" :label="label" :value="value" /></el-select></el-form-item><el-form-item label="状态筛选"><el-select v-model="statusFilter" clearable placeholder="全部状态"><el-option label="已启用" value="ENABLED" /><el-option label="已停用" value="DISABLED" /></el-select></el-form-item><el-button @click="resetFilters">重置筛选</el-button></el-form>

      <el-table v-if="!listError" :data="paged.rows" v-loading="loading" empty-text="暂无符合条件的数据源" style="width:100%" data-testid="source-table">
        <el-table-column prop="source_name" label="数据源名称" min-width="170" fixed="left" />
        <el-table-column label="数据源编号" min-width="175" show-overflow-tooltip><template #default="{row}"><span class="id-text">{{ row.source_id }}</span></template></el-table-column>
        <el-table-column label="数据源类型" width="130"><template #default="{row}">{{ types[row.source_type] || row.source_type }}</template></el-table-column>
        <el-table-column prop="host" label="数据库地址" min-width="135" />
        <el-table-column prop="port" label="端口" width="80" />
        <el-table-column prop="database_name" label="数据库名称" min-width="145" />
        <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
        <el-table-column label="最近更新时间" min-width="165"><template #default="{row}">{{ displayTime(row.updated_at) }}</template></el-table-column>
        <el-table-column label="操作" width="270" fixed="right"><template #default="{row}"><div class="table-actions">
          <el-button link type="primary" :disabled="!canManage" @click="openOverview(row)">查看</el-button>
          <el-button link type="primary" :loading="busy[row.source_id]==='test'" :disabled="!canManage || row.status!=='ENABLED'" @click="test(row)">测试连接</el-button>
          <el-button link type="primary" :loading="busy[row.source_id]==='scan'" :disabled="!canManage || row.status!=='ENABLED'" @click="scan(row)">开始扫描</el-button>
          <el-dropdown :disabled="!canManage" @command="moreAction(row,$event)"><el-button link type="primary">更多操作⌄</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="edit">编辑配置</el-dropdown-item><el-dropdown-item command="status">{{ row.status==='DISABLED'?'启用数据源':'停用数据源' }}</el-dropdown-item><el-dropdown-item command="delete" :disabled="row.status!=='DISABLED'" divided>删除数据源</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div></template></el-table-column>
      </el-table>
      <div v-if="!listError" class="source-pagination"><el-pagination :current-page="paged.page" v-model:page-size="pageSize" :page-sizes="resourcePageSizes" :total="visible.length" layout="sizes,prev,pager,next,jumper" :disabled="loading||saving||hasBusy" @current-change="page=$event" /></div>
    </div>
    <div v-for="row in rows.filter(r=>checked[r.source_id])" :key="row.source_id" class="panel" style="padding:18px 20px">
      <div class="panel-title">{{ row.source_name }} · 连接测试 <span class="panel-sub">{{ displayTime(checked[row.source_id].tested_at) }}</span></div>
      <el-descriptions :column="4" border><el-descriptions-item label="连接状态"><el-tag :type="checked[row.source_id].connected?'success':'danger'">{{ checked[row.source_id].error?'请求失败':checked[row.source_id].connected?'连接成功':'连接失败' }}</el-tag></el-descriptions-item><el-descriptions-item label="数据库类型">{{ checked[row.source_id].database_product || '—' }}</el-descriptions-item><el-descriptions-item label="数据库版本">{{ checked[row.source_id].database_version || '—' }}</el-descriptions-item><el-descriptions-item label="响应时间">{{ checked[row.source_id].response_time_ms == null?'—':checked[row.source_id].response_time_ms+' ms' }}</el-descriptions-item></el-descriptions>
      <p v-if="!checked[row.source_id].connected" style="color:#c45656;margin-bottom:0">失败原因：{{ checked[row.source_id].reason }}</p>
    </div>
    <SourceOverviewDrawer v-model="overviewOpen" :source="overviewSource" />
    <el-dialog v-model="dialog" :title="editingId?'编辑数据源':'新增数据源'" width="720px" :show-close="!saving" :close-on-press-escape="!saving" :close-on-click-modal="false" @close="closeForm">
      <DemoNotice :show="formDemo" />
      <el-alert v-if="formScopeError||listError" :title="formScopeError||listError" type="warning" :closable="false" show-icon class="form-notice" />
      <el-alert v-if="form.connection_mode==='ENV_REFERENCE'" type="info" :closable="false" title="保存将转为动态配置，请重新填写数据库密码。" style="margin-bottom:16px" />
      <el-form label-position="top" :model="form" :disabled="saving">
        <h3>基本信息</h3><div class="grid-2">
          <el-form-item v-if="session.user?.role==='ADMIN'" label="所属提供方"><el-select v-model="form.provider_subject_id" :disabled="!!editingId" clearable placeholder="未选择时由管理员管理"><el-option v-for="owner in owners" :key="owner.subject_id" :label="owner.subject_name" :value="owner.subject_id" /></el-select></el-form-item>
          <el-form-item label="数据源名称" required><el-input v-model="form.source_name" maxlength="120" placeholder="例如：广州港业务数据库" /></el-form-item>
          <el-form-item label="数据源类型"><el-select v-model="form.source_type" style="width:100%"><el-option label="MySQL数据库" value="MYSQL" /></el-select></el-form-item>
        </div><el-form-item label="备注说明"><el-input v-model="form.description" maxlength="1000" type="textarea" :rows="2" placeholder="例如：广州港海铁联运业务数据源" /></el-form-item>
        <h3>连接信息</h3><div class="grid-2">
          <el-form-item label="数据库地址" required><el-input v-model="form.host" placeholder="请输入服务器 IP 或域名，例如 192.168.1.20" /></el-form-item>
          <el-form-item label="端口" required><el-input-number v-model="form.port" :min="1" :max="65535" :precision="0" style="width:100%" /></el-form-item>
          <el-form-item label="数据库名称" required><el-input v-model="form.database_name" placeholder="例如：port_source_db" /></el-form-item>
          <el-form-item label="数据库用户名" required><el-input v-model="form.username" maxlength="128" placeholder="建议使用只读账号，不建议使用root" /></el-form-item>
          <el-form-item label="数据库密码" :required="!editingId || form.connection_mode==='ENV_REFERENCE'"><el-input v-model="form.password" maxlength="1024" type="password" autocomplete="new-password" :placeholder="editingId && form.connection_mode!=='ENV_REFERENCE'?'密码已配置，无需修改可留空':'请输入数据库密码'" /></el-form-item>
          <el-form-item label="加密连接"><el-select v-model="form.ssl_mode" style="width:100%"><el-option label="优先加密（默认）" value="PREFERRED" /><el-option label="必须加密" value="REQUIRED" /><el-option label="验证证书与主机" value="VERIFY_IDENTITY" /><el-option label="验证证书" value="VERIFY_CA" /><el-option label="不加密（仅本机实验）" value="DISABLED" /></el-select></el-form-item>
        </div>


      </el-form>
      <template #footer><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button :loading="saving" @click="save(true)" :disabled="!!formScopeError||!!listError||form.status==='DISABLED'">保存并测试连接</el-button><el-button type="primary" :loading="saving" :disabled="!!formScopeError||!!listError" @click="save(false)">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.source-actions { display:flex;gap:8px; }
.source-filters { display:grid;grid-template-columns:minmax(240px,2fr) repeat(2,minmax(150px,1fr)) auto;gap:12px;align-items:end;margin-bottom:20px; }
.source-filters .el-form-item { margin-bottom:0; }
.source-pagination { display:flex;justify-content:flex-end;gap:12px;align-items:center;flex-wrap:wrap;margin-top:20px;font-size:12px;color:#718497; }
.form-notice { margin-bottom:12px; }
@media(max-width:900px) { .source-filters { grid-template-columns:1fr 1fr; } }
@media(max-width:760px) { .source-filters { grid-template-columns:1fr; } }
</style>
