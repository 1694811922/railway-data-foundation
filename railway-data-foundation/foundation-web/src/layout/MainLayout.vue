<script setup>
import { useRoute, useRouter } from 'vue-router'
import { session, explicitDemo } from '../utils/sessionState'
import { menus, roleNames } from '../utils/workspace'
import { logout as endSession } from '../api/auth'
import { ref } from 'vue'
import PasswordChange from '../components/PasswordChange.vue'
const exiting=ref(false)
const route = useRoute(); const router = useRouter()
async function logout(){exiting.value=true;try{await endSession();router.replace('/login')}catch{/* The request handler displays the error and clears expired sessions. */}finally{exiting.value=false}}
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark">轨</div><div class="brand-title">统一数据底座</div></div>

      <el-menu :default-active="route.path.startsWith('/resources/') ? '/resources' : route.path.startsWith('/runtime-traces/') ? '/runtime-traces' : route.path" router>
        <template v-for="[key,label] in menus[session.user?.role]||[]" :key="key">
          <el-sub-menu v-if="Array.isArray(label)" :index="key"><template #title>{{key}}</template><el-menu-item v-for="[path,title] in label" :key="path" :index="path">{{title}}</el-menu-item></el-sub-menu>
          <el-menu-item v-else :index="key">{{label}}</el-menu-item>
        </template>
      </el-menu>

    </aside>
    <div class="workspace">
      <header class="topbar"><div class="top-title">统一数据底座虚拟接入与共享治理平台</div><div class="top-meta"><span class="env">{{explicitDemo?'● 显式演示':'● 实验环境'}}</span><span>{{session.user?.display_name}} · {{roleNames[session.user?.role]}} · {{session.user?.department||session.user?.subject_name||'平台管理'}}</span><PasswordChange :key="session.epoch" /><el-button text size="small" :loading="exiting" @click="logout">退出</el-button></div></header>
      <main class="main-content"><router-view :key="session.epoch" /></main>
    </div>
  </div>
</template>
