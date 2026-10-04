<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hub } from '../../utils/dataHub'
import { resourcePayload, validateResource, resourceError } from '../../utils/resourceForm'
import { isDemoResource, isDemoSource } from '../../mock/demoData'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { catalogQuery, catalogRouteQuery, filterResources, catalogPage, resourcePageSizes } from '../../utils/resourceCatalog'
const route = useRoute(), router = useRouter()
const rows = ref([]), demo = ref(false), dialog = ref(false), editing = ref('')
const saving = ref(false), publishing = ref(''), loading = ref(false), formDemo = ref(false)
const loadError=ref(''), complete=ref(true), notice=ref(''), readAt=ref(null)
const initial=catalogQuery(route.query), search=reactive({...initial.filters}), applied=ref(initial.filters)
const page=ref(initial.page), pageSize=ref(initial.pageSize)
let loadVersion=0, hasReal=false
const filtered=computed(()=>filterResources(rows.value,applied.value))
const paged=computed(()=>catalogPage(filtered.value,page.value,pageSize.value))
const sourceOptions=computed(()=>Array.from(new Map(rows.value.map(row=>[row.source_id,{id:row.source_id,name:row.source_name || row.source_id}])).values()))
const hasFilters=computed(()=>Object.values(applied.value).some(Boolean))
async function syncQuery() {
  const next={...route.query,...catalogRouteQuery(applied.value,paged.value.page,pageSize.value)}
  await router.replace({path:route.path,query:next})
}
function query() { applied.value={...search}; page.value=1; syncQuery() }
function reset() { Object.assign(search,catalogQuery().filters); query() }
function turnPage(value) { page.value=value; syncQuery() }
function resize(value) { pageSize.value=value; page.value=1; syncQuery() }
function viewResource(row) {
  router.push({path:'/resources/'+row.resource_id,query:catalogRouteQuery(applied.value,paged.value.page,pageSize.value)})
}
watch(()=>route.query,query=>{
  const state=catalogQuery(query)
  Object.assign(search,state.filters); applied.value=state.filters; page.value=state.page; pageSize.value=state.pageSize
})
const blank = () => ({ resource_name:'', source_id:'', source_name:'', schema_name:'', physical_table:'', resource_type:'TABLE', business_description:'', owner_department:'', security_level:'内部', update_mode:'实时' })
const form = reactive(blank())
async function load() {
  if(loading.value) return
  const version=++loadVersion
  loading.value = true
  loadError.value=''
  try {
    const result=await hub.resourceCatalog({allowDemo:!hasReal})
    if(version!==loadVersion) return
    rows.value=result.rows; demo.value=result.demo; complete.value=result.complete; notice.value=result.notice
    if(!result.demo) hasReal=true
    readAt.value=new Date().toLocaleTimeString('zh-CN',{hour12:false})
    page.value=paged.value.page
    await syncQuery()
  } catch {
    if(version!==loadVersion) return
    rows.value=[]; demo.value=false; loadError.value='资源目录读取失败，请检查后端服务后刷新重试。未展示旧结果或模拟资源。'
    complete.value=false; notice.value=''; readAt.value=null
  } finally { if(version===loadVersion) loading.value = false }
}
function open(row) {
  Object.assign(form, blank(), row || {})
  editing.value = row?.resource_id || ''
  if (!row && route.query.fromTable) {
    Object.assign(form, { schema_name:route.query.schema || '', physical_table:route.query.fromTable,
      source_id:route.query.source || '', source_name:route.query.sourceName || '', resource_name:`${route.query.fromTable} 数据资源` })
  }
  formDemo.value = row ? isDemoResource(row.resource_id) : isDemoSource(form.source_id)
  dialog.value = true
}
async function save() {
  if (saving.value) return
  const invalid = validateResource(form)
  if (invalid) return ElMessage.warning(invalid)
  saving.value = true
  try {
    const result = await hub.saveResource(resourcePayload(form), editing.value || undefined)
    dialog.value = false
    ElMessage.success(result.demo ? '演示资源已保存' : '资源已保存')
    await router.push(`/resources/${result.value.resource_id}`)
  } catch (error) { ElMessage.error(resourceError(error, '保存资源')) }
  finally { saving.value = false }
}
async function publish(row) {
  if (publishing.value) return
  const invalid = validateResource(row)
  if (invalid) return ElMessage.warning(invalid)
  publishing.value = row.resource_id
  try {
    const result = await hub.publishResource(row.resource_id)
    ElMessage.success(`已发布 ${result.value.resource_id}${result.demo ? '（演示数据）' : ''}`)
    await load()
  } catch (error) { ElMessage.error(resourceError(error, '发布资源')) }
  finally { publishing.value = '' }
}
watch(() => [route.query.fromTable,route.query.source,route.query.schema,route.query.task].join('|'), () => { if (route.query.fromTable) open() }, { immediate:true })
onMounted(load)
onBeforeUnmount(()=>loadVersion++)
</script>

