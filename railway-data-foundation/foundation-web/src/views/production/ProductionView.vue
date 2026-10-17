<script setup>
import { computed,onBeforeUnmount,onMounted,reactive,ref,watch } from 'vue'
import KafkaProductionLogs from '../../components/KafkaProductionLogs.vue'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { capabilityApi } from '../../api/capability'
import { productionApi } from '../../api/production'
import { explicitDemo,session } from '../../utils/sessionState'
import { productionLabels,productionCounts,recordPage,sendSelected } from '../../utils/production'
const logVisible=ref(false)
const route=useRoute(), candidates=ref([]),capability=ref(''),context=ref(null),rows=ref([]),selected=ref([]),total=ref(0),page=ref(1),size=ref(20)
const error=ref(''),loading=ref(false),optionsLoading=ref(false),optionsError=ref(''),hasMore=ref(true),sending=ref(false),cancelled=ref(false),results=ref([]),record=ref(null)
const filters=reactive({containerNo:'',trainNo:'',eventType:'',range:[]})
let active=true,read=0,scope=0,optionsOffset=0,optionRead=0
const counts=computed(()=>productionCounts(results.value))
const chosen=computed(()=>candidates.value.find(row=>row.capability_id===capability.value))
const eligible=row=>row.type==='KAFKA_TOPIC'&&row.status==='ENABLED'&&row.health_status==='UP'
const message=e=>e?.response?.data?.message||e.message||'操作失败，请重试'
function clearData(){rows.value=[];selected.value=[];total.value=0;record.value=null}
async function options(){if(explicitDemo)return;const token=++optionRead;optionsLoading.value=true;optionsError.value='';try{const list=await capabilityApi.list({limit:200,offset:optionsOffset});if(!active||token!==optionRead)return
  if(!Array.isArray(list))throw new Error('能力列表响应无效');for(const row of list.filter(row=>row.type==='KAFKA_TOPIC'))if(!candidates.value.some(old=>old.capability_id===row.capability_id))candidates.value.push(row)
  optionsOffset+=list.length;hasMore.value=list.length===200
  const requested=typeof route.query.capability==='string'?route.query.capability:''
  if(!capability.value&&requested){const row=candidates.value.find(row=>row.capability_id===requested)||await capabilityApi.get(requested);if(!active||token!==optionRead)return;if(row.type!=='KAFKA_TOPIC')throw new Error('请选择Kafka主题能力');if(!candidates.value.some(old=>old.capability_id===requested))candidates.value.push(row);capability.value=requested}
  if(!capability.value)capability.value=candidates.value.find(eligible)?.capability_id||''
 }catch(e){if(active&&token===optionRead)optionsError.value=message(e)}finally{if(active&&token===optionRead)optionsLoading.value=false}}
async function load(){const token=++read,currentScope=scope;clearData();context.value=null;error.value='';loading.value=false;if(explicitDemo||!capability.value)return;loading.value=true
 try{const admission=await productionApi.context(capability.value);if(!active||token!==read||currentScope!==scope)return
  if(admission?.runtime?.capability_id!==capability.value||!admission.runtime.enabled)throw new Error('能力当前不可发送')
  const params={limit:size.value,offset:(page.value-1)*size.value};for(const key of ['containerNo','trainNo','eventType'])if(filters[key].trim())params[key]=filters[key].trim()
  if(filters.range?.length===2){params.from=new Date(filters.range[0]).toISOString();params.until=new Date(filters.range[1]).toISOString()}
  const data=recordPage(await productionApi.records(capability.value,params));if(!active||token!==read||currentScope!==scope)return;context.value=admission.runtime;rows.value=data.items;total.value=data.total
 }catch(e){if(active&&token===read&&currentScope===scope){clearData();context.value=null;error.value=message(e)}}finally{if(active&&token===read)loading.value=false}}
function query(){page.value=1;load()}
async function send(items){if(sending.value||loading.value||!context.value||explicitDemo||!items.length||items.length>50)return
 const currentScope=scope,epoch=session.epoch,id=capability.value,topic=context.value.topic
 sending.value=true;cancelled.value=false
 try{await ElMessageBox.confirm(`将 ${items.length} 条源记录发送到 ${topic}。重复发送会产生新消息。`,'确认发送',{confirmButtonText:'发送',cancelButtonText:'取消',type:'warning'})
  if(!active||currentScope!==scope||epoch!==session.epoch)return
  results.value=items.map(row=>({id:row.id,state:'PENDING',reason:'',receipt:null}))
  await sendSelected(results.value,{send:recordId=>productionApi.send(id,recordId),topic,current:()=>active&&currentScope===scope&&epoch===session.epoch,cancelled:()=>cancelled.value,changed:()=>{}})
 }catch(e){if(e!=='cancel'&&e!=='close'&&active&&currentScope===scope)error.value=message(e)}finally{if(active&&currentScope===scope)sending.value=false}}
