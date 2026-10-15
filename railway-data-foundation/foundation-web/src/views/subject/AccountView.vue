<script setup>
import { ref, reactive, watch, onMounted, onBeforeUnmount } from 'vue'
import { accountApi } from '../../api/account'
import { explicitDemo, session } from '../../utils/sessionState'
import { roleNames } from '../../utils/workspace'
import { accountFormError, accountPasswordError, latestAccountReader } from '../../utils/account'
import { ElMessage, ElMessageBox } from 'element-plus'
import RegistrationReview from '../../components/RegistrationReview.vue'

const rows = ref([]), total = ref(0), page = ref(1), size = ref(20), loading = ref(false), error = ref('')
const filters = reactive({ keyword: '', role: '', enabled: '' })
const visible = ref(false), resetVisible = ref(false), busy = ref(false), formError = ref(''), conflict = ref(false), uncertain = ref(false)
const resetTarget = ref(null), resetPassword = ref('')
const form = reactive({ username: '', display_name: '', role: 'PROVIDER', subject_id: null, password: '' })
const subjects = ref([]), subjectTotal = ref(0), subjectLoading = ref(false), subjectError = ref('')
const listReader = latestAccountReader(), subjectReader = latestAccountReader()
let active = true, subjectKeyword = ''
const message = (e, fallback) => e.response?.data?.message || fallback

async function load() {
  if (explicitDemo) return
  loading.value = true; error.value = ''; rows.value = []
  const params = { keyword: filters.keyword.trim(), role: filters.role, limit: size.value, offset: (page.value - 1) * size.value }
  if (filters.enabled !== '') params.enabled = filters.enabled
  await listReader.run(() => accountApi.catalog(params), result => {
    rows.value = result.items; total.value = result.total
    if (page.value > 1 && !rows.value.length && total.value > 0) page.value = Math.ceil(total.value / size.value)
  }, e => { total.value = 0; error.value = message(e, '账号读取失败，请重试') }, () => { loading.value = false })
}
function search() { if (page.value !== 1) page.value = 1; else load() }
function changeSize(value) { size.value = value; search() }
watch(page, load)

