<script setup>
import { useRoute, useRouter } from 'vue-router'
const route = useRoute(); const router = useRouter()
function logout() { sessionStorage.removeItem('foundation_demo_login'); router.replace('/login') }
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark">轨</div><div class="brand-title">统一数据底座</div><div class="brand-sub">DATA FOUNDATION</div></div>
      <div class="nav-label">平台导航</div>
      <el-menu :default-active="route.path.startsWith('/resources/') ? '/resources' : route.path" router>
        <el-menu-item index="/dashboard">首页</el-menu-item>
        <el-sub-menu index="access"><template #title>接入管理</template><el-menu-item index="/sources">数据源管理</el-menu-item><el-menu-item index="/scans">元数据扫描</el-menu-item></el-sub-menu>
        <el-sub-menu index="catalog"><template #title>数据资源</template><el-menu-item index="/resources">资源目录</el-menu-item></el-sub-menu>
        <el-menu-item index="/capabilities">接口能力</el-menu-item>
        <el-menu-item index="/about">系统说明</el-menu-item>
      </el-menu>
      <div class="sidebar-foot">铁路数据共享治理原型 · V0.2 演示</div>
    </aside>
    <div class="workspace">
      <header class="topbar"><div class="top-title">统一数据底座虚拟接入与共享治理平台</div><div class="top-meta"><span class="env">● 实验环境</span><span><span class="avatar">管</span>管理员</span><el-button text size="small" @click="logout">退出</el-button></div></header>
      <main class="main-content"><router-view /></main>
    </div>
  </div>
</template>
