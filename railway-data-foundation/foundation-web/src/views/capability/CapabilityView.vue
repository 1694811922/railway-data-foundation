<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { session,explicitDemo } from '../../utils/sessionState'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hub } from '../../utils/dataHub'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { capabilitySupport, validateCapability, invocationCommand } from '../../utils/capabilityForm'
import { filterCapabilities, capabilitySaveScope } from '../../utils/capabilityCatalog'
import { catalogPage, resourcePageSizes } from '../../utils/resourceCatalog'
import { isDemoCapability } from '../../mock/demoData'
import { capabilityActionReason, capabilityEditScope, capabilityPayload, capabilityRowScope, supportedRest, supportedKafka, kafkaRuntimeMatches } from '../../utils/capabilityWorkflow'

const route = useRoute()
const router = useRouter()
const rows = ref([]), resources = ref([]), demo = ref(false)
const dialog = ref(false), detail = ref(null), runtime = ref(null), editId = ref('')
const saving = ref(false), busy = ref(''), filter = ref(typeof route.query.resource==='string'?route.query.resource:'')
const loading=ref(false), capabilityError=ref(''), resourceError=ref(''), notices=ref([])
const formDemo=ref(false), detailDemo=ref(false), keyword=ref(''), typeFilter=ref(''), statusFilter=ref(''), healthFilter=ref('')
const page=ref(1), pageSize=ref(10)
let readVersion=0, guideVersion=0, contextVersion=0, active=true, editScope=null
const contextCurrent=version=>active && version===contextVersion
const guideLoading = ref(false), guideError = ref(''), commandInput = ref(null)
const types = [
  { value: 'REST_API', label: 'REST 接口' },
  { value: 'KAFKA_TOPIC', label: 'Kafka 消息主题' },
  { value: 'CDC_STREAM', label: 'CDC 变化流' },
  { value: 'READONLY_QUERY', label: '只读查询' },
]
const authOptions = [
  { value: 'NONE', label: '无认证' }, { value: 'TOKEN', label: '令牌认证' },
  { value: 'BASIC', label: '用户名密码认证' }, { value: 'SASL', label: 'SASL 认证' },
]
const blank = () => ({
  capability_name: '', resource_id: filter.value || '', type: 'REST_API',
  http_method: 'GET', endpoint: '', topic: '', source_table: '', protocol: 'HTTP',
  auth_type: 'NONE', message_schema: '', query_template_code: '', description: '',
})
const form = reactive(blank())
const formAuthOptions = computed(() => authOptions.filter(a =>
  (form.type === 'KAFKA_TOPIC' ? ['NONE', 'SASL'] : ['NONE', 'TOKEN', 'BASIC']).includes(a.value)))
const callCommand = computed(() => invocationCommand(runtime.value))
const visible = computed(() => filterCapabilities(rows.value,{keyword:keyword.value,resource_id:filter.value,type:typeFilter.value,status:statusFilter.value,health_status:healthFilter.value},resources.value))
const paged=computed(()=>catalogPage(visible.value,page.value,pageSize.value))
const selectableResources = computed(() => resources.value.filter(r => r.status === 'PUBLISHED' && (r._demo===true)===demo.value))
const canRegister=computed(()=>!loading.value && !busy.value && !saving.value && !resourceError.value && selectableResources.value.length>0)
const states=[{value:'DRAFT',label:'草稿'},{value:'VERIFIED',label:'已验证'},{value:'ENABLED',label:'已启用'},{value:'DISABLED',label:'已停用'},{value:'RETIRED',label:'已退役'}]
const healths=[{value:'UNKNOWN',label:'未知'},{value:'UP',label:'正常'},{value:'DOWN',label:'异常'},{value:'DEGRADED',label:'降级'}]
const formScopeError=computed(()=>capabilitySaveScope(form,resources.value,formDemo.value))
watch([filter,keyword,typeFilter,statusFilter,healthFilter,pageSize],()=>{page.value=1})
function resetFilters(){keyword.value='';filter.value='';typeFilter.value='';statusFilter.value='';healthFilter.value='';page.value=1}
const actionReason=(row,name)=>capabilityActionReason(row,name,isDemoCapability(row.capability_id))
const resourceName = id => resources.value.find(r => r.resource_id === id)?.resource_name || id
const errorMessage = error => error.response?.data?.message ||
  (error.code === 'ECONNABORTED' || error.code === 'ERR_NETWORK' ? '接口能力操作结果尚未确认，请刷新后检查实际状态，不要重复提交' :
    !error.response ? '接口能力操作请求失败，请检查后端服务' : error.message || '接口能力操作失败')