async function loadSubjects(keyword = '', append = false) {
  if (!visible.value || form.role === 'ADMIN' || explicitDemo) return
  subjectKeyword = keyword; subjectError.value = ''; subjectLoading.value = true
  if (!append) { subjects.value = []; subjectTotal.value = 0; form.subject_id = null }
  const params = { keyword, role: form.role, limit: 20, offset: append ? subjects.value.length : 0 }
  await subjectReader.run(() => accountApi.subjects(params), result => {
    subjects.value = append ? [...subjects.value, ...result.items] : result.items; subjectTotal.value = result.total
  }, e => { subjectError.value = message(e, '主体读取失败，请重试'); if (!append) form.subject_id = null }, () => { subjectLoading.value = false })
}
function roleChanged() {
  subjectReader.invalidate(); subjects.value = []; subjectTotal.value = 0; form.subject_id = null; subjectLoading.value = false; subjectError.value = ''
  loadSubjects()
}
function open() {
  if (busy.value || explicitDemo) return
  Object.assign(form, { username: '', display_name: '', role: 'PROVIDER', subject_id: null, password: '' })
  formError.value = ''; conflict.value = false; uncertain.value = false; visible.value = true; loadSubjects()
}
function close() {
  if (busy.value) return
  visible.value = false; resetVisible.value = false; form.password = ''; resetPassword.value = ''; resetTarget.value = null
  formError.value = ''; conflict.value = false; uncertain.value = false; subjectReader.invalidate(); subjects.value = []; subjectTotal.value = 0; subjectLoading.value = false
}
async function save() {
  if (busy.value || uncertain.value || explicitDemo || !visible.value) return
  formError.value = accountFormError(form)
  if (formError.value) return
  busy.value = true
  try {
    await accountApi.create({ ...form, display_name: form.display_name.trim(), subject_id: form.role === 'ADMIN' ? null : form.subject_id })
    if (active) { busy.value = false; close(); ElMessage.success('用户账号已创建'); search() }
  } catch (e) { if (active) { formError.value = message(e, '保存结果尚未确认，请关闭后刷新核对账号，勿重复提交'); uncertain.value = !e.response || e.response.status >= 500 } }
  finally { form.password = ''; busy.value = false }
}
async function status(row) {
  if (busy.value || loading.value || explicitDemo || row.user_id === session.user?.user_id) return
  busy.value = true; error.value = ''
  try {
    await ElMessageBox.confirm(`确认${row.enabled ? '停用' : '启用'}账号“${row.username}”？`, '账号状态', { confirmButtonText: '确认', cancelButtonText: '取消', closeOnClickModal: false })
    if (!active) return
    await accountApi.status(row.user_id, { version: row.version, enabled: !row.enabled })
    if (active) { ElMessage.success('账号状态已更新'); await load() }
  } catch (e) {
    if (active && e !== 'cancel' && e !== 'close') {
      await load(); if (active) error.value = message(e, '操作结果尚未确认，请刷新核对账号状态')
    }
  } finally { busy.value = false }
}
function reset(row) {
  if (busy.value || loading.value || explicitDemo || row.user_id === session.user?.user_id) return
  resetTarget.value = { ...row }; resetPassword.value = ''; formError.value = ''; conflict.value = false; uncertain.value = false; resetVisible.value = true
}
async function savePassword() {
  if (busy.value || conflict.value || uncertain.value || explicitDemo || !resetVisible.value) return
  formError.value = accountPasswordError(resetPassword.value)
  if (formError.value) return
  busy.value = true
  try {
    await accountApi.password(resetTarget.value.user_id, { version: resetTarget.value.version, password: resetPassword.value })
    if (active) { busy.value = false; close(); ElMessage.success('密码已重置，原登录会话将失效'); load() }
  } catch (e) {
    if (active) { formError.value = message(e, '重置结果尚未确认，请关闭并核对账号状态'); conflict.value = e.response?.status === 409; uncertain.value = !e.response || e.response.status >= 500 }
  } finally { resetPassword.value = ''; busy.value = false }
}
function refreshAfterConflict() { close(); load() }
onMounted(load)
onBeforeUnmount(() => {
  active = false; listReader.invalidate(); subjectReader.invalidate(); form.password = ''; resetPassword.value = ''; resetTarget.value = null
  rows.value = []; subjects.value = []; ElMessageBox.close()
})
</script>