<template>
  <div class="stack">
    <div class="page-heading">
      <div><div class="eyebrow">数据资源 / 目录</div><h1>数据资源目录</h1><p>选择已扫描的物理表，补全业务信息后发布资源</p></div>
      <div class="actions"><el-button :loading="loading" @click="load">刷新目录</el-button><el-button type="primary" @click="router.push('/scans')">＋ 从扫描结果创建资源</el-button></div>
    </div>
    <DemoNotice :show="demo" text="当前目录使用前端演示数据；真实来源的保存失败不会转换为演示成功。" />
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon data-testid="catalog-error" />
    <el-alert v-else-if="!complete" :title="notice" description="检索和分页仅涵盖已读取的资源，匹配数不代表系统总数；列表变动时请刷新确认。" type="warning" :closable="false" show-icon data-testid="catalog-incomplete" />
    <div class="panel">
      <div class="panel-title">资源目录 <span class="panel-sub" data-testid="catalog-count">{{ loadError ? '读取失败' : '已读取 '+rows.length+' 项 · 匹配 '+filtered.length+' 项' }}</span></div>
      <el-form :model="search" label-position="top" class="catalog-filters" @submit.prevent="query">
        <el-form-item label="关键词" class="keyword"><el-input v-model="search.keyword" maxlength="200" clearable placeholder="搜索资源名称、编号、物理表、来源或责任单位" @keyup.enter="query" /></el-form-item>
        <el-form-item label="来源系统"><el-select v-model="search.source_id" clearable filterable placeholder="全部来源"><el-option v-for="source in sourceOptions" :key="source.id" :value="source.id" :label="source.name" /></el-select></el-form-item>
        <el-form-item label="资源状态"><el-select v-model="search.status" clearable placeholder="全部状态"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /></el-select></el-form-item>
        <el-form-item label="安全等级"><el-select v-model="search.security_level" clearable placeholder="全部等级"><el-option v-for="level in ['公开','内部','敏感','机密']" :key="level" :value="level" :label="level" /></el-select></el-form-item>
        <div class="filter-actions"><el-button type="primary" :disabled="loading || !!loadError" @click="query">查询</el-button><el-button :disabled="loading" @click="reset">重置</el-button></div>
      </el-form>
      <p class="catalog-scope">{{ demo ? '演示目录' : '真实资源目录' }}{{ readAt ? ' · 最近读取 '+readAt : '' }} · 检索在已读取元数据中执行，空格分隔的关键词需同时匹配。{{ !complete && !loadError ? '当前范围不完整。' : '' }}</p>
      <el-table v-if="!loadError" :data="paged.rows" v-loading="loading" :empty-text="hasFilters ? '没有符合条件的资源，请调整筛选条件' : '暂无资源，请先执行元数据扫描'" data-testid="catalog-table">
        <el-table-column prop="resource_id" label="资源编号" min-width="170"><template #default="{row}"><span class="id-text">{{ row.resource_id }}</span></template></el-table-column>
        <el-table-column prop="resource_name" label="资源名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="source_name" label="来源系统" min-width="140" />
        <el-table-column prop="physical_table" label="物理表" min-width="130" />
        <el-table-column prop="security_level" label="安全等级" width="95" />
        <el-table-column prop="update_mode" label="更新方式" width="95" />
        <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
        <el-table-column prop="version" label="版本" width="65" />
        <el-table-column label="操作" width="180" fixed="right"><template #default="{row}">
          <el-button link type="primary" @click="viewResource(row)">查看</el-button>
          <el-button link type="primary" :disabled="!!publishing || loading" @click="open(row)">编辑</el-button>
          <el-button v-if="row.status==='DRAFT'" link type="primary" :loading="publishing===row.resource_id" :disabled="!!publishing || loading" @click="publish(row)">发布</el-button>
        </template></el-table-column>
      </el-table>
      <div v-if="!loadError" class="catalog-pagination"><span>{{ complete ? '筛选结果分页' : '已读取结果分页' }}</span><el-pagination :current-page="paged.page" :page-size="pageSize" :page-sizes="resourcePageSizes" :total="filtered.length" layout="sizes, prev, pager, next, jumper" :disabled="loading" @current-change="turnPage" @size-change="resize" /></div>
    </div>
    <el-dialog v-model="dialog" :title="editing ? '编辑数据资源' : '从扫描结果创建资源'" width="720px" :show-close="!saving" :close-on-press-escape="!saving" :close-on-click-modal="false">
      <el-alert :title="formDemo ? '本次使用演示来源，仅在当前浏览器保存。' : '本次使用真实来源，保存成功后才登记到底座。'" :type="formDemo ? 'warning' : 'info'" :closable="false" show-icon />
      <p class="field-help">来源系统、数据库模式和物理表由扫描结果带出，登记后保持固定；业务信息需要人工确认。</p>
      <el-form label-position="top" :model="form">
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
      <template #footer><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存资源</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.actions { display:flex; gap:8px; }
.field-help { color:#64748b; font-size:13px; line-height:1.7; margin:12px 0 18px; }
.catalog-filters { display:grid; grid-template-columns:minmax(240px,2fr) repeat(3,minmax(135px,1fr)) auto; gap:12px; align-items:end; }
.catalog-filters .el-form-item { margin-bottom:0; }
.filter-actions { display:flex; gap:8px; padding-bottom:1px; }
.filter-actions .el-button + .el-button { margin-left:0; }
.catalog-scope { color:#718497; font-size:12px; line-height:1.7; margin:14px 0; }
.catalog-pagination { display:flex; justify-content:space-between; gap:12px; align-items:center; flex-wrap:wrap; margin-top:20px; color:#718497; font-size:12px; }
@media(max-width:1400px) { .catalog-filters { grid-template-columns:repeat(3,minmax(0,1fr)); } .keyword { grid-column:span 2; } }
@media(max-width:760px) { .catalog-filters { grid-template-columns:1fr; } .keyword { grid-column:auto; } .actions { flex-wrap:wrap; } }
</style>
