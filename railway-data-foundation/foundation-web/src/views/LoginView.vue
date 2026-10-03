<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
const router = useRouter()
const name = ref('管理员')
function enter() { sessionStorage.setItem('foundation_demo_login', '1'); router.replace('/dashboard') }
</script>
<template>
  <div class="login-screen">
    <div class="login-left">
      <div><div class="brand-mark">轨</div><div class="eyebrow" style="color:#7fd2d8">RAILWAY DATA FOUNDATION</div><h1>让数据资源<br>可发现、可管理、可共享。</h1><p>统一数据底座虚拟接入与共享治理平台 · 演示环境</p></div>
      <div style="color:#9cb9c7;font-size:12px">数据留在源端，治理汇聚底座。</div>
    </div>
    <div class="login-right"><div class="login-box"><div class="eyebrow">PLATFORM ACCESS</div><h2>欢迎进入</h2><p class="dim">汇报演示入口</p><el-form @submit.prevent="enter"><el-form-item label="当前用户"><el-input v-model="name" readonly /></el-form-item><el-button type="primary" native-type="submit">进入平台</el-button></el-form><p class="dim" style="font-size:12px;line-height:1.7;margin-top:18px">此入口只用于前端演示，没有接入后端身份认证。</p></div></div>
  </div>
</template>
