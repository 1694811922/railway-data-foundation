<script setup>
import {providerReviewLabel} from '../utils/providerReview'
defineProps({row:{type:Object,required:true}})
</script>
<template><el-tag :type="row.provider_confirmed_at?'success':row.provider_review_mode==='REQUIRED'&&row.status==='PENDING'?'warning':'info'">{{providerReviewLabel(row)}}</el-tag></template>
