<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import {hub} from '../utils/dataHub'
import {session} from '../utils/sessionState'
import {lifecycleImpact} from '../utils/resourceLifecycle'
import {resourceError} from '../utils/resourceForm'
import {receivingReadGate} from '../utils/receiving'
const props=defineProps({resource:Object,demo:Boolean}),pending=ref(null),error=ref(''),loading=ref(false),gate=receivingReadGate()
async function load(){
 const resource=props.resource;if(!resource)return
 const token=gate.next(),epoch=session.epoch;pending.value=null;error.value='';loading.value=true
 try{const result=lifecycleImpact(await hub.resourceLifecycleImpact(resource.resource_id),resource,props.demo);if(gate.current(token)&&epoch===session.epoch)pending.value=result.kafka_revoke_pending}
 catch(e){if(gate.current(token)&&epoch===session.epoch)error.value=resourceError(e,'读取权限撤回状态')}
 finally{if(gate.current(token)&&epoch===session.epoch)loading.value=false}
}
watch(()=>[props.resource?.resource_id,props.resource?.version,session.epoch],()=>{gate.invalidate();load()},{immediate:true})
onBeforeUnmount(()=>gate.dispose())
</script>
<template><div v-if="pending>0||error" class="stack" data-testid="resource-lifecycle-notice">
 <el-alert :title="error||`权限撤回待完成：${pending}个Kafka身份。当前资源状态不代表Broker已全部撤权。`" :type="error?'error':'warning'" :closable="false" />
 <div><el-button :loading="loading" @click="load">刷新权限状态</el-button><el-button v-if="pending>0&&session.user?.role==='ADMIN'" @click="$router.push('/kafka-subscriptions')">进入Kafka订阅处理</el-button></div>
</div></template>
