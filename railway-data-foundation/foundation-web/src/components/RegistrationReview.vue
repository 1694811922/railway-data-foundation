<script setup>
import { ref, reactive, watch, onMounted, onBeforeUnmount } from 'vue'
import { registrationApi } from '../api/registration'
import { accountApi } from '../api/account'
import { latestAccountReader } from '../utils/account'
import { explicitDemo } from '../utils/sessionState'
import { roleNames } from '../utils/workspace'
import { ElMessage } from 'element-plus'
const emit = defineEmits(['approved'])
const rows=ref([]), total=ref(0), page=ref(1), status=ref('PENDING'), loading=ref(false), error=ref(''), busy=ref(false)
const target=ref(null), subjects=ref([]), subjectTotal=ref(0), subjectLoading=ref(false), dialogError=ref(''), uncertain=ref(false)
const form=reactive({ approve:true, subject_id:null, reason:'' })
const reader=latestAccountReader(), subjectReader=latestAccountReader()
let active=true, keyword=''
const names={PENDING:'待审核',APPROVED:'已通过',REJECTED:'已拒绝'}
async function load(){if(explicitDemo)return;loading.value=true;error.value='';rows.value=[];await reader.run(()=>registrationApi.list({status:status.value,limit:20,offset:(page.value-1)*20}),data=>{rows.value=data.items;total.value=data.total},e=>{error.value=e.response?.data?.message||'注册申请读取失败';total.value=0},()=>{loading.value=false})}
function search(){if(page.value!==1)page.value=1;else load()}
async function loadSubjects(text='',append=false){
  if(!target.value)return;keyword=text;subjectLoading.value=true;dialogError.value=''
  if(!append){subjects.value=[];subjectTotal.value=0;form.subject_id=null}
  await subjectReader.run(()=>accountApi.subjects({role:target.value.requested_role,keyword:text,limit:20,offset:append?subjects.value.length:0}),data=>{subjects.value=append?[...subjects.value,...data.items]:data.items;subjectTotal.value=data.total},e=>{dialogError.value=e.response?.data?.message||'主体读取失败'},()=>{subjectLoading.value=false})
}
function open(row){target.value={...row};Object.assign(form,{approve:true,subject_id:null,reason:''});dialogError.value='';uncertain.value=false;loadSubjects()}
function close(){if(busy.value)return;target.value=null;subjects.value=[];subjectReader.invalidate();subjectLoading.value=false}
async function save(){
  if(busy.value||uncertain.value||!target.value||explicitDemo)return
  if(form.approve&&!form.subject_id){dialogError.value='请选择核定的共享主体';return}
  if(!form.approve&&!form.reason.trim()){dialogError.value='请填写拒绝原因';return}
  busy.value=true;dialogError.value=''
  try{await registrationApi.review(target.value.registration_id,{version:target.value.version,approve:form.approve,subject_id:form.approve?form.subject_id:null,reason:form.reason.trim()});if(active){busy.value=false;close();ElMessage.success('注册申请已处理');emit('approved');load()}}
  catch(e){if(active){dialogError.value=e.response?.data?.message||'审核结果未确认，请关闭后刷新核对，勿重复提交';uncertain.value=!e.response||e.response.status>=500||e.response.status===409}}
  finally{busy.value=false}
}
watch(page,load);onMounted(load);onBeforeUnmount(()=>{active=false;reader.invalidate();subjectReader.invalidate();target.value=null})
</script>
<template>
  <div v-if="!explicitDemo" class="panel registration-review">
    <div class="review-heading"><h2>注册申请</h2><div><el-select v-model="status" aria-label="注册审核状态" @change="search"><el-option v-for="(name,key) in names" :key="key" :value="key" :label="name" /></el-select><el-button @click="load">刷新申请</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">重试读取申请</el-button></el-alert>
    <el-table :data="rows" v-loading="loading" empty-text="暂无注册申请" data-testid="registration-table">
      <el-table-column prop="username" label="登录账号" min-width="160" />
      <el-table-column prop="display_name" label="用户名称" min-width="130" />
      <el-table-column label="申请角色" width="120"><template #default="{row}">{{roleNames[row.requested_role]}}</template></el-table-column>
      <el-table-column prop="organization_name" label="申请单位" min-width="180" />
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status==='APPROVED'?'success':row.status==='REJECTED'?'danger':'warning'">{{names[row.status]}}</el-tag></template></el-table-column>
      <el-table-column prop="review_reason" label="审核意见" min-width="150" />
      <el-table-column label="操作" width="80"><template #default="{row}"><el-button v-if="row.status==='PENDING'" link type="primary" :disabled="busy" @click="open(row)">审核</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="total && !error" :current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @update:current-page="page=$event" />
    <el-dialog :model-value="!!target" title="审核注册申请" width="560px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close" @update:model-value="v=>{if(!v)close()}">
      <el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" />
      <el-form label-position="top" :disabled="busy || uncertain" @submit.prevent="save">
        <el-form-item label="申请人"><span>{{target?.display_name}}（{{target?.username}}）</span></el-form-item>
        <el-form-item label="申请单位"><span>{{target?.organization_name}}</span></el-form-item>
        <el-form-item label="审核结果"><el-radio-group v-model="form.approve"><el-radio :value="true">通过</el-radio><el-radio :value="false">拒绝</el-radio></el-radio-group></el-form-item>
        <el-form-item v-if="form.approve" label="核定共享主体" required><el-select v-model="form.subject_id" aria-label="核定共享主体" filterable remote :remote-method="loadSubjects" :loading="subjectLoading" :teleported="false" placeholder="选择已核实的同角色单位"><el-option v-for="s in subjects" :key="s.subject_id" :value="s.subject_id" :label="s.subject_name" /><template #footer><el-button v-if="subjects.length<subjectTotal" link :disabled="subjectLoading" @click="loadSubjects(keyword,true)">加载更多主体</el-button><el-button link :disabled="subjectLoading" @click="loadSubjects(keyword)">刷新主体</el-button></template></el-select></el-form-item>
        <el-form-item label="审核意见" :required="!form.approve"><el-input v-model="form.reason" type="textarea" maxlength="500" /></el-form-item>
      </el-form>
      <el-alert v-if="form.approve" title="请核实单位身份；没有对应主体时，先在共享主体管理中登记。" type="warning" :closable="false" />
      <template #footer><el-button :disabled="busy" @click="close">关闭</el-button><el-button v-if="!uncertain" type="primary" :loading="busy" :disabled="form.approve && subjectLoading" @click="save">确认审核</el-button><el-button v-else @click="close();load()">关闭并刷新</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.review-heading { display:flex; justify-content:space-between; align-items:center; gap:16px; margin-bottom:18px; }
.review-heading h2 { margin:0; font-size:20px; }
.review-heading .el-select { width:130px; margin-right:12px; }
.el-form-item .el-select { width:100%; }
.el-pagination { margin-top:18px; justify-content:flex-end; }
</style>
