<script setup>
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {sharingApi} from '../../api/sharing'
import {fetchDashboardList} from '../../utils/dashboard'
import {assertPolicy,assertRelationship,relationshipStatuses} from '../../utils/sharing'
import {displayTime} from '../../utils/time'
import PolicyDetails from '../../components/PolicyDetails.vue'
const router=useRouter(),mock=import.meta.env.VITE_USE_MOCK==='true'
const rows=ref([]),relations=ref([]),loading=ref(false),error=ref(''),relationError=ref(''),notice=ref('')
const keyword=ref(''),page=ref(1),size=ref(10),drawer=ref(false),selected=ref(null)
let active=true,epoch=0
const relationByPolicy=computed(()=>new Map(relations.value.map(row=>[row.policy.policy_id,row])))
const filtered=computed(()=>rows.value.filter(row=>[row.policy_id,row.resource_id,...row.allowed_fields,relationByPolicy.value.get(row.policy_id)?.relationship_name].some(value=>String(value||'').toLowerCase().includes(keyword.value.trim().toLowerCase()))))
const paged=computed(()=>filtered.value.slice((page.value-1)*size.value,page.value*size.value))
watch(()=>[keyword.value,size.value],()=>{page.value=1})
watch(()=>filtered.value.length,()=>{page.value=Math.min(page.value,Math.max(1,Math.ceil(filtered.value.length/size.value)))})
async function load(){
  if(mock)return
  const current=++epoch;loading.value=true;rows.value=[];relations.value=[];error.value='';relationError.value='';notice.value='';drawer.value=false;selected.value=null
  const results=await Promise.allSettled([
    fetchDashboardList(sharingApi.policies,'policy_id').then(data=>{data.rows.forEach(row=>assertPolicy(row));return data}),
    fetchDashboardList(sharingApi.list,'relationship_id').then(data=>{data.rows.forEach(row=>assertRelationship(row));return data}),
  ])
  if(!active||current!==epoch)return
  results.forEach((result,i)=>{
    if(result.status==='rejected'){if(i===0)error.value='策略读取失败，请确认新版后端已启动后重试';else relationError.value='关联关系读取失败，暂不显示关系状态';return}
    ;[rows,relations][i].value=result.value.rows
    if(!result.value.complete)notice.value=result.value.notice||'当前仅展示有界读取范围内的策略和关系'
  });loading.value=false
}
onMounted(load);onBeforeUnmount(()=>{active=false;++epoch})
</script>
<template>
  <div class="stack">
    <div class="page-heading"><div><h1>访问策略</h1></div><div><el-button :disabled="loading||mock" @click="load">刷新策略</el-button><el-button type="primary" @click="router.push('/relationships')">管理共享关系</el-button></div></div>

    <el-alert v-if="mock" title="策略目录当前只使用真实后端，请设置VITE_USE_MOCK=false并重启前端。" type="warning" :closable="false" />
    <div class="panel"><div class="panel-title">策略目录 </div>
      <el-input v-model="keyword" aria-label="搜索策略" placeholder="搜索策略编号、资源编号、关系名称或允许字段" clearable class="search" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" data-testid="policy-error" /><el-alert v-if="relationError" :title="relationError" type="warning" :closable="false" /><el-alert v-if="notice" :title="notice" type="warning" :closable="false" />
      <el-table :data="paged" v-loading="loading" data-testid="policy-table" empty-text="暂无策略，请在共享关系中设置访问条件">
        <el-table-column label="共享关系" min-width="180"><template #default="{row}">{{relationByPolicy.get(row.policy_id)?.relationship_name||'未读取到关联关系'}}</template></el-table-column>
        <el-table-column prop="policy_id" label="策略编号" min-width="200" show-overflow-tooltip />
        <el-table-column label="允许字段" min-width="200" show-overflow-tooltip><template #default="{row}">{{row.allowed_fields.join('、')}}</template></el-table-column>
        <el-table-column prop="time_field" label="时间条件字段" width="150" />
        <el-table-column label="数据时间范围" min-width="260"><template #default="{row}">{{displayTime(row.data_from)}} 至 {{displayTime(row.data_until)}}</template></el-table-column>
        <el-table-column label="关系状态" width="120"><template #default="{row}">{{relationshipStatuses[relationByPolicy.get(row.policy_id)?.status]||'未知'}}</template></el-table-column>
        <el-table-column prop="version" label="策略版本" width="90" /><el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button link type="primary" @click="selected=row;drawer=true">查看</el-button></template></el-table-column>
      </el-table><el-pagination v-model:current-page="page" v-model:page-size="size" :total="filtered.length" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" />
    </div><el-drawer v-model="drawer" title="访问策略详情" size="680px"><PolicyDetails v-if="selected" :policy="selected" /></el-drawer>
  </div>
</template>
<style scoped>.search{margin:16px 0}.el-pagination{justify-content:flex-end;margin-top:20px}.el-alert{margin-bottom:10px}</style>
