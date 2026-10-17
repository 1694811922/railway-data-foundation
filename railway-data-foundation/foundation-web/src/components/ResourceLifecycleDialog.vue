<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {hub} from '../utils/dataHub'
import {session} from '../utils/sessionState'
import {lifecycleImpact,lifecycleBody,lifecycleLabels} from '../utils/resourceLifecycle'
import {resourceError,isResourceConflict} from '../utils/resourceForm'
import {receivingReadGate} from '../utils/receiving'
const props=defineProps({modelValue:Boolean,resource:Object,action:String,demo:Boolean})
const emit=defineEmits(['update:modelValue','busy','changed']),router=useRouter(),gate=receivingReadGate()
const reason=ref(''),impact=ref(null),loading=ref(false),saving=ref(false),error=ref(''),conflict=ref(false),result=ref(null)
let writing=false
async function readImpact(){
 if(!props.modelValue||!props.resource)return
 const token=gate.next(),id=props.resource.resource_id,epoch=session.epoch;loading.value=true;error.value='';impact.value=null
 try{const data=lifecycleImpact(await hub.resourceLifecycleImpact(id),props.resource,props.demo);if(gate.current(token)&&epoch===session.epoch)impact.value=data}
 catch(e){if(gate.current(token)&&epoch===session.epoch)error.value=resourceError(e,'读取关联影响')}
 finally{if(gate.current(token)&&epoch===session.epoch)loading.value=false}
}
async function submit(){
 if(saving.value||loading.value||!impact.value||conflict.value)return
 let body;try{body=lifecycleBody(props.resource,props.action,reason.value)}catch(e){error.value=e.message;return}
 const token=gate.next(),epoch=session.epoch,resource={...props.resource},action=props.action;error.value='';saving.value=true;writing=true;emit('busy',true)
 try{
  const reply=await hub.changeResourceLifecycle(resource.resource_id,action,body)
  if(!gate.current(token)||epoch!==session.epoch)return
  const value=reply.value;if(reply.demo!==props.demo||!value?.resource||value.resource.resource_id!==resource.resource_id||value.resource.version!==resource.version+1)throw new Error('操作回执未确认，请关闭并刷新资源状态')
  result.value=value;emit('changed',value.resource);ElMessage.success(`${lifecycleLabels[action]}已完成${reply.demo?'（演示）':''}`)
 }catch(e){if(gate.current(token)&&epoch===session.epoch){conflict.value=isResourceConflict(e);error.value=resourceError(e,lifecycleLabels[action])}}
 finally{saving.value=false;writing=false;emit('busy',false)}
}
watch(()=>[props.modelValue,props.resource?.resource_id,props.action,session.epoch],()=>{gate.invalidate();impact.value=null;result.value=null;reason.value='';error.value='';conflict.value=false;if(props.modelValue)readImpact()},{immediate:true})
onBeforeUnmount(()=>gate.dispose())
</script>
<template><el-dialog :model-value="modelValue" :title="lifecycleLabels[action]||'资源状态操作'" width="640px" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="!writing&&emit('update:modelValue',$event)">
 <div class="stack" v-loading="loading" data-testid="resource-lifecycle-dialog">
  <template v-if="!result"><div><strong>{{resource?.resource_name}}</strong> · V{{resource?.version}}</div>
   <el-alert v-if="demo" title="演示状态操作，不执行真实授权或撤权" type="warning" :closable="false" />
   <el-descriptions v-if="impact" :column="2" border><el-descriptions-item label="关联接口能力">{{impact.capabilities}}</el-descriptions-item><el-descriptions-item label="启用中的REST关系">{{impact.active_rest_relationships}}</el-descriptions-item><el-descriptions-item label="Kafka待停止身份">{{impact.kafka_executions}}</el-descriptions-item><el-descriptions-item label="Kafka撤权待完成">{{impact.kafka_revoke_pending}}</el-descriptions-item></el-descriptions>
   <el-alert v-if="action==='unpublish'" title="下架将暂停REST共享并请求撤回Kafka权限；已接收数据保留，在途操作可能完成。" type="warning" :closable="false" />
   <el-alert v-if="action==='republish'" title="重新发布不会恢复原共享权限，须由管理员手动恢复REST关系或重新配置Kafka。" type="info" :closable="false" />
   <el-alert v-if="action==='retire'" title="退役不可恢复，将终止剩余申请与授权；保留资源、物理表登记及全部历史，不删除业务源数据。" type="error" :closable="false" />
   <el-input v-model="reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="请填写操作原因" aria-label="资源状态操作原因" :disabled="saving||conflict" />
  </template>
  <template v-else><el-result icon="success" :title="lifecycleLabels[action]+'已完成'" :sub-title="`资源版本 V${result.resource.version}`" />
   <el-alert v-if="result.impact?.kafka_revoke_pending" title="资源登记状态已更新，Kafka权限撤回待完成；尚不能视为全部消费已停止。" type="warning" :closable="false" data-testid="resource-revoke-pending" />
   <el-alert v-else-if="result.impact" title="资源登记状态已更新，当前没有待撤回的Kafka身份。" type="success" :closable="false" />
   <el-alert v-if="!result.impact" title="资源状态已更新，关联权限状态读取失败，请刷新核对。" type="warning" :closable="false" />
   <el-button v-if="session.user?.role==='ADMIN'&&result.impact?.kafka_revoke_pending" @click="emit('update:modelValue',false);router.push('/kafka-subscriptions')">进入Kafka订阅处理</el-button>
  </template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="resource-lifecycle-error" />
  <el-button v-if="!impact&&!loading&&!result" :disabled="conflict" @click="readImpact">重试读取影响</el-button>
  <el-alert v-if="conflict" title="请关闭对话框，刷新资源后重新核对；本次操作未提交。" type="warning" :closable="false" />
 </div>
 <template #footer><el-button :disabled="saving" @click="emit('update:modelValue',false)">{{result?'关闭':'取消'}}</el-button><el-button v-if="!result" :type="action==='retire'?'danger':'primary'" :loading="saving" :disabled="loading||!impact||conflict" @click="submit">确认{{lifecycleLabels[action]}}</el-button></template>
</el-dialog></template>
