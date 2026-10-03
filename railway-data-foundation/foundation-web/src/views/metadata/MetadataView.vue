<script setup>
import { onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hub } from '../../utils/dataHub'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { displayTime } from '../../utils/time'
import { canUseDemo } from '../../utils/request'
const router=useRouter(), route=useRoute()
const tasks=ref([]), sources=ref([]), tables=ref([]), fields=ref([])
const selected=ref(null), selectedTable=ref(null), selectedSource=ref(''), demo=ref(false), tablesDemo=ref(false), drawer=ref(false), loading=ref(false), scanning=ref(false), resultsLoading=ref(false)
const fieldTask=ref(null)
let selectionVersion=0, fieldVersion=0
async function load(){loading.value=true;try{const [t,s]=await Promise.all([hub.tasks(),hub.sources()]);tasks.value=t.rows||[];sources.value=s.rows||[];demo.value=t.demo;if(!selected.value&&tasks.value.length)await select(tasks.value.find(t=>t.task_id===route.query.task)||tasks.value[0])}catch{ElMessage.error('扫描任务加载失败')}finally{loading.value=false}}
async function select(task){selected.value=task;tables.value=[];fields.value=[];drawer.value=false;selectedTable.value=null;fieldTask.value=null;tablesDemo.value=false;++fieldVersion;const version=++selectionVersion;resultsLoading.value=true;try{const result=await hub.tables(task.task_id);if(version===selectionVersion){tables.value=result.rows||[];tablesDemo.value=result.demo}}catch{if(version===selectionVersion)ElMessage.error('扫描结果加载失败，请重试')}finally{if(version===selectionVersion)resultsLoading.value=false}}
async function showFields(table){const task=selected.value,version=++fieldVersion;fields.value=[];try{const result=await hub.fieldsForTask(task.task_id,table.table_name);if(version!==fieldVersion||selected.value?.task_id!==task.task_id)return;selectedTable.value=table;fieldTask.value=task;fields.value=result.rows||[];tablesDemo.value ||= result.demo;drawer.value=true}catch{if(version===fieldVersion)ElMessage.error('字段加载失败，请重试')}}
async function scan(){const source=sources.value.find(s=>s.source_id===selectedSource.value);if(!source){ElMessage.warning('请选择数据源');return}scanning.value=true;try{const result=await hub.startScan(source);if(result.value.status==='FAILED')ElMessage.error(result.value.failure_reason||'扫描失败');else ElMessage.success(result.demo?'演示扫描完成':'扫描完成');selected.value=null;await load();const task=tasks.value.find(t=>t.task_id===result.value.task_id);if(task)await select(task)}catch(e){if(canUseDemo(e))ElMessage.error('扫描请求失败，请检查后端服务后重试')}finally{scanning.value=false}}
function create(table,task=selected.value){if(!table||task?.status!=='SUCCESS'){ElMessage.warning('请先选择成功的扫描结果');return}router.push({path:'/resources',query:{fromTable:table.table_name,schema:table.schema_name,source:task.source_id,sourceName:task.source_name,task:task.task_id}})}
onMounted(load)
</script>
<template><div class="stack">
  <div class="page-heading"><div><div class="eyebrow">接入管理</div><h1>元数据扫描</h1><p>只读取数据库结构，不读取或保存业务明细</p></div><div style="display:flex;gap:8px"><el-select v-model="selectedSource" placeholder="选择已启用的数据源" style="width:240px"><el-option v-for="s in sources" :key="s.source_id" :value="s.source_id" :label="s.source_name" :disabled="s.status==='DISABLED'" /></el-select><el-button type="primary" :loading="scanning" @click="scan">开始扫描</el-button><el-button @click="load">刷新任务</el-button></div></div>
  <DemoNotice :show="demo||tablesDemo" text="当前展示演示扫描结果，请检查后端服务或演示模式配置。" />
  <div class="grid-2" style="grid-template-columns:minmax(320px,.85fr) minmax(500px,1.5fr)">
    <div class="panel"><div class="panel-title">扫描任务 <span class="panel-sub">{{ tasks.length }} 条记录</span></div><div v-loading="loading">
      <div v-for="task in tasks" :key="task.task_id" :data-task-id="task.task_id" @click="select(task)" :style="{borderColor:selected?.task_id===task.task_id?'#79bcc7':'#e8eef2',background:selected?.task_id===task.task_id?'#f1f9fa':'#fff'}" style="cursor:pointer;border:1px solid;border-radius:10px;padding:14px;margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;gap:5px"><strong>{{ task.source_name || task.source_id }}</strong><StatusTag :value="task.status" /></div>
        <div class="id-text" style="margin:7px 0">扫描任务编号：{{ task.task_id }}</div>
        <div class="dim" style="font-size:13px;line-height:1.8">发现表数量：{{ task.table_count ?? '—' }} · 发现字段数量：{{ task.field_count ?? '—' }}<br>开始时间：{{ displayTime(task.started_at) }}<br>完成时间：{{ displayTime(task.finished_at) }}</div>
        <el-button link type="primary" @click.stop="select(task)">查看扫描结果</el-button>
      </div><div v-if="!tasks.length" class="empty-hint">暂无扫描任务，请选择数据源开始扫描</div>
    </div></div>
    <div class="panel"><div class="panel-title">扫描结果 <span class="panel-sub">{{ selected?.source_name || '请选择任务' }}</span></div>
      <el-alert v-if="selected?.status==='FAILED'" :title="selected.failure_reason || '扫描失败，请检查连接和账号权限'" type="error" :closable="false" style="margin-bottom:16px" />
      <el-table :data="tables" v-loading="resultsLoading" empty-text="暂无扫描结果">
        <el-table-column prop="table_name" label="表名" min-width="150" /><el-table-column prop="table_comment" label="表说明" min-width="155" /><el-table-column prop="field_count" label="字段数量" width="95" /><el-table-column prop="estimated_rows" label="估算记录数" width="105" />
        <el-table-column label="扫描状态" width="100"><template #default><StatusTag :value="selected?.status || 'SUCCESS'" /></template></el-table-column>
        <el-table-column label="操作" min-width="160"><template #default="{row}"><el-button link type="primary" @click="showFields(row)">查看字段</el-button><el-button link type="primary" @click="create(row)">创建资源</el-button></template></el-table-column>
      </el-table><p class="dim">估算记录数来自数据库元数据，不代表实际共享量。</p>
    </div>
  </div>
  <el-drawer v-model="drawer" :title="`${selectedTable?.table_name || ''} · 字段详情`" :size="1080" style="max-width:95vw">
    <p class="dim">{{ selectedTable?.table_comment }} · {{ fields.length }} 个字段</p>
    <el-table :data="fields"><el-table-column prop="field_name" label="字段名称" min-width="145" /><el-table-column label="字段类型" min-width="145"><template #default="{row}">{{ row.column_type || row.data_type }}</template></el-table-column><el-table-column label="是否主键" width="95"><template #default="{row}">{{ row.primary_key?'是':'否' }}</template></el-table-column><el-table-column label="是否允许为空" width="120"><template #default="{row}">{{ row.nullable?'是':'否' }}</template></el-table-column><el-table-column prop="default_value" label="默认值" width="95" /><el-table-column prop="technical_comment" label="字段说明" min-width="185" /><el-table-column label="字段顺序" width="100"><template #default="{row,$index}">{{ row.ordinal_position || $index+1 }}</template></el-table-column></el-table>
    <template #footer><el-button type="primary" @click="create(selectedTable,fieldTask)">从此表创建资源</el-button></template>
  </el-drawer>
</div></template>
