<script setup>
defineProps({ show: Boolean, text: { type: String, default: '此区域使用前端演示数据；相关后端接口尚未实现，操作只保存在当前浏览器。' } })
</script>
<template><div v-if="show" class="note-banner"><strong>演示数据</strong><span>{{ text }}</span></div></template>
