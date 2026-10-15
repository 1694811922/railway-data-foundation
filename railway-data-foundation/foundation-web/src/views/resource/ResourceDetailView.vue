<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hub } from '../../utils/dataHub'
import { resourceError, validateResource, isResourceConflict } from '../../utils/resourceForm'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import SchemaComparisonPanel from '../../components/SchemaComparisonPanel.vue'
import ResourceHistoryPanel from '../../components/ResourceHistoryPanel.vue'
import { catalogPage, catalogQuery, catalogRouteQuery, resourcePageSizes } from '../../utils/resourceCatalog'
import { filterCapabilities } from '../../utils/capabilityCatalog'
import { detailRows, fieldChanges, fieldEditScope, filterResourceFields, validateFieldChanges } from '../../utils/resourceDetail'
const route = useRoute(), router = useRouter()
function returnToCatalog() {
  const state=catalogQuery(route.query)
  router.push({path:'/resources',query:catalogRouteQuery(state.filters,state.page,state.pageSize)})
}
const item = ref(null), fields = ref([]), capabilities = ref([]), demo = ref(false), tab = ref('base')
const dialog = ref(false), loading = ref(false), publishing = ref(false), savingField = ref(false)
const loadError = ref(''), fieldError = ref(''), capabilityError = ref('')
const fieldConflict=ref('')
const fieldsLoading=ref(false), capabilitiesLoading=ref(false)
const fieldFilter=reactive({keyword:'',mark:'',sensitivity_level:''}), capabilityFilter=reactive({keyword:'',type:'',status:'',health_status:''})
const fieldPage=ref(1), fieldSize=ref(10), capabilityPage=ref(1), capabilitySize=ref(10)
const filteredFields=computed(()=>filterResourceFields(fields.value,fieldFilter))
const filteredCapabilities=computed(()=>filterCapabilities(capabilities.value,capabilityFilter))
const fieldRows=computed(()=>catalogPage(filteredFields.value,fieldPage.value,fieldSize.value))
const capabilityRows=computed(()=>catalogPage(filteredCapabilities.value,capabilityPage.value,capabilitySize.value))
const busy=computed(()=>publishing.value || savingField.value)
const capabilityTypes=['REST_API','KAFKA_TOPIC','CDC_STREAM','READONLY_QUERY']
const capabilityTypesText={REST_API:'REST接口',KAFKA_TOPIC:'Kafka主题',CDC_STREAM:'CDC变化流',READONLY_QUERY:'只读查询'}
watch([()=>fieldFilter.keyword,()=>fieldFilter.mark,()=>fieldFilter.sensitivity_level,fieldSize],()=>{fieldPage.value=1})
watch([()=>capabilityFilter.keyword,()=>capabilityFilter.type,()=>capabilityFilter.status,()=>capabilityFilter.health_status,capabilitySize],()=>{capabilityPage.value=1})
watch(()=>fieldRows.value.page,page=>{fieldPage.value=page})
watch(()=>capabilityRows.value.page,page=>{capabilityPage.value=page})
const edit = reactive({ field_name:'', business_name:'', business_description:'', time_field:false, sensitivity_level:'内部', data_type:'', technical_comment:'' })
let readVersion = 0, fieldReadVersion=0, capabilityReadVersion=0, active=true, editScope=null
const current=(version,id)=>active && version===readVersion && route.params.id===id
async function loadSection(section,allowPendingWrite=false) {
  const id=item.value?.resource_id, version=readVersion, mode=demo.value
  if(!id || loading.value || (busy.value && allowPendingWrite!==true)) return
  const isField=section==='fields', epoch=isField ? ++fieldReadVersion : ++capabilityReadVersion
  const pending=isField ? fieldsLoading : capabilitiesLoading, error=isField ? fieldError : capabilityError, rows=isField ? fields : capabilities
  pending.value=true; error.value=''; rows.value=[]
  try {
    const result=await (isField ? hub.resourceFields(id) : hub.resourceCapabilities(id))
    if(!current(version,id) || epoch!==(isField ? fieldReadVersion : capabilityReadVersion)) return
    rows.value=detailRows(result,id,isField ? 'field_name' : 'capability_id',mode)
  } catch {
    if(current(version,id) && epoch===(isField ? fieldReadVersion : capabilityReadVersion))
      error.value=isField ? '字段元数据加载失败，请重新加载；当前不显示替代字段。' : '关联接口能力加载失败，请重新加载。'
  } finally {
    if(current(version,id) && epoch===(isField ? fieldReadVersion : capabilityReadVersion)) pending.value=false
  }
}
async function load(allowPendingWrite=false) {
  if(busy.value && allowPendingWrite!==true) return
  const version = ++readVersion, id = route.params.id
  ++fieldReadVersion; ++capabilityReadVersion; fieldsLoading.value=false; capabilitiesLoading.value=false
  dialog.value=false; editScope=null
  fieldConflict.value=''
  loading.value = true; loadError.value = ''; fieldError.value = ''; capabilityError.value = ''
  item.value = null; fields.value = []; capabilities.value = []
  try {
    const result = await hub.resource(id)
    if (!current(version,id)) return
    if(!result.value || result.value.resource_id!==id) throw new Error('返回资源与当前详情编号不一致')
    item.value = result.value; demo.value = result.demo
    loading.value=false
    await Promise.all([loadSection('fields',true),loadSection('capabilities',true)])
  } catch (error) {
    if (current(version,id)) loadError.value = error.response?.status === 404 ? '资源不存在，请返回目录重新选择' : resourceError(error, '加载资源详情')
  } finally { if (current(version,id)) loading.value = false }
}
function openField(row) {
  if(loading.value || busy.value || fieldsLoading.value || fieldError.value || !item.value) return
  fieldConflict.value=''
  editScope={resource_id:item.value.resource_id,version:item.value.version,resource_version:row.resource_version,field_name:row.field_name,demo:demo.value}
  Object.assign(edit, { field_name:row.field_name, business_name:row.business_name || '', business_description:row.business_description || '',
    time_field:!!row.time_field, sensitivity_level:row.sensitivity_level || '内部', data_type:row.data_type, technical_comment:row.technical_comment || '' })
  dialog.value = true
}
async function saveField() {
  if (busy.value || fieldConflict.value || loading.value || fieldsLoading.value || fieldError.value || !dialog.value) return
  const invalid=fieldEditScope(editScope,item.value,fields.value,demo.value) || validateFieldChanges(edit)
  if(invalid) return ElMessage.warning(invalid)
  const scope={...editScope}, version=readVersion, changes=fieldChanges(edit,scope.resource_version)
  savingField.value = true
  try {
    const result = await hub.saveField(scope.resource_id, scope.field_name, changes)
    if(!current(version,scope.resource_id)) return
    dialog.value = false
    ElMessage.success(result.demo ? '演示字段已保存' : '字段已保存')
    await load(true)
  } catch (error) { if(current(version,scope.resource_id)) {
    if(isResourceConflict(error))fieldConflict.value=resourceError(error,'保存字段')
    ElMessage.error(resourceError(error, '保存字段'))
  } }
  finally { savingField.value = false }
}
async function publish() {
  if (busy.value || loading.value || !item.value || item.value.status!=='DRAFT') return
  const invalid = validateResource(item.value)
  if (invalid) return ElMessage.warning(invalid)
  publishing.value = true
  const version=readVersion, id=item.value.resource_id
  try {
    const result = await hub.publishResource(id)
    if(!current(version,id)) return
    ElMessage.success(`已发布 ${result.value.resource_id}${result.demo ? '（演示数据）' : ''}`)
    await load(true)
  } catch (error) { if(current(version,id)) ElMessage.error(resourceError(error, '发布资源')) }
  finally { publishing.value = false }
}
function resetFieldFilter(){Object.assign(fieldFilter,{keyword:'',mark:'',sensitivity_level:''});fieldPage.value=1}
function resetCapabilityFilter(){Object.assign(capabilityFilter,{keyword:'',type:'',status:'',health_status:''});capabilityPage.value=1}
function openCapabilities(create=false){
  if(!item.value || loading.value || busy.value || (create && item.value.status!=='PUBLISHED')) return
  router.push({path:'/capabilities',query:{resource:item.value.resource_id,create:create ? '1' : undefined}})
}
watch(() => route.params.id, () => {
  // Pending writes remain locked until settled, but never refresh/close a new resource view.
  ++readVersion; ++fieldReadVersion; ++capabilityReadVersion
  dialog.value=false; editScope=null; tab.value='base'; resetFieldFilter();resetCapabilityFilter()
  // Reads for a newly selected resource are safe while an old resource write is finishing.
  load(true)
}, { immediate:true })
onBeforeUnmount(()=>{active=false;++readVersion;++fieldReadVersion;++capabilityReadVersion;editScope=null;dialog.value=false})
</script>

