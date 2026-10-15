<script setup>
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {hub} from '../utils/dataHub'
import {historyActions,resourceProperties,historyList,historyDetail,historyChanges,historyValue} from '../utils/resourceHistory'
import {resourceError} from '../utils/resourceForm'
import {displayTime} from '../utils/time'
import {catalogPage,resourcePageSizes} from '../utils/resourceCatalog'
import StatusTag from './StatusTag.vue'
const props=defineProps({resource:{type:Object,required:true},demo:Boolean})
const entries=ref([]),total=ref(0),listPage=ref(1),selected=ref(0),detail=ref(null),listError=ref(''),detailError=ref(''),loading=ref(false),reading=ref(false)
const keyword=ref(''),fieldPage=ref(1),fieldSize=ref(10)
const fields=computed(()=>{const terms=keyword.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);return (detail.value?.snapshot.fields || []).filter(row=>terms.every(term=>[row.field_name,row.business_name,row.technical_comment].join(' ').toLocaleLowerCase().includes(term)))})
const paged=computed(()=>catalogPage(fields.value,fieldPage.value,fieldSize.value)),changes=computed(()=>historyChanges(detail.value))
let active=true,listEpoch=0,detailEpoch=0
watch([keyword,fieldSize],()=>{fieldPage.value=1});watch(()=>paged.value.page,value=>{fieldPage.value=value})
function clear(){++detailEpoch;detail.value=null;detailError.value='';reading.value=false;keyword.value='';fieldPage.value=1}
async function load(){
  const epoch=++listEpoch;clear();selected.value=0;entries.value=[];total.value=0;listError.value='';loading.value=true
  try{
    const result=historyList(await hub.resourceVersions(props.resource.resource_id,{limit:20,offset:(listPage.value-1)*20}),props.resource.resource_id,props.demo)
    if(!active||epoch!==listEpoch)return
    if(!result.items.length&&result.total>0&&listPage.value>1){listPage.value=1;await load();return}
    entries.value=result.items;total.value=result.total
    if(result.items.length)await choose(result.items[0].version)
  }catch(error){if(active&&epoch===listEpoch)listError.value=resourceError(error,'加载版本历史')}
  finally{if(active&&epoch===listEpoch)loading.value=false}
}
async function choose(version){
  clear();selected.value=version;reading.value=true;const epoch=detailEpoch,list=listEpoch
  try{
    const response=await hub.resourceVersion(props.resource.resource_id,version)
    if(!active||list!==listEpoch||epoch!==detailEpoch||selected.value!==version)return
    detail.value=historyDetail(response,props.resource,version,props.demo)
  }catch(error){if(active&&list===listEpoch&&epoch===detailEpoch)detailError.value=resourceError(error,'读取历史快照')}
  finally{if(active&&list===listEpoch&&epoch===detailEpoch)reading.value=false}
}
function changePage(page){listPage.value=page;load()}
onMounted(load);onBeforeUnmount(()=>{active=false;++listEpoch;++detailEpoch})
</script>
<template>
  <section class="history" data-testid="resource-history-panel">
    <div class="toolbar"><strong>已留存版本</strong><el-button :loading="loading" @click="load">刷新版本历史</el-button></div>
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon data-testid="history-list-error" />
    <el-table v-else :data="entries" v-loading="loading" empty-text="暂无已留存版本" data-testid="history-list">
      <el-table-column label="版本" width="100"><template #default="{row}">V{{ row.version }}</template></el-table-column>
      <el-table-column label="操作类型" min-width="150"><template #default="{row}">{{ historyActions[row.action] }}</template></el-table-column>
      <el-table-column label="修改字段" min-width="160"><template #default="{row}">{{ row.changed_field || '—' }}</template></el-table-column>
      <el-table-column label="留存时间" min-width="180"><template #default="{row}">{{ displayTime(row.recorded_at) }}</template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button link type="primary" @click="choose(row.version)">{{ selected===row.version?'已选中':'查看' }}</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="total>20" :current-page="listPage" :page-size="20" :total="total" layout="total,prev,pager,next" @current-change="changePage" data-testid="history-list-pagination" />
    <div class="snapshot" v-loading="reading">
      <div v-if="detailError" data-testid="history-detail-error"><el-alert :title="detailError" type="error" :closable="false" show-icon /><el-button @click="choose(selected)">重试读取快照</el-button></div>
      <template v-if="detail">
        <h3>V{{ selected }} 快照 <el-tag>{{ historyActions[detail.snapshot.entry.action] }}</el-tag></h3>
        <el-alert v-if="detail.snapshot.entry.action==='BASELINE'" title="此版本为启用历史留存时的状态，更早版本未留存。" type="info" :closable="false" show-icon data-testid="history-baseline" />
        <el-descriptions :column="2" border data-testid="history-resource">
          <el-descriptions-item v-for="(label,key) in resourceProperties" :key="key" :label="label"><StatusTag v-if="key==='status'" :value="detail.snapshot.resource[key]" /><template v-else>{{ detail.snapshot.resource[key] }}</template></el-descriptions-item>
          <el-descriptions-item label="来源系统">{{ detail.snapshot.resource.source_name }}</el-descriptions-item>
          <el-descriptions-item label="物理表">{{ detail.snapshot.resource.schema_name }}.{{ detail.snapshot.resource.physical_table }}</el-descriptions-item>
          <el-descriptions-item label="登记扫描">{{ detail.snapshot.resource.scan_task_id }}</el-descriptions-item>
        </el-descriptions>
        <h3>本次变化</h3>
        <el-alert v-if="changes===null" title="没有上一版本快照，无法确定本次变化。" type="info" :closable="false" show-icon data-testid="history-no-previous" />
        <el-alert v-else-if="!changes.length" title="本次未改变已留存属性" type="success" :closable="false" show-icon />
        <el-table v-else :data="changes" data-testid="history-changes"><el-table-column prop="scope" label="变化范围" width="120" /><el-table-column prop="field_name" label="字段名" min-width="140" /><el-table-column prop="property" label="变化属性" width="140" /><el-table-column label="原值" min-width="180"><template #default="{row}"><StatusTag v-if="row.property==='资源状态'" :value="row.before" /><template v-else>{{ historyValue(row.before) }}</template></template></el-table-column><el-table-column label="新值" min-width="180"><template #default="{row}"><StatusTag v-if="row.property==='资源状态'" :value="row.after" /><template v-else>{{ historyValue(row.after) }}</template></template></el-table-column></el-table>
        <h3>字段快照</h3><el-input v-model="keyword" aria-label="历史字段关键词" placeholder="字段名、业务名称或技术注释" clearable maxlength="200" />
        <el-table :data="paged.rows" data-testid="history-fields"><el-table-column prop="field_name" label="字段名" min-width="140" /><el-table-column prop="business_name" label="业务名称" min-width="130" /><el-table-column prop="data_type" label="数据类型" min-width="130" /><el-table-column label="主键" width="80"><template #default="{row}">{{ row.primary_key?'是':'否' }}</template></el-table-column><el-table-column label="时间字段" width="100"><template #default="{row}">{{ row.time_field?'是':'否' }}</template></el-table-column><el-table-column prop="sensitivity_level" label="敏感等级" width="100" /><el-table-column prop="business_description" label="业务说明" min-width="180" /><el-table-column prop="technical_comment" label="技术注释" min-width="180" /></el-table>
        <el-pagination v-model:current-page="fieldPage" v-model:page-size="fieldSize" :page-sizes="resourcePageSizes" :total="fields.length" layout="total,sizes,prev,pager,next" data-testid="history-fields-pagination" />
      </template>
    </div>
  </section>
</template>
<style scoped>
.history{padding-top:18px}.toolbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}.snapshot{margin-top:24px;min-height:30px}.snapshot h3{font-size:16px;display:flex;align-items:center;gap:12px;margin:24px 0 14px}.snapshot .el-alert{margin-bottom:16px}.el-pagination{justify-content:flex-end;margin-top:16px}.snapshot .el-input{max-width:440px;margin-bottom:14px}.snapshot .el-descriptions{overflow-wrap:anywhere}.snapshot .el-button{margin-top:12px}
</style>
