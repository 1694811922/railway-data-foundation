<script setup>
import { ref, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/auth'
import { explicitDemo } from '../utils/sessionState'
const router=useRouter(),username=ref(''),password=ref(''),busy=ref(false),error=ref('')
async function enter(){if(busy.value)return;busy.value=true;error.value='';try{await login(username.value,password.value);await router.replace('/dashboard')}catch(e){error.value=e.response?.data?.message||'登录失败，请检查账号和服务连接'}finally{password.value='';busy.value=false}}
onBeforeUnmount(()=>{password.value=''})
</script>
<template><div class="login-screen"><div class="login-left"><div><div class="brand-mark">轨</div><h1>让数据资源<br>可发现、可管理、可共享。</h1><p>统一数据底座虚拟接入与共享治理平台</p></div></div><div class="login-right"><div class="login-box"><h2>{{explicitDemo?'演示工作台':'登录工作台'}}</h2><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-if="explicitDemo" title="显式演示模式，与真实系统隔离" type="warning" :closable="false" /><el-form @submit.prevent="enter" label-position="top"><template v-if="!explicitDemo"><el-form-item label="登录账号"><el-input v-model="username" aria-label="登录账号" autocomplete="username" maxlength="64" /></el-form-item><el-form-item label="登录密码"><el-input v-model="password" aria-label="登录密码" type="password" show-password autocomplete="current-password" maxlength="72" /></el-form-item></template><el-button type="primary" native-type="submit" :loading="busy">进入平台</el-button></el-form><router-link v-if="!explicitDemo" to="/register" class="register-link">没有账号？注册账号</router-link></div></div></div></template>
<style scoped>.register-link { display:block; margin-top:24px; text-align:center; color:#16738a; }</style>