async function load(allowPending=false) {
  if((saving.value || busy.value) && allowPending!==true)return
  const version=++readVersion
  loading.value=true
  try {
    const result=await hub.capabilityCatalog(route.query.resource)
    if(!active || version!==readVersion)return
    rows.value=result.capabilities.rows;resources.value=result.resources.rows;demo.value=result.demo
    capabilityError.value=result.capabilities.error?result.capabilities.notice:''
    resourceError.value=result.resources.error?result.resources.notice:''
    notices.value=[['能力列表',result.capabilities],['资源选项',result.resources]].filter(([,data])=>!data.error&&!data.complete).map(([label,data])=>label+'：'+data.notice)
    page.value=paged.value.page
    // Refreshing the list also invalidates old status and invocation snapshots.
    if(detail.value){
      const latest=rows.value.find(row=>row.capability_id===detail.value.capability_id)
      ++guideVersion;runtime.value=null;guideError.value='';guideLoading.value=false
      if(latest)showDetail(latest)
      else detail.value=null
    }
  } catch {
    if(!active || version!==readVersion)return
    rows.value=[];resources.value=[];demo.value=false;notices.value=[]
    capabilityError.value='接口能力读取失败，请刷新重试';resourceError.value='所属资源读取失败，请刷新重试'
  } finally {if(active && version===readVersion)loading.value=false}
}
function open(row) {
  if(saving.value || busy.value || loading.value || resourceError.value)return
  if(!row && !canRegister.value)return ElMessage.warning('请先创建并发布资源，然后刷新资源列表')
  if(row){
    const invalid=capabilityRowScope(row,rows.value,demo.value)
    if(invalid || row.status!=='DRAFT')return ElMessage.warning(invalid || '只有草稿能力可编辑')
  }
  Object.assign(form, blank(), row || {})
  editId.value = row?.capability_id || ''
  formDemo.value=row?isDemoCapability(row.capability_id):demo.value
  editScope=row?{...row}:null
  dialog.value = true
}
function typeChanged(type) {
  form.protocol = { REST_API: 'HTTP', KAFKA_TOPIC: 'KAFKA', CDC_STREAM: 'DEBEZIUM', READONLY_QUERY: '' }[type]
  form.auth_type = { REST_API: 'NONE', KAFKA_TOPIC: 'NONE', CDC_STREAM: '', READONLY_QUERY: '' }[type]
  if (type === 'CDC_STREAM') form.source_table = resources.value.find(r => r.resource_id === form.resource_id)?.physical_table || ''
}
function fillExample() {
  if(saving.value)return
  const names = { REST_API: '集装箱事件查询接口', KAFKA_TOPIC: '集装箱实时事件主题', CDC_STREAM: '集装箱变化流', READONLY_QUERY: '集装箱事件查询模板' }
  if (!form.capability_name.trim()) form.capability_name = names[form.type]
  typeChanged(form.type)
  if (form.type === 'REST_API') { form.http_method = 'GET'; form.endpoint = '/containers/events' }
  if (form.type === 'KAFKA_TOPIC') form.topic = 'port.container.event.v1'
  if (form.type === 'CDC_STREAM') form.topic = 'port.container.event.cdc'
  if (form.type === 'READONLY_QUERY') form.query_template_code = 'QUERY_CONTAINER_EVENT'
}
async function save() {
  if(saving.value || busy.value || loading.value || !dialog.value || resourceError.value)return
  const scope=capabilitySaveScope(form,resources.value,formDemo.value) || capabilityEditScope(editScope,form,rows.value,formDemo.value)
  if(scope)return ElMessage.warning(scope)
  const invalid = validateCapability(form)
  if (invalid) return ElMessage.warning(invalid)
  saving.value = true
  const context=contextVersion,id=editId.value || undefined,mode=formDemo.value,body=capabilityPayload(form)
  try {
    const result = await hub.saveCapability(body, id, mode)
    if(!contextCurrent(context))return
    dialog.value = false
    ElMessage.success(result.demo ? '演示能力已保存' : '能力已保存')
    await load(true)
  } catch (error) { if(contextCurrent(context))ElMessage.error(errorMessage(error)) }
  finally { saving.value = false }
}
async function action(row, name) {
  if (busy.value || saving.value || loading.value) return
  const invalid=capabilityRowScope(row,rows.value,demo.value) || actionReason(row,name)
  if(invalid)return ElMessage.warning(invalid)
  const context=contextVersion
  busy.value = row.capability_id + ':' + name
  try {
    const result = await hub.capabilityAction(row.capability_id, name, isDemoCapability(row.capability_id))
    if(!contextCurrent(context))return
    if (name === 'verify' && result.value?.health_status !== 'UP') {
      ElMessage.warning(row.type === 'KAFKA_TOPIC' ? '消息主题验证未通过，请检查 Kafka 服务和主题' : '接口验证未通过，请检查提供服务与数据源')
    } else {
      const label = { verify: '验证', enable: '启用', disable: '停用' }[name]
      ElMessage.success(label + '完成' + (result.demo ? '（演示数据）' : ''))
    }
  } catch (error) { if(contextCurrent(context))ElMessage.error(errorMessage(error)) }
  finally { if(contextCurrent(context))await load(true); busy.value = '' }
}
async function removeCapability(row){
  if(busy.value||saving.value||loading.value)return
  if(row.status!=='DRAFT')return ElMessage.warning('仅草稿能力可以删除')
  const context=contextVersion
  try{
    await ElMessageBox.confirm(`确认删除草稿能力“${row.capability_name}”？`,'删除接口能力',{confirmButtonText:'确认删除',cancelButtonText:'取消',type:'warning'})
    busy.value=row.capability_id+':delete'
    await hub.deleteCapability(row.capability_id,isDemoCapability(row.capability_id))
    if(!contextCurrent(context))return
    ElMessage.success('草稿能力已删除');await load(true)
  }catch(error){if(error!=='cancel'&&error!=='close'&&contextCurrent(context))ElMessage.error(errorMessage(error))}
  finally{busy.value=''}
}
async function showDetail(row) {
  if(!active || capabilityRowScope(row,rows.value,demo.value))return
  const version=++guideVersion
  detail.value = row
  detailDemo.value=isDemoCapability(row.capability_id)
  runtime.value = null
  guideError.value = ''
  guideLoading.value = false
  if (supportedRest(row) || supportedKafka(row)) {
    guideLoading.value = true
    try {
      const result = await (supportedKafka(row)?hub.capabilityKafkaRuntime(row.capability_id, detailDemo.value):hub.capabilityInvocation(row.capability_id, detailDemo.value))
      if (active && version===guideVersion && detail.value?.capability_id === row.capability_id) {
        if(result.demo!==detailDemo.value || result.value?.capability_id!==row.capability_id)throw new Error('调用信息与能力不一致')
        if(supportedKafka(row)&&!kafkaRuntimeMatches(row,result.value))throw new Error('发送状态与能力不一致')
        runtime.value = result.value
      }
    } catch {
      if (active && version===guideVersion && detail.value?.capability_id === row.capability_id) guideError.value = supportedKafka(row)?'发送状态加载失败，请检查底座服务，或重试。':'调用信息加载失败，请检查底座服务与提供服务地址配置，或重试。'
    } finally {
      if (active && version===guideVersion && detail.value?.capability_id === row.capability_id) guideLoading.value = false
    }
  }
}
async function copyCommand() {
  try {
    await navigator.clipboard.writeText(callCommand.value)
    ElMessage.success('调用命令已复制，可粘贴到 VS Code 的 PowerShell 终端')
  } catch {
    commandInput.value?.focus()
    commandInput.value?.textarea?.select()
    ElMessage.info('浏览器未允许复制，已选中命令，请按 Ctrl+C')
  }
}
onMounted(async () => { const context=contextVersion;await load(); if (contextCurrent(context) && route.query.create === '1') open() })
watch(()=>[route.query.resource,route.query.create].join('|'),async()=>{
  const context=++contextVersion
  ++readVersion
  filter.value=typeof route.query.resource==='string'?route.query.resource:''
  detail.value=null;++guideVersion
  dialog.value=false;editScope=null
  await load(true)
  if(contextCurrent(context) && route.query.create==='1')open()
})
onBeforeUnmount(()=>{active=false;readVersion++;guideVersion++;contextVersion++;editScope=null})
</script>