<template>
  <div class="stack">
    <div class="page-heading"><h1>用户账号</h1><div><el-button :disabled="explicitDemo || busy" @click="load">刷新账号</el-button><el-button type="primary" :disabled="explicitDemo || busy" @click="open">创建账号</el-button></div></div>
    <el-alert v-if="explicitDemo" title="演示模式不读取或操作真实账号" type="warning" :closable="false" />
    <RegistrationReview @approved="load" />
    <div class="panel">
      <el-form class="account-filters" :disabled="explicitDemo" @submit.prevent="search">
        <el-input v-model="filters.keyword" placeholder="账号、用户名称、主体或单位" aria-label="搜索账号" maxlength="120" clearable @clear="search" />
        <el-select v-model="filters.role" aria-label="筛选账号角色" placeholder="全部角色" clearable @change="search"><el-option v-for="(name,key) in roleNames" :key="key" :label="name" :value="key" /></el-select>
        <el-select v-model="filters.enabled" aria-label="筛选账号状态" placeholder="全部状态" clearable @change="search"><el-option label="启用" :value="true" /><el-option label="停用" :value="false" /></el-select>
        <el-button type="primary" native-type="submit">查询</el-button>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="account-error"><el-button link type="primary" @click="load">重试读取</el-button></el-alert>
      <el-table :data="rows" v-loading="loading" data-testid="account-table" empty-text="暂无符合条件的账号">
        <el-table-column prop="username" label="登录账号" min-width="190" show-overflow-tooltip />
        <el-table-column prop="display_name" label="用户名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="角色" width="125"><template #default="{row}"><el-tag>{{roleNames[row.role]}}</el-tag></template></el-table-column>
        <el-table-column prop="subject_name" label="所属主体" min-width="170" show-overflow-tooltip><template #default="{row}">{{row.subject_name || '平台管理'}}</template></el-table-column>
        <el-table-column prop="department" label="所属单位" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="80"><template #default="{row}"><el-tag :type="row.enabled ? 'success' : 'info'">{{row.enabled ? '启用' : '停用'}}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="190" fixed="right"><template #default="{row}"><span v-if="row.user_id === session.user?.user_id">当前账号</span><template v-else><el-button link :type="row.enabled ? 'warning' : 'success'" :disabled="busy || loading" @click="status(row)">{{row.enabled ? '停用' : '启用'}}</el-button><el-button link type="primary" :disabled="busy || loading" @click="reset(row)">重置密码</el-button></template></template></el-table-column>
      </el-table>
      <el-pagination v-if="!error && total > 0" :current-page="page" :page-size="size" :total="total" :page-sizes="[10,20,50]" :disabled="explicitDemo" layout="total, sizes, prev, pager, next" @update:current-page="page=$event" @update:page-size="changeSize" />
    </div>
    <el-dialog :model-value="visible" title="创建用户账号" width="600px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close" @update:model-value="value => { if (!value) close() }">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      <el-form label-position="top" :disabled="busy" @submit.prevent="save">
        <el-form-item label="登录账号" required><el-input v-model="form.username" autocomplete="off" maxlength="64" /></el-form-item>
        <el-form-item label="用户名称" required><el-input v-model="form.display_name" maxlength="120" /></el-form-item>
        <el-form-item label="角色" required><el-select v-model="form.role" @change="roleChanged"><el-option v-for="(name,key) in roleNames" :key="key" :label="name" :value="key" /></el-select></el-form-item>
        <el-form-item v-if="form.role !== 'ADMIN'" label="关联共享主体" required>
          <el-select v-model="form.subject_id" filterable remote :remote-method="loadSubjects" :loading="subjectLoading" placeholder="搜索已启用的主体或单位" :teleported="false">
            <el-option v-for="row in subjects" :key="row.subject_id" :label="row.subject_name" :value="row.subject_id"><span>{{row.subject_name}}</span><span class="option-unit">{{row.department}}</span></el-option>
            <template #footer><el-button v-if="subjects.length < subjectTotal" link type="primary" :disabled="subjectLoading || busy" @click="loadSubjects(subjectKeyword, true)">加载更多主体（已显示 {{subjects.length}} / {{subjectTotal}}）</el-button></template>
          </el-select>
          <el-alert v-if="subjectError" :title="subjectError" type="error" :closable="false"><el-button link @click="loadSubjects(subjectKeyword)">重试读取主体</el-button></el-alert>
        </el-form-item>
        <el-form-item label="初始密码" required><el-input v-model="form.password" type="password" autocomplete="new-password" placeholder="至少12个字符，不超过72个UTF-8字节" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="busy" @click="close">取消</el-button><el-button type="primary" :loading="busy" :disabled="subjectLoading || uncertain" @click="save">保存账号</el-button></template>
    </el-dialog>
    <el-dialog :model-value="resetVisible" title="重置密码" width="500px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close" @update:model-value="value => { if (!value) close() }">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      <el-form label-position="top" :disabled="busy || conflict" @submit.prevent="savePassword">
        <el-form-item label="登录账号"><span>{{resetTarget?.username}}</span></el-form-item>
        <el-form-item label="新密码" required><el-input v-model="resetPassword" type="password" autocomplete="new-password" placeholder="至少12个字符，不超过72个UTF-8字节" /></el-form-item>
      </el-form>
      <el-alert title="重置成功后，该账号的原登录会话将失效。" type="warning" :closable="false" />
      <template #footer><el-button :disabled="busy" @click="close">取消</el-button><el-button v-if="conflict || uncertain" type="primary" @click="refreshAfterConflict">关闭并刷新</el-button><el-button v-else type="primary" :loading="busy" @click="savePassword">保存新密码</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.account-filters { display:flex; gap:12px; margin-bottom:20px; }
.account-filters .el-input { flex:1; }
.account-filters .el-select { width:150px; }
.el-pagination { margin-top:20px; justify-content:flex-end; }
.el-form-item .el-select { width:100%; }
.option-unit { float:right; margin-left:20px; color:var(--el-text-color-secondary); font-size:12px; }
@media(max-width:900px) { .account-filters { flex-wrap:wrap; } .account-filters .el-input { flex-basis:100%; } }
</style>
