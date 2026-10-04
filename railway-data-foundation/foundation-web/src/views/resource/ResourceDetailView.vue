<script setup>
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hub } from '../../utils/dataHub'
import { resourceError, validateResource } from '../../utils/resourceForm'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { catalogQuery, catalogRouteQuery } from '../../utils/resourceCatalog'
const route = useRoute(), router = useRouter()
function returnToCatalog() {
  const state=catalogQuery(route.query)
  router.push({path:'/resources',query:catalogRouteQuery(state.filters,state.page,state.pageSize)})
}
const item = ref(null), fields = ref([]), capabilities = ref([]), demo = ref(false), tab = ref('base')
const dialog = ref(false), loading = ref(false), publishing = ref(false), savingField = ref(false)
const loadError = ref(''), fieldError = ref(''), capabilityError = ref('')
const edit = reactive({ field_name:'', business_name:'', business_description:'', time_field:false, sensitivity_level:'内部', data_type:'', technical_comment:'' })
let readVersion = 0
async function load() {
  const version = ++readVersion, id = route.params.id
  loading.value = true; loadError.value = ''; fieldError.value = ''; capabilityError.value = ''
  item.value = null; fields.value = []; capabilities.value = []
  try {
    const result = await hub.resource(id)
    if (version !== readVersion) return
    item.value = result.value; demo.value = result.demo
    const [f,c] = await Promise.allSettled([hub.resourceFields(id), hub.resourceCapabilities(id)])
    if (version !== readVersion) return
    if (f.status === 'fulfilled') fields.value = f.value.rows || []
    else fieldError.value = '字段元数据加载失败，请刷新重试；当前不显示替代字段。'
    if (c.status === 'fulfilled') capabilities.value = c.value.rows || []
    else capabilityError.value = '关联接口能力加载失败，请刷新重试。'
  } catch (error) {
    if (version === readVersion) loadError.value = error.response?.status === 404 ? '资源不存在，请返回目录重新选择' : resourceError(error, '加载资源详情')
  } finally { if (version === readVersion) loading.value = false }
}
function openField(row) {
  Object.assign(edit, { field_name:row.field_name, business_name:row.business_name || '', business_description:row.business_description || '',
    time_field:!!row.time_field, sensitivity_level:row.sensitivity_level || '内部', data_type:row.data_type, technical_comment:row.technical_comment || '' })
  dialog.value = true
}
async function saveField() {
  if (savingField.value) return
  savingField.value = true
  const id = item.value.resource_id
  const changes = { business_name:edit.business_name, business_description:edit.business_description, time_field:edit.time_field, sensitivity_level:edit.sensitivity_level }
  try {
    const result = await hub.saveField(id, edit.field_name, changes)
    dialog.value = false
    ElMessage.success(result.demo ? '演示字段已保存' : '字段已保存')
    if (route.params.id === id) await load()
  } catch (error) { ElMessage.error(resourceError(error, '保存字段')) }
  finally { savingField.value = false }
}
async function publish() {
  if (publishing.value) return
  const invalid = validateResource(item.value)
  if (invalid) return ElMessage.warning(invalid)
  publishing.value = true
  try {
    const result = await hub.publishResource(item.value.resource_id)
    ElMessage.success(`已发布 ${result.value.resource_id}${result.demo ? '（演示数据）' : ''}`)
    await load()
  } catch (error) { ElMessage.error(resourceError(error, '发布资源')) }
  finally { publishing.value = false }
}
watch(() => route.params.id, () => { dialog.value=false; tab.value='base'; load() }, { immediate:true })
</script>

