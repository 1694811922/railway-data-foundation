<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hub } from '../../utils/dataHub'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { capabilitySupport, validateCapability, invocationCommand } from '../../utils/capabilityForm'

const route = useRoute()
const rows = ref([]), resources = ref([]), demo = ref(false)
const dialog = ref(false), detail = ref(null), runtime = ref(null), editId = ref('')
const saving = ref(false), busy = ref(''), filter = ref('')
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
  capability_name: '', resource_id: route.query.resource || '', type: 'REST_API',
  http_method: 'GET', endpoint: '', topic: '', source_table: '', protocol: 'HTTP',
  auth_type: 'NONE', message_schema: '', query_template_code: '', description: '',
})
const form = reactive(blank())
const formAuthOptions = computed(() => authOptions.filter(a =>
  (form.type === 'KAFKA_TOPIC' ? ['NONE', 'SASL'] : ['NONE', 'TOKEN', 'BASIC']).includes(a.value)))
const callCommand = computed(() => invocationCommand(runtime.value))
const visible = computed(() => filter.value ? rows.value.filter(r => r.resource_id === filter.value) : rows.value)
const selectableResources = computed(() => resources.value.filter(r => demo.value || r.status === 'PUBLISHED'))
const supportedRest = row => row.type === 'REST_API' && row.http_method === 'GET' && row.auth_type === 'NONE' && row.endpoint === '/containers/events'
const canVerify = row => ['DRAFT', 'VERIFIED', 'DISABLED'].includes(row.status) &&
  (demo.value || supportedRest(row) || (row.type === 'KAFKA_TOPIC' && row.auth_type === 'NONE'))
const canEnable = row => ['VERIFIED', 'DISABLED'].includes(row.status) && row.health_status === 'UP' &&
  (demo.value || supportedRest(row))
const resourceName = id => resources.value.find(r => r.resource_id === id)?.resource_name || id
const errorMessage = error => error.response?.data?.message ||
  (error.code === 'ECONNABORTED' ? '接口能力操作请求超时，请刷新后检查实际状态' :
    !error.response ? '接口能力操作请求失败，请检查后端服务' : error.message || '接口能力操作失败')

