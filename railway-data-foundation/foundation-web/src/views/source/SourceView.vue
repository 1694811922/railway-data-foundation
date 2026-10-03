<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hub } from '../../utils/dataHub'
import DemoNotice from '../../components/DemoNotice.vue'
import StatusTag from '../../components/StatusTag.vue'
import { displayTime } from '../../utils/time'
import { canUseDemo } from '../../utils/request'
const router=useRouter()
const rows=ref([]), demo=ref(false), loading=ref(false), dialog=ref(false), saving=ref(false), editingId=ref('')
const checked=reactive({}), busy=reactive({})
const blank=()=>({source_name:'',source_type:'MYSQL',description:'',host:'',port:3306,database_name:'',username:'',password:'',ssl_mode:'PREFERRED',status:'ENABLED',connection_mode:'DYNAMIC'})
const form=reactive(blank())
const types={MYSQL:'MySQL数据库',ORACLE:'Oracle数据库',POSTGRESQL:'PostgreSQL数据库'}
async function load(){loading.value=true;try{const result=await hub.sources();rows.value=result.rows||[];demo.value=result.demo}catch{ElMessage.error('数据源列表加载失败')}finally{loading.value=false}}
function open(row){Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,blank(),row||{},{password:''});editingId.value=row?.source_id||'';dialog.value=true}
function validate(){
  if(!form.source_name.trim() || !form.host.trim() || !form.database_name.trim() || !form.username.trim()){ElMessage.warning('请填写数据源名称、数据库地址、数据库名称和用户名');return false}
  if(!/^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(form.host.trim())){ElMessage.warning('请输入合法的 IPv4 地址或域名，不要填写完整连接地址');return false}
  if(!/^[A-Za-z0-9_]{1,64}$/.test(form.database_name.trim())){ElMessage.warning('数据库名称仅支持字母、数字和下划线');return false}
  if(!Number.isInteger(Number(form.port)) || Number(form.port)<1 || Number(form.port)>65535){ElMessage.warning('端口必须为 1–65535 范围内的整数');return false}
  if(!form.password && (!editingId.value || form.connection_mode==='ENV_REFERENCE')){ElMessage.warning('新增或转换数据源时请输入数据库密码');return false}
  return true
}
async function save(andTest=false){if(!validate())return;saving.value=true;try{
  const payload={source_name:form.source_name.trim(),source_type:form.source_type,description:form.description,
    host:form.host.trim(),port:Number(form.port),database_name:form.database_name.trim(),username:form.username.trim(),ssl_mode:form.ssl_mode,status:form.status}
  if(form.password)payload.password=form.password
  const result=await hub.saveSource(payload,editingId.value||undefined,demo.value)
  form.password='';dialog.value=false;await load();if(editingId.value)delete checked[editingId.value]
  ElMessage.success(result.demo?'演示配置已保存到当前浏览器，密码未保存':'数据源配置已保存')
  if(andTest)await test(result.value)
}catch(e){if(canUseDemo(e))ElMessage.error('数据源保存请求失败，请检查后端服务后重试')}finally{saving.value=false}}
async function test(row){busy[row.source_id]=true;delete checked[row.source_id];try{const result=await hub.testSource(row);checked[row.source_id]=result.value;if(result.value.connected)ElMessage.success(result.demo?'演示连接成功':'连接成功');else ElMessage.error(result.value.reason||'连接失败')}catch(e){if(canUseDemo(e))ElMessage.error('连接测试请求失败，请检查后端服务')}finally{busy[row.source_id]=false}}
async function scan(row){busy[row.source_id]=true;try{const result=await hub.startScan(row);if(result.value.status==='FAILED')ElMessage.error(result.value.failure_reason||'扫描失败');else ElMessage.success(result.demo?'演示扫描完成':'扫描完成');router.push({path:'/scans',query:{task:result.value.task_id}})}catch(e){if(canUseDemo(e))ElMessage.error('扫描请求失败，请检查后端服务后重试')}finally{busy[row.source_id]=false}}
async function changeStatus(row){busy[row.source_id]=true;try{const status=row.status==='DISABLED'?'ENABLED':'DISABLED';await hub.sourceStatus(row.source_id,status,row._demo===true);delete checked[row.source_id];await load();ElMessage.success(status==='ENABLED'?'数据源已启用':'数据源已停用')}catch(e){if(canUseDemo(e))ElMessage.error('启停请求失败，请检查后端服务')}finally{busy[row.source_id]=false}}
onMounted(load)
</script>
<template>
  <div class="stack">
    <div class="page-heading"><div><div class="eyebrow">接入管理</div><h1>数据源管理</h1><p>登记连接配置，测试外部数据库，发现表与字段结构</p></div><el-button type="primary" @click="open()">＋ 新增数据源</el-button></div>
    <DemoNotice :show="demo" text="当前为演示数据，配置仅保存于当前浏览器；密码不会保存，连接与扫描结果仅用于展示。" />
    <div class="panel"><div class="panel-title">接入数据源 <span class="panel-sub">共 {{ rows.length }} 个</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="暂无数据源，请先新增" style="width:100%">
        <el-table-column prop="source_name" label="数据源名称" min-width="170" fixed="left" />
        <el-table-column label="数据源ID" min-width="175"><template #default="{row}"><span class="id-text">{{ row.source_id }}</span></template></el-table-column>
        <el-table-column label="数据源类型" width="130"><template #default="{row}">{{ types[row.source_type] || row.source_type }}</template></el-table-column>
        <el-table-column prop="host" label="数据库地址" min-width="135" />
        <el-table-column prop="port" label="端口" width="80" />
        <el-table-column prop="database_name" label="数据库名称" min-width="145" />
        <el-table-column label="状态" width="95"><template #default="{row}"><StatusTag :value="row.status || 'ENABLED'" /></template></el-table-column>
        <el-table-column label="最近更新时间" min-width="165"><template #default="{row}">{{ displayTime(row.updated_at) }}</template></el-table-column>
        <el-table-column label="操作" min-width="265" fixed="right"><template #default="{row}"><div class="table-actions">
          <el-button link type="primary" :disabled="busy[row.source_id] || row.status==='DISABLED'" @click="test(row)">测试连接</el-button>
          <el-button link type="primary" :disabled="busy[row.source_id] || row.status==='DISABLED'" @click="scan(row)">开始扫描</el-button>
          <el-button link type="primary" :disabled="busy[row.source_id]" @click="open(row)">编辑</el-button>
          <el-button link :type="row.status==='DISABLED'?'primary':'warning'" :disabled="busy[row.source_id]" @click="changeStatus(row)">{{ row.status==='DISABLED'?'启用':'停用' }}</el-button>
        </div></template></el-table-column>
      </el-table>
    </div>
    <div v-for="row in rows.filter(r=>checked[r.source_id])" :key="row.source_id" class="panel" style="padding:18px 20px">
      <div class="panel-title">{{ row.source_name }} · 连接测试</div>
      <el-descriptions :column="4" border><el-descriptions-item label="连接状态"><el-tag :type="checked[row.source_id].connected?'success':'danger'">{{ checked[row.source_id].connected?'连接成功':'连接失败' }}</el-tag></el-descriptions-item><el-descriptions-item label="数据库类型">{{ checked[row.source_id].database_product || '—' }}</el-descriptions-item><el-descriptions-item label="数据库版本">{{ checked[row.source_id].database_version || '—' }}</el-descriptions-item><el-descriptions-item label="响应时间">{{ checked[row.source_id].response_time_ms }} ms</el-descriptions-item></el-descriptions>
      <p v-if="!checked[row.source_id].connected" style="color:#c45656;margin-bottom:0">失败原因：{{ checked[row.source_id].reason }}</p>
    </div>
    <el-dialog v-model="dialog" :title="editingId?'编辑数据源':'新增数据源'" width="720px" @closed="form.password=''">
      <el-alert v-if="form.connection_mode==='ENV_REFERENCE'" type="info" :closable="false" title="此数据源使用旧配置方式。保存后将转为动态配置，请重新输入密码；现有数据源无需转换也可继续使用。" style="margin-bottom:16px" />
      <el-form label-position="top" :model="form">
        <h3>基本信息</h3><div class="grid-2">
          <el-form-item label="数据源名称" required><el-input v-model="form.source_name" maxlength="120" placeholder="例如：广州港业务数据库" /></el-form-item>
          <el-form-item label="数据源类型"><el-select v-model="form.source_type" style="width:100%"><el-option label="MySQL数据库" value="MYSQL" /></el-select></el-form-item>
        </div><el-form-item label="备注说明"><el-input v-model="form.description" maxlength="1000" type="textarea" :rows="2" placeholder="例如：广州港海铁联运业务数据源" /></el-form-item>
        <h3>连接信息</h3><div class="grid-2">
          <el-form-item label="数据库地址" required><el-input v-model="form.host" placeholder="请输入服务器 IP 或域名，例如 192.168.1.20" /></el-form-item>
          <el-form-item label="端口" required><el-input-number v-model="form.port" :min="1" :max="65535" :precision="0" style="width:100%" /><span class="dim">MySQL默认端口为3306</span></el-form-item>
          <el-form-item label="数据库名称" required><el-input v-model="form.database_name" placeholder="例如：port_source_db" /></el-form-item>
          <el-form-item label="数据库用户名" required><el-input v-model="form.username" maxlength="128" placeholder="建议使用只读账号，不建议使用root" /></el-form-item>
          <el-form-item label="数据库密码" :required="!editingId || form.connection_mode==='ENV_REFERENCE'"><el-input v-model="form.password" maxlength="1024" type="password" autocomplete="new-password" :placeholder="editingId && form.connection_mode!=='ENV_REFERENCE'?'密码已配置，无需修改可留空':'请输入数据库密码'" /></el-form-item>
          <el-form-item label="加密连接"><el-select v-model="form.ssl_mode" style="width:100%"><el-option label="优先加密（默认）" value="PREFERRED" /><el-option label="必须加密" value="REQUIRED" /><el-option label="验证证书与主机" value="VERIFY_IDENTITY" /><el-option label="验证证书" value="VERIFY_CA" /><el-option label="不加密（仅本机实验）" value="DISABLED" /></el-select></el-form-item>
        </div>
        <p class="dim">数据库密码仅用于后端建立连接，查询不会回显，浏览器不会持久保存明文密码。</p>
        <el-alert type="info" :closable="false" title="建议使用只读数据库账号，仅授予元数据读取和必要查询权限。扫描只读取数据库结构。" />
      </el-form>
      <template #footer><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button :loading="saving" @click="save(true)" :disabled="form.status==='DISABLED'">保存并测试连接</el-button><el-button type="primary" :loading="saving" @click="save(false)">保存</el-button></template>
    </el-dialog>
  </div>
</template>