<template>
  <div v-if="item" class="stack">
    <div class="detail-actions"><el-button link @click="returnToCatalog">← 返回资源目录</el-button><div>
      <el-button :loading="loading" :disabled="publishing || savingField" @click="load">刷新详情</el-button>
      <el-button v-if="item.status==='DRAFT'" type="primary" :loading="publishing" :disabled="loading || savingField" @click="publish">发布资源</el-button>
    </div></div>
    <div class="detail-hero"><div><div class="eyebrow">数据资源 / 详情</div><h1>{{ item.resource_name }}</h1><div class="detail-pills"><span class="id-text">{{ item.resource_id }}</span><span>来源系统：{{ item.source_name || item.source_id }}</span><span>版本 V{{ item.version }}</span></div></div><StatusTag :value="item.status" /></div>
    <DemoNotice :show="demo" text="当前资源和字段使用演示数据，编辑仅保存在当前浏览器。" />
    <el-alert v-if="item.status==='DRAFT'" title="请核对业务信息和字段元数据后发布资源；发布成功后才能登记接口能力。" type="info" show-icon :closable="false" />
    <div class="panel" v-loading="loading"><el-tabs v-model="tab">
      <el-tab-pane label="基本信息" name="base"><el-descriptions :column="2" border style="margin-top:18px">
        <el-descriptions-item label="资源编号" :span="2"><span class="id-text">{{ item.resource_id }}</span></el-descriptions-item>
        <el-descriptions-item label="资源名称" :span="2">{{ item.resource_name }}</el-descriptions-item>
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
      <el-tab-pane :label="`字段元数据 · ${fields.length}`" name="fields">
        <div class="panel-title" style="margin-top:16px">字段元数据 <span class="panel-sub">技术信息来自扫描 · 业务信息人工维护</span></div>
        <el-alert v-if="fieldError" :title="fieldError" type="error" show-icon :closable="false" class="section-error" />
        <el-table v-else :data="fields" empty-text="暂无字段元数据">
          <el-table-column prop="field_name" label="字段名" min-width="145" fixed="left" />
          <el-table-column prop="business_name" label="业务名称" min-width="130" />
          <el-table-column prop="data_type" label="数据类型" min-width="125" />
          <el-table-column label="主键" width="75"><template #default="{row}">{{ row.primary_key ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="时间字段" width="95"><template #default="{row}">{{ row.time_field ? '是' : '否' }}</template></el-table-column>
          <el-table-column prop="sensitivity_level" label="敏感等级" width="100" />
          <el-table-column prop="business_description" label="业务描述" min-width="180" show-overflow-tooltip />
          <el-table-column prop="technical_comment" label="技术注释" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="80" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="loading || savingField" @click="openField(row)">编辑</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane :label="`接口能力 · ${capabilities.length}`" name="capabilities">
        <div class="panel-title" style="margin-top:16px">关联接口能力 <el-button type="primary" :disabled="item.status!=='PUBLISHED' || loading" @click="router.push({path:'/capabilities',query:{resource:item.resource_id,create:'1'}})">＋ 注册能力</el-button></div>
        <p v-if="item.status!=='PUBLISHED'" class="dim">请先发布资源，再登记接口能力。</p>
        <el-alert v-if="capabilityError" :title="capabilityError" type="error" show-icon :closable="false" class="section-error" />
        <el-table v-else :data="capabilities" empty-text="暂未登记接口能力">
          <el-table-column prop="capability_id" label="能力编号" min-width="180"><template #default="{row}"><span class="id-text">{{ row.capability_id }}</span></template></el-table-column>
          <el-table-column prop="capability_name" label="能力名称" min-width="180" />
          <el-table-column label="类型" width="125"><template #default="{row}"><StatusTag :value="row.type" /></template></el-table-column>
          <el-table-column label="访问地址／主题" min-width="170"><template #default="{row}">{{ row.endpoint || row.topic || row.query_template_code || '—' }}</template></el-table-column>
          <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
          <el-table-column label="健康状态" width="95"><template #default="{row}"><StatusTag :value="row.health_status" /></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs></div>
    <el-dialog v-model="dialog" :title="`编辑字段 · ${edit.field_name}`" width="600px" :show-close="!savingField" :close-on-press-escape="!savingField" :close-on-click-modal="false">
      <el-alert title="只修改业务属性，字段名、类型和技术注释保持扫描结果。" type="info" :closable="false" />
      <p class="field-context">字段类型：{{ edit.data_type }} · 技术注释：{{ edit.technical_comment || '暂无' }}</p>
      <el-form label-position="top">
        <el-form-item label="业务名称"><el-input v-model="edit.business_name" maxlength="120" placeholder="便于业务人员理解的字段名称" /></el-form-item>
        <el-form-item label="业务描述"><el-input v-model="edit.business_description" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        <el-form-item label="时间字段"><el-switch v-model="edit.time_field" /></el-form-item>
        <el-form-item label="敏感等级"><el-select v-model="edit.sensitivity_level" style="width:100%"><el-option v-for="x in ['公开','内部','敏感','机密']" :key="x" :label="x" :value="x" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="savingField" @click="dialog=false">取消</el-button><el-button type="primary" :loading="savingField" @click="saveField">保存字段</el-button></template>
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
</style>
