<script setup>
defineProps({ show: Boolean })
</script>
<template><div v-if="show" data-testid="demo-notice"><el-tag type="warning">演示数据</el-tag></div></template>
