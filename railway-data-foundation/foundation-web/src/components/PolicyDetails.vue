<script setup>
import { displayTime } from '../utils/time'
defineProps({policy:{type:Object,required:true}})
</script>
<template>
  <el-descriptions :column="1" :label-width="130" border>
    <el-descriptions-item label="策略编号"><span class="id-text">{{policy.policy_id}}</span></el-descriptions-item>
    <el-descriptions-item label="允许返回字段"><div class="field-tags"><el-tag v-for="field in policy.allowed_fields" :key="field">{{field}}</el-tag></div></el-descriptions-item>
    <el-descriptions-item label="时间条件字段">{{policy.time_field}}</el-descriptions-item>
    <el-descriptions-item label="数据时间范围">{{displayTime(policy.data_from)}} 至 {{displayTime(policy.data_until)}}（含起点，不含终点）</el-descriptions-item>
    <el-descriptions-item label="策略版本">{{policy.version}}</el-descriptions-item>
  </el-descriptions>
</template>
<style scoped>.field-tags{display:flex;gap:6px;flex-wrap:wrap}</style>
