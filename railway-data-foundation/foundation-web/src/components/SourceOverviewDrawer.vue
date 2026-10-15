<script setup>
import { computed, onBeforeUnmount, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { hub } from '../utils/dataHub'
import { displayTime } from '../utils/time'
import StatusTag from './StatusTag.vue'
import DemoNotice from './DemoNotice.vue'

const props=defineProps({modelValue:Boolean,source:Object})
const emit=defineEmits(['update:modelValue'])
const router=useRouter(), sections=reactive({tasks:null,resources:null}), loading=reactive({tasks:false,resources:false})
let revision=0, active=true
const sequence={tasks:0,resources:0}
const visible=computed({get:()=>props.modelValue,set:value=>emit('update:modelValue',value)})
const types={MYSQL:'MySQL数据库',ORACLE:'Oracle数据库',POSTGRESQL:'PostgreSQL数据库'}
const sslModes={PREFERRED:'优先加密',REQUIRED:'必须加密',VERIFY_CA:'验证证书',VERIFY_IDENTITY:'验证证书与主机',DISABLED:'不加密'}
const keys=[{key:'tasks',label:'最近扫描任务'},{key:'resources',label:'最近登记资源'}]
async function loadSection(key) {
  if(!active || !visible.value || !props.source || loading[key])return
  const context=revision, request=++sequence[key], source=props.source
  loading[key]=true;sections[key]=null
  try {
    const result=await hub.sourceOverview(source,key)
    if(active && visible.value && context===revision && request===sequence[key])sections[key]=result
  } catch {
    if(active && visible.value && context===revision && request===sequence[key])sections[key]={rows:[],error:true,notice:'关联元数据读取失败，请重试'}
  } finally {if(active && context===revision && request===sequence[key])loading[key]=false}
}
function navigate(key,row) {
  if(!active || !visible.value || loading[key] || sections[key]?.error || row.source_id!==props.source?.source_id)return
  const id=key==='tasks'?'task_id':'resource_id'
  if(!sections[key]?.rows.some(item=>item[id]===row[id]))return
  visible.value=false
  router.push(key==='tasks'?{path:'/scans',query:{task:row.task_id}}:{path:'/resources/'+encodeURIComponent(row.resource_id)})
}
function catalog() {
  if(!active || !visible.value || !props.source)return
  const source=props.source.source_id
  visible.value=false;router.push({path:'/resources',query:{sourceFilter:source}})
}
watch(()=>[props.modelValue,props.source],()=>{
  ++revision
  for(const key of ['tasks','resources']){++sequence[key];sections[key]=null;loading[key]=false}
  if(props.modelValue && props.source)void Promise.allSettled(['tasks','resources'].map(loadSection))
},{immediate:true})
onBeforeUnmount(()=>{active=false;++revision})
</script>
<template>
  <el-drawer v-model="visible" title="数据源概览" :size="1080" style="max-width:95vw" data-testid="source-overview">
    <div v-if="source" class="stack overview-body">
      <div class="overview-heading"><div><h2>{{ source.source_name }}</h2><span class="id-text">{{ source.source_id }}</span></div><StatusTag :value="source.status" /></div>
      <DemoNotice :show="source._demo===true" />
      <el-descriptions :column="2" border data-testid="source-overview-info">
        <el-descriptions-item label="数据源类型">{{ types[source.source_type] || source.source_type || '—' }}</el-descriptions-item>
        <el-descriptions-item label="连接配置方式">{{ source.connection_mode==='DYNAMIC'?'动态登记':source.connection_mode==='ENV_REFERENCE'?'旧环境引用':'未标注' }}</el-descriptions-item>
        <el-descriptions-item label="数据库地址">{{ source.host || '—' }}</el-descriptions-item><el-descriptions-item label="端口">{{ source.port ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="数据库名称">{{ source.database_name || '—' }}</el-descriptions-item><el-descriptions-item label="数据库用户名">{{ source.username || '—' }}</el-descriptions-item>
        <el-descriptions-item label="加密连接">{{ sslModes[source.ssl_mode] || '未标注' }}</el-descriptions-item><el-descriptions-item label="最近更新时间">{{ displayTime(source.updated_at) }}</el-descriptions-item>
        <el-descriptions-item label="备注说明" :span="2">{{ source.description || '暂无说明' }}</el-descriptions-item>
      </el-descriptions>

      <section v-for="section in keys" :key="section.key" class="panel overview-section" :data-testid="'source-overview-'+section.key">
        <div class="panel-title">{{ section.label }}<el-button link type="primary" :loading="loading[section.key]" @click="loadSection(section.key)">刷新{{ section.key==='tasks'?'扫描':'资源' }}</el-button></div>
        <el-alert v-if="sections[section.key]?.error" :title="sections[section.key].notice" type="error" show-icon :closable="false" />
        <template v-else>
          <el-alert v-if="sections[section.key]&&!sections[section.key].complete" :title="sections[section.key].notice || '读取范围未完整'" type="warning" show-icon :closable="false" />
          <p class="panel-sub" :data-testid="'source-overview-'+section.key+'-count'">{{ loading[section.key]?'正在读取…':(sections[section.key]&&!sections[section.key].complete?'≥ ':'')+(sections[section.key]?.rows.length ?? 0)+' 项关联记录 · 展示最近5项' }}</p>
          <el-table :data="(sections[section.key]?.rows || []).slice(0,5)" v-loading="loading[section.key]" :empty-text="section.key==='tasks'?'暂无关联扫描任务':'暂无关联资源'">
            <template v-if="section.key==='tasks'">
              <el-table-column prop="task_id" label="扫描任务编号" min-width="240" show-overflow-tooltip /><el-table-column label="状态" width="90"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column>
              <el-table-column label="发现表／字段" width="120"><template #default="{row}">{{ row.table_count ?? '—' }} / {{ row.field_count ?? '—' }}</template></el-table-column>
              <el-table-column label="开始时间" min-width="155"><template #default="{row}">{{ displayTime(row.started_at) }}</template></el-table-column>
              <el-table-column prop="failure_reason" label="失败原因" min-width="170" show-overflow-tooltip />
            </template>
            <template v-else>
              <el-table-column prop="resource_name" label="资源名称" min-width="185" show-overflow-tooltip /><el-table-column prop="physical_table" label="物理表" min-width="150" />
              <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column><el-table-column prop="security_level" label="安全等级" width="95" />
              <el-table-column label="登记时间" min-width="155"><template #default="{row}">{{ displayTime(row.created_at) }}</template></el-table-column>
            </template>
            <el-table-column label="操作" width="95" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="loading[section.key]" @click="navigate(section.key,row)">查看{{ section.key==='tasks'?'任务':'资源' }}</el-button></template></el-table-column>
          </el-table>
        </template>
      </section>
    </div>
    <template #footer><el-button @click="visible=false">关闭</el-button><el-button type="primary" :disabled="!source" @click="catalog">查看来源资源目录</el-button></template>
  </el-drawer>
</template>
<style scoped>
.overview-body { gap:18px; }
.overview-heading { display:flex;align-items:center;justify-content:space-between;gap:16px; }
.overview-heading h2 { margin:0 0 8px; }
.overview-heading .id-text { overflow-wrap:anywhere; }
.overview-section { padding:18px; }
</style>
