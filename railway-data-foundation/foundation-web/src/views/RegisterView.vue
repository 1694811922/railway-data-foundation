<script setup>
import { reactive, ref, onMounted, onBeforeUnmount } from 'vue'
import { registrationApi } from '../api/registration'
import { explicitDemo } from '../utils/sessionState'
import { registrationBody, registrationError } from '../utils/registration'
const form = reactive({ username:'', display_name:'', role:'DEMAND', organization_name:'', password:'', confirm_password:'' })
const bootstrap = ref(false), ready = ref(false), loading = ref(false), busy = ref(false), error = ref(''), success = ref(''), uncertain = ref(false)
let active = true
function clearPasswords() { form.password = ''; form.confirm_password = '' }
async function load() {
  if (explicitDemo || loading.value) return
  loading.value = true; ready.value = false; error.value = ''
  try { const data = await registrationApi.options(); if (active) { bootstrap.value = data.bootstrap_available; ready.value = true } }
  catch (e) { if (active) error.value = e.response?.data?.message || '注册配置读取失败，请重试' }
  finally { if (active) loading.value = false }
}
async function submit() {
  if (!ready.value || busy.value || uncertain.value || success.value || explicitDemo) return
  error.value = registrationError(form, bootstrap.value)
  if (error.value) return
  busy.value = true
  try {
    await registrationApi.submit(registrationBody(form, bootstrap.value), bootstrap.value)
    if (active) success.value = bootstrap.value ? '管理员已创建，请使用刚才设置的账号和密码登录。' : '注册申请已提交，管理员审核通过后可登录。'
  } catch (e) { if (active) { error.value = e.response?.data?.message || '提交结果尚未确认，请联系管理员核对，勿重复提交'; uncertain.value = !e.response || e.response.status >= 500; if (e.response?.status === 409) { ready.value = false; clearPasswords() } } }
  finally { clearPasswords(); if (active) busy.value = false }
}
onMounted(load)
onBeforeUnmount(() => { active = false; clearPasswords() })
</script>
<template>
  <div class="login-screen">
    <div class="login-left"><div><div class="brand-mark">轨</div><h1>统一入口<br>连接数据供需。</h1><p>统一数据底座虚拟接入与共享治理平台</p></div></div>
    <div class="login-right"><div class="login-box registration-box" v-loading="loading">
      <h2>{{ bootstrap ? '初始化管理员' : '注册账号' }}</h2>
      <el-alert v-if="explicitDemo" title="演示模式不注册真实账号" type="warning" :closable="false" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="success" :title="success" type="success" :closable="false" data-testid="registration-success" />
      <template v-if="!success && !explicitDemo">
        <el-alert v-if="bootstrap && ready" title="本机尚无管理员，首个账号将成为平台管理员。" type="info" :closable="false" />
        <el-form label-position="top" :disabled="!ready || busy || uncertain" @submit.prevent="submit">
          <el-form-item label="登录账号" required><el-input v-model="form.username" aria-label="注册登录账号" autocomplete="username" maxlength="64" /></el-form-item>
          <el-form-item label="用户名称" required><el-input v-model="form.display_name" aria-label="注册用户名称" maxlength="120" /></el-form-item>
          <template v-if="!bootstrap">
            <el-form-item label="申请角色" required><el-select v-model="form.role" aria-label="申请角色"><el-option label="数据提供方" value="PROVIDER" /><el-option label="数据需求方" value="DEMAND" /></el-select></el-form-item>
            <el-form-item label="所属单位" required><el-input v-model="form.organization_name" aria-label="注册所属单位" maxlength="120" /></el-form-item>
          </template>
          <el-form-item label="设置密码" required><el-input v-model="form.password" aria-label="注册密码" type="password" show-password autocomplete="new-password" placeholder="至少12个字符，不超过72个UTF-8字节" /></el-form-item>
          <el-form-item label="确认密码" required><el-input v-model="form.confirm_password" aria-label="确认注册密码" type="password" autocomplete="new-password" /></el-form-item>
          <el-button type="primary" native-type="submit" :loading="busy" :disabled="!ready || uncertain">{{ bootstrap ? '创建管理员' : '提交注册申请' }}</el-button>
        </el-form>
        <el-button v-if="!ready && error && !busy" @click="load">重新读取注册配置</el-button>
      </template>
      <router-link to="/login" class="auth-link">返回登录</router-link>
    </div></div>
  </div>
</template>
<style scoped>
.registration-box { width:min(430px,100%); }
.el-alert { margin:16px 0; }
.el-form-item { margin-bottom:16px; }
.el-select { width:100%; }
.auth-link { display:block; text-align:center; margin-top:22px; color:#16738a; }
</style>
