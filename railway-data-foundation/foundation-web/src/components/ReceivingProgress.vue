<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import {receivingApi} from '../api/receiving'
import {session,explicitDemo} from '../utils/sessionState'
import {receivingReadGate,receivingStates} from '../utils/receiving'
import {receivingProgress,progressLabels,progressHints} from '../utils/receivingProgress'
import {displayTime} from '../utils/time'
const props=defineProps({task:Object}),emit=defineEmits(['close'])
const gate=receivingReadGate(),data=ref(null),error=ref(''),loading=ref(false)
async function load(){const id=props.task?.task_id;const token=gate.next();data.value=null;error.value='';loading.value=false;if(!id||explicitDemo)return;loading.value=true
 try{const result=receivingProgress(await receivingApi.progress(id));if(!gate.current(token)||props.task?.task_id!==id)return;if(result.task.task_id!==id)throw new Error('进度与当前任务不一致');data.value=result}
 catch(e){if(gate.current(token))error.value=e.response?.data?.message||e.message||'进度查询失败，请重试'}finally{if(gate.current(token))loading.value=false}}
function close(){gate.invalidate();data.value=null;error.value='';loading.value=false;emit('close')}
watch(()=>props.task?.task_id,load)
watch(()=>session.epoch,close)
onBeforeUnmount(()=>{gate.dispose();data.value=null})
</script>
<template>
 <el-dialog :model-value="!!task" title="接收进度" width="1050px" @close="close">
  <div v-loading="loading" style="min-height:180px">
   <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">重试</el-button></el-alert>
   <template v-if="data">
    <el-descriptions :column="3" border><el-descriptions-item label="任务名称">{{data.task.task_name}}</el-descriptions-item><el-descriptions-item label="运行状态">{{receivingStates[data.task.runtime_state]||data.task.runtime_state}}</el-descriptions-item><el-descriptions-item label="已落库消息">{{data.task.received_count}}</el-descriptions-item><el-descriptions-item label="消息主题" :span="2">{{data.topic}}</el-descriptions-item><el-descriptions-item label="待接收位置差">{{data.summary.remaining_positions??'未知'}}</el-descriptions-item><el-descriptions-item label="检查点读取时间">{{displayTime(data.checkpoint_read_at)}}</el-descriptions-item><el-descriptions-item label="查询完成时间">{{displayTime(data.checked_at)}}</el-descriptions-item><el-descriptions-item label="最近接收时间">{{displayTime(data.task.last_received_at)}}</el-descriptions-item></el-descriptions>
    <el-alert :title="progressLabels[data.summary.status]" :description="progressHints[data.summary.status]" :type="data.summary.status==='POSITION_INVALID'?'warning':'info'" :closable="false" class="progress-notice" />
    <el-alert v-if="data.task.error_reason" :title="data.task.error_reason" type="error" :closable="false" />
    <el-table :data="data.partitions"><el-table-column prop="partition" label="分区" width="65"/><el-table-column prop="earliest_offset" label="最早可用位置" min-width="120"/><el-table-column prop="latest_offset" label="末尾下一位置" min-width="120"/><el-table-column label="落库下一位置" min-width="120"><template #default="{row}">{{row.next_offset??'未建立'}}</template></el-table-column><el-table-column label="Kafka已提交位置" min-width="145"><template #default="{row}">{{row.committed_offset??'未提交'}}</template></el-table-column><el-table-column label="待接收位置差" min-width="125"><template #default="{row}">{{row.remaining_positions??'未知'}}</template></el-table-column><el-table-column label="状态" min-width="175"><template #default="{row}">{{progressLabels[row.status]}}</template></el-table-column></el-table>
    <p class="progress-note">位置差不等于实际消息条数。查询不会消费消息；持续接收期间位置可能变化。</p>
   </template>
  </div>
  <template #footer><el-button @click="close">关闭</el-button><el-button type="primary" :loading="loading" :disabled="explicitDemo" @click="load">刷新进度</el-button></template>
 </el-dialog>
</template>
<style scoped>.progress-notice{margin:16px 0}.progress-note{margin-top:14px;color:#718497;font-size:13px}</style>
