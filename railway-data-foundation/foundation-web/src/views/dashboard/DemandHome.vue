<script setup>
import { ref,onMounted,onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { session } from '../../utils/sessionState'
import { resourceApi } from '../../api/resource'
import { sharingApi } from '../../api/sharing'
import { subscriptionApi } from '../../api/subscription'
import { credentialApi } from '../../api/access'
import { fetchDashboardList } from '../../utils/dashboard'
const router=useRouter(),cards=ref([]),error=ref(''),loading=ref(false);let active=true
async function load(){loading.value=true;cards.value=[];error.value='';try{
 const data=await Promise.all([fetchDashboardList(resourceApi.list,'resource_id'),fetchDashboardList(sharingApi.list,'relationship_id'),subscriptionApi.list({limit:1,offset:0}),fetchDashboardList(credentialApi.list,'credential_id')])
 if(active)cards.value=[['可发现资源',data[0].rows.length,'/resources'],['我的共享申请',data[1].rows.length,'/relationships'],['我的 Kafka 订阅',data[2].total,'/kafka-subscriptions'],['我的REST调用凭据',data[3].rows.length,'/credentials']]
 }catch(e){if(active)error.value=e.response?.data?.message||'工作台读取失败'}finally{if(active)loading.value=false}}
onMounted(load);onBeforeUnmount(()=>{active=false})
</script>
<template><div class="stack" v-loading="loading"><div class="page-heading"><h1>数据需求方工作台</h1><el-button @click="load">刷新</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" /><div class="grid-2"><div v-for="[title,count,path] in cards" :key="path" class="panel"><h3>{{title}}</h3><div style="font-size:34px;color:#126f89;margin:20px 0">{{count}}</div><el-button @click="router.push(path)">查看</el-button></div></div><div class="panel"><h3>{{session.user?.subject_name}}</h3><p>选择公开资源 → 提交共享申请 → 管理员审批与配置 → 领取访问凭据</p><p>业务数据由外部提供服务交付，底座只登记资源和管理授权。</p></div></div></template>
