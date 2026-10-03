<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hub } from '../../utils/dataHub'
import { resourcePayload, validateResource, resourceError } from '../../utils/resourceForm'
import { isDemoResource, isDemoSource } from '../../mock/demoData'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
const route = useRoute(), router = useRouter()
const rows = ref([]), sources = ref([]), demo = ref(false), dialog = ref(false), editing = ref('')
const saving = ref(false), publishing = ref(''), loading = ref(false), formDemo = ref(false)
const blank = () => ({ resource_name:'', source_id:'', source_name:'', schema_name:'', physical_table:'', resource_type:'TABLE', business_description:'', owner_department:'', security_level:'内部', update_mode:'实时' })
const form = reactive(blank())
async function load() {
  loading.value = true
  try {
    const [r,s] = await Promise.all([hub.resources(), hub.sources()])
    rows.value = r.rows || []; demo.value = r.demo; sources.value = s.rows || []
  } catch { ElMessage.error('资源目录加载失败，请重试') }
  finally { loading.value = false }
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
watch(() => route.fullPath, () => { if (route.query.fromTable) open() }, { immediate:true })
onMounted(load)
</script>

<template>
  <div class="stack">
    <div class="page-heading">
      <div><div class="eyebrow">数据资源 / 目录</div><h1>数据资源目录</h1><p>选择已扫描的物理表，补全业务信息后发布资源</p></div>
      <div class="actions"><el-button :loading="loading" @click="load">刷新目录</el-button><el-button type="primary" @click="router.push('/scans')">＋ 从扫描结果创建资源</el-button></div>
    </div>
    <DemoNotice :show="demo" text="当前目录使用前端演示数据；真实来源的保存失败不会转换为演示成功。" />
    <div class="panel">
      <div class="panel-title">资源目录 <span class="panel-sub">{{ rows.length }} 项资源</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="暂无资源，请先执行元数据扫描">
        <el-table-column prop="resource_id" label="资源编号" min-width="170"><template #default="{row}"><span class="id-text">{{ row.resource_id }}</span></template></el-table-column>
        <el-table-column prop="resource_name" label="资源名称" min-width="220" />
        <el-table-column prop="source_name" label="来源系统" min-width="140" />
        <el-table-column prop="physical_table" label="物理表" min-width="130" />
        <el-table-column prop="security_level" label="安全等级" width="95" />
        <el-table-column prop="update_mode" label="更新方式" width="95" />
        <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
        <el-table-column prop="version" label="版本" width="65" />
        <el-table-column label="操作" width="180" fixed="right"><template #default="{row}">
          <el-button link type="primary" @click="router.push(`/resources/${row.resource_id}`)">查看</el-button>
          <el-button link type="primary" :disabled="!!publishing" @click="open(row)">编辑</el-button>
          <el-button v-if="row.status==='DRAFT'" link type="primary" :loading="publishing===row.resource_id" :disabled="!!publishing" @click="publish(row)">发布</el-button>
        </template></el-table-column>
      </el-table>
    </div>
    <el-dialog v-model="dialog" :title="editing ? '编辑数据资源' : '从扫描结果创建资源'" width="720px" :show-close="!saving" :close-on-press-escape="!saving" :close-on-click-modal="false">
      <el-alert :title="formDemo ? '本次使用演示来源，仅在当前浏览器保存。' : '本次使用真实来源，保存成功后才登记到底座。'" :type="formDemo ? 'warning' : 'info'" :closable="false" show-icon />
      <p class="field-help">来源系统、数据库模式和物理表由扫描结果带出，登记后保持固定；业务信息需要人工确认。</p>
      <el-form label-position="top" :model="form">
        <div class="grid-2">
          <el-form-item label="来源系统"><el-input :model-value="sources.find(s=>s.source_id===form.source_id)?.source_name || form.source_name || form.source_id" readonly /></el-form-item>
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
</style>