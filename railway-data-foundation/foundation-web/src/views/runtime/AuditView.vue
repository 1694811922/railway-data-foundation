<script setup>
import {onMounted,onBeforeUnmount,reactive,ref} from 'vue'
import {useRouter} from 'vue-router'
import {telemetryApi} from '../../api/telemetry'
import {filterForm,runtimeQuery,eventPage,assertEvent,outcomes,reasons,quantity,tagType} from '../../utils/telemetry'
import {displayTime} from '../../utils/time'
import RuntimeFilters from '../../components/RuntimeFilters.vue'
import RuntimeEventDetails from '../../components/RuntimeEventDetails.vue'
const router=useRouter(),mock=import.meta.env.VITE_USE_MOCK==='true',form=reactive(filterForm())
const rows=ref([]),total=ref(0),page=ref(1),size=ref(20),loading=ref(false),error=ref(''),drawer=ref(false),selected=ref(null),detailError=ref(''),detailLoading=ref(false),selectedId=ref('')
let active=true,readEpoch=0,detailEpoch=0,applied=runtimeQuery(form)
async function load(){
  if(mock)return
  const current=++readEpoch;rows.value=[];total.value=0;error.value='';loading.value=true
  const params={...applied,limit:size.value,offset:(page.value-1)*size.value}
  try{const result=eventPage(await telemetryApi.events(params),params);if(active&&current===readEpoch){rows.value=result.rows;total.value=result.total}}
  catch{if(active&&current===readEpoch)error.value='调用记录读取失败，请重新查询'}finally{if(active&&current===readEpoch)loading.value=false}
}
function query(){try{applied=runtimeQuery(form);page.value=1;load()}catch(e){++readEpoch;loading.value=false;rows.value=[];total.value=0;error.value=e.message}}
function reset(){Object.assign(form,filterForm());query()}
async function show(row){const current=++detailEpoch;selectedId.value=row.event_id;drawer.value=true;selected.value=null;detailError.value='';detailLoading.value=true
  try{const event=assertEvent(await telemetryApi.event(row.event_id),{id:row.event_id});if(active&&current===detailEpoch)selected.value=event}
  catch{if(active&&current===detailEpoch)detailError.value='事件详情读取失败'}finally{if(active&&current===detailEpoch)detailLoading.value=false}}
function drawerChanged(open){if(!open){++detailEpoch;selected.value=null;selectedId.value='';detailError.value='';detailLoading.value=false}}
onMounted(load);onBeforeUnmount(()=>{active=false;++readEpoch;++detailEpoch})
</script>
<template><div class="stack">
  <div class="page-heading"><h1>调用审计</h1><el-button :disabled="mock" :loading="loading" @click="load">刷新记录</el-button></div>
  <el-alert v-if="mock" title="演示模式下不读取运行记录" type="warning" :closable="false" />
  <div class="panel"><RuntimeFilters :form="form" :busy="loading" @query="query" @reset="reset" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon data-testid="audit-error" />
    <el-table v-else :data="rows" v-loading="loading" empty-text="暂无符合条件的调用记录" data-testid="audit-table">
      <el-table-column label="完成时间" min-width="175"><template #default="{row}">{{displayTime(row.completed_at)}}</template></el-table-column>
      <el-table-column label="结果" width="85"><template #default="{row}"><el-tag :type="tagType(row.outcome)">{{outcomes[row.outcome]}}</el-tag></template></el-table-column>
      <el-table-column label="执行结果" min-width="170"><template #default="{row}">{{reasons[row.reason_code]}}</template></el-table-column>
      <el-table-column prop="resource_id" label="资源编号" min-width="190" show-overflow-tooltip /><el-table-column prop="relationship_id" label="关系编号" min-width="190" show-overflow-tooltip />
      <el-table-column label="记录数" width="95"><template #default="{row}">{{quantity(row.record_count)}}</template></el-table-column><el-table-column label="字节数（B）" width="125"><template #default="{row}">{{quantity(row.byte_count)}}</template></el-table-column><el-table-column prop="duration_ms" label="耗时（ms）" width="105" />
      <el-table-column label="操作" width="125" fixed="right"><template #default="{row}"><el-button link type="primary" @click="show(row)">详情</el-button><el-button link type="primary" @click="router.push('/runtime-traces/'+row.trace_id)">追踪</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="!error" v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[20,50,100]" layout="total,sizes,prev,pager,next" :disabled="loading||mock" @change="load" />
  </div>
  <el-drawer v-model="drawer" title="调用事件详情" size="700px" @update:model-value="drawerChanged"><div v-loading="detailLoading"><template v-if="detailError"><el-alert :title="detailError" type="error" :closable="false" /><el-button @click="show({event_id:selectedId})">重试详情</el-button></template><RuntimeEventDetails :event="selected" /></div></el-drawer>
</div></template>
<style scoped>.el-pagination{justify-content:flex-end;margin-top:20px}.el-alert{margin-bottom:16px}</style>
