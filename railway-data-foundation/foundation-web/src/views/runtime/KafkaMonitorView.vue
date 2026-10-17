<script setup>
import {ref,onBeforeUnmount,watch} from 'vue'
import {session,explicitDemo} from '../../utils/sessionState'
import KafkaProductionLogs from '../../components/KafkaProductionLogs.vue'
import ReceivingTransferLogs from '../../components/ReceivingTransferLogs.vue'
import KafkaConsumptionPanel from '../../components/KafkaConsumptionPanel.vue'
import {subscriptionApi} from '../../api/subscription'
import {assertSubscriptionPage} from '../../utils/subscription'
import {receivingReadGate} from '../../utils/receiving'
const tab=ref('production'),keyword=ref(''),candidates=ref([]),total=ref(0),error=ref(''),loading=ref(false),selected=ref(''),gate=receivingReadGate()
async function load(more=false){if(explicitDemo||loading.value)return;const token=gate.next(),offset=more?candidates.value.length:0;loading.value=true;error.value='';if(!more){candidates.value=[];total.value=0;selected.value=''}
  try{const data=assertSubscriptionPage(await subscriptionApi.list({keyword:keyword.value,limit:20,offset}),{limit:20,offset});if(gate.current(token)){candidates.value=more?[...candidates.value,...data.items]:data.items;total.value=data.total}}
  catch(e){if(gate.current(token)){candidates.value=[];total.value=0;selected.value='';error.value=e.response?.data?.message||e.message||'订阅列表读取失败'}}finally{if(gate.current(token))loading.value=false}}
watch(tab,value=>{if(value==='consumption'&&!candidates.value.length)load()})
onBeforeUnmount(()=>{gate.dispose();candidates.value=[];selected.value=''})
</script>
<template><div class="stack">
  <div class="page-heading"><h1>Kafka 传输监测</h1></div>
  <div class="flow"><div><b>① 提供方发送</b><span>Kafka 回执 · 主题 / 分区 / 位置</span></div><span>→</span><div><b>② 需求方接收</b><span>持久落库 · 重读去重 · 位置提交</span></div><span>→</span><div><b>③ 技术摘要上报</b><span>确认 / 待补报 / 拒绝</span></div></div>
  <div class="panel"><el-tabs v-model="tab"><el-tab-pane label="发送日志" name="production"><KafkaProductionLogs v-if="tab==='production'" /></el-tab-pane><el-tab-pane label="接收过程" name="receiver"><ReceivingTransferLogs v-if="tab==='receiver'" admin /></el-tab-pane><el-tab-pane label="订阅消费摘要" name="consumption"><div v-if="tab==='consumption'" class="stack"><div class="selector"><el-input v-model="keyword" placeholder="搜索授权名称或编号" style="width:300px" @keyup.enter="load()" /><el-button :disabled="explicitDemo" :loading="loading" @click="load()">查询订阅</el-button><el-select v-model="selected" placeholder="选择订阅授权" aria-label="监测订阅授权" style="width:400px" clearable><el-option v-for="row in candidates" :key="row.grant_id" :value="row.grant_id" :label="`${row.grant_name} · ${row.topic}`" /></el-select><el-button v-if="candidates.length<total" :loading="loading" @click="load(true)">加载更多</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load()">重试</el-button></el-alert><KafkaConsumptionPanel v-if="selected" :key="selected" :grant-id="selected" /><el-empty v-else description="选择订阅授权" /></div></el-tab-pane></el-tabs></div>
</div></template>
<style scoped>.flow{display:flex;gap:20px;align-items:center;background:#edf6f8;border:1px solid #dae8ed;border-radius:10px;padding:24px}.flow>div{flex:1;display:flex;flex-direction:column;gap:9px}.flow b{color:#184255;font-size:17px}.flow span{color:#607c8b;font-size:13px}.selector{display:flex;gap:12px;flex-wrap:wrap;align-items:center}@media(max-width:900px){.flow{flex-wrap:wrap}.flow>div{min-width:180px}}</style>