watch(capability,()=>{scope++;read++;results.value=[];context.value=null;clearData();page.value=1;load()})
watch(()=>session.epoch,()=>{scope++;read++;optionRead++;cancelled.value=true;sending.value=false;clearData();context.value=null;results.value=[];candidates.value=[]})
onMounted(options);onBeforeUnmount(()=>{active=false;scope++;read++;optionRead++;cancelled.value=true;clearData();results.value=[];context.value=null})
</script>
<template>
 <div><div class="page-heading"><h2>数据发送</h2><el-button :disabled="sending||explicitDemo" @click="logVisible=true">发送记录</el-button></div>
  <el-alert v-if="explicitDemo" title="演示模式不读取真实源记录，也不发送消息" type="info" :closable="false" />
  <el-card v-else>
   <el-alert v-if="optionsError" :title="optionsError" type="error" :closable="false"><el-button @click="options">重试加载能力</el-button></el-alert>
   <el-form label-position="top" class="filters">
    <el-form-item label="接口能力"><el-select v-model="capability" :disabled="sending" :loading="optionsLoading" placeholder="选择Kafka能力" filterable><el-option v-for="row in candidates" :key="row.capability_id" :label="row.capability_name+' · '+row.topic" :value="row.capability_id" :disabled="!eligible(row)" /></el-select></el-form-item>
    <el-form-item label="集装箱编号"><el-input v-model="filters.containerNo" :disabled="sending" maxlength="50" clearable /></el-form-item>
    <el-form-item label="车次"><el-input v-model="filters.trainNo" :disabled="sending" maxlength="50" clearable /></el-form-item>
    <el-form-item label="事件类型"><el-input v-model="filters.eventType" :disabled="sending" maxlength="50" clearable /></el-form-item>
    <el-form-item label="事件时间"><el-date-picker v-model="filters.range" type="datetimerange" :disabled="sending" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item>
   </el-form>
   <div class="toolbar"><el-button type="primary" :disabled="sending||!capability" :loading="loading" @click="query">查询源记录</el-button><el-button v-if="hasMore" :loading="optionsLoading" :disabled="sending" @click="options">加载更多能力</el-button><el-button type="primary" :disabled="sending||loading||!selected.length||!context" @click="send(selected)">发送选中记录（{{selected.length}}）</el-button><el-button v-if="sending" :disabled="cancelled" @click="cancelled=true">取消后续发送</el-button></div>
   <el-descriptions v-if="context" :column="3" border class="binding"><el-descriptions-item label="资源编号">{{context.resource_id}}</el-descriptions-item><el-descriptions-item label="物理表">{{context.physical_table}}</el-descriptions-item><el-descriptions-item label="目标主题">{{context.topic}}</el-descriptions-item></el-descriptions>
   <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button :disabled="sending" @click="load">重试查询</el-button></el-alert>
   <el-table :data="rows" v-loading="loading" @selection-change="selected=$event" row-key="id">
    <el-table-column type="selection" :selectable="()=>!sending" width="50" /><el-table-column prop="id" label="记录编号" width="100" /><el-table-column prop="container_no" label="集装箱编号" min-width="160" /><el-table-column prop="event_type" label="事件类型" width="130" /><el-table-column prop="event_time" label="事件时间" min-width="190" /><el-table-column prop="train_no" label="车次" width="120" /><el-table-column prop="operation_status" label="作业状态" width="130" />
    <el-table-column label="操作" width="160"><template #default="{row}"><el-button link @click="record=row">查看</el-button><el-button link type="primary" :disabled="sending||!context" @click="send([row])">发送</el-button></template></el-table-column>
   </el-table>
   <el-pagination :current-page="page" :page-size="size" :page-sizes="[10,20,50]" :total="total" :disabled="sending" layout="total,sizes,prev,pager,next" @current-change="page=$event;load()" @size-change="size=$event;query()" />
  </el-card>
  <el-card v-if="results.length" class="results"><h3>本次发送结果</h3><p>发送成功 {{counts.SUCCESS}} · 未发送 {{counts.NOT_SENT}} · 结果待确认 {{counts.UNCONFIRMED}} · 未开始 {{counts.PENDING}}</p><el-alert title="发送成功表示Kafka已确认接收；需求方是否落库请在数据接收页面核对" type="info" :closable="false" />
   <el-table :data="results"><el-table-column prop="id" label="源记录编号" width="130" /><el-table-column label="发送状态" width="140"><template #default="{row}"><el-tag :type="row.state==='SUCCESS'?'success':row.state==='UNCONFIRMED'?'warning':row.state==='NOT_SENT'?'danger':'info'">{{productionLabels[row.state]}}</el-tag></template></el-table-column><el-table-column label="主题" min-width="200"><template #default="{row}">{{row.receipt?.topic||'—'}}</template></el-table-column><el-table-column label="分区" width="80"><template #default="{row}">{{row.receipt?.partition??'—'}}</template></el-table-column><el-table-column label="消息位置" width="110"><template #default="{row}">{{row.receipt?.offset??'—'}}</template></el-table-column><el-table-column prop="reason" label="错误或提示" min-width="220" /></el-table>
  </el-card>
  <el-dialog :model-value="!!record" title="源记录详情" width="700px" @close="record=null"><pre v-if="record">{{JSON.stringify(record,null,2)}}</pre></el-dialog>
 </div>
<el-drawer v-model="logVisible" title="本方发送记录" size="90%" destroy-on-close><KafkaProductionLogs v-if="logVisible" /></el-drawer>
</template>
<style scoped>
.filters{display:grid;grid-template-columns:2fr 1fr 1fr 1fr;gap:16px}.filters .el-form-item:last-child{grid-column:span 2}.toolbar{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px}.binding{margin:16px 0}.results{margin-top:20px}.el-pagination{margin-top:20px}pre{white-space:pre-wrap;overflow-wrap:anywhere} @media(max-width:1000px){.filters{grid-template-columns:1fr 1fr}}
</style>
