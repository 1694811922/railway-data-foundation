<script setup>
import { computed,onBeforeUnmount,onMounted,reactive,ref,watch } from 'vue'
import { useRouter } from 'vue-router'
import { hub } from '../utils/dataHub'
import { resourceError } from '../utils/resourceForm'
import { catalogPage,resourcePageSizes } from '../utils/resourceCatalog'
import { displayTime } from '../utils/time'
import { changeLabels,propertyLabels,fieldProperties,comparisonCandidates,comparisonResult,filterChanges,propertyValue,fieldSummary } from '../utils/schemaComparison'
const props=defineProps({resource:{type:Object,required:true},demo:Boolean})
const router=useRouter(),scans=ref([]),total=ref(0),scanPage=ref(1),selected=ref('')
const candidatesLoading=ref(false),comparing=ref(false),candidateError=ref(''),error=ref(''),result=ref(null)
const filters=reactive({keyword:'',type:''}),page=ref(1),size=ref(10)
const filtered=computed(()=>filterChanges(result.value?.changes || [],filters))
const paged=computed(()=>catalogPage(filtered.value,page.value,size.value))
const target=computed(()=>scans.value.find(scan=>scan.task_id===selected.value))
let active=true,readEpoch=0,compareEpoch=0
const current=(epoch)=>active&&epoch===readEpoch
watch([()=>filters.keyword,()=>filters.type,size],()=>{page.value=1})
watch(()=>paged.value.page,value=>{page.value=value})
function clearComparison(){++compareEpoch;result.value=null;error.value='';comparing.value=false;page.value=1;Object.assign(filters,{keyword:'',type:''})}
watch(selected,clearComparison)
async function loadCandidates() {
  const epoch=++readEpoch;clearComparison();scans.value=[];selected.value='';total.value=0;candidateError.value=''
  if(!props.resource.scan_task_id){candidateError.value='资源未提供登记扫描编号，暂不可比较';return}
  candidatesLoading.value=true
  try {
    const response=await hub.comparisonScans(props.resource.resource_id,{limit:20,offset:(scanPage.value-1)*20})
    if(!current(epoch))return
    const value=comparisonCandidates(response,props.resource,props.demo)
    if(!value.items.length && value.total>0 && scanPage.value>1){scanPage.value=1;await loadCandidates();return}
    scans.value=value.items;total.value=value.total;selected.value=value.items[0]?.task_id || ''
  }catch(reason){if(current(epoch))candidateError.value=resourceError(reason,'加载对比扫描')}
  finally{if(current(epoch))candidatesLoading.value=false}
}
async function compare() {
  if(candidatesLoading.value || comparing.value || candidateError.value || !target.value)return
  const task=selected.value,epoch=++compareEpoch,read=readEpoch
  result.value=null;error.value='';comparing.value=true
  try {
    const response=await hub.compareSchema(props.resource.resource_id,task)
    if(!current(read)||epoch!==compareEpoch||selected.value!==task)return
    result.value=comparisonResult(response,props.resource,task,props.demo)
  }catch(reason){if(current(read)&&epoch===compareEpoch&&selected.value===task)error.value=resourceError(reason,'比较结构')}
  finally{if(current(read)&&epoch===compareEpoch&&selected.value===task)comparing.value=false}
}
function changePage(value){scanPage.value=value;loadCandidates()}
onMounted(loadCandidates)
onBeforeUnmount(()=>{active=false;++readEpoch;++compareEpoch})
</script>
<template>
  <section class="comparison" data-testid="schema-comparison-panel">
    <div class="snapshot-header">
      <div><span class="dim">登记扫描</span><p class="id-text">{{ resource.scan_task_id || '未提供' }}</p></div>
      <div><span class="dim">物理表</span><p>{{ resource.schema_name }}.{{ resource.physical_table }}</p></div>
    </div>
    <div class="selector">
      <el-select v-model="selected" aria-label="对比扫描" placeholder="选择后续成功扫描" :loading="candidatesLoading" :disabled="candidatesLoading || !!candidateError" filterable>
        <el-option v-for="scan in scans" :key="scan.task_id" :value="scan.task_id" :label="`${displayTime(scan.started_at)} · ${scan.task_id}`" />
      </el-select>
      <el-button type="primary" :loading="comparing" :disabled="candidatesLoading || !!candidateError || !target" @click="compare">开始比较</el-button>
      <el-button :loading="candidatesLoading" @click="loadCandidates">刷新对比扫描</el-button>
    </div>
    <el-pagination v-if="total>20" :current-page="scanPage" :page-size="20" :total="total" layout="total,prev,pager,next" @current-change="changePage" data-testid="comparison-scan-pagination" />
    <p v-if="target" class="dim">对比扫描时间：{{ displayTime(target.started_at) }} → {{ displayTime(target.finished_at) }}</p>
    <el-alert v-if="candidateError" :title="candidateError" type="error" :closable="false" show-icon data-testid="comparison-candidate-error" />
    <el-empty v-else-if="!candidatesLoading && !scans.length" description="暂无后续成功扫描"><el-button @click="router.push('/scans')">进入扫描工作台</el-button></el-empty>
    <div v-if="error" class="error" data-testid="comparison-error"><el-alert :title="error" type="error" :closable="false" show-icon /><el-button @click="compare">重试比较</el-button></div>
    <div v-loading="comparing" class="results">
      <template v-if="result">
        <el-descriptions :column="2" border size="small" class="origins" data-testid="comparison-origins">
          <el-descriptions-item label="基准扫描">{{ result.baseline_scan.task_id }}<br>{{ displayTime(result.baseline_scan.started_at) }} → {{ displayTime(result.baseline_scan.finished_at) }}</el-descriptions-item>
          <el-descriptions-item label="对比扫描">{{ result.target_scan.task_id }}<br>{{ displayTime(result.target_scan.started_at) }} → {{ displayTime(result.target_scan.finished_at) }}</el-descriptions-item>
          <el-descriptions-item label="比较时资源版本">{{ result.resource_version }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="result.comparison_status==='TABLE_NOT_FOUND'" title="扫描未发现目标表，请核对扫描范围与来源配置。" type="warning" :closable="false" show-icon data-testid="comparison-unknown" />
        <el-alert v-else-if="result.comparison_status==='SCOPE_UNCONFIRMED'" title="比较范围无法确认，该扫描没有可用于核对数据库模式的表。" type="warning" :closable="false" show-icon data-testid="comparison-unknown" />
        <template v-else>
          <div class="counts" data-testid="comparison-summary"><div v-for="[key,label] in [['added','新增'],['removed','删除'],['modified','修改'],['unchanged','未变化']]" :key="key"><span>{{ label }}</span><strong>{{ result.summary[key] }}</strong></div></div>
          <el-alert v-if="result.comparison_status==='UNCHANGED'" title="未发现结构变化" type="success" :closable="false" show-icon data-testid="comparison-unchanged" />
          <el-table v-if="result.table_changes.length" :data="result.table_changes" class="table-change" data-testid="comparison-table-changes">
            <el-table-column label="变化属性" width="130"><template #default="{row}">{{ propertyLabels[row.property] }}</template></el-table-column>
            <el-table-column prop="before" label="原值" min-width="200" />
            <el-table-column prop="after" label="新值" min-width="200" />
          </el-table>
          <div class="filters"><el-input v-model="filters.keyword" aria-label="结构变化关键词" placeholder="字段名、变化属性或技术注释" clearable maxlength="200" /><el-select v-model="filters.type" aria-label="结构变化类型" placeholder="全部变化类型" clearable><el-option v-for="(label,type) in changeLabels" :key="type" :label="label" :value="type" /></el-select></div>
          <el-table :data="paged.rows" row-key="field_name" empty-text="暂无匹配的字段变化" data-testid="comparison-changes">
            <el-table-column type="expand" width="45"><template #default="{row}"><el-table :data="(row.change_type==='MODIFIED'?row.changed_properties:fieldProperties).map(property=>({property}))" class="properties" data-testid="comparison-properties"><el-table-column label="变化属性" width="150"><template #default="{row:property}">{{ propertyLabels[property.property] }}</template></el-table-column><el-table-column label="原值" min-width="200"><template #default="{row:property}">{{ propertyValue(row.before,property.property) }}</template></el-table-column><el-table-column label="新值" min-width="200"><template #default="{row:property}">{{ propertyValue(row.after,property.property) }}</template></el-table-column></el-table></template></el-table-column>
            <el-table-column label="变化类型" width="110"><template #default="{row}"><el-tag :type="{ADDED:'success',REMOVED:'danger',MODIFIED:'warning'}[row.change_type]">{{ changeLabels[row.change_type] }}</el-tag></template></el-table-column>
            <el-table-column prop="field_name" label="字段名" min-width="160" />
            <el-table-column label="变化属性" min-width="180"><template #default="{row}">{{ row.changed_properties.length?row.changed_properties.map(key=>propertyLabels[key]).join('、'):'整个字段' }}</template></el-table-column>
            <el-table-column label="原值" min-width="220"><template #default="{row}">{{ fieldSummary(row.before) }}</template></el-table-column>
            <el-table-column label="新值" min-width="220"><template #default="{row}">{{ fieldSummary(row.after) }}</template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="resourcePageSizes" :total="filtered.length" layout="total,sizes,prev,pager,next" class="pagination" data-testid="comparison-pagination" />
        </template>
      </template>
    </div>
  </section>
</template>
<style scoped>
.comparison {padding-top:18px}.snapshot-header {display:grid;grid-template-columns:1fr 1fr;gap:20px;padding:16px;background:#f7fafc;border:1px solid #e5edf2;border-radius:8px}
.snapshot-header p {margin:8px 0 0;overflow-wrap:anywhere}.selector {display:flex;gap:12px;margin:18px 0}.selector .el-select {flex:1;min-width:220px}.selector .el-button+.el-button {margin-left:0}
.origins {margin-bottom:18px}.results {min-height:30px;margin-top:20px}.counts {display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:20px}.counts>div {border:1px solid #e5edf2;border-radius:8px;padding:16px;display:flex;align-items:center;justify-content:space-between}.counts strong {font-size:26px;color:#0e7490}
.filters {display:grid;grid-template-columns:2fr 1fr;gap:12px;margin:18px 0}.properties {padding:8px 32px}.pagination {justify-content:flex-end;margin-top:18px}.error .el-button {margin-top:12px}.table-change {margin-top:18px}
@media(max-width:800px){.selector{flex-wrap:wrap}.snapshot-header{grid-template-columns:1fr}.counts{grid-template-columns:repeat(2,1fr)}.properties{padding:8px}.filters{grid-template-columns:1fr}}
</style>
