<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {telemetryApi} from '../../api/telemetry'
import {traceEvents,uuidPattern,collectionPoints,outcomes,tagType} from '../../utils/telemetry'
import {displayTime} from '../../utils/time'
import RuntimeEventDetails from '../../components/RuntimeEventDetails.vue'
const route=useRoute(),router=useRouter(),mock=import.meta.env.VITE_USE_MOCK==='true'
const input=ref(''),rows=ref([]),loading=ref(false),error=ref(''),searched=ref(false)
let active=true,epoch=0
async function load(id=String(route.params.id||'')){
  const current=++epoch;rows.value=[];error.value='';searched.value=false;loading.value=false
  if(mock||!id)return
  if(!uuidPattern.test(id)){error.value='请填写有效的追踪编号';return}
  loading.value=true;searched.value=true
  try{const data=traceEvents(await telemetryApi.trace(id),id);if(active&&current===epoch)rows.value=data}
  catch{if(active&&current===epoch)error.value='追踪记录读取失败，请重试'}finally{if(active&&current===epoch)loading.value=false}
}
function search(){const id=input.value.trim();if(!uuidPattern.test(id)){++epoch;loading.value=false;rows.value=[];error.value='请填写有效的追踪编号';return}if(route.params.id===id)load(id);else router.push('/runtime-traces/'+id)}
watch(()=>route.params.id,id=>{input.value=String(id||'');load(String(id||''))},{immediate:true})
onBeforeUnmount(()=>{active=false;++epoch})
</script>
<template><div class="stack">
  <div class="page-heading"><h1>调用追踪</h1><el-button @click="router.push('/runtime-events')">查看调用审计</el-button></div>
  <el-alert v-if="mock" title="演示模式下不读取追踪记录" type="warning" :closable="false" />
  <div class="panel"><el-form class="trace-search" @submit.prevent="search"><el-input v-model="input" placeholder="输入追踪编号" aria-label="查询追踪编号" clearable /><el-button type="primary" native-type="submit" :loading="loading" :disabled="mock">查询追踪</el-button></el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="trace-error" />
    <div v-else v-loading="loading"><el-empty v-if="!rows.length&&!loading" :description="searched?'未找到该追踪的上报记录':'请输入追踪编号，或从调用审计进入'" />
      <el-timeline v-else data-testid="trace-timeline"><el-timeline-item v-for="event in rows" :key="event.event_id" :timestamp="displayTime(event.completed_at)" :type="tagType(event.outcome)" placement="top"><h3>{{collectionPoints[event.collection_point]}} · {{outcomes[event.outcome]}}</h3><RuntimeEventDetails :event="event" /></el-timeline-item></el-timeline>
    </div>
  </div>
</div></template>
<style scoped>.trace-search{display:flex;gap:12px;max-width:760px;margin-bottom:22px}.el-timeline{padding-left:12px}.el-timeline h3{margin:6px 0 16px}</style>