async function load() {
  try {
    const [c, r] = await Promise.all([hub.capabilities(), hub.resources()])
    rows.value = c.rows || []
    resources.value = r.rows || []
    demo.value = c.demo
    filter.value = route.query.resource || filter.value
  } catch { ElMessage.error('接口能力加载失败') }
}
function open(row) {
  Object.assign(form, blank(), row || {})
  editId.value = row?.capability_id || ''
  dialog.value = true
}
function typeChanged(type) {
  form.protocol = { REST_API: 'HTTP', KAFKA_TOPIC: 'KAFKA', CDC_STREAM: 'DEBEZIUM', READONLY_QUERY: '' }[type]
  form.auth_type = { REST_API: 'NONE', KAFKA_TOPIC: 'NONE', CDC_STREAM: '', READONLY_QUERY: '' }[type]
  if (type === 'CDC_STREAM') form.source_table = resources.value.find(r => r.resource_id === form.resource_id)?.physical_table || ''
}
function fillExample() {
  const names = { REST_API: '集装箱事件查询接口', KAFKA_TOPIC: '集装箱实时事件主题', CDC_STREAM: '集装箱变化流', READONLY_QUERY: '集装箱事件查询模板' }
  if (!form.capability_name.trim()) form.capability_name = names[form.type]
  typeChanged(form.type)
  if (form.type === 'REST_API') { form.http_method = 'GET'; form.endpoint = '/containers/events' }
  if (form.type === 'KAFKA_TOPIC') form.topic = 'port.container.event.v1'
  if (form.type === 'CDC_STREAM') form.topic = 'port.container.event.cdc'
  if (form.type === 'READONLY_QUERY') form.query_template_code = 'QUERY_CONTAINER_EVENT'
}
async function save() {
  const invalid = validateCapability(form)
  if (invalid) return ElMessage.warning(invalid)
  saving.value = true
  try {
    const keys = ['capability_name', 'resource_id', 'type', 'http_method', 'endpoint', 'topic', 'source_table', 'protocol', 'auth_type', 'message_schema', 'query_template_code', 'description']
    const body = Object.fromEntries(keys.map(key => [key, form[key]]))
    const result = await hub.saveCapability(body, editId.value || undefined, demo.value)
    dialog.value = false
    ElMessage.success(result.demo ? '演示能力已保存' : '能力已保存')
    await load()
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { saving.value = false }
}
async function action(row, name) {
  if (busy.value) return
  busy.value = row.capability_id + ':' + name
  try {
    const result = await hub.capabilityAction(row.capability_id, name, demo.value)
    if (name === 'verify' && result.value?.health_status !== 'UP') {
      ElMessage.warning(row.type === 'KAFKA_TOPIC' ? '消息主题验证未通过，请检查 Kafka 服务和主题' : '接口验证未通过，请检查提供服务与数据源')
    } else {
      const label = { verify: '验证', enable: '启用', disable: '停用' }[name]
      ElMessage.success(label + '完成' + (result.demo ? '（演示数据）' : ''))
    }
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { await load(); busy.value = '' }
}
async function showDetail(row) {
  detail.value = row
  runtime.value = null
  guideError.value = ''
  guideLoading.value = false
  if (supportedRest(row)) {
    guideLoading.value = true
    try {
      const result = await hub.capabilityInvocation(row.capability_id, demo.value)
      if (detail.value?.capability_id === row.capability_id) runtime.value = result.value
    } catch {
      if (detail.value?.capability_id === row.capability_id) guideError.value = '调用信息加载失败，请检查底座服务与提供服务地址配置，或重试。'
    } finally {
      if (detail.value?.capability_id === row.capability_id) guideLoading.value = false
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
onMounted(async () => { await load(); if (route.query.create === '1') open() })
</script>

<template>
  <div class="stack">
    <div class="page-heading">
      <div>
        <div class="eyebrow">接口能力 / 管理</div>
        <h1>接口能力管理</h1>
        <p>登记访问方式，验证连接后启用接口，按需停用</p>
      </div>
      <div style="display:flex;gap:8px">
        <el-select v-model="filter" clearable placeholder="全部资源" style="width:230px">
          <el-option v-for="r in resources" :key="r.resource_id" :label="r.resource_name" :value="r.resource_id" />
        </el-select>
        <el-button type="primary" @click="open()">＋ 注册接口能力</el-button>
      </div>
    </div>
    <DemoNotice :show="demo" text="当前为演示数据，能力操作仅影响当前浏览器。" />
    <el-alert v-if="!demo" title="集装箱事件 REST 查询接口支持真实验证、启用和停用；Kafka 可验证主题元数据，其他类型暂未接入运行时启停。" type="info" :closable="false" show-icon />
    <div class="panel">
      <div class="panel-title">能力列表 <span class="panel-sub">{{ visible.length }} 项</span></div>
      <el-table :data="visible" empty-text="暂无接口能力">
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
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{row}">
            <div class="table-actions">
              <el-button link type="primary" @click="showDetail(row)">查看</el-button>
              <el-button link type="primary" :disabled="!!busy || row.status !== 'DRAFT'" @click="open(row)">编辑</el-button>
              <el-button link type="primary" :loading="busy === row.capability_id + ':verify'" :disabled="!!busy || !canVerify(row)" @click="action(row,'verify')">验证</el-button>
              <el-button v-if="row.status !== 'ENABLED'" link type="primary" :loading="busy === row.capability_id + ':enable'" :disabled="!!busy || !canEnable(row)" @click="action(row,'enable')">启用</el-button>
              <el-button v-else link type="warning" :loading="busy === row.capability_id + ':disable'" :disabled="!!busy || (!demo && !supportedRest(row))" @click="action(row,'disable')">停用</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="editId ? '编辑接口能力' : '注册接口能力'" width="760px">
      <el-alert :title="capabilitySupport[form.type]" type="info" :closable="false" show-icon class="form-notice" />
      <div class="form-toolbar"><span>关联资源 → 填写访问方式 → 保存 → 验证 → 启用</span><el-button link type="primary" @click="fillExample">填入本机示例配置</el-button></div>
      <el-form label-position="top">
        <div class="grid-2">
          <el-form-item label="所属资源" required>
            <el-select v-model="form.resource_id" :disabled="!!editId" placeholder="选择已发布资源" style="width:100%" @change="typeChanged(form.type)">
              <el-option v-for="r in selectableResources" :key="r.resource_id" :label="r.resource_name" :value="r.resource_id" />
            </el-select>
            <div class="field-help">选择这项能力提供的数据集，只能关联已发布资源。</div>
          </el-form-item>
          <el-form-item label="能力类型">
            <el-select v-model="form.type" :disabled="!!editId" style="width:100%" @change="typeChanged">
              <el-option v-for="t in types" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="能力名称" required><el-input v-model="form.capability_name" maxlength="200" placeholder="例如：集装箱事件查询接口" /><div class="field-help">供目录展示和检索，同一资源下名称不能重复。</div></el-form-item>
          <el-form-item v-if="form.type === 'REST_API'" label="请求方式">
            <el-select v-model="form.http_method" style="width:100%"><el-option label="GET（查询数据）" value="GET" /><el-option label="POST（提交请求）" value="POST" /></el-select>
          </el-form-item>
          <el-form-item v-if="form.type === 'REST_API'" label="接口路径" required><el-input v-model="form.endpoint" placeholder="/containers/events" /><div class="field-help">填写以 / 开头的路径，不填完整网址；提供服务地址由后端配置，保存后可在详情查看。</div></el-form-item>
          <el-form-item v-if="['KAFKA_TOPIC','CDC_STREAM'].includes(form.type)" label="主题名称" required><el-input v-model="form.topic" placeholder="port.container.event.v1" /><div class="field-help">消息发送或订阅的主题名称，消息服务器地址由后端配置。</div></el-form-item>
          <el-form-item v-if="form.type === 'CDC_STREAM'" label="来源表" required><el-input v-model="form.source_table" readonly placeholder="选择资源后自动带出" /><div class="field-help">采集这张物理表的新增、修改和删除事件。</div></el-form-item>
          <el-form-item v-if="form.type === 'READONLY_QUERY'" label="查询模板代码" required><el-input v-model="form.query_template_code" placeholder="QUERY_CONTAINER_EVENT" /><div class="field-help">已约定的查询模板编号，不是 SQL 语句。</div></el-form-item>
          <el-form-item v-if="form.type !== 'READONLY_QUERY'" label="协议">
            <el-select v-if="form.type === 'REST_API'" v-model="form.protocol" style="width:100%"><el-option label="HTTP" value="HTTP" /><el-option label="HTTPS（加密传输）" value="HTTPS" /></el-select>
            <el-input v-else v-model="form.protocol" readonly />
          </el-form-item>
          <el-form-item v-if="['REST_API','KAFKA_TOPIC'].includes(form.type)" label="认证方式">
            <el-select v-model="form.auth_type" style="width:100%"><el-option v-for="a in formAuthOptions" :key="a.value" :label="a.label" :value="a.value" /></el-select>
            <div class="field-help">调用者访问接口或主题的认证方式，与数据库账号密码不同；此处只登记方式。</div>
          </el-form-item>
        </div>
        <el-form-item v-if="form.type === 'KAFKA_TOPIC'" label="消息结构"><el-input v-model="form.message_schema" type="textarea" :rows="2" placeholder="描述消息包含哪些字段及其类型，可填写 JSON 结构" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" placeholder="说明用途、查询条件或消息更新方式，不填写密码或令牌" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存能力</el-button></template>
    </el-dialog>

    <el-dialog :model-value="!!detail" title="接口能力详情" width="900px" @close="detail=null">
      <template v-if="detail">
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
              <el-descriptions-item label="必带请求头"><code>{{ runtime.header_name }}: {{ runtime.header_value }}</code></el-descriptions-item>
            </el-descriptions>
            <p>提供服务地址 + 接口路径组成完整地址。提供服务直接读取业务数据库；底座负责检查能力状态。</p>
            <div class="guide-heading"><strong>PowerShell 调用示例</strong><el-button size="small" @click="copyCommand">复制调用命令</el-button></div>
            <el-input ref="commandInput" :model-value="callCommand" type="textarea" :rows="3" readonly aria-label="PowerShell 调用命令" class="command-input" />
            <p class="panel-sub">{{ demo ? '演示配置与编号仅供展示，不能直接查询真实服务。' : '复制到 VS Code 的 PowerShell 终端执行。未启用或已停用时会被拒绝；地址须能从调用方访问。' }}</p>
          </template>
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
.form-toolbar { font-size: 13px; color: #64748b; }
.field-help { color: #64748b; font-size: 13px; line-height: 1.6; margin-top: 6px; }
.command-input { margin-top: 8px; }
.command-input :deep(textarea) { font-family: Consolas, monospace; font-size: 13px; line-height: 1.6; }
</style>