<template>
  <div v-if="item" class="stack">
    <div class="detail-actions"><el-button link @click="returnToCatalog">← 返回资源目录</el-button><div>
      <el-button :loading="loading" :disabled="publishing || savingField" @click="load">刷新详情</el-button>
      <el-button v-if="item.status==='DRAFT'" type="primary" :loading="publishing" :disabled="loading || savingField" @click="publish">发布资源</el-button>
    </div></div>
    <div class="detail-hero"><div><h1>{{ item.resource_name }}</h1><div class="detail-pills"><span class="id-text">{{ item.resource_id }}</span><span>来源系统：{{ item.source_name || item.source_id }}</span><span>版本 V{{ item.version }}</span></div></div><StatusTag :value="item.status" /></div>
    <DemoNotice :show="demo" />

    <div class="panel" v-loading="loading"><el-tabs v-model="tab">
      <el-tab-pane label="基本信息" name="base"><el-descriptions :column="2" border style="margin-top:18px">
        <el-descriptions-item label="资源编号" :span="2"><span class="id-text">{{ item.resource_id }}</span></el-descriptions-item>
        <el-descriptions-item label="资源名称" :span="2">{{ item.resource_name }}</el-descriptions-item>
        <el-descriptions-item label="扫描来源" :span="2"><el-button v-if="item.scan_task_id" link type="primary" :data-testid="'resource-scan-origin'" @click="router.push({path:'/scans',query:{task:item.scan_task_id}})">{{ item.scan_task_id }}</el-button><span v-else>未返回扫描编号</span></el-descriptions-item>
        <el-descriptions-item label="来源系统">{{ item.source_name || item.source_id }}</el-descriptions-item>
        <el-descriptions-item label="数据库模式">{{ item.schema_name }}</el-descriptions-item>
        <el-descriptions-item label="物理表">{{ item.physical_table }}</el-descriptions-item>
        <el-descriptions-item label="资源类型">{{ item.resource_type==='TABLE' ? '数据库表' : item.resource_type }}</el-descriptions-item>
        <el-descriptions-item label="责任单位">{{ item.owner_department }}</el-descriptions-item>
        <el-descriptions-item label="安全等级">{{ item.security_level }}</el-descriptions-item>
        <el-descriptions-item label="更新方式">{{ item.update_mode }}</el-descriptions-item>
        <el-descriptions-item label="状态"><StatusTag :value="item.status" /></el-descriptions-item>
        <el-descriptions-item label="版本">V{{ item.version }}</el-descriptions-item>
        <el-descriptions-item label="业务描述" :span="2">{{ item.business_description }}</el-descriptions-item>
      </el-descriptions></el-tab-pane>
      <el-tab-pane :label="`字段元数据 · ${fieldsLoading || fieldError ? '—' : fields.length}`" name="fields">
        <div class="panel-title" style="margin-top:16px">字段元数据 <el-button :loading="fieldsLoading" :disabled="loading || busy" @click="loadSection('fields')">重新加载字段</el-button></div>

        <div class="detail-filters">
          <el-input v-model="fieldFilter.keyword" aria-label="资源字段关键词" placeholder="字段名、业务名称、类型或说明" clearable maxlength="200" />
          <el-select v-model="fieldFilter.mark" aria-label="字段标记" placeholder="全部字段标记" clearable><el-option label="主键字段" value="primary" /><el-option label="时间字段" value="time" /></el-select>
          <el-select v-model="fieldFilter.sensitivity_level" aria-label="字段敏感等级" placeholder="全部敏感等级" clearable><el-option v-for="level in ['公开','内部','敏感','机密']" :key="level" :label="level" :value="level" /></el-select>
          <el-button @click="resetFieldFilter">重置字段筛选</el-button>
        </div>
        <p v-if="!fieldError && !fieldsLoading" class="panel-sub" data-testid="resource-field-count">已读取 {{ fields.length }} 项 · 匹配 {{ filteredFields.length }} 项 · 主键 {{ fields.filter(row=>row.primary_key).length }} 项 · 时间字段 {{ fields.filter(row=>row.time_field).length }} 项</p>
        <el-alert v-if="fieldError" :title="fieldError" type="error" show-icon :closable="false" class="section-error" data-testid="resource-field-error" />
        <el-table v-else :data="fieldRows.rows" v-loading="fieldsLoading" empty-text="暂无匹配的字段元数据" data-testid="resource-field-list">
          <el-table-column prop="field_name" label="字段名" min-width="145" fixed="left" />
          <el-table-column prop="business_name" label="业务名称" min-width="130" />
          <el-table-column prop="data_type" label="数据类型" min-width="125" />
          <el-table-column label="主键" width="75"><template #default="{row}">{{ row.primary_key ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="时间字段" width="95"><template #default="{row}">{{ row.time_field ? '是' : '否' }}</template></el-table-column>
          <el-table-column prop="sensitivity_level" label="敏感等级" width="100" />
          <el-table-column prop="business_description" label="业务描述" min-width="180" show-overflow-tooltip />
          <el-table-column prop="technical_comment" label="技术注释" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="80" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="loading || busy || fieldsLoading" @click="openField(row)">编辑</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-if="!fieldError" v-model:current-page="fieldPage" v-model:page-size="fieldSize" :page-sizes="resourcePageSizes" :total="filteredFields.length" layout="total, sizes, prev, pager, next" class="detail-pagination" data-testid="resource-field-pagination" />
      </el-tab-pane>
      <el-tab-pane :label="`接口能力 · ${capabilitiesLoading || capabilityError ? '—' : capabilities.length}`" name="capabilities">
        <div class="panel-title" style="margin-top:16px">关联接口能力 <div class="detail-buttons"><el-button :loading="capabilitiesLoading" :disabled="loading || busy" @click="loadSection('capabilities')">重新加载能力</el-button><el-button :disabled="loading || busy" @click="openCapabilities()">管理关联能力</el-button><el-button type="primary" :disabled="item.status!=='PUBLISHED' || loading || busy" @click="openCapabilities(true)">＋ 注册能力</el-button></div></div>
        <p v-if="item.status!=='PUBLISHED'" class="dim">请先发布资源，再登记接口能力。</p>
        <div class="detail-filters capability-filters">
          <el-input v-model="capabilityFilter.keyword" aria-label="关联能力关键词" placeholder="能力名称、编号、路径或主题" clearable maxlength="200" />
          <el-select v-model="capabilityFilter.type" aria-label="关联能力类型" placeholder="全部类型" clearable><el-option v-for="type in capabilityTypes" :key="type" :label="capabilityTypesText[type]" :value="type" /></el-select>
          <el-select v-model="capabilityFilter.status" aria-label="关联能力状态" placeholder="全部状态" clearable><el-option v-for="[value,label] in [['DRAFT','草稿'],['VERIFIED','已验证'],['ENABLED','已启用'],['DISABLED','已停用'],['RETIRED','已退役']]" :key="value" :label="label" :value="value" /></el-select>
          <el-select v-model="capabilityFilter.health_status" aria-label="关联能力健康状态" placeholder="全部健康状态" clearable><el-option v-for="[value,label] in [['UNKNOWN','未知'],['UP','正常'],['DOWN','不可用'],['DEGRADED','降级']]" :key="value" :label="label" :value="value" /></el-select>
          <el-button @click="resetCapabilityFilter">重置能力筛选</el-button>
        </div>
        <p v-if="!capabilityError && !capabilitiesLoading" class="panel-sub" data-testid="resource-capability-count">已读取 {{ capabilities.length }} 项 · 匹配 {{ filteredCapabilities.length }} 项</p>
        <el-alert v-if="capabilityError" :title="capabilityError" type="error" show-icon :closable="false" class="section-error" data-testid="resource-capability-error" />
        <el-table v-else :data="capabilityRows.rows" v-loading="capabilitiesLoading" empty-text="暂无匹配的接口能力" data-testid="resource-capability-list">
          <el-table-column prop="capability_id" label="能力编号" min-width="180"><template #default="{row}"><span class="id-text">{{ row.capability_id }}</span></template></el-table-column>
          <el-table-column prop="capability_name" label="能力名称" min-width="180" />
          <el-table-column label="类型" width="125"><template #default="{row}"><StatusTag :value="row.type" /></template></el-table-column>
          <el-table-column label="访问地址／主题" min-width="170"><template #default="{row}">{{ row.endpoint || row.topic || row.query_template_code || '—' }}</template></el-table-column>
          <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
          <el-table-column label="健康状态" width="95"><template #default="{row}"><StatusTag :value="row.health_status" /></template></el-table-column>
        </el-table>
        <el-pagination v-if="!capabilityError" v-model:current-page="capabilityPage" v-model:page-size="capabilitySize" :page-sizes="resourcePageSizes" :total="filteredCapabilities.length" layout="total, sizes, prev, pager, next" class="detail-pagination" data-testid="resource-capability-pagination" />
      </el-tab-pane>
      <el-tab-pane label="结构变化" name="schema"><SchemaComparisonPanel v-if="tab==='schema'" :key="`${item.resource_id}:${item.version}:${demo}`" :resource="item" :demo="demo" /></el-tab-pane>
      <el-tab-pane label="版本历史" name="history"><ResourceHistoryPanel v-if="tab==='history'" :key="`${item.resource_id}:${item.version}:${demo}`" :resource="item" :demo="demo" /></el-tab-pane>
    </el-tabs></div>
    <el-dialog v-model="dialog" :title="`编辑字段 · ${edit.field_name}`" width="600px" :show-close="!savingField" :close-on-press-escape="!savingField" :close-on-click-modal="false">
      <div v-if="fieldConflict" data-testid="resource-field-conflict"><el-alert :title="fieldConflict" type="warning" :closable="false" show-icon /><el-button style="margin:12px 0" :disabled="busy" @click="load">取消并刷新详情</el-button></div>

      <p class="field-context">字段类型：{{ edit.data_type }} · 技术注释：{{ edit.technical_comment || '暂无' }}</p>
      <el-form label-position="top" :disabled="savingField">
        <el-form-item label="业务名称"><el-input v-model="edit.business_name" maxlength="120" placeholder="便于业务人员理解的字段名称" /></el-form-item>
        <el-form-item label="业务描述"><el-input v-model="edit.business_description" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        <el-form-item label="时间字段"><el-switch v-model="edit.time_field" /></el-form-item>
        <el-form-item label="敏感等级"><el-select v-model="edit.sensitivity_level" style="width:100%"><el-option v-for="x in ['公开','内部','敏感','机密']" :key="x" :label="x" :value="x" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="savingField" @click="dialog=false">取消</el-button><el-button type="primary" :loading="savingField" :disabled="!!fieldConflict" @click="saveField">保存字段</el-button></template>
    </el-dialog>
  </div>
  <div v-else class="panel empty-hint" v-loading="loading">
    <p>{{ loadError || '正在加载资源详情…' }}</p><el-button v-if="loadError" @click="load">重新加载</el-button><el-button link type="primary" @click="returnToCatalog">返回资源目录</el-button>
  </div>
</template>
<style scoped>
.detail-actions { display:flex; justify-content:space-between; align-items:center; }
.field-context { color:#64748b; line-height:1.7; font-size:13px; }
.section-error { margin-bottom:16px; }
.detail-filters { display:grid; grid-template-columns:minmax(220px,2fr) minmax(130px,1fr) minmax(130px,1fr) auto; gap:12px; margin:18px 0; }
.capability-filters { grid-template-columns:minmax(200px,2fr) repeat(3,minmax(120px,1fr)) auto; }
.detail-pagination { justify-content:flex-end; margin-top:20px; }
.detail-buttons { display:flex; gap:8px; flex-wrap:wrap; }
@media(max-width:1100px) { .detail-filters,.capability-filters { grid-template-columns:1fr 1fr; } }
</style>