<template>
  <div class="stack">
    <div class="page-heading">
      <div>

        <h1>接口能力管理</h1>

      </div>
      <div style="display:flex;gap:8px">
        <el-button :loading="loading" :disabled="!!busy || saving" @click="load">刷新列表</el-button>
        <el-button type="primary" :disabled="!canRegister" @click="open()">＋ 注册接口能力</el-button>
      </div>
    </div>
    <DemoNotice :show="demo" />
    <el-alert v-if="capabilityError" :title="capabilityError" type="error" :closable="false" show-icon data-testid="capability-list-error" />
    <el-alert v-if="resourceError" :title="resourceError" type="error" :closable="false" show-icon data-testid="capability-resource-error" />
    <el-alert v-if="notices.length" :title="notices.join('；')" type="warning" :closable="false" show-icon />


    <div class="panel">
      <div class="panel-title">能力列表 <span class="panel-sub" data-testid="capability-count">{{ capabilityError?'读取失败':'已读取 '+rows.length+' 项 · 匹配 '+visible.length+' 项' }}</span></div>
      <el-form label-position="top" class="capability-filters">
        <el-form-item label="关键词" class="keyword"><el-input v-model="keyword" clearable maxlength="200" placeholder="搜索能力名称、编号、资源或访问地址" /></el-form-item>
        <el-form-item label="关联资源"><el-select v-model="filter" clearable filterable placeholder="全部资源"><el-option v-for="r in resources" :key="r.resource_id" :label="r.resource_name" :value="r.resource_id" /></el-select></el-form-item>
        <el-form-item label="能力类型"><el-select v-model="typeFilter" clearable placeholder="全部类型"><el-option v-for="t in types" :key="t.value" :label="t.label" :value="t.value" /></el-select></el-form-item>
        <el-form-item label="能力状态"><el-select v-model="statusFilter" clearable placeholder="全部状态"><el-option v-for="s in states" :key="s.value" :label="s.label" :value="s.value" /></el-select></el-form-item>
        <el-form-item label="健康状态"><el-select v-model="healthFilter" clearable placeholder="全部健康状态"><el-option v-for="h in healths" :key="h.value" :label="h.label" :value="h.value" /></el-select></el-form-item>
        <el-button @click="resetFilters">重置筛选</el-button>
      </el-form>

      <el-table v-if="!capabilityError" :data="paged.rows" v-loading="loading" empty-text="暂无符合条件的接口能力" data-testid="capability-table">
        <el-table-column prop="capability_name" label="能力名称" min-width="200" fixed="left" show-overflow-tooltip />
        <el-table-column label="所属资源" min-width="190" show-overflow-tooltip>
          <template #default="{row}">{{ row.resource_name || resourceName(row.resource_id) }}</template>
        </el-table-column>
        <el-table-column label="能力类型" width="126"><template #default="{row}"><StatusTag :value="row.type" /></template></el-table-column>
        <el-table-column label="访问地址／主题" min-width="180" show-overflow-tooltip>
          <template #default="{row}">{{ row.endpoint || row.topic || row.source_table || row.query_template_code || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
        <el-table-column label="健康状态" width="90"><template #default="{row}"><StatusTag :value="row.health_status" /></template></el-table-column>
        <el-table-column prop="version" label="版本" width="65" />
        <el-table-column prop="capability_id" label="能力编号" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="270" fixed="right">
          <template #default="{row}">
            <div class="table-actions">
              <el-button link type="primary" @click="showDetail(row)">查看</el-button>
              <el-button v-if="session.user?.role==='PROVIDER'&&supportedKafka(row)&&row.status==='ENABLED'&&row.health_status==='UP'" link type="primary" :disabled="explicitDemo" @click="router.push({path:'/production',query:{capability:row.capability_id}})">发送数据</el-button>
              <el-button link type="primary" :disabled="!!busy || saving || loading || !!resourceError || row.status !== 'DRAFT'" @click="open(row)">编辑</el-button>
              <span :title="actionReason(row,'verify')"><el-button link type="primary" :loading="busy === row.capability_id + ':verify'" :disabled="!!busy || saving || loading || !!actionReason(row,'verify')" @click="action(row,'verify')">验证</el-button></span>
              <span v-if="row.status !== 'ENABLED'" :title="actionReason(row,'enable')"><el-button link type="primary" :loading="busy === row.capability_id + ':enable'" :disabled="!!busy || saving || loading || !!actionReason(row,'enable')" @click="action(row,'enable')">启用</el-button></span>
              <span v-else :title="actionReason(row,'disable')"><el-button link type="warning" :loading="busy === row.capability_id + ':disable'" :disabled="!!busy || saving || loading || !!actionReason(row,'disable')" @click="action(row,'disable')">停用</el-button></span>
              <el-button v-if="row.status==='DRAFT'" link type="danger" :loading="busy===row.capability_id+':delete'" :disabled="!!busy||saving||loading" @click="removeCapability(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!capabilityError" class="capability-pagination"><el-pagination :current-page="paged.page" v-model:page-size="pageSize" :page-sizes="resourcePageSizes" :total="visible.length" layout="sizes,prev,pager,next,jumper" :disabled="loading" @current-change="page=$event" /></div>
    </div>

    <el-dialog v-model="dialog" :title="editId ? '编辑接口能力' : '注册接口能力'" width="760px" :show-close="!saving" :close-on-press-escape="!saving" :close-on-click-modal="false">
      <DemoNotice :show="formDemo" />
      <el-alert v-if="formScopeError" :title="formScopeError" type="warning" :closable="false" show-icon class="form-notice" />

      <div class="form-toolbar"><el-button link type="primary" :disabled="saving" @click="fillExample">填入本机示例配置</el-button></div>
      <el-form label-position="top" :disabled="saving">
        <div class="grid-2">
          <el-form-item label="所属资源" required>
            <el-select v-model="form.resource_id" :disabled="!!editId" placeholder="选择已发布资源" style="width:100%" @change="typeChanged(form.type)">
              <el-option v-for="r in selectableResources" :key="r.resource_id" :label="r.resource_name" :value="r.resource_id" />
            </el-select>

          </el-form-item>
          <el-form-item label="能力类型">
            <el-select v-model="form.type" :disabled="!!editId" style="width:100%" @change="typeChanged">
              <el-option v-for="t in types" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="能力名称" required><el-input v-model="form.capability_name" maxlength="120" placeholder="例如：集装箱事件查询接口" /></el-form-item>
          <el-form-item v-if="form.type === 'REST_API'" label="请求方式">
            <el-select v-model="form.http_method" style="width:100%"><el-option label="GET（查询数据）" value="GET" /><el-option label="POST（提交请求）" value="POST" /></el-select>
          </el-form-item>
          <el-form-item v-if="form.type === 'REST_API'" label="接口路径" required><el-input v-model="form.endpoint" placeholder="/containers/events" /></el-form-item>
          <el-form-item v-if="['KAFKA_TOPIC','CDC_STREAM'].includes(form.type)" label="主题名称" required><el-input v-model="form.topic" placeholder="port.container.event.v1" /></el-form-item>
          <el-form-item v-if="form.type === 'CDC_STREAM'" label="来源表" required><el-input v-model="form.source_table" readonly placeholder="选择资源后自动带出" /></el-form-item>
          <el-form-item v-if="form.type === 'READONLY_QUERY'" label="查询模板代码" required><el-input v-model="form.query_template_code" placeholder="模板编号，如 QUERY_CONTAINER_EVENT" /></el-form-item>
          <el-form-item v-if="form.type !== 'READONLY_QUERY'" label="协议">
            <el-select v-if="form.type === 'REST_API'" v-model="form.protocol" style="width:100%"><el-option label="HTTP" value="HTTP" /><el-option label="HTTPS（加密传输）" value="HTTPS" /></el-select>
            <el-input v-else v-model="form.protocol" readonly />
          </el-form-item>
          <el-form-item v-if="['REST_API','KAFKA_TOPIC'].includes(form.type)" label="认证方式">
            <el-select v-model="form.auth_type" style="width:100%"><el-option v-for="a in formAuthOptions" :key="a.value" :label="a.label" :value="a.value" /></el-select>

          </el-form-item>
        </div>
        <el-form-item v-if="form.type === 'KAFKA_TOPIC'" label="消息结构"><el-input v-model="form.message_schema" type="textarea" :rows="2" maxlength="10000" placeholder="描述消息包含哪些字段及其类型，可填写 JSON 结构" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="1000" placeholder="说明用途、查询条件或消息更新方式，不填写密码或令牌" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!!formScopeError" @click="save">保存能力</el-button></template>
    </el-dialog>

    <el-dialog :model-value="!!detail" title="接口能力详情" width="900px" @close="detail=null;guideVersion++">
      <template v-if="detail">
        <DemoNotice :show="detailDemo" />
        <el-descriptions :column="2" border>
          <el-descriptions-item label="能力名称" :span="2">{{ detail.capability_name }}</el-descriptions-item>
          <el-descriptions-item label="能力编号" :span="2"><span class="id-text">{{ detail.capability_id }}</span></el-descriptions-item>
          <el-descriptions-item label="所属资源" :span="2">{{ detail.resource_name || resourceName(detail.resource_id) }}</el-descriptions-item>
          <el-descriptions-item label="能力类型"><StatusTag :value="detail.type" /></el-descriptions-item>
          <el-descriptions-item label="版本">V{{ detail.version }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :value="detail.status" /></el-descriptions-item>
          <el-descriptions-item label="健康状态"><StatusTag :value="detail.health_status" /></el-descriptions-item>
          <el-descriptions-item label="访问地址／主题" :span="2">{{ detail.endpoint || detail.topic || detail.query_template_code || '—' }}</el-descriptions-item>
          <el-descriptions-item label="协议">{{ detail.protocol || '—' }}</el-descriptions-item>
          <el-descriptions-item label="认证方式">{{ authOptions.find(a => a.value === detail.auth_type)?.label || '—' }}</el-descriptions-item>
          <el-descriptions-item label="说明" :span="2">{{ detail.description || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="runtime" :title="runtime.reason" :type="runtime.enabled ? 'success' : 'warning'" :closable="false" show-icon style="margin-top:16px" />
        <div v-if="supportedRest(detail)" class="runtime-guide">
          <div class="guide-heading"><strong>调用方式</strong><el-button link type="primary" :loading="guideLoading" @click="showDetail(detail)">刷新调用信息</el-button></div>
          <p v-if="guideLoading">正在读取后端提供服务配置…</p>
          <el-alert v-else-if="guideError" :title="guideError" type="error" :closable="false" show-icon />
          <template v-else-if="runtime">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="提供服务地址"><code>{{ runtime.provider_base_url }}</code></el-descriptions-item>
              <el-descriptions-item label="完整调用地址"><code>{{ runtime.http_method }} {{ runtime.request_url }}</code></el-descriptions-item>
              <el-descriptions-item label="能力请求头"><code>{{ runtime.header_name }}: {{ runtime.header_value }}</code></el-descriptions-item>
              <el-descriptions-item label="共享关系请求头"><code>X-Relationship-Id: 已确认启用的关系编号</code></el-descriptions-item>
              <el-descriptions-item label="调用身份请求头"><code>Authorization: Bearer 调用令牌</code></el-descriptions-item>
            </el-descriptions>

            <div class="guide-heading"><strong>PowerShell 调用示例</strong><el-button size="small" @click="copyCommand">复制调用命令</el-button></div>
            <el-input ref="commandInput" :model-value="callCommand" type="textarea" :rows="3" readonly aria-label="PowerShell 调用命令" class="command-input" />

          </template>
        </div>
        <div v-else-if="supportedKafka(detail)" class="runtime-guide">
          <div class="guide-heading"><strong>消息发送状态</strong><el-button link type="primary" :loading="guideLoading" @click="showDetail(detail)">刷新发送状态</el-button></div>
          <p v-if="guideLoading">正在读取后端发送状态…</p>
          <el-alert v-else-if="guideError" :title="guideError" type="error" :closable="false" show-icon />
          <p>启停控制提供服务发送新消息。读取权限在“共享治理 → Kafka 订阅授权”单独管理；停用能力后异步撤权，已读取的消息无法收回。</p>
          <el-button type="primary" plain @click="router.push({path:'/kafka-subscriptions',query:{keyword:detail.topic}})">管理订阅授权</el-button>
        </div>
        <el-alert v-else :title="capabilitySupport[detail.type]" type="info" :closable="false" show-icon style="margin-top:16px" />
        <div v-if="detail.message_schema" class="runtime-guide"><strong>消息结构</strong><pre>{{ detail.message_schema }}</pre></div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.runtime-guide { margin-top: 16px; padding: 16px; background: #f5f7fb; border-radius: 8px; }
.runtime-guide p { margin: 10px 0; line-height: 1.7; }
.runtime-guide code, .runtime-guide pre { overflow-wrap: anywhere; white-space: pre-wrap; font-size: 13px; }
.form-notice { margin-bottom: 12px; }
.form-toolbar, .guide-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.form-toolbar { justify-content:flex-end; }
.command-input { margin-top: 8px; }
.command-input :deep(textarea) { font-family: Consolas, monospace; font-size: 13px; line-height: 1.6; }
.capability-filters { display:grid; grid-template-columns:minmax(240px,2fr) repeat(4,minmax(130px,1fr)) auto; gap:12px; align-items:end; margin-bottom:20px; }
.capability-filters .el-form-item { margin-bottom:0; }
.capability-pagination { display:flex; justify-content:flex-end; gap:12px; align-items:center; flex-wrap:wrap; margin-top:20px; font-size:12px; color:#718497; }
@media(max-width:1500px) { .capability-filters { grid-template-columns:repeat(3,minmax(0,1fr)); } .keyword { grid-column:span 2; } }
@media(max-width:760px) { .capability-filters { grid-template-columns:1fr; } .keyword { grid-column:auto; } }
</style>
