<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage,ElMessageBox } from 'element-plus'
import { saveDownload } from '../../utils/download'
import { receivingApi } from '../../api/receiving'
import { subscriptionApi } from '../../api/subscription'
import { session, explicitDemo } from '../../utils/sessionState'
import { receivingStates, receivingColors, receivingPage, receivingPayload, readablePayload, takeReceivingDraft, clearReceivingDraft, receivingReadGate } from '../../utils/receiving'
import { displayTime } from '../../utils/time'
import ReceivingProgress from '../../components/ReceivingProgress.vue'
import { receivingGrant, resolveReceivingEntry } from '../../utils/receivingEntry'
const progressTask=ref(null)
const exporting=ref(false),exportError=ref(''),confirmingExport=ref(false)
let downloadController,downloadVersion=0
function cancelDownload(){downloadVersion++;downloadController?.abort();downloadController=null;exporting.value=false;exportError.value='';if(confirmingExport.value)ElMessageBox.close();confirmingExport.value=false}
async function downloadMessage(row,format='record'){
 const id=detail.value?.task_id;if(!id||exporting.value||explicitDemo)return
 await downloadRequest(signal=>receivingApi.download(id,row.message_id,format,signal))
}
async function exportMessages(){
 const id=detail.value?.task_id;if(!id||exporting.value||confirmingExport.value||explicitDemo)return
 const epoch=session.epoch,scope=downloadVersion
 confirmingExport.value=true
 try{await ElMessageBox.confirm('导出当前主题、分区和接收时间范围内的已落库消息，最多10000条或50MB。文件包含业务数据，请妥善保存。','导出筛选结果',{confirmButtonText:'导出',cancelButtonText:'取消'})}catch{return}finally{confirmingExport.value=false}
 if(!detailVisible.value||detail.value?.task_id!==id||epoch!==session.epoch||scope!==downloadVersion)return
 const params={topic:filter.topic||undefined,partition:filter.partition,from:filter.range?.[0]?.toISOString(),until:filter.range?.[1]?.toISOString()}
 await downloadRequest(signal=>receivingApi.export(id,params,signal))
}
async function downloadRequest(operation){
 const version=++downloadVersion,epoch=session.epoch;downloadController=new AbortController();exporting.value=true;exportError.value=''
 try{const file=await operation(downloadController.signal);if(version!==downloadVersion||epoch!==session.epoch||!detailVisible.value)return;saveDownload(file);ElMessage.success(`文件已准备下载，共${file.count}条消息`)}
 catch(e){if(version===downloadVersion&&epoch===session.epoch&&e.code!=='ERR_CANCELED')exportError.value=e.response?.data?.message||e.message||'下载失败，请重试'}finally{if(version===downloadVersion){exporting.value=false;downloadController=null}}
}
watch(()=>session.epoch,cancelDownload)
const route = useRoute()
const router = useRouter()
const linkedGrant = ref(''), entryLoading = ref(false), entryError = ref('')
const items = ref([]), total = ref(0), page = ref(1), size = ref(20), keyword = ref(''), loading = ref(false), error = ref(''), busy = ref(false)
const visible = ref(false), createError = ref(''), candidates = ref([]), candidateTotal = ref(0), candidateOffset = ref(0)
const selectionLoading = ref(false), selectionFailed = ref(false)
const updating = ref(null)
const form = reactive({ task_name: '', grant_id: '', password: '', consumption_token: '', start_position: 'EARLIEST' })
const detail = ref(null), detailVisible = ref(false), messages = ref([]), messageTotal = ref(0), messagePage = ref(1), messageSize = ref(20), detailError = ref(''), payload = ref(null), payloadVisible = ref(false)
const filter = reactive({ topic: '', partition: undefined, range: [] })
const gates = { list: receivingReadGate(), detail: receivingReadGate(), payload: receivingReadGate(), candidates: receivingReadGate(), create: receivingReadGate(), write: receivingReadGate(), entry: receivingReadGate() }
let timer
function clearSecrets() { form.password = ''; form.consumption_token = ''; clearReceivingDraft() }
async function load() {
  if (explicitDemo || session.user?.role !== 'DEMAND' || entryLoading.value || entryError.value) return
  const token = gates.list.next(); loading.value = true; error.value = ''
  try { const value = receivingPage(await receivingApi.list({ keyword: keyword.value, grantId: linkedGrant.value || undefined, limit: size.value, offset: (page.value - 1) * size.value })); if (gates.list.current(token)) { items.value = value.items; total.value = value.total } }
  catch (e) { if (gates.list.current(token)) { items.value=[];error.value = e.response?.data?.message || e.message || '接收服务连接失败'; total.value = 0 } }
  finally { if (gates.list.current(token)) loading.value = false }
}
async function candidatesLoad(reset = true) {
  const token = gates.candidates.next(); if (reset) { candidates.value = []; candidateOffset.value = 0 }
  try { const result = await subscriptionApi.list({ limit: 20, offset: candidateOffset.value }); if (!gates.candidates.current(token)) return; candidates.value.push(...result.items.filter(x => x.broker_access_confirmed && !candidates.value.some(c => c.grant_id === x.grant_id))); candidateTotal.value = result.total; candidateOffset.value += result.items.length }
  catch (e) { if (gates.candidates.current(token)) createError.value = e.response?.data?.message || '授权列表加载失败' }
}
async function openCreate(grant = '', prepared = null, transferredDraft = null) {
  if (explicitDemo || busy.value) return
  const transferred = transferredDraft || (grant ? takeReceivingDraft(grant, session.epoch) : null)
  const token = gates.create.next()
  updating.value = null
  clearSecrets(); selectionLoading.value = false; selectionFailed.value = false; Object.assign(form, { task_name: '', grant_id: grant, start_position: 'EARLIEST' }); createError.value = ''; visible.value = true
  if (prepared) { candidates.value = [prepared]; candidateTotal.value = 1; candidateOffset.value = 1 }
  else await candidatesLoad()
  if(!gates.create.current(token)||!visible.value)return
  if (grant) {
    try { const value = prepared || await subscriptionApi.get(grant); if (!gates.create.current(token)||!visible.value || route.query.grant !== grant) return; if (!value.broker_access_confirmed) throw new Error('该订阅当前不可接收'); if (!candidates.value.some(x => x.grant_id === grant)) candidates.value.push(value); form.task_name = `${value.grant_name}接收`; if (transferred?.epoch === session.epoch) { form.password=transferred.password;form.consumption_token=transferred.consumption_token } }
    catch (e) { if(gates.create.current(token)){createError.value = e.response?.data?.message || e.message; clearSecrets()} }
  }
}
async function selectGrant(id) {
  clearSecrets(); const row = candidates.value.find(x => x.grant_id === id); if (row) form.task_name = `${row.grant_name}接收`
  const token = gates.create.next(), epoch = session.epoch
  const current = () => gates.create.current(token) && epoch === session.epoch && visible.value && form.grant_id === id
  selectionLoading.value = true; selectionFailed.value = false; createError.value = ''
  try {
    const result = await resolveReceivingEntry(id, { list: receivingApi.list, subscription: subscriptionApi.get, current })
    if (!current()) return
    if (result.kind === 'existing') { clearSecrets(); visible.value = false; ElMessage.info('该订阅已有接收任务'); await showDetail(result.task) }
  } catch (e) { if (current()) { clearSecrets(); selectionFailed.value = true; createError.value = e.response?.data?.message || e.message } }
  finally { if (gates.create.current(token)) selectionLoading.value = false }
}
function updateCredentials(row) { if(busy.value || explicitDemo) return; gates.create.invalidate(); selectionLoading.value=false;selectionFailed.value=false;clearSecrets(); updating.value={...row}; candidates.value=[{grant_id:row.grant_id,grant_name:row.grant_name||row.task_name,topic:row.topic}];candidateTotal.value=1;candidateOffset.value=1;Object.assign(form,{task_name:row.task_name,grant_id:row.grant_id,start_position:row.start_position});createError.value='';visible.value=true }
async function create() {
  if (busy.value || explicitDemo || selectionLoading.value || selectionFailed.value) return
  createError.value = ''; busy.value = true
  const token=gates.write.next()
  try { const body=receivingPayload(form);const result = updating.value ? await receivingApi.credentials(updating.value.task_id,updating.value.version,body) : await receivingApi.create(body); if(!gates.write.current(token))return;clearSecrets(); visible.value = false; ElMessage.success(updating.value?'接收凭据已更新':'接收任务已创建'); await load(); if(gates.write.current(token))await showDetail(result) }
  catch (e) { if(gates.write.current(token)){clearSecrets(); createError.value = e.response?.data?.message || e.message} }
  finally { if(gates.write.current(token))busy.value = false }
}
async function action(row, name) {
  if (busy.value || explicitDemo) return
  busy.value = true
  const token=gates.write.next()
  try { await receivingApi.action(row.task_id, name, row.version); if(!gates.write.current(token))return;ElMessage.success(name === 'stop' ? '已提交停止操作' : '已提交启动操作'); await load(); if (gates.write.current(token)&&detail.value?.task_id === row.task_id) await loadDetail() }
  catch { if(gates.write.current(token)){await load(); if (gates.write.current(token)&&detailVisible.value) await loadDetail()} }
  finally { if(gates.write.current(token))busy.value = false }
}
async function showDetail(row) { cancelDownload();gates.payload.invalidate(); payload.value = null; detail.value = row; messagePage.value = 1; Object.assign(filter, { topic: '', partition: undefined, range: [] }); detailVisible.value = true; await loadDetail() }
async function loadDetail() {
  const id = detail.value?.task_id; if (!id || !detailVisible.value) return
  const token = gates.detail.next(); detailError.value = ''; messages.value = []
  try {
    const current = await receivingApi.get(id)
    const result = receivingPage(await receivingApi.messages(id, { topic: filter.topic || undefined, partition: filter.partition, from: filter.range?.[0]?.toISOString(), until: filter.range?.[1]?.toISOString(), limit: messageSize.value, offset: (messagePage.value - 1) * messageSize.value }))
    if (gates.detail.current(token) && detailVisible.value) { detail.value = current; messages.value = result.items; messageTotal.value = result.total }
  } catch (e) { if (gates.detail.current(token)) { detailError.value = e.response?.data?.message || e.message; messageTotal.value = 0 } }
}
function closeDetail() { cancelDownload();detailVisible.value = false; gates.detail.invalidate(); gates.payload.invalidate(); detail.value = null; messages.value = []; payload.value = null; payloadVisible.value = false }
async function viewMessage(row) {
  const id = detail.value?.task_id, token = gates.payload.next(); payload.value = null; payloadVisible.value = true
  try { const result = await receivingApi.message(id, row.message_id); if (gates.payload.current(token) && payloadVisible.value) payload.value = result }
  catch (e) { if (gates.payload.current(token)) { payloadVisible.value = false; ElMessage.error(e.response?.data?.message || '消息读取失败') } }
}
function resetPageState() {
  Object.values(gates).forEach(x => x.invalidate()); closeDetail(); progressTask.value = null
  visible.value = false; updating.value = null; busy.value = false; loading.value = false; selectionLoading.value = false; selectionFailed.value = false
  clearSecrets(); candidates.value = []; items.value = []; total.value = 0; page.value = 1; keyword.value = ''; error.value = ''; createError.value = ''
  messageTotal.value = 0; detailError.value = ''; candidateTotal.value = 0; candidateOffset.value = 0
  Object.assign(form, { task_name: '', grant_id: '', start_position: 'EARLIEST' })
}
async function initializeEntry() {
  // Take the single-use in-memory draft before clearing old page state. Never
  // retain it on a failed lookup or when an existing task is found.
  let transferred
  try { const grant = receivingGrant(route.query.grant); if (grant) transferred = takeReceivingDraft(grant, session.epoch) } catch { clearReceivingDraft() }
  resetPageState(); entryError.value = ''; entryLoading.value = false; linkedGrant.value = ''
  if (explicitDemo || session.user?.role !== 'DEMAND') return
  const token = gates.entry.next(), epoch = session.epoch
  const current = () => gates.entry.current(token) && epoch === session.epoch
  try {
    linkedGrant.value = receivingGrant(route.query.grant)
    if (!linkedGrant.value) { await load(); return }
    entryLoading.value = true
    const result = await resolveReceivingEntry(linkedGrant.value, { list: receivingApi.list, subscription: subscriptionApi.get, current })
    if (!current()) return
    if (result.kind === 'existing') { items.value = [result.task]; total.value = 1; await showDetail(result.task) }
    else if (result.kind === 'create') await openCreate(linkedGrant.value, result.grant, transferred)
  } catch (e) { if (current()) { entryError.value = e.response?.data?.message || e.message || '接收任务关联读取失败'; clearSecrets() } }
  finally { transferred = null; if (current()) entryLoading.value = false }
}
watch(() => route.query.grant, initializeEntry, { immediate: true })
watch(() => session.epoch, () => { resetPageState(); entryLoading.value = false; entryError.value = ''; linkedGrant.value = '' })
onMounted(() => { timer = setInterval(() => { if (!loading.value && !busy.value) load() }, 5000) })
onBeforeUnmount(() => { cancelDownload();clearInterval(timer); clearSecrets(); payload.value = null; detail.value = null; messages.value = []; items.value = []; Object.values(gates).forEach(x => x.dispose()) })
</script>
<template>
  <div class="page-header"><div><h2>数据接收</h2></div><el-button v-if="!linkedGrant" type="primary" :disabled="explicitDemo || busy || entryLoading" @click="openCreate()">创建接收任务</el-button><el-button v-if="route.query.grant!==undefined" @click="router.replace({path:'/receiving'})">查看全部任务</el-button></div>
  <el-alert v-if="entryError" :title="entryError" type="error" :closable="false"><el-button link @click="initializeEntry">重试</el-button></el-alert>
  <el-alert v-if="explicitDemo" title="数据接收仅支持真实登录与真实授权，演示模式不可执行。" type="warning" :closable="false" />
  <el-card v-else v-loading="entryLoading">
    <div class="receiver-toolbar"><el-input v-model="keyword" placeholder="任务名称或授权编号" clearable style="width:280px" @keyup.enter="page=1;load()"/><el-button @click="page=1;load()">查询</el-button><el-button @click="load">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">重试</el-button></el-alert>
    <el-table :data="items" v-loading="loading" row-key="task_id">
      <el-table-column prop="task_name" label="任务名称" min-width="190"/><el-table-column prop="grant_name" label="授权名称" min-width="160" show-overflow-tooltip/><el-table-column prop="resource_name" label="数据资源" min-width="170" show-overflow-tooltip/><el-table-column prop="topic" label="消息主题" min-width="180" show-overflow-tooltip/>
      <el-table-column label="运行状态" width="120"><template #default="{row}"><el-tag :type="receivingColors[row.runtime_state] || 'info'">{{receivingStates[row.runtime_state] || row.runtime_state}}</el-tag></template></el-table-column>
      <el-table-column prop="received_count" label="已落库消息" width="120"/><el-table-column label="最近接收时间" min-width="175"><template #default="{row}">{{displayTime(row.last_received_at)}}</template></el-table-column>
      <el-table-column prop="error_reason" label="错误原因" min-width="210" show-overflow-tooltip/>
      <el-table-column label="操作" width="390" fixed="right"><template #default="{row}"><el-button link type="primary" @click="showDetail(row)">查看数据</el-button><el-button link type="primary" @click="progressTask=row">接收进度</el-button><el-button v-if="row.desired_state==='STOPPED'" link type="primary" :disabled="busy||row.runtime_state==='STOPPING'" @click="action(row,'start')">启动</el-button><el-button v-else link type="danger" :disabled="busy" @click="action(row,'stop')">停止</el-button><el-button v-if="row.runtime_state==='BLOCKED'" link type="primary" :disabled="busy" @click="action(row,'resume')">恢复</el-button><el-button v-if="row.runtime_state==='STOPPED'" link type="primary" :disabled="busy" @click="updateCredentials(row)">更新凭据</el-button></template></el-table-column>
    </el-table><el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[10,20,50]" :total="total" layout="total,sizes,prev,pager,next" @change="load"/>
  </el-card>
  <ReceivingProgress :task="progressTask" @close="progressTask=null" />
  <el-dialog v-model="visible" :title="updating?'更新接收凭据':'创建接收任务'" width="660px" :close-on-click-modal="false" :show-close="!busy" @closed="clearSecrets();gates.candidates.invalidate();gates.create.invalidate()">
    <el-alert v-if="createError" :title="createError" type="error" :closable="false"><el-button v-if="selectionFailed" link @click="selectGrant(form.grant_id)">重试关联</el-button></el-alert>
    <el-form label-position="top"><el-form-item label="已授权订阅"><el-select v-model="form.grant_id" :disabled="!!updating||busy" filterable style="width:100%" @change="selectGrant"><el-option v-for="row in candidates" :key="row.grant_id" :label="row.grant_name+' · '+row.topic" :value="row.grant_id"/></el-select><el-button v-if="candidateOffset<candidateTotal" link @click="candidatesLoad(false)">加载更多</el-button></el-form-item>
      <el-form-item label="任务名称"><el-input v-model="form.task_name" :disabled="!!updating||busy" maxlength="120"/></el-form-item><el-form-item label="Kafka专属密码"><el-input v-model="form.password" :disabled="busy||selectionLoading||selectionFailed" type="password" show-password autocomplete="off"/></el-form-item><el-form-item label="订阅技术令牌"><el-input v-model="form.consumption_token" :disabled="busy||selectionLoading||selectionFailed" type="password" show-password autocomplete="off"/></el-form-item>
      <el-form-item label="首次接收起点"><el-radio-group v-model="form.start_position" :disabled="!!updating||busy"><el-radio value="EARLIEST">可保留历史</el-radio><el-radio value="LATEST">仅新消息</el-radio></el-radio-group></el-form-item>
      <el-alert title="凭据将加密保存在需求方服务。启动后后台持续落库，关闭页面不会停止。" type="warning" :closable="false"/>
    </el-form><template #footer><el-button :disabled="busy" @click="visible=false">取消</el-button><el-button type="primary" :loading="busy||selectionLoading" :disabled="selectionFailed" @click="create">{{updating?'保存凭据':'创建任务'}}</el-button></template>
  </el-dialog>
  <el-drawer :model-value="detailVisible" title="接收数据" size="85%" @close="closeDetail">
    <template v-if="detail"><el-descriptions :column="3" border><el-descriptions-item label="任务名称">{{detail.task_name}}</el-descriptions-item><el-descriptions-item label="运行状态">{{receivingStates[detail.runtime_state]}}</el-descriptions-item><el-descriptions-item label="已落库消息">{{detail.received_count}}</el-descriptions-item><el-descriptions-item label="待补报摘要">{{detail.pending_reports}}</el-descriptions-item></el-descriptions>
      <div class="receiver-toolbar"><el-input :disabled="exporting" v-model="filter.topic" placeholder="消息主题" clearable style="width:230px"/><el-input-number :disabled="exporting" v-model="filter.partition" :min="0" placeholder="分区" style="width:130px"/><el-date-picker :disabled="exporting" v-model="filter.range" type="datetimerange" start-placeholder="接收开始时间" end-placeholder="结束时间"/><el-button :disabled="exporting" @click="messagePage=1;loadDetail()">查询</el-button><el-button :disabled="exporting" @click="loadDetail">刷新</el-button><el-button type="primary" :disabled="!!detailError||exporting||explicitDemo" @click="exportMessages">导出筛选结果</el-button><el-button v-if="exporting" @click="cancelDownload">取消下载</el-button></div><el-alert v-if="exportError" :title="exportError" type="error" :closable="false"/>
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false"><el-button link @click="loadDetail">重试</el-button></el-alert>
      <el-table :data="messages"><el-table-column prop="topic" label="消息主题" min-width="180"/><el-table-column prop="partition_id" label="分区" width="70"/><el-table-column prop="message_offset" label="消息位置" width="100"/><el-table-column prop="payload_bytes" label="内容字节数" width="120"/><el-table-column label="接收时间" min-width="170"><template #default="{row}">{{displayTime(row.received_at)}}</template></el-table-column><el-table-column label="操作" width="185"><template #default="{row}"><el-button link type="primary" @click="viewMessage(row)">查看内容</el-button><el-button link type="primary" :disabled="exporting" @click="downloadMessage(row)">下载消息</el-button></template></el-table-column></el-table>
      <el-pagination v-model:current-page="messagePage" v-model:page-size="messageSize" :page-sizes="[10,20,50]" :total="messageTotal" layout="total,sizes,prev,pager,next" @change="loadDetail"/>
    </template>
  </el-drawer>
  <el-dialog v-model="payloadVisible" title="消息内容" width="800px" @close="cancelDownload" @closed="payload=null;gates.payload.invalidate()"><template v-if="payload"><el-descriptions :column="3" border><el-descriptions-item label="分区">{{payload.partition_id}}</el-descriptions-item><el-descriptions-item label="消息位置">{{payload.message_offset}}</el-descriptions-item><el-descriptions-item label="内容字节数">{{payload.payload_bytes}}</el-descriptions-item></el-descriptions><h4>消息键</h4><pre class="receiver-content">{{readablePayload(payload.message_key)}}</pre><h4>消息内容</h4><pre class="receiver-content">{{readablePayload(payload.message_value)}}</pre><h4>消息头</h4><pre class="receiver-content">{{payload.headers_json}}</pre></template><el-empty v-else description="正在读取消息"/><el-alert v-if="exportError" :title="exportError" type="error" :closable="false"/><template #footer><el-button :disabled="!payload||exporting||payload?.message_value?.encoding==='NULL'" @click="downloadMessage(payload,'content')">下载内容</el-button><el-button type="primary" :disabled="!payload||exporting" @click="downloadMessage(payload,'record')">下载完整消息</el-button><el-button v-if="exporting" @click="cancelDownload">取消下载</el-button></template></el-dialog>
</template>
<style scoped>
.receiver-toolbar{display:flex;gap:12px;flex-wrap:wrap;margin:18px 0}.receiver-content{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f7fa;padding:16px;max-height:420px;overflow:auto;font-size:14px}.el-pagination{margin-top:18px}
</style>
