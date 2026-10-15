<script setup>
import { ref,onBeforeUnmount,onMounted,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { resourceApi } from '../../api/resource'
import { capabilityApi } from '../../api/capability'
import StatusTag from '../../components/StatusTag.vue'
import { capabilityTypes } from '../../utils/dashboard'
import { subscriptionCapabilities } from '../../utils/subscriptionCatalog'
const route=useRoute(),router=useRouter(),resource=ref(null),fields=ref([]),capabilities=ref([]),loading=ref(false),error=ref('')
let epoch=0,active=true
const typeLabel=type=>capabilityTypes.find(row=>row.type===type)?.label||type
const canApply=row=>!!resource.value?.provider_subject_id && (row.type==='REST_API'||subscriptionCapabilities([row],resource.value.resource_id).length===1)
async function load(){const id=route.params.id,token=++epoch;resource.value=null;fields.value=[];capabilities.value=[];error.value='';loading.value=true
  try{const data=await Promise.all([resourceApi.get(id),resourceApi.fields(id),capabilityApi.byResource(id)]);if(active&&epoch===token)[resource.value,fields.value,capabilities.value]=data}
  catch(e){if(active&&epoch===token)error.value=e.response?.data?.message||'资源读取失败，请重试'}finally{if(active&&epoch===token)loading.value=false}}
onMounted(load);watch(()=>route.params.id,load);onBeforeUnmount(()=>{active=false;++epoch})
</script>
<template><div class="stack" v-loading="loading"><div class="page-heading"><h1>{{resource?.resource_name||'资源详情'}}</h1><el-button @click="router.push('/resources')">返回目录</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false"><el-button @click="load">重试</el-button></el-alert><template v-if="resource">
  <div class="panel"><el-descriptions :column="2" border><el-descriptions-item label="资源编号">{{resource.resource_id}}</el-descriptions-item><el-descriptions-item label="来源系统">{{resource.source_name}}</el-descriptions-item><el-descriptions-item label="责任单位">{{resource.owner_department}}</el-descriptions-item><el-descriptions-item label="状态"><StatusTag :value="resource.status" /></el-descriptions-item><el-descriptions-item label="安全等级">{{resource.security_level}}</el-descriptions-item><el-descriptions-item label="更新方式">{{resource.update_mode}}</el-descriptions-item><el-descriptions-item label="业务描述" :span="2">{{resource.business_description}}</el-descriptions-item></el-descriptions></div>
  <div class="panel"><el-tabs><el-tab-pane label="字段元数据"><el-table :data="fields"><el-table-column prop="field_name" label="字段名" /><el-table-column prop="business_name" label="业务名称" /><el-table-column prop="data_type" label="数据类型" /><el-table-column prop="business_description" label="业务说明" /><el-table-column prop="sensitivity_level" label="敏感等级" /></el-table></el-tab-pane><el-tab-pane label="可申请接口能力"><el-table :data="capabilities" empty-text="暂无可申请能力"><el-table-column prop="capability_name" label="能力名称" /><el-table-column label="能力类型"><template #default="{row}">{{typeLabel(row.type)}}</template></el-table-column><el-table-column label="接口路径 / 消息主题"><template #default="{row}">{{row.endpoint||row.topic}}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button v-if="canApply(row)" link type="primary" @click="router.push({path:row.type==='KAFKA_TOPIC'?'/kafka-subscriptions':'/relationships',query:{resource:resource.resource_id,capability:row.capability_id}})">{{row.type==='KAFKA_TOPIC'?'申请订阅':'申请共享'}}</el-button><span v-else>暂不可申请</span></template></el-table-column></el-table></el-tab-pane></el-tabs></div>
</template></div></template>
