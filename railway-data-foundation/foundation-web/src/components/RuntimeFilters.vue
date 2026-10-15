<script setup>
import {collectionPoints,outcomes,scopeTypes} from '../utils/telemetry'
defineProps({form:Object,metrics:Boolean,busy:Boolean})
defineEmits(['query','reset'])
</script>
<template>
  <el-form label-position="top" class="runtime-filters" @submit.prevent="$emit('query')">
    <el-form-item label="采集位置"><el-select v-model="form.collectionPoint" aria-label="采集位置"><el-option v-for="(label,value) in collectionPoints" :key="value" :value="value" :label="label" /></el-select></el-form-item>
    <el-form-item label="筛选对象"><el-select v-model="form.scopeType" aria-label="筛选对象" @change="form.scopeId=''"><el-option v-for="(option,value) in scopeTypes" :key="value" :value="value" :label="option.label" /></el-select></el-form-item>
    <el-form-item label="关联编号"><el-input v-model="form.scopeId" clearable :placeholder="scopeTypes[form.scopeType].label+'编号（可选）'" aria-label="关联编号" /></el-form-item>
    <el-form-item v-if="!metrics" label="调用结果"><el-select v-model="form.outcome" clearable placeholder="全部结果" aria-label="调用结果"><el-option v-for="(label,value) in outcomes" :key="value" :value="value" :label="label" /></el-select></el-form-item>
    <el-form-item v-if="!metrics" label="追踪编号"><el-input v-model="form.traceId" clearable placeholder="追踪编号（可选）" aria-label="追踪编号筛选" /></el-form-item>
    <el-form-item label="时间范围" class="time-range"><el-date-picker v-model="form.range" type="datetimerange" :format="metrics?'YYYY-MM-DD HH:mm':'YYYY-MM-DD HH:mm:ss'" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item>
    <div class="filter-buttons"><el-button type="primary" native-type="submit" :loading="busy">查询</el-button><el-button @click="$emit('reset')">重置</el-button></div>
  </el-form>
</template>
<style scoped>
.runtime-filters{display:grid;grid-template-columns:repeat(4,minmax(150px,1fr));gap:14px;align-items:end;margin-bottom:20px}.el-form-item{margin:0}.el-select,.el-date-editor{width:100%}.time-range{grid-column:span 2}.filter-buttons{display:flex;gap:8px;padding-bottom:1px}
@media(max-width:1100px){.runtime-filters{grid-template-columns:1fr 1fr}}
@media(max-width:700px){.runtime-filters{grid-template-columns:1fr}.time-range{grid-column:auto}}
</style>
